package com.finagent.task;

import com.finagent.chunk.ChunkRepository;
import com.finagent.chunk.ReindexMode;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;

import java.time.Duration;
import java.util.List;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/**
 * 阶段 7 端到端（e2e）冒烟：真 HTTP + 真 Kafka + 真 Postgres + 真对象存储。
 *
 * <p>与 {@link DocumentTaskFlowIntegrationTest} 的分工：
 * 那个测试把 {@code ReindexService} 换成 mock，只验证消息链；
 * 本测试走**全真**链路，并把「增量重嵌复用占比」量出来（阶段 7 的验收指标之一）。
 *
 * <p>跑法（两个容器必须在跑）：
 * <pre>
 * . .\scripts\load-env.ps1
 * mvn test "-Dsurefire.excludedGroups=" "-Dgroups=e2e" "-Dtest=DocumentTaskE2ETest"
 * </pre>
 *
 * <p><b>副作用</b>：{@code realReindexBumpsVersionAndReusesEmbeddings} 会给 document 7
 * 新建一个 chunk 版本（v_n → v_n+1），旧的标记 SUPERSEDED 但保留。这是 e2e 的应有之义，
 * 但也意味着 chunk id 会变（golden set 里按 id 固定的证据块需按 ACTIVE 版本重建，见阶段 7 报告）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Tag("e2e")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class DocumentTaskE2ETest {

    private static final long DOC_ID = 7L;
    private static final Path PDF = Path.of("docs", "东方财富2025年年度报告.pdf");

    @LocalServerPort
    private int port;

    @Autowired
    private ObjectMapper json;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ChunkRepository chunks;

    @Autowired
    private TaskProperties props;

    @Autowired
    private KafkaTemplate<String, String> kafka;

    @Autowired
    private TaskMessageRepository taskMessages;

    @Value("${spring.kafka.bootstrap-servers}")
    private String bootstrapServers;

    @Test
    @Order(1)
    @DisplayName("上传与库里同一份 PDF → 幂等快路径直接返回 DONE，不重复解析")
    void uploadSamePdfIsIdempotent() throws Exception {
        Assumptions.assumeTrue(Files.exists(PDF), "缺少样例 PDF：" + PDF.toAbsolutePath());
        byte[] pdf = Files.readAllBytes(PDF);

        Map<?, ?> body = upload(pdf);

        // content_hash 与 document 7 完全一致 → 命中幂等快路径，返回旧 id + DONE
        assertThat(((Number) body.get("documentId")).longValue()).isEqualTo(DOC_ID);
        assertThat(body.get("parseStatus")).isEqualTo("DONE");

        // 没有新建文档
        Integer sameHash = jdbc.queryForObject(
                "SELECT count(*) FROM document WHERE content_hash = ?", Integer.class, sha256Hex(pdf));
        assertThat(sameHash).isEqualTo(1);
    }

    @Test
    @Order(2)
    @DisplayName("GET /api/documents/{id} → DONE + 当前 active chunk 版本")
    void detailEndpointReportsDone() {
        String raw = RestClient.create()
                .get().uri(baseUrl() + "/api/documents/" + DOC_ID)
                .retrieve().body(String.class);
        Map<?, ?> detail = json.readValue(raw, Map.class);

        assertThat(detail.get("parseStatus")).isEqualTo("DONE");
        assertThat(((Number) detail.get("activeChunkVersion")).intValue())
                .isGreaterThanOrEqualTo(3);
        assertThat(String.valueOf(detail.get("title"))).contains("东方财富2025年年度报告");
        assertThat(detail.get("companyCode")).isEqualTo("300059");
        assertThat(detail.get("companyName")).isEqualTo("东方财富");
    }

    @Test
    @Order(3)
    @DisplayName("真 reindex：版本 +1、块数不变、增量重嵌复用占比 > 80%")
    void realReindexBumpsVersionAndReusesEmbeddings() throws Exception {
        int before = chunks.activeVersion(DOC_ID);

        DocumentTaskEvent event = DocumentTaskEvent.forReindex(DOC_ID, ReindexMode.RECHUNK);
        kafka.send(props.reindexTopic(), String.valueOf(DOC_ID),
                json.writeValueAsString(event)).get();

        int after = awaitVersion(DOC_ID, before + 1, 300);
        assertThat(after).isEqualTo(before + 1);

        int children = countOf(after, "CHILD");
        int parents = countOf(after, "PARENT");
        assertThat(children).isEqualTo(654);
        assertThat(parents).isEqualTo(104);

        Integer withoutEmbedding = jdbc.queryForObject("""
                SELECT count(*) FROM document_chunk
                 WHERE document_id = ? AND chunk_version = ? AND chunk_level = 'CHILD'
                   AND status = 'ACTIVE' AND embedding IS NULL
                """, Integer.class, DOC_ID, after);
        assertThat(withoutEmbedding).isZero();

        // 复用 = 新旧版本里同一 embedding_text_hash 的块，向量逐字节相同（直接拷贝旧向量）
        // 注意 count(DISTINCT b.id)：同一版本里可能出现 content 完全相同的两个块（hash 相同），
        // 直接 count(*) 会按 join 行数放大（实测 660 > 654），必须按「新版本块」去重。
        Integer reused = jdbc.queryForObject("""
                SELECT count(DISTINCT b.id)
                  FROM document_chunk a
                  JOIN document_chunk b
                    ON a.document_id = b.document_id
                   AND a.embedding_text_hash = b.embedding_text_hash
                   AND a.chunk_level = 'CHILD'
                   AND b.chunk_level = 'CHILD'
                   AND a.chunk_version = ?
                   AND b.chunk_version = ?
                   AND a.embedding::text = b.embedding::text
                 WHERE a.document_id = ?
                """, Integer.class, before, after, DOC_ID);

        double ratio = reused * 1.0 / children;
        String line = "增量重嵌复用占比 = " + reused + "/" + children
                + " = " + String.format("%.2f%%", ratio * 100);
        System.out.println("[E2E] " + line);

        Files.writeString(Path.of("target", "task9-e2e-result.json"), """
                {"before": %d, "after": %d, "children": %d, "parents": %d,
                 "reused": %d, "ratio": %.4f, "line": "%s"}
                """.formatted(before, after, children, parents, reused, ratio, line));

        assertThat(ratio).as(line).isGreaterThan(0.8);
    }

    @Test
    @Order(4)
    @DisplayName("DLQ 消息可取出、修好根因后重投即恢复")
    void dlqMessageCanBeReplayedToRecover() throws Exception {
        long badId = 999_999_998L;
        DocumentTaskEvent bad = DocumentTaskEvent.forReindex(badId, ReindexMode.RECHUNK);
        kafka.send(props.reindexTopic(), String.valueOf(badId),
                json.writeValueAsString(bad)).get();

        String dlqPayload = pollDlq(props.reindexTopic() + props.dlqSuffix(), bad.eventId(), 90);
        assertThat(dlqPayload).as("DLQ 未收到 eventId=" + bad.eventId()).isNotNull();

        // 模拟运维：从 DLQ 取出消息、修好根因（把不存在的 documentId 改成真实存在的 7）后重投。
        // 保留原 eventId —— 这才是「重放同一条事件」，listener 会把它从 FAILED 重新走到 SUCCESS。
        @SuppressWarnings("unchecked")
        Map<String, Object> fixed = (Map<String, Object>) json.readValue(dlqPayload, Map.class);
        fixed.put("documentId", DOC_ID);

        int before = chunks.activeVersion(DOC_ID);
        kafka.send(props.reindexTopic(), String.valueOf(DOC_ID),
                json.writeValueAsString(fixed)).get();

        TaskMessage msg = awaitStatus(bad.eventId(), "SUCCESS", 300);
        assertThat(msg.status()).isEqualTo("SUCCESS");
        assertThat(awaitVersion(DOC_ID, before + 1, 300)).isEqualTo(before + 1);
    }

    // ---------- helpers ----------

    private String baseUrl() {
        return "http://localhost:" + port;
    }

    private Map<?, ?> upload(byte[] pdf) {
        MultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
        form.add("file", new ByteArrayResource(pdf) {
            @Override
            public String getFilename() {
                return "eastmoney-2025.pdf";
            }
        });
        form.add("companyCode", "300059");
        form.add("companyName", "东方财富");
        form.add("market", "SZ");
        form.add("docType", "ANNUAL");
        form.add("docYear", "2025");
        form.add("title", "东方财富2025年年度报告");

        String raw = RestClient.create()
                .post().uri(baseUrl() + "/api/documents")
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(form)
                .retrieve()
                .body(String.class);
        return json.readValue(raw, Map.class);
    }

    private int countOf(int version, String level) {
        Integer n = jdbc.queryForObject("""
                SELECT count(*) FROM document_chunk
                 WHERE document_id = ? AND chunk_version = ? AND chunk_level = ?
                   AND status = 'ACTIVE'
                """, Integer.class, DOC_ID, version, level);
        return n == null ? 0 : n;
    }

    private int awaitVersion(long documentId, int expected, int timeoutSeconds) {
        long deadline = System.currentTimeMillis() + timeoutSeconds * 1000L;
        int last = chunks.activeVersion(documentId);
        while (System.currentTimeMillis() < deadline) {
            last = chunks.activeVersion(documentId);
            if (last >= expected) {
                return last;
            }
            sleep(500);
        }
        fail("等待 document " + documentId + " 的 chunk 版本达到 " + expected + " 超时，当前 " + last);
        return last;
    }

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

    private static String sha256Hex(byte[] bytes) {
        try {
            var md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(bytes);
            StringBuilder sb = new StringBuilder(d.length * 2);
            for (byte b : d) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
