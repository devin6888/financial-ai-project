package com.finagent.task;

import java.util.Optional;

import com.finagent.chunk.ReindexMode;
import com.finagent.chunk.ReindexService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * document.reindex 消费者（阶段 7 worker）。
 *
 * <p>职责边界：只做「接消息 + 状态机（task_message）+ ack」，重切 + 增量重嵌
 * 在 {@link ReindexService#reindex(long, ReindexMode)} 里，不在这里写第二套。
 *
 * <p>装配条件与 {@code DocumentParseService} 一致：重切要从 COS 读回
 * NormalizedDocument，没有 COS 凭据时不装配。
 */
@Component
@ConditionalOnProperty(prefix = "finagent.cos",
        name = {"secret-id", "secret-key", "region", "bucket"})
public class DocumentReindexListener {

    private static final Logger log = LoggerFactory.getLogger(DocumentReindexListener.class);

    private final ReindexService reindexService;
    private final TaskMessageRepository taskMessages;
    private final ObjectMapper json;

    public DocumentReindexListener(ReindexService reindexService,
                                   TaskMessageRepository taskMessages,
                                   ObjectMapper json) {
        this.reindexService = reindexService;
        this.taskMessages = taskMessages;
        this.json = json;
    }

    @KafkaListener(topics = "${finagent.task.reindex-topic}")
    public void onReindex(String payload, Acknowledgment ack) {
        DocumentTaskEvent event = deserialize(payload);
        process(event);
        ack.acknowledge();
    }

    /**
     * 核心编排（包可见，供单测直接调用）：落 PROCESSING → reindex(RECHUNK) → SUCCESS。
     * 失败时标记 FAILED 并原样抛回。
     */
    void process(DocumentTaskEvent event) {
        Optional<TaskMessage> existing = taskMessages.findByEventId(event.eventId());
        if (existing.isPresent() && "SUCCESS".equals(existing.get().status())) {
            log.info("document.reindex 已成功处理过 eventId={}，跳过重复投递", event.eventId());
            return;
        }
        long id = taskMessages.upsert(event.eventId(), "document.reindex",
                String.valueOf(event.documentId()), event.documentId());
        taskMessages.markStatus(id, "PROCESSING");
        try {
            reindexService.reindex(event.documentId(), ReindexMode.RECHUNK);
            taskMessages.markStatus(id, "SUCCESS");
            log.info("document.reindex 完成 document_id={} eventId={}",
                    event.documentId(), event.eventId());
        } catch (RuntimeException e) {
            taskMessages.markStatus(id, "FAILED");
            log.warn("document.reindex 失败 document_id={} eventId={}",
                    event.documentId(), event.eventId(), e);
            throw e;
        }
    }

    private DocumentTaskEvent deserialize(String payload) {
        try {
            return json.readValue(payload, DocumentTaskEvent.class);
        } catch (Exception e) {
            throw new IllegalStateException("反序列化 document.reindex 消息失败", e);
        }
    }
}
