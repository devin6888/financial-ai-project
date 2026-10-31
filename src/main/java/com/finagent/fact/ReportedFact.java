package com.finagent.fact;

import java.math.BigDecimal;

/**
 * 证据里**直接写着**的事实：值必须能在该块正文里逐字找到，本类不做任何换算。
 *
 * @param metric     指标名（表格行标签，可能带分组前缀，如「分行业/证券业」）
 * @param period     期间：四位数年份（2025 / 2024），或增长率这类「2025vs2024」
 * @param value      数值（元或百分数，见 {@link #unit()}）
 * @param unit       {@link #UNIT_YUAN} 或 {@link #UNIT_PERCENT}
 * @param raw        证据里的原始字面量（含千分位），渲染事实表时照抄它
 * @param chunkId    来源块
 * @param evidenceNo 该块在本次提示词里的编号（E1..En）
 */
public record ReportedFact(String metric, String period, BigDecimal value, String unit, String raw,
                           long chunkId, int evidenceNo) {

    public static final String UNIT_YUAN = "元";
    public static final String UNIT_PERCENT = "%";

    public boolean isAmount() {
        return UNIT_YUAN.equals(unit);
    }

    public boolean isPercent() {
        return UNIT_PERCENT.equals(unit);
    }

    /** "E4"；evidenceNo <= 0 时返回空串（调用方不该依赖它做展示）。 */
    public String evidence() {
        return evidenceNo > 0 ? "E" + evidenceNo : "";
    }

    /** 期间里的四位数年份；解析不了返回 null（增长率那种期间故意解析成 null）。 */
    public Integer year() {
        if (period == null || period.length() != 4) {
            return null;
        }
        for (int i = 0; i < period.length(); i++) {
            if (!Character.isDigit(period.charAt(i))) {
                return null;
            }
        }
        return Integer.valueOf(period);
    }
}
