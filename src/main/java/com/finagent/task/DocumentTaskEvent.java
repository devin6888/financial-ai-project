package com.finagent.task;

import com.finagent.chunk.ReindexMode;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * document.parse / document.reindex 两个 topic 共用的消息体（契约 C9）。
 *
 * <p>字段与 C9 一致：eventId（投递唯一标识，落 task_message 做消息级去重）、
 * documentId、mode（仅 reindex 用，本阶段只 RECHUNK）、occurredAt。
 *
 * <p>occurredAt 用 ISO-8601 字符串（{@code OffsetDateTime.now().toString()}）而非时间类型：
 * 事件里它是纯元数据（DLQ 排查用），消费端不做时间运算，字符串足够，
 * 也避免序列化依赖 JavaTimeModule（单测里 JsonMapper.builder() 不带时间模块）。
 */
public record DocumentTaskEvent(
        String eventId,
        long documentId,
        String mode,
        String occurredAt) {

    /** document.parse 事件（无 mode）。 */
    public static DocumentTaskEvent forParse(long documentId) {
        return new DocumentTaskEvent(
                UUID.randomUUID().toString(), documentId, null, OffsetDateTime.now().toString());
    }

    /** document.reindex 事件（mode = ReindexMode.name()）。 */
    public static DocumentTaskEvent forReindex(long documentId, ReindexMode mode) {
        return new DocumentTaskEvent(
                UUID.randomUUID().toString(), documentId, mode.name(), OffsetDateTime.now().toString());
    }
}
