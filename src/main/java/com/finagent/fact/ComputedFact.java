package com.finagent.fact;

import java.math.BigDecimal;
import java.util.List;

/**
 * 由公式算出来的事实。
 *
 * <p>硬约束：**必须带公式与输入**，否则不许进提示词 —— 让用户能复核「这个百分比是怎么来的」，
 * 也是「系统计算」四个字唯一站得住的理由。
 *
 * @param formula    展开了实际数字的公式串，例如 (16,067,741,039.51 − …) / … × 100
 * @param inputs     参与计算的原值（都来自证据）
 * @param evidenceNos 这些输入所在的证据编号
 * @param note       与证据披露值是否一致之类的说明，可空
 */
public record ComputedFact(String metric, String period, BigDecimal value, String unit, String formula,
                           List<ReportedFact> inputs, List<Integer> evidenceNos, String note) {

    public ComputedFact {
        inputs = inputs == null ? List.of() : List.copyOf(inputs);
        evidenceNos = evidenceNos == null ? List.of() : List.copyOf(evidenceNos);
    }

    /** "E4" 或 "E4,E5"；去重且保序。 */
    public String evidence() {
        StringBuilder sb = new StringBuilder();
        for (Integer no : evidenceNos) {
            if (no == null || no <= 0) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(',');
            }
            sb.append('E').append(no);
        }
        return sb.toString();
    }

    public String display() {
        return UnitConverter.percent(value);
    }
}
