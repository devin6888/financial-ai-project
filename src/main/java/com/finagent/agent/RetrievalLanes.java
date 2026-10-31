package com.finagent.agent;

import com.finagent.scope.ResolvedScope;
import com.finagent.understanding.AnswerShape;

import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 车道规划与年份覆盖校验（ST16 Task 3）。
 *
 * <p>**为什么单独成类**：这两件事都是纯函数（不查库、不调模型），可以脱离 Spring 测；
 * 而 {@code RealAnalysisNodes.retrieve} 里已经有一堆 IO，把判断逻辑埋进去就只能整类集成测试。
 *
 * <p>规则表（与计划 §3.2 一致）：
 *
 * <pre>
 * 车道          什么时候加                          过滤               topK
 * 整句          永远                                空                 8
 * 子问题        needDecompose 且拆出 >= 1 条        空                 8
 * 按年份        yearRange 跨年，每年一条，最多 3 年  docYear = Y        4
 * 章节定向      形态命中 ATTRIBUTION 或 BREAKDOWN    sectionPath 前缀   4
 * </pre>
 */
final class RetrievalLanes {

    /** 章节定向用的前缀：主营业务分析、分行业分产品、经营情况都在第三节。 */
    static final String SECTION_PREFIX = "第三节";

    /** 章节车道的 topK：比整句少一半，控制 rerank 开销。 */
    static final int SECTION_TOP_K = 4;

    /** 年份车道的 topK。 */
    static final int YEAR_TOP_K = 4;

    /** 补跑时的 topK：缺年份说明这一路之前没拿到，给足预算。 */
    static final int YEAR_RETRY_TOP_K = 8;

    /** 最多支持几年的区间；再宽就不是「年」的问题了，退化为不加年份过滤。 */
    static final int MAX_YEAR_LANES = 3;

    static final String WARN_YEAR_TOO_WIDE = "year_range_too_wide";
    static final String WARN_YEAR_MISSING_PREFIX = "year_coverage_missing:";
    /** 补跑只在第一轮做：有这个标记就不再补。 */
    static final String WARN_YEAR_RETRIED = "year_lane_retried";

    private RetrievalLanes() {
    }

    /**
     * 规划车道。
     *
     * @param question    原始问题（整句车道的文本在 normalized 为空时用它）
     * @param normalized  QU 归一化后的问题
     * @param subQuestions 拆出来的子问题（已自包含）
     * @param shapes      答案形态
     * @param yearRange   QU 的年份区间，可为 null
     * @param scopeYears  作用域里实际有哪些年份（空 = 不限，视为全库）
     * @param warnings    出参：宽度超限、缺年份的告警写这里
     */
    static List<RetrievalLane> plan(String question, String normalized, List<String> subQuestions,
                                   Collection<AnswerShape> shapes, int[] yearRange,
                                   Set<Integer> scopeYears, List<String> warnings) {
        List<RetrievalLane> lanes = new ArrayList<>();
        String full = normalized == null || normalized.isBlank() ? question : normalized;
        lanes.add(RetrievalLane.full(full));

        if (subQuestions != null) {
            int i = 0;
            for (String sub : subQuestions) {
                if (sub != null && !sub.isBlank()) {
                    lanes.add(RetrievalLane.sub(i, sub));
                }
                i++;
            }
        }

        Set<AnswerShape> set = EnumSet.noneOf(AnswerShape.class);
        if (shapes != null) {
            set.addAll(shapes);
        }
        if (set.contains(AnswerShape.ATTRIBUTION) || set.contains(AnswerShape.BREAKDOWN)) {
            lanes.add(RetrievalLane.section(full, SECTION_TOP_K));
        }

        for (Integer year : yearsOf(yearRange, warnings)) {
            // 作用域里根本没有这一年的文档：加一路只会白花一次 rerank，
            // 直接记告警，让答案第 ④ 段写「作用域内无该年文档」
            if (!scopeYears.isEmpty() && !scopeYears.contains(year)) {
                addOnce(warnings, WARN_YEAR_MISSING_PREFIX + year);
                continue;
            }
            lanes.add(RetrievalLane.year(year, full, YEAR_TOP_K));
        }
        return lanes;
    }

    /**
     * 年份区间 → 逐年列表。
     *
     * <p>三条边界：区间为空或单年 → 空（单年问题不需要按年分路，QU 的年份过滤本来就生效）；
     * 区间超过 3 年 → 空 + 告警（车道爆炸，退化为不加年份过滤）；
     * 区间倒置（2025~2024）→ 按大小排序后处理，不抛异常。
     */
    static List<Integer> yearsOf(int[] range, List<String> warnings) {
        if (range == null || range.length < 2) {
            return List.of();
        }
        int from = Math.min(range[0], range[1]);
        int to = Math.max(range[0], range[1]);
        if (from <= 0 || to <= 0 || from == to) {
            return List.of();
        }
        int count = to - from + 1;
        if (count > MAX_YEAR_LANES) {
            if (warnings != null) {
                addOnce(warnings, WARN_YEAR_TOO_WIDE);
            }
            return List.of();
        }
        List<Integer> years = new ArrayList<>(count);
        for (int y = to; y >= from; y--) {
            years.add(y);
        }
        return years;
    }

    /**
     * 合并之后，哪些年份一条证据都没有。
     *
     * <p>判定依据是命中块的 documentId → document.doc_year（作用域里已有这份映射，
     * 不再单独查库）：车道过滤只保证「检索时限定在这一年」，
     * 但检索可能一条都没命中，所以要用结果反过来校验。
     */
    static List<Integer> missingYears(List<Map<String, Object>> hits, ResolvedScope scope, int[] range) {
        List<Integer> years = yearsOf(range, null);
        if (years.isEmpty() || scope == null) {
            return List.of();
        }
        Set<Integer> inScope = scope.docYears();
        Set<Integer> hitYears = new LinkedHashSet<>();
        if (hits != null) {
            for (Map<String, Object> hit : hits) {
                Object docIdValue = hit.get("documentId");
                if (!(docIdValue instanceof Number number)) {
                    continue;
                }
                long docId = number.longValue();
                scope.documents().stream()
                        .filter(d -> d.id() == docId && d.docYear() != null)
                        .findFirst()
                        .ifPresent(d -> hitYears.add(d.docYear()));
            }
        }
        List<Integer> missing = new ArrayList<>();
        for (Integer year : years) {
            if (inScope.isEmpty() || inScope.contains(year)) {
                if (!hitYears.contains(year)) {
                    missing.add(year);
                }
            }
        }
        return missing;
    }

    private static void addOnce(List<String> warnings, String warning) {
        if (warnings != null && !warnings.contains(warning)) {
            warnings.add(warning);
        }
    }
}
