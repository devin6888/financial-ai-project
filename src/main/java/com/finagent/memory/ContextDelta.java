package com.finagent.memory;

import java.util.List;

/**
 * 一轮问答能提供的结构化信息（合并的输入）。
 *
 * <p>来源只有两处：Query Understanding 的实体（公司代码 / 年份 / 指标）与本轮作用域
 * （用户明确选了哪几份文档）。<b>不来自模型对答案的自由发挥</b>。
 */
public record ContextDelta(Long companyId, Integer yearFrom, Integer yearTo,
                           List<Long> documentIds, String currentMetric) {

    public static final ContextDelta EMPTY = new ContextDelta(null, null, null, List.of(), null);

    public ContextDelta {
        documentIds = documentIds == null ? List.of() : List.copyOf(documentIds);
    }

    public boolean isEmpty() {
        return companyId == null && yearFrom == null && yearTo == null
                && documentIds.isEmpty() && (currentMetric == null || currentMetric.isBlank());
    }
}
