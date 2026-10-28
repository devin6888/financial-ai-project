package com.finagent.memory;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 合并优先级：显式覆盖 → 缺省继承 → 推断只在为空时补。 */
class StructuredContextMergerTest {

    private final StructuredContextMerger merger = new StructuredContextMerger();

    @Test
    @DisplayName("用户显式给新公司 → 直接覆盖旧值")
    void explicitOverrides() {
        ConversationContext existing = existing(2L, 2024, 2024, List.of(38L), "营业总收入");

        StructuredContextMerger.Merged merged = merger.merge(existing,
                new ContextDelta(7L, null, null, List.of(), null), true);

        assertThat(merged.companyId()).isEqualTo(7L);
        assertThat(merged.warnings()).isEmpty();
    }

    @Test
    @DisplayName("本轮没提 → 继承上一轮（年份也要继承）")
    void inheritsWhenSilent() {
        ConversationContext existing = existing(2L, 2024, 2024, List.of(38L), "营业总收入");

        StructuredContextMerger.Merged merged = merger.merge(existing, ContextDelta.EMPTY, true);

        assertThat(merged.companyId()).isEqualTo(2L);
        assertThat(merged.yearFrom()).isEqualTo(2024);
        assertThat(merged.yearTo()).isEqualTo(2024);
        assertThat(merged.documentIds()).containsExactly(38L);
        assertThat(merged.currentMetric()).isEqualTo("营业总收入");
    }

    @Test
    @DisplayName("模型推断的值不覆盖已有显式值，只留 warning")
    void inferredDoesNotOverrideExplicit() {
        ConversationContext existing = existing(2L, 2024, 2024, List.of(38L), "营业总收入");

        StructuredContextMerger.Merged merged = merger.merge(existing,
                new ContextDelta(7L, 2025, 2025, List.of(7L), "净利润"), false);

        assertThat(merged.companyId()).isEqualTo(2L);
        assertThat(merged.yearFrom()).isEqualTo(2024);
        assertThat(merged.documentIds()).containsExactly(38L);
        assertThat(merged.currentMetric()).isEqualTo("营业总收入");
        assertThat(merged.warnings()).containsExactly(
                "context_inferred_ignored_company", "context_inferred_ignored_years",
                "context_inferred_ignored_documents", "context_inferred_ignored_metric");
    }

    @Test
    @DisplayName("推断值可以填空白（现有为空时才生效）")
    void inferredFillsEmpty() {
        StructuredContextMerger.Merged merged = merger.merge(null,
                new ContextDelta(7L, 2025, null, List.of(7L), "净利润"), false);

        assertThat(merged.companyId()).isEqualTo(7L);
        assertThat(merged.yearFrom()).isEqualTo(2025);
        assertThat(merged.yearTo()).isEqualTo(2025);
        assertThat(merged.documentIds()).containsExactly(7L);
        assertThat(merged.warnings()).isEmpty();
    }

    @Test
    @DisplayName("清空语义：base 为 null 时只保留本轮给的字段")
    void clearingLeavesOnlyDelta() {
        StructuredContextMerger.Merged merged = merger.merge(null,
                new ContextDelta(7L, null, null, List.of(), null), true);

        assertThat(merged.companyId()).isEqualTo(7L);
        assertThat(merged.yearFrom()).isNull();
        assertThat(merged.documentIds()).isEmpty();
        assertThat(merged.currentMetric()).isNull();
    }

    @Test
    @DisplayName("只给 from 不给 to → 视为同一年")
    void singleYearMeansFromEqualsTo() {
        StructuredContextMerger.Merged merged = merger.merge(existing(2L, null, null, List.of(), null),
                new ContextDelta(null, 2025, null, List.of(), null), true);

        assertThat(merged.yearFrom()).isEqualTo(2025);
        assertThat(merged.yearTo()).isEqualTo(2025);
    }

    private static ConversationContext existing(Long companyId, Integer from, Integer to,
                                               List<Long> docs, String metric) {
        return new ConversationContext(1L, 1L, companyId, "300059", "东方财富", from, to, docs,
                metric, "{}", null);
    }
}
