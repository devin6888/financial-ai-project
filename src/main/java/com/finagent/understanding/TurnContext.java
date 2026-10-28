package com.finagent.understanding;

import java.util.List;

/**
 * 指代消解的输入：会话已确认的结构化槽位 + 最近几轮原文（压缩后）。
 *
 * <p>两类信息分工不同，缺一不可：
 * <ul>
 *   <li><b>结构化槽位</b>（公司 / 年份 / 文档 / 指标）：确定的事实，可以直接补进查询；</li>
 *   <li><b>最近几轮原文</b>：让模型看懂「它」「那」指的是什么，尤其是「那净利润呢」这种
 *       只换了指标的追问 —— 槽位里没有指标，只能从上一轮推断。</li>
 * </ul>
 *
 * <p>{@link #EMPTY} 是「没有会话」的唯一表示；{@code isEmpty()} 为真时 RouterService 走单轮路径，
 * 行为与阶段 8 完全一致。
 */
public record TurnContext(String companyCode, String companyName, Integer yearFrom, Integer yearTo,
                          List<Long> documentIds, String currentMetric, List<ContextTurn> recent) {

    public static final TurnContext EMPTY = new TurnContext(null, null, null, null,
            List.of(), null, List.of());

    public TurnContext {
        documentIds = documentIds == null ? List.of() : List.copyOf(documentIds);
        recent = recent == null ? List.of() : List.copyOf(recent);
        companyCode = blankToNull(companyCode);
        companyName = blankToNull(companyName);
        currentMetric = blankToNull(currentMetric);
    }

    /** 什么都没有 → 不需要消解（不是「补不出」）。 */
    public boolean isEmpty() {
        return companyCode == null && companyName == null && yearFrom == null && recent.isEmpty();
    }

    public boolean hasCompany() {
        return companyCode != null || companyName != null;
    }

    public boolean hasYear() {
        return yearFrom != null;
    }

    /** 「东方财富（300059）」；只有代码或只有名字时退化成那一个。 */
    public String companyLabel() {
        if (companyName != null && companyCode != null) {
            return companyName + "（" + companyCode + "）";
        }
        return companyName != null ? companyName : companyCode;
    }

    /** 「2025年」/「2024-2025年」；没有年份返回 null。 */
    public String yearLabel() {
        if (yearFrom == null) {
            return null;
        }
        int to = yearTo == null ? yearFrom : yearTo;
        return to == yearFrom ? yearFrom + "年" : yearFrom + "-" + to + "年";
    }

    /** 喂给 LLM 的文本块；全空返回空串（调用方据此走单轮路径）。 */
    public String render() {
        StringBuilder sb = new StringBuilder();
        if (hasCompany() || hasYear() || currentMetric != null) {
            sb.append("会话结构化上下文：");
            boolean first = true;
            if (hasCompany()) {
                sb.append("company=").append(companyLabel());
                first = false;
            }
            if (hasYear()) {
                sb.append(first ? "" : "，").append("year=").append(yearLabel());
                first = false;
            }
            if (currentMetric != null) {
                sb.append(first ? "" : "，").append("metric=").append(currentMetric);
                first = false;
            }
            if (!documentIds.isEmpty()) {
                sb.append(first ? "" : "，").append("documents=").append(documentIds);
            }
            sb.append('\n');
        }
        if (!recent.isEmpty()) {
            sb.append("最近对话（从旧到新）：\n");
            for (ContextTurn turn : recent) {
                String line = turn.render();
                if (!line.isBlank()) {
                    sb.append(line).append('\n');
                }
            }
        }
        return sb.toString().strip();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
