package com.finagent.memory;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 会话的结构化上下文（设计 §11.10）：公司、年份范围、文档范围、当前指标。
 *
 * <p>它解决的是「那净利润呢」这类追问里<b>缺失的主语</b>：这句话本身没有公司、没有年份，
 * 只有会话上下文里有。存的是<b>状态</b>，不是事件 —— 每会话一行，覆盖写（D9）。
 *
 * @param companyCode / companyName 读时从 company 表 join 出来，不冗余存（公司改名要能跟着变）
 * @param contextJson 完整快照，便于回放「当时会话里到底是什么」
 */
public record ConversationContext(
        long conversationId,
        long tenantId,
        Long companyId,
        String companyCode,
        String companyName,
        Integer yearFrom,
        Integer yearTo,
        List<Long> documentIds,
        String currentMetric,
        String contextJson,
        OffsetDateTime updatedAt
) {

    public ConversationContext {
        documentIds = documentIds == null ? List.of() : List.copyOf(documentIds);
    }

    public boolean isEmpty() {
        return companyId == null && yearFrom == null && yearTo == null
               && documentIds.isEmpty() && (currentMetric == null || currentMetric.isBlank());
    }

    /**
     * 渲染成一行给人/模型看的文字。
     *
     * <p>为什么这一行要带公司名而不只是代码：模型看到 300059 未必立刻对上「东方财富」，
     * 而追问补全最需要的就是这个名字。
     */
    public String render() {
        List<String> parts = new ArrayList<>();
        if (companyId != null) {
            String name = companyName == null || companyName.isBlank() ? "" : "（" + companyName + "）";
            parts.add("company=" + (companyCode == null ? companyId : companyCode) + name);
        }
        if (yearFrom != null) {
            parts.add("year=" + (yearTo == null || yearTo.equals(yearFrom)
                    ? String.valueOf(yearFrom) : yearFrom + "~" + yearTo));
        }
        if (!documentIds.isEmpty()) {
            parts.add("document=" + documentIds.stream().map(String::valueOf).reduce((a, b) -> a + "、" + b).orElse(""));
        }
        if (currentMetric != null && !currentMetric.isBlank()) {
            parts.add("metric=" + currentMetric);
        }
        return String.join("，", parts);
    }
}
