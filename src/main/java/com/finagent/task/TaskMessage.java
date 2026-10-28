package com.finagent.task;

import java.time.OffsetDateTime;

/**
 * task_message 表的一行：一次 Kafka 事件的处理留痕。
 *
 * <p>用途（阶段 7，设计文档 §13.1）：
 * <ul>
 *   <li>消息级幂等 —— event_id 唯一，同一条事件重投只落一行；</li>
 *   <li>重试计数 —— 瞬时错误有限重试，retry_count 记录次数；</li>
 *   <li>死信留痕 —— 坏消息进 DLQ 前，last_error 记下原因供人工排查。</li>
 * </ul>
 */
public record TaskMessage(
        long id,
        String eventId,
        String topic,
        String messageKey,
        long documentId,
        String status,
        int retryCount,
        String lastError,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {
}
