package com.finagent.memory;

import java.time.OffsetDateTime;

/**
 * 一轮对话里的一条消息（USER 或 ASSISTANT）。
 *
 * <p>{@code scopeSnapshot} 是<b>这一轮实际用了什么范围</b>的快照（见 {@link ScopeSnapshots}），
 * 里面的 chunkVersions 让历史引用能指回「当时那一版」切块 —— 文档重切后 chunk id 会变，
 * 没有这个快照，回看历史消息就解析不回引用了。
 *
 * <p>{@code citationsJson} 原样存引用数组的 JSON：引用结构随阶段演进（现在有 childChunkId /
 * parentChunkId / page / bbox），存结构化列反而每次都要迁移。
 */
public record ConversationMessage(long id, long conversationId, String role, String content,
                                 String citationsJson, String scopeSnapshot, String traceId,
                                 int tokenCount, OffsetDateTime createdAt) {

    public static final String ROLE_USER = "USER";
    public static final String ROLE_ASSISTANT = "ASSISTANT";
}
