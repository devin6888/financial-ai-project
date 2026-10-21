package com.finagent.understanding;

import java.util.List;

/**
 * Query Understanding 的结构化输出（§9.2）。
 *
 * <p>为什么大多数字段可空：§9.14 要求「<b>单字段校验失败时丢弃该字段，而不是丢弃整个结果</b>」。
 * 所以这里必须允许部分缺失，由 {@link RouterService} 逐字段校验。
 *
 * @param queryType       判定的类型；无法判定时为 SEMANTIC_EXPLANATION
 * @param normalizedQuery 可独立检索的归一化查询（本阶段只做单轮，基本等于原文）
 * @param companyIds      命中的公司代码（已校验存在于 company 表）
 * @param yearRange       年份范围 [from, to]；单一年份则 from == to
 * @param metrics         命中的财务指标名
 * @param needMultiQuery  是否需要多查询扩展（§9.6：本阶段只判定不执行）
 * @param needDecompose   是否需要查询分解
 * @param docType         报告类型：ANNUAL / SEMI_ANNUAL / Q1 / Q3；未指明为 null
 * @param degraded        是否走了降级路径
 * @param route           走了哪条：RULE / LLM / DEGRADED:原因
 */
public record QueryUnderstanding(
        QueryType queryType,
        String normalizedQuery,
        List<String> companyIds,
        int[] yearRange,
        List<String> metrics,
        boolean needMultiQuery,
        boolean needDecompose,
        String docType,
        boolean degraded,
        String route
) {
    /**
     * 兼容阶段 4 的 9 参调用点（docType 之前不存在）。
     *
     * <p>保留而不是逐个改调用点，是因为那 30 处调用点里没有一处与 docType 相关 ——
     * 改它们只会制造 diff，不会增加任何信息。
     */
    public QueryUnderstanding(QueryType queryType, String normalizedQuery, List<String> companyIds,
                              int[] yearRange, List<String> metrics, boolean needMultiQuery,
                              boolean needDecompose, boolean degraded, String route) {
        this(queryType, normalizedQuery, companyIds, yearRange, metrics, needMultiQuery,
                needDecompose, null, degraded, route);
    }

    /** Sparse 路是否需要走精确子串模式（有实体才走得通）。 */
    public boolean hasEntities() {
        return (companyIds != null && !companyIds.isEmpty())
                || (metrics != null && !metrics.isEmpty());
    }

    public boolean fromRule() {
        return "RULE".equals(route);
    }

    /** 是否显式指明了报告类型。 */
    public boolean hasDocType() {
        return docType != null && !docType.isBlank();
    }

    /**
     * 「不使用 QU」的基线：原样检索，Sparse 走词级召回。
     *
     * <p>它和 {@link #failed} 的区别必须保留：这条是一次<b>刻意的对照</b>
     * （阶段 6 评测「有 QU vs 无 QU」），不是故障。混成一个状态，
     * 评测时会把「基线差」误读成「QU 出故障了」。
     */
    public static QueryUnderstanding none(String query) {
        return new QueryUnderstanding(QueryType.SEMANTIC_EXPLANATION, query,
                List.of(), null, List.of(), false, false, null, false, "NONE");
    }

    /** QU 自身异常时的保守结果：语义解释 + degraded。 */
    public static QueryUnderstanding failed(String query, String reason) {
        return new QueryUnderstanding(QueryType.SEMANTIC_EXPLANATION, query,
                List.of(), null, List.of(), false, false, null, true, "DEGRADED:" + reason);
    }
}
