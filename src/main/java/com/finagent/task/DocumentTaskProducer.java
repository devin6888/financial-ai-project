package com.finagent.task;

import com.finagent.chunk.ReindexMode;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * 文档任务的 Kafka 生产者（阶段 7）。
 *
 * <p>职责：把「解析文档 / 重索引文档」的意图编码成 C9 消息体，投到对应 topic。
 * 不解析、不切块、不嵌入 —— 那些都在 worker（listener）里做，这里只投递。
 *
 * <p>key 用 documentId 字符串：同一文档的事件进同一分区（单分区时天然有序），
 * 重复投递也能按 key 定位。
 */
@Component
public class DocumentTaskProducer {

    private final KafkaTemplate<String, String> kafka;
    private final ObjectMapper json;
    private final TaskProperties props;

    public DocumentTaskProducer(KafkaTemplate<String, String> kafka,
                                ObjectMapper json,
                                TaskProperties props) {
        this.kafka = kafka;
        this.json = json;
        this.props = props;
    }

    /** 投递 document.parse：异步解析一条龙（解析 → 切块 → 嵌入）。 */
    public void sendParse(long documentId) {
        send(props.parseTopic(), DocumentTaskEvent.forParse(documentId));
    }

    /** 投递 document.reindex：从已存 NormalizedDocument 重切 + 增量重嵌（不重新解析）。 */
    public void sendReindex(long documentId, ReindexMode mode) {
        send(props.reindexTopic(), DocumentTaskEvent.forReindex(documentId, mode));
    }

    private void send(String topic, DocumentTaskEvent event) {
        final String payload;
        try {
            payload = json.writeValueAsString(event);
        } catch (Exception e) {
            throw new IllegalStateException(
                    "序列化 Kafka 事件失败（topic=" + topic + "）：" + e.getMessage(), e);
        }
        // 异步投递：不阻塞调用方（REST 秒回 documentId）；发送失败由 Producer 的重试/回调兜底
        kafka.send(topic, String.valueOf(event.documentId()), payload);
    }
}
