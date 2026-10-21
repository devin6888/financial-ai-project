package com.finagent.chat;

import com.finagent.retrieval.RetrievalTrace;

import java.util.List;

/**
 * 一次问答的完整轨迹。
 *
 * <p>为什么<b>组合</b> RetrievalTrace 而不是往里加字段：RetrievalTrace 是 15 参 record，
 * 被 21 个检索测试断言，加字段的收益为零、改动面却是 21 处。组合还能让「检索段」与
 * 「装配/生成段」的耗时各归各位。
 */
public record ChatTrace(
        String traceId,
        RetrievalTrace retrieval,
        ScopeTrace scope,
        int contextBlocks,
        int contextTokens,
        int citationsEmitted,
        int citationsDropped,
        long contextMs,
        long answerMs,
        List<String> warnings
) {
    public ChatTrace {
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }

    public long totalMs() {
        return (retrieval == null ? 0 : retrieval.totalMs()) + contextMs + answerMs;
    }
}
