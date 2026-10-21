package com.finagent.answer;

import java.util.List;

/**
 * 模型必须返回的结构（§9.12）。
 *
 * <p>故意不含任何 id：模型只回证据编号（E1、E2…），chunk_id / 页码 / bbox 全部由后端从
 * {@code ContextBlock} 取。这样「引用必须真实存在」是结构性保证。
 *
 * @param usedEvidence 证据编号列表，允许写成 "E1" 或 "1"
 * @param confidence   high / medium / low；后端不盲信，见 AnswerService 的降级规则
 */
public record AnswerDto(
        boolean sufficient,
        String answer,
        List<FactDto> facts,
        List<String> usedEvidence,
        String confidence,
        String note
) {
    /** 一个数字事实。value 保持字符串，避免 double 把 16,067,741,039.51 变成科学计数法。 */
    public record FactDto(String metric, String period, String value, String unit,
                          String scope, String evidence) {
        public static final String SOURCE_PDF = "PDF";
    }
}
