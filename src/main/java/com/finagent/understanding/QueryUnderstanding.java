package com.finagent.understanding;

import java.util.List;

/**
 * Query Understanding 的结构化输出（§9.2、ST9 §16）。
 *
 * <p>为什么大多数字段可空：§9.14 要求「<b>单字段校验失败时丢弃该字段，而不是丢弃整个结果</b>」。
 * 所以这里必须允许部分缺失，由 {@link RouterService} 逐字段校验。
 *
 * <p><b>三个名字不要混</b>（ST9 §16.2）：
 * <ul>
 *   <li>{@code companyCodes} —— <b>证券代码</b>（6 位数字，如 300059）。用户字面写了代码时来自原文；
 *       否则由 {@link CompanyResolver} 查库得到。</li>
 *   <li>{@code companyMentions} —— <b>问题原文里的公司名字面</b>（如「东方财富」），由模型抽取，
 *       且必须能逐字回指原文（{@link RouterService#validateLlmUnderstand} 做子串校验，编不出来）。</li>
 *   <li>{@code ResolvedScope.companyIds()} —— <b>数据库 company.id 主键</b>（如 7），与上面两个都不同。</li>
 * </ul>
 *
 * @param queryType          判定的类型；无法判定时为 SEMANTIC_EXPLANATION
 * @param normalizedQuery    可独立检索的归一化查询（多轮时由指代消解补全，单轮基本等于原文）
 * @param companyCodes       证券代码（不是数据库主键，见类注释）
 * @param yearRange          年份范围 [from, to]；单一年份则 from == to
 * @param metrics            命中的财务指标名
 * @param needMultiQuery     是否需要多查询扩展（§9.6：本阶段只判定不执行）
 * @param needDecompose      是否需要查询分解
 * @param docType            报告类型：ANNUAL / SEMI_ANNUAL / Q1 / Q3；未指明为 null
 * @param degraded           是否走了降级路径
 * @param route              走了哪条：RULE / LLM / DEGRADED:原因 / NONE
 * @param inferredFromContext 公司或年份是否来自<b>上文补全</b>（不是用户这一轮说的）
 * @param needLongTermMemory 是否需要召回长期记忆（用户偏好/习惯类问题）。阶段 9 Task 8 的 Gate
 *                           一半靠这个词，另一半靠规则词典 —— 两者任一命中就召回
 * @param companyMentions    问题原文里出现的公司名（字面）。<b>放在最后一个组件是刻意的</b>：
 *                           它和 companyCodes 都是 List&lt;String&gt;，若相邻，旧调用点按位置传参
 *                           会静默错位（把代码当名字），编译器不会报错
 */
