package com.finagent.chat;

import com.finagent.scope.ChatScope;

/**
 * POST /api/chat 的请求体。
 *
 * @param scope           研究范围；为 null 表示「整个知识库」（默认 C 模式）
 * @param extensionPolicy 范围外文档是否允许临时扩展：ASK（默认，返回建议由用户点）/ NEVER
 */
public record ChatRequest(String question, ChatScope scope, String extensionPolicy) {

    public static final String POLICY_ASK = "ASK";
    public static final String POLICY_NEVER = "NEVER";

    public ChatRequest {
        question = question == null ? "" : question.trim();
        extensionPolicy = extensionPolicy == null || extensionPolicy.isBlank()
                ? POLICY_ASK : extensionPolicy.trim().toUpperCase();
    }

    public boolean allowsExtension() {
        return !POLICY_NEVER.equals(extensionPolicy);
    }
}
