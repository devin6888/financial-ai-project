package com.finagent.task;

import com.finagent.chunk.ReindexMode;
import com.finagent.chunk.ReindexService;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Properties;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * 阶段 7 集成测试：真 Kafka + 真 Postgres，验证「投递 → worker 消费 → 状态机 → DLQ」这条链路。
 *
 * <p><b>为什么标 e2e</b>：需要 finagent-kafka（localhost:9092）与 finagent-postgres 两个容器都在跑；
 * 默认 {@code mvn test} 会排除。跑法：
 * <pre>
 * . .\scripts\load-env.ps1
 * mvn test "-Dsurefire.excludedGroups=" "-Dgroups=e2e" "-Dtest=DocumentTaskFlowIntegrationTest"
 * </pre>
 *
 * <p><b>为什么把 ReindexService 换成 Mockito mock</b>：本测试要验证的是「Kafka 投递 → listener
 * 被调用 → task_message 状态机 → ack/DLQ」这条消息链，而不是切块算法。若用真的 ReindexService，
 * 每跑一次都会给 document 7 新建一个 chunk 版本（1516 行），把 demo 数据越跑越乱，还会打
 * embedding API；而切块/嵌入本身已由 {@code ChunkWriterTest}/{@code ReindexServiceTest} 用真库覆盖。
 * 这里用 mock 断言「listener 确实把正确的 (documentId, mode) 交给了重索引服务」。
 */
@SpringBootTest
@ActiveProfiles("test")
@Tag("e2e")
class DocumentTaskFlowIntegrationTest {

    private static final long DOC_ID = 7L;

    @MockitoBean
    private ReindexService reindexService;

    @Autowired
    private KafkaTemplate<String, String> kafka;

    @Autowired
    private ObjectMapper json;

    @Autowired
    private TaskProperties props;

    @Autowired
    private TaskMessageRepository taskMessages;

    @Value("${spring.kafka.bootstrap-servers}")
    private String bootstrapServers;

    @Test
    @DisplayName("投递 document.reindex → worker 消费 → task_message SUCCESS 且调到重索引服务")
    void reindexEventIsConsumedAndMarkedSuccess() throws Exception {
        DocumentTaskEvent event = DocumentTaskEvent.forReindex(DOC_ID, ReindexMode.RECHUNK);
        kafka.send(props.reindexTopic(), String.valueOf(DOC_ID),
                json.writeValueAsString(event)).get();

        TaskMessage msg = awaitStatus(event.eventId(), "SUCCESS", 60);

        assertThat(msg.documentId()).isEqualTo(DOC_ID);
        assertThat(msg.topic()).isEqualTo(props.reindexTopic());
        assertThat(msg.retryCount()).isZero();
        // 断言 listener 真的走到了重索引（而不是只把状态改成 SUCCESS 就 ack）
        verify(reindexService, times(1)).reindex(DOC_ID, ReindexMode.RECHUNK);
    }

    @Test
    @DisplayName("同一 eventId 重复投递 → 幂等，不会重复执行")
    void duplicateEventIdIsIdempotent() throws Exception {
        DocumentTaskEvent event = DocumentTaskEvent.forReindex(DOC_ID, ReindexMode.RECHUNK);
        String payload = json.writeValueAsString(event);

        kafka.send(props.reindexTopic(), String.valueOf(DOC_ID), payload).get();
        awaitStatus(event.eventId(), "SUCCESS", 60);

        // at-least-once 重投：同 eventId 再投一次，listener 应凭 task_message 直接跳过
        kafka.send(props.reindexTopic(), String.valueOf(DOC_ID), payload).get();
        Thread.sleep(5000);

        verify(reindexService, times(1)).reindex(DOC_ID, ReindexMode.RECHUNK);
        assertThat(taskMessages.findByEventId(event.eventId())).isPresent();
    }

    @Test
    @DisplayName("坏消息（不存在的 documentId）→ 进 DLQ，task_message 标 FAILED")
    void badMessageGoesToDlq() throws Exception {
        long badId = 999_999_999L;
        DocumentTaskEvent bad = new DocumentTaskEvent(
                UUID.randomUUID().toString(), badId, null, OffsetDateTime.now().toString());
        kafka.send(props.parseTopic(), String.valueOf(badId),
                json.writeValueAsString(bad)).get();

        String dlqTopic = props.parseTopic() + props.dlqSuffix();
        String dlqPayload = pollDlq(dlqTopic, bad.eventId(), 90);

        assertThat(dlqPayload)
                .as("DLQ %s 未在 90 秒内收到 eventId=%s", dlqTopic, bad.eventId())
                .isNotNull();

        TaskMessage msg = awaitStatus(bad.eventId(), "FAILED", 30);
        // 不存在的 documentId 属于「业务不可恢复」，不该被当成瞬时错误重试
        assertThat(msg.retryCount()).isZero();
    }

    // ---------- helpers ----------

    private TaskMessage awaitStatus(String eventId, String expected, int timeoutSeconds) {
        long deadline = System.currentTimeMillis() + timeoutSeconds * 1000L;
        TaskMessage last = null;
        while (System.currentTimeMillis() < deadline) {
            last = taskMessages.findByEventId(eventId).orElse(null);
            if (last != null && expected.equals(last.status())) {
                return last;
            }
            sleep(300);
        }
        fail("等待 task_message eventId=" + eventId + " 变为 " + expected + " 超时，当前="
                + (last == null ? "无记录" : last.status()));
        return last;
    }

    /** 用独立消费者读 DLQ，直到看到指定 eventId（或超时）。 */
    private String pollDlq(String topic, String eventId, int timeoutSeconds) {
        Properties p = new Properties();
        p.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        p.put(ConsumerConfig.GROUP_ID_CONFIG, "dlq-probe-" + UUID.randomUUID());
        p.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        p.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        p.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        p.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(p)) {
            consumer.subscribe(List.of(topic));
            long deadline = System.currentTimeMillis() + timeoutSeconds * 1000L;
            while (System.currentTimeMillis() < deadline) {
                for (ConsumerRecord<String, String> rec : consumer.poll(Duration.ofMillis(500))) {
                    if (rec.value() != null && rec.value().contains(eventId)) {
                        return rec.value();
                    }
                }
            }
        }
        return null;
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
