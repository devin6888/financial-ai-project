package com.finagent.retrieval;

/**
 * 一次检索请求。
 *
 * <p>默认值直接取 §9.13 的配额。用紧凑构造器兜住非法值 ——
 * 调用方传 0 或负数时应当<b>回落到默认</b>，而不是变成「什么都不召回却不报错」。
 *
 * @param topDense    Dense 单路召回条数（§9.13：30）
 * @param topSparse   Sparse 单路召回条数（§9.13：30）
 * @param fusionLimit RRF 之后截断到多少再送 Rerank（§9.13：50）
 * @param topRerank   Rerank 输出条数（§9.13：8）
 * @param useRerank   是否精排；关闭时直接返回 RRF 结果（评测时用于对比）
 */
public record RetrievalRequest(
        String query,
        RetrievalFilters filters,
        int topDense,
        int topSparse,
        int fusionLimit,
        int topRerank,
        boolean useRerank
) {
    public static final int DEFAULT_TOP_DENSE = 30;
    public static final int DEFAULT_TOP_SPARSE = 30;
    public static final int DEFAULT_FUSION_LIMIT = 50;
    public static final int DEFAULT_TOP_RERANK = 8;

    public RetrievalRequest {
        if (query == null || query.isBlank()) {
            throw new IllegalArgumentException("query 不能为空");
        }
        query = query.strip();
        filters = filters == null ? RetrievalFilters.none() : filters;
        topDense = topDense <= 0 ? DEFAULT_TOP_DENSE : topDense;
        topSparse = topSparse <= 0 ? DEFAULT_TOP_SPARSE : topSparse;
        fusionLimit = fusionLimit <= 0 ? DEFAULT_FUSION_LIMIT : fusionLimit;
        topRerank = topRerank <= 0 ? DEFAULT_TOP_RERANK : topRerank;
    }

    /** 用 §9.13 的默认配额。 */
    public static RetrievalRequest of(String query) {
        return new RetrievalRequest(query, null, 0, 0, 0, 0, true);
    }

    public static RetrievalRequest of(String query, RetrievalFilters filters) {
        return new RetrievalRequest(query, filters, 0, 0, 0, 0, true);
    }
}
