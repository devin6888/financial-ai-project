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

    /** 数字事实一律来自 PDF 原文（用户 Q1=A：不接 FinancialFact）。 */
    public record Fact(String metric, String period, String value, String unit, String scope,
                       String evidence, String source) {
        public static final String SOURCE_PDF = "PDF";
    }

    /** 证据不足的拒答：不调 LLM、不返回自由文本。 */
    public static AnswerResult refuse(String warning, String message) {
        return new AnswerResult(false, message, List.of(), List.of(),
                CONFIDENCE_INSUFFICIENT, null, List.of(warning), 0);
    }
}
