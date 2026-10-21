package com.finagent.retrieval;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Reciprocal Rank Fusion（§9.4）。
 *
 * <p>公式：<b>RRF_score(d) = Σ_m 1 / (k + rank_m(d))</b>，k 默认 60。
 *
 * <p><b>为什么按排名而不按分数融合</b>：Dense 的余弦相似度在 0~1，
 * Sparse 的 ts_rank 在 0.0x 量级 —— 量纲完全不同。直接加权求和等于让 Dense
 * 单方面决定结果；RRF 只看名次，天然免疫分数尺度差异。
 *
 * <p><b>为什么「没召回」必须是 null 而不是 0</b>：k=60 时 1/(60+0)=1/60，
 * 比任何真实命中（最低约 1/(60+30)=1/90）都高。把 null 当 0 会让
 * 「只在某一路出现」的 chunk 分数虚高，融合结果完全失真。
 *
 * <p>注意 RRF <b>治不了</b>「某一路的排序本身很差」—— 它只是把名次换算成分数。
 * Sparse 排序弱的问题由 Rerank 解决，不是 RRF。
 */
public final class RrfFusion {

    public static final int DEFAULT_K = 60;

    public static List<RetrievedChunk> fuse(List<RetrievedChunk> candidates) {
        return fuse(candidates, DEFAULT_K);
    }

    public static List<RetrievedChunk> fuse(List<RetrievedChunk> candidates, int k) {
        if (candidates == null || candidates.isEmpty()) {
            return List.of();
        }
        List<RetrievedChunk> scored = new ArrayList<>(candidates.size());
        for (RetrievedChunk c : candidates) {
            double score = 0.0;
            if (c.denseRank() != null) {
                score += 1.0 / (k + c.denseRank());
            }
            if (c.sparseRank() != null) {
                score += 1.0 / (k + c.sparseRank());
            }
            scored.add(c.withRrfScore(score));
        }
        // 同分按 chunkId 升序：保证结果可复现，否则同一评测集跑两次排序可能不同
        scored.sort(Comparator.comparingDouble(RetrievedChunk::rrfScore).reversed()
                .thenComparingLong(RetrievedChunk::chunkId));
        return scored;
    }

    private RrfFusion() {
    }
}
