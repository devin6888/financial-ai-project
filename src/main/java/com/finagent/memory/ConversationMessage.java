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
 *
 * <p>{@code elapsedMs} 是这一轮的<b>服务端</b>总耗时（见 {@code ChatTrace.totalMs()}）。
 * 为什么要落库：前端原来按墙钟自己算，页面一刷新历史消息就再也显示不出「用时」；
 * 存下来之后历史回放与刚跑完的时刻是同一个数。旧行为 null（不显示）。
 */
public record ConversationMessage(long id, long conversationId, String role, String content,
                                 String citationsJson, String scopeSnapshot, String traceId,
                                 int tokenCount, Integer elapsedMs, OffsetDateTime createdAt) {

    public static final String ROLE_USER = "USER";
    public static final String ROLE_ASSISTANT = "ASSISTANT";
}
