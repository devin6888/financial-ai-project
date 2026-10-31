package com.finagent.answer;

import com.finagent.citation.Citation;

import java.util.List;

/**
 * 答案的最终形态：结构化、可引用、可解释。
 *
 * @param confidence high / medium / low / insufficient / error
 * @param warnings   降级原因，全部会进 trace 与 API 响应
 */
public record AnswerResult(
        boolean sufficient,
        String answer,
        List<Fact> facts,
        List<Citation> citations,
        String confidence,
        String note,
        List<String> warnings,
        /** 被剔除的引用数（模型编了不存在的编号）；trace 与评测都要看这个数 */
        int citationsDropped
) {
    public static final String CONFIDENCE_HIGH = "high";
    public static final String CONFIDENCE_MEDIUM = "medium";
    public static final String CONFIDENCE_LOW = "low";
    public static final String CONFIDENCE_INSUFFICIENT = "insufficient";
    public static final String CONFIDENCE_ERROR = "error";

    public static final String WARNING_NO_EVIDENCE = "NO_EVIDENCE_IN_SCOPE";
    public static final String WARNING_GENERATION_FAILED = "answer_generation_failed";
    public static final String WARNING_FACTS_TRUNCATED = "facts_truncated";
    public static final String WARNING_MULTI_DOC_CONFLICT = "fact_multi_doc_conflict";
    public static final String WARNING_VALUE_MISMATCH = "fact_value_mismatch";
    public static final String WARNING_CITATIONS_DROPPED = "answer_citations_dropped";
    /** 答案里的数字在被引证据里找不到 → 拒答（阶段 6 评测抓到模型编造员工总数）。 */
    public static final String WARNING_ANSWER_UNGROUNDED = "answer_value_not_in_evidence";
    /** 模型自己判定证据不足（sufficient=false）：即使它不给答案/解释，也要有可读文案与机器可读原因。 */
    public static final String WARNING_EVIDENCE_INSUFFICIENT = "evidence_insufficient";
    /** 单条 fact 的数值在证据里找不到 → 丢弃该 fact。 */
    public static final String WARNING_FACT_UNGROUNDED = "fact_value_not_in_evidence:";

    /** 事实来源三态（ST16 Task 2 / C20.2）：证据直给 / 系统计算 / 系统从证据行抽取。 */
    public static final String ORIGIN_REPORTED = "REPORTED";
    public static final String ORIGIN_COMPUTED = "COMPUTED";
    public static final String ORIGIN_EXTRACTED = "EXTRACTED";

    /** 模型一条 facts 都没给时，后端用抽取结果兜底 —— 必须留痕，否则「facts 是模型给的」这句话就不成立了。 */
    public static final String WARNING_FACTS_BACKFILLED = "facts_backfilled";

    /**
     * 数字事实一律来自 PDF 原文（用户 Q1=A：不接 FinancialFact）。
     *
     * @param origin  REPORTED / COMPUTED / EXTRACTED（C20.2）
     * @param formula 只有 COMPUTED 有：展开实际数字的公式串，供人复核
     */
    public record Fact(String metric, String period, String value, String unit, String scope,
                       String evidence, String source, String origin, String formula) {
        public static final String SOURCE_PDF = "PDF";

        /** 兼容构造：老的调用点（模型给的事实）默认 REPORTED、没有公式。 */
        public Fact(String metric, String period, String value, String unit, String scope,
                    String evidence, String source) {
            this(metric, period, value, unit, scope, evidence, source, ORIGIN_REPORTED, null);
        }
    }

    /** 证据不足的拒答：不调 LLM、不返回自由文本。 */
    public static AnswerResult refuse(String warning, String message) {
        return new AnswerResult(false, message, List.of(), List.of(),
                CONFIDENCE_INSUFFICIENT, null, List.of(warning), 0);
    }
}
