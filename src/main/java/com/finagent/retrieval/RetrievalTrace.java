package com.finagent.retrieval;

import com.finagent.understanding.QueryType;

import java.util.List;

/**
 * 一次检索的完整轨迹（§9.10）。
 *
 * <p>为什么必须留：出问题时「为什么这条没被召回」需要证据。只留最终结果等于把排查线索全丢了。
 *
 * <p><b>Query Understanding 的三个字段不能省</b>（§9.14）：queryType 与 route 是后续
 * 算混淆矩阵的唯一依据 —— 只看最终答案好不好，永远分不清「检索差」和「QU 判错」。
 *
 * @param quRoute      RULE / LLM / NONE / DEGRADED:原因
 * @param quDegraded   QU 是否走了降级路径（与 Rerank 的降级无关）
 * @param fusedCount   RRF 去重融合后的条数（截断前）
 * @param rerankStatus Rerank 的结局，见 {@link RerankStatus}
 * @param rerankModel  实际使用的模型名（SKIPPED 时是装配的那个，便于确认配置）
 */
public record RetrievalTrace(
        String query,
        QueryType queryType,
        String quRoute,
        boolean quDegraded,
        int denseHits,
        int sparseHits,
        int fusedCount,
        int returned,
        RerankStatus rerankStatus,
        String rerankModel,
        long denseMs,
        long sparseMs,
        long fuseMs,
        long rerankMs,
        List<String> warnings
) {
    public RetrievalTrace {
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }

    public long totalMs() {
        return denseMs + sparseMs + fuseMs + rerankMs;
    }

    public boolean rerankDegraded() {
        return rerankStatus == RerankStatus.FAILED;
    }

    /** 日志与冒烟评测用的一行摘要。 */
    public String summary() {
        return "dense=" + denseHits + " sparse=" + sparseHits + " fused=" + fusedCount
                + " returned=" + returned + " rerank=" + rerankStatus
                + " qu=" + queryType + "/" + quRoute + " cost=" + totalMs() + "ms";
    }
}
