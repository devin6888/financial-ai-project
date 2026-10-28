package com.finagent.task;

import java.util.Optional;

import com.finagent.document.DocumentParseService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * document.parse 消费者（阶段 7 worker）。
 *
 * <p>职责边界：只做「接消息 + 状态机（task_message）+ ack」，真正的
 * 「解析 → 切块 → 嵌入」编排在 {@link DocumentParseService#parseDocumentAsync(long)} 里，
 * 不在这里写第二套（设计文档 §8 的 Document Worker 编排职责）。
 *
 * <p>装配条件与 {@link DocumentParseService} 一致：没有 COS 凭据时不装配，
 * 应用仍能启动（阶段 1 定下的原则）。
 */
@Component
@ConditionalOnProperty(prefix = "finagent.cos",
        name = {"secret-id", "secret-key", "region", "bucket"})
public class DocumentParseListener {

    private static final Logger log = LoggerFactory.getLogger(DocumentParseListener.class);

    private final DocumentParseService parseService;
    private final TaskMessageRepository taskMessages;
    private final ObjectMapper json;

    public DocumentParseListener(DocumentParseService parseService,
                                 TaskMessageRepository taskMessages,
                                 ObjectMapper json) {
        this.parseService = parseService;
        this.taskMessages = taskMessages;
        this.json = json;
    }

    @KafkaListener(topics = "${finagent.task.parse-topic}")
    public void onParse(String payload, Acknowledgment ack) {
        DocumentTaskEvent event = deserialize(payload);
        process(event);
        ack.acknowledge();
    }

    /**
     * 核心编排（包可见，供单测直接调用，脱离 Kafka）：落 PROCESSING → 解析编排 → SUCCESS。
     * 失败时标记 FAILED 并原样抛回（是否重试/进 DLQ 是外层错误处理器的职责）。
     */
    void process(DocumentTaskEvent event) {
        Optional<TaskMessage> existing = taskMessages.findByEventId(event.eventId());
        if (existing.isPresent() && "SUCCESS".equals(existing.get().status())) {
            log.info("document.parse 已成功处理过 eventId={}，跳过重复投递", event.eventId());
            return;
        }
        long id = taskMessages.upsert(event.eventId(), "document.parse",
                String.valueOf(event.documentId()), event.documentId());
        taskMessages.markStatus(id, "PROCESSING");
        try {
            parseService.parseDocumentAsync(event.documentId());
            taskMessages.markStatus(id, "SUCCESS");
            log.info("document.parse 完成 document_id={} eventId={}",
                    event.documentId(), event.eventId());
        } catch (RuntimeException e) {
            taskMessages.markStatus(id, "FAILED");
            log.warn("document.parse 失败 document_id={} eventId={}",
                    event.documentId(), event.eventId(), e);
            throw e;
        }
    }

    private DocumentTaskEvent deserialize(String payload) {
        try {
            return json.readValue(payload, DocumentTaskEvent.class);
        } catch (Exception e) {
            throw new IllegalStateException("反序列化 document.parse 消息失败", e);
        }
    }
}
