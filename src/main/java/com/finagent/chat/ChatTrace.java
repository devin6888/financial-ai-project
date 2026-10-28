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
        MemoryTrace memory,
        int contextBlocks,
        int contextTokens,
        int citationsEmitted,
        int citationsDropped,
        long contextMs,
        long answerMs,
        // QU（查询理解）耗时；阶段 13 起记录，0 表示未采集。
        long quMs,
        List<String> warnings
) {
    public ChatTrace {
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
        memory = memory == null ? MemoryTrace.EMPTY : memory;
    }

    /** 兼容阶段 8 之前的 10 参调用点（那时还没有记忆段）。 */
    public ChatTrace(String traceId, RetrievalTrace retrieval, ScopeTrace scope, int contextBlocks,
                     int contextTokens, int citationsEmitted, int citationsDropped, long contextMs,
                     long answerMs, List<String> warnings) {
        this(traceId, retrieval, scope, MemoryTrace.EMPTY, contextBlocks, contextTokens,
                citationsEmitted, citationsDropped, contextMs, answerMs, 0, warnings);
    }

    /** 兼容阶段 12 的 11 参调用点（那时还没有 QU 计时）。 */
    public ChatTrace(String traceId, RetrievalTrace retrieval, ScopeTrace scope, MemoryTrace memory,
                     int contextBlocks, int contextTokens, int citationsEmitted, int citationsDropped,
                     long contextMs, long answerMs, List<String> warnings) {
        this(traceId, retrieval, scope, memory, contextBlocks, contextTokens, citationsEmitted,
                citationsDropped, contextMs, answerMs, 0, warnings);
    }

    /** 端到端总耗时：QU + 检索（含精排）+ 上下文装配 + 生成。 */
    public long totalMs() {
        return quMs + (retrieval == null ? 0 : retrieval.totalMs()) + contextMs + answerMs;
    }
}
