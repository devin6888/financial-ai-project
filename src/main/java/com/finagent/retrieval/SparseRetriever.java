package com.finagent.retrieval;

import java.util.List;

/**
 * Sparse 召回抽象（§9.4 要求保留可替换性）。
 *
 * <p>为什么要有这层接口：PostgreSQL FTS 的排序与经典 BM25 不等价（无 IDF），
 * 若阶段 6 评测证明排序不足，可以换实现（ParadeDB pg_search / 自算 BM25），
 * <b>而不必改动上层 RRF / Rerank / Parent-Child Pipeline</b>。
 */
public interface SparseRetriever {

    /**
     * @param query  原始查询
     * @param terms  QU 抽出的实体；为空时应传 {@link SparseMode#FTS_OR}
     * @param mode   两种模式之一
     * @return 按相关性降序，sparseRank 从 1 起
     */
    List<RetrievedChunk> retrieve(String query, List<String> terms, SparseMode mode,
                                  RetrievalFilters filters, int topK);
}
