package com.finagent.chat;

import com.finagent.scope.ChatScope;

/**
 * POST /api/chat 的请求体。
 *
 * @param scope           研究范围；为 null 表示「沿用会话配置」，会话也没有配置时=「整个知识库」（C 模式）
 * @param question        用户当前问题原文
 * @param extensionPolicy 范围外文档是否允许临时扩展：ASK（默认，返回建议由用户点）/ NEVER
 * @param conversationId  会话 id；为 null 表示单轮问答（不落库，行为与阶段 8 完全一致）
 */
public record ChatRequest(String question, ChatScope scope, String extensionPolicy, Long conversationId) {

    public static final String POLICY_ASK = "ASK";
    public static final String POLICY_NEVER = "NEVER";

    public ChatRequest {
        question = question == null ? "" : question.trim();
        extensionPolicy = extensionPolicy == null || extensionPolicy.isBlank() ? POLICY_ASK : extensionPolicy.trim().toUpperCase();
    }

    /** 兼容构造器：阶段 5~8 的单轮调用点不用改。 */
    public ChatRequest(String question, ChatScope scope, String extensionPolicy) {
        this(question, scope, extensionPolicy, null);
    }

    public boolean hasConversation() {
        return conversationId != null;
    }

    public boolean allowsExtension() {
        return !POLICY_NEVER.equals(extensionPolicy);
    }
}
