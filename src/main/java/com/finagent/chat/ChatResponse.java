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
        ExtensionSuggestion extensionSuggested
) {
    public ChatResponse {
        facts = facts == null ? List.of() : List.copyOf(facts);
        citations = citations == null ? List.of() : List.copyOf(citations);
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }

    /** 建议把某几份文档加入本轮范围。 */
    public record ExtensionSuggestion(List<Long> documentIds, String reason) { }
}
