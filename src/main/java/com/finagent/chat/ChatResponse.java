package com.finagent.chat;

import com.finagent.answer.AnswerResult;
import com.finagent.citation.Citation;

import java.util.List;

/**
 * POST /api/chat 的响应。
 *
 * <p>answer / facts / citations / confidence 与 §9.12 一致；{@code sufficient=false}
 * 也是 200，因为「证据不足」是业务结果，不是服务器故障。
 *
 * @param extensionSuggested 范围外可能相关的文档提示；本阶段恒 null（Task 9 之后接）
 * @param conversationId     这一轮落在哪个会话；单轮问答时为 null
 * @param messageId          落库后 ASSISTANT 那条消息的 id；未落库时为 null
 */
public record ChatResponse(
        boolean sufficient,
        String answer,
        List<AnswerResult.Fact> facts,
        List<Citation> citations,
        String confidence,
        String note,
        ScopeInfo scope,
        List<String> warnings,
        ChatTrace trace,
        ExtensionSuggestion extensionSuggested,
        Long conversationId,
        Long messageId
) {
    public ChatResponse {
        facts = facts == null ? List.of() : List.copyOf(facts);
        citations = citations == null ? List.of() : List.copyOf(citations);
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }

    /** 兼容构造器：单轮问答的构造点（阶段 5~8）不用改。 */
    public ChatResponse(boolean sufficient, String answer, List<AnswerResult.Fact> facts,
                        List<Citation> citations, String confidence, String note, ScopeInfo scope,
                        List<String> warnings, ChatTrace trace, ExtensionSuggestion extensionSuggested) {
        this(sufficient, answer, facts, citations, confidence, note, scope, warnings, trace,
                extensionSuggested, null, null);
    }

    public ChatResponse withConversation(Long conversationId, Long messageId) {
        return new ChatResponse(sufficient, answer, facts, citations, confidence, note, scope,
                warnings, trace, extensionSuggested, conversationId, messageId);
    }

    public ChatResponse withWarnings(List<String> newWarnings) {
        return new ChatResponse(sufficient, answer, facts, citations, confidence, note, scope,
                newWarnings, trace, extensionSuggested, conversationId, messageId);
    }

    /** 建议把某几份文档加入本轮范围。 */
    public record ExtensionSuggestion(List<Long> documentIds, String reason) { }
}
