package com.finagent.memory;

import java.time.OffsetDateTime;

/**
 * 较早对话的压缩摘要：每会话一行，覆盖写。
 *
 * <p><b>为什么不保留历史摘要行</b>（ST9 §2.6 / D9）：摘要可以由消息重算，
 * 它是「当前状态」而不是「事件」；真正不可重算的是「谁在什么时候重写了摘要」，
 * 那条记录进 {@code memory_event}。
 *
 * @param summaryUntilMessageId 已汇总到哪条消息；下一轮只汇总它之后的消息
 * @param version               重写次数，从 1 起
 */
public record ConversationSummary(long conversationId, String summary,
                                  Long summaryUntilMessageId, int version,
                                  OffsetDateTime updatedAt) {
}