public record QueryUnderstanding(
        QueryType queryType,
        String normalizedQuery,
        List<String> companyCodes,
        int[] yearRange,
        List<String> metrics,
        boolean needMultiQuery,
        boolean needDecompose,
        String docType,
        boolean degraded,
        String route,
        boolean inferredFromContext,
        boolean needLongTermMemory,
        List<String> companyMentions
) {
    /**
     * 只带「类型 + 查询」的最小构造：其余字段取默认值（无实体、无年份、路由记为 RULE）。
     *
     * <p>为什么要它：这个 record 有 13 个组件，其中两个相邻的 {@code List<String>}
     * （companyCodes / companyMentions）一旦按位置传参就会静默错位 —— 编译器不报错，
     * 运行时表现为「把公司名当成了证券代码」。所以对外只留两个入口：
     * 这个工厂给出默认值，其余字段用 {@code withXxx} 逐个点名改。
     * 历史上那 4 个「少几个参数」的兼容构造器正是靠位置猜语义，已全部删除。
     */
    public static QueryUnderstanding of(QueryType queryType, String normalizedQuery) {
        return new QueryUnderstanding(queryType, normalizedQuery, List.of(), null, List.of(),
                false, false, null, false, "RULE", false, false, List.of());
    }

    /** 改年份区间（单一年份传同一个数）。 */
    public QueryUnderstanding withYears(int from, int to) {
        return new QueryUnderstanding(queryType, normalizedQuery, companyCodes, new int[]{from, to},
                metrics, needMultiQuery, needDecompose, docType, degraded, route,
                inferredFromContext, needLongTermMemory, companyMentions);
    }

    /** 改年份区间（手里已经是一个 int[] 时用它；null 表示不限年份）。 */
    public QueryUnderstanding withYearRange(int[] range) {
        return new QueryUnderstanding(queryType, normalizedQuery, companyCodes, range, metrics,
                needMultiQuery, needDecompose, docType, degraded, route,
                inferredFromContext, needLongTermMemory, companyMentions);
    }

    /** 改命中的指标名。 */
    public QueryUnderstanding withMetrics(List<String> newMetrics) {
        return new QueryUnderstanding(queryType, normalizedQuery, companyCodes, yearRange, newMetrics,
                needMultiQuery, needDecompose, docType, degraded, route,
                inferredFromContext, needLongTermMemory, companyMentions);
    }

    /** 改路由标记（RULE / LLM / DEGRADED:原因 / NONE）。 */
    public QueryUnderstanding withRoute(String newRoute) {
        return new QueryUnderstanding(queryType, normalizedQuery, companyCodes, yearRange, metrics,
                needMultiQuery, needDecompose, docType, degraded, newRoute,
                inferredFromContext, needLongTermMemory, companyMentions);
    }

    /** 改降级标记。 */
    public QueryUnderstanding withDegraded(boolean isDegraded) {
        return new QueryUnderstanding(queryType, normalizedQuery, companyCodes, yearRange, metrics,
                needMultiQuery, needDecompose, docType, isDegraded, route,
                inferredFromContext, needLongTermMemory, companyMentions);
    }

    /** 改报告类型（ANNUAL / SEMI_ANNUAL / Q1 / Q3）。 */
    public QueryUnderstanding withDocType(String newDocType) {
        return new QueryUnderstanding(queryType, normalizedQuery, companyCodes, yearRange, metrics,
                needMultiQuery, needDecompose, newDocType, degraded, route,
                inferredFromContext, needLongTermMemory, companyMentions);
    }

    /** 改问题原文里的公司名字面（模型抽取的那一层，不是证券代码）。 */
    public QueryUnderstanding withCompanyMentions(List<String> mentions) {
        return new QueryUnderstanding(queryType, normalizedQuery, companyCodes, yearRange, metrics,
                needMultiQuery, needDecompose, docType, degraded, route,
                inferredFromContext, needLongTermMemory, mentions);
    }

    /** 改「是否要多查询扩展」。 */
    public QueryUnderstanding withNeedMultiQuery(boolean need) {
        return new QueryUnderstanding(queryType, normalizedQuery, companyCodes, yearRange, metrics,
                need, needDecompose, docType, degraded, route,
                inferredFromContext, needLongTermMemory, companyMentions);
    }

    /** 改「是否要查询分解」。 */
    public QueryUnderstanding withNeedDecompose(boolean need) {
        return new QueryUnderstanding(queryType, normalizedQuery, companyCodes, yearRange, metrics,
                needMultiQuery, need, docType, degraded, route,
                inferredFromContext, needLongTermMemory, companyMentions);
    }

    /** Sparse 路是否需要走精确子串模式（有实体才走得通）。 */
    public boolean hasEntities() {
        return (companyCodes != null && !companyCodes.isEmpty())
                || (metrics != null && !metrics.isEmpty());
    }

    public boolean fromRule() {
        return "RULE".equals(route);
    }

    /** 是否显式指明了报告类型。 */
    public boolean hasDocType() {
        return docType != null && !docType.isBlank();
    }

    /** 问题里是否提到了公司（任何形式：代码或公司名）。 */
    public boolean mentionsCompany() {
        return (companyCodes != null && !companyCodes.isEmpty())
                || (companyMentions != null && !companyMentions.isEmpty());
    }

    /** 拷一份并改 inferredFromContext（record 没有 setter，只能整份重建）。 */
    public QueryUnderstanding withInferredFromContext(boolean inferred) {
        return new QueryUnderstanding(queryType, normalizedQuery, companyCodes, yearRange, metrics,
                needMultiQuery, needDecompose, docType, degraded, route, inferred, needLongTermMemory,
                companyMentions);
    }

    /**
     * 拷一份并改公司代码：{@link CompanyResolver} 用公司名查库之后回写（ST9 §16.3 第 3 条）。
     *
     * <p>只改代码、不动 normalizedQuery：归一化查询里的公司名本来就是原文抄来的，改代码是为了让
     * Sparse 精确模式拿到正确的「代码词」（词表里拼的是代码）。
     */
    public QueryUnderstanding withCompanyCodes(List<String> codes) {
        return new QueryUnderstanding(queryType, normalizedQuery, codes, yearRange, metrics,
                needMultiQuery, needDecompose, docType, degraded, route, inferredFromContext,
                needLongTermMemory, companyMentions);
    }

    /** 拷一份并改 needLongTermMemory（Gate 判定后回写，便于 trace 看到最终结论）。 */
    public QueryUnderstanding withNeedLongTermMemory(boolean need) {
        return new QueryUnderstanding(queryType, normalizedQuery, companyCodes, yearRange, metrics,
                needMultiQuery, needDecompose, docType, degraded, route, inferredFromContext, need,
                companyMentions);
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
                List.of(), null, List.of(), false, false, null, false, "NONE", false, false, List.of());
    }

    /** QU 自身异常时的保守结果：语义解释 + degraded。 */
    public static QueryUnderstanding failed(String query, String reason) {
        return new QueryUnderstanding(QueryType.SEMANTIC_EXPLANATION, query,
                List.of(), null, List.of(), false, false, null, true, "DEGRADED:" + reason,
                false, false, List.of());
    }
}
