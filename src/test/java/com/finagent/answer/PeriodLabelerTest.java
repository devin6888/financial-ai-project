package com.finagent.answer;

import com.finagent.citation.Citation;
import com.finagent.scope.DocMeta;
import com.finagent.scope.ResolvedScope;
import com.finagent.scope.ScopeKind;
import com.finagent.understanding.QueryType;
import com.finagent.understanding.QueryUnderstanding;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 期间标注：引用文档年份与问题年份不一致时必须留痕，且不改数字。 */
class PeriodLabelerTest {

    private final PeriodLabeler labeler = new PeriodLabeler();

    @Test
    @DisplayName("问 2023、引用来自 2024 年报 → warning + note")
    void flagsMismatch() {
        var r = labeler.label(qu(2023, 2023), List.of(citation(38)), scope());

        assertThat(r.warnings()).containsExactly("evidence_period_mismatch:2023@2024");
        assertThat(r.note()).contains("2024").contains("对比列");
    }

    @Test
    @DisplayName("问 2024、引用来自 2024 年报 → 不留痕")
    void noFlagWhenSameYear() {
        var r = labeler.label(qu(2024, 2024), List.of(citation(38)), scope());

        assertThat(r.warnings()).isEmpty();
        assertThat(r.note()).isNull();
    }

    @Test
    @DisplayName("跨年对比题（from != to）→ 不做单年份判定，避免误报")
    void skipsRange() {
        var r = labeler.label(qu(2024, 2025), List.of(citation(7), citation(38)), scope());

        assertThat(r.warnings()).isEmpty();
        assertThat(r.note()).isNull();
    }

    @Test
    @DisplayName("引用来自多个错误年份 → 每个年份一条 warning，note 里都列出")
    void multipleYears() {
        var r = labeler.label(qu(2023, 2023), List.of(citation(7), citation(38)), scope());

        assertThat(r.warnings()).containsExactly("evidence_period_mismatch:2023@2025", "evidence_period_mismatch:2023@2024");
        assertThat(r.note()).contains("2025").contains("2024");
    }

    private static Citation citation(long docId) {
        return new Citation(docId, 1L, 2L, 10, 10, null, null, "TEXT", null, null, "报告", "第八节");
    }

    private static ResolvedScope scope() {
        return ResolvedScope.of(ScopeKind.DOCUMENTS, List.of(doc(7, 2025), doc(38, 2024)),
                ResolvedScope.REASON_SESSION, List.of());
    }

    private static DocMeta doc(long id, int year) {
        return new DocMeta(id, 2L, "300059", "东方财富", year, "ANNUAL",
                LocalDate.of(year, 1, 1), LocalDate.of(year, 12, 31), 1, "东方财富" + year + "年年度报告");
    }

    private static QueryUnderstanding qu(int from, int to) {
        return new QueryUnderstanding(QueryType.EXACT_FACT, "q", List.of("300059"), new int[]{from, to},
                List.of(), false, false, null, false, "TEST");
    }
}
