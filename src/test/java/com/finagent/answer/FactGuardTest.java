package com.finagent.answer;

import com.finagent.answer.AnswerDto.FactDto;
import com.finagent.context.ContextBlock;
import com.finagent.understanding.QueryType;
import com.finagent.understanding.QueryUnderstanding;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 事实守门人：编号解析、跨文档冲突、年份判定。 */
class FactGuardTest {

    private final FactGuard guard = new FactGuard();

    @Test
    @DisplayName("evidence 写法 E12 / [E12] / 12 / E12,E13 都能解析出编号")
    void parsesEvidenceVariants() {
        assertThat(FactGuard.evidenceNo("E12")).isEqualTo(12);
        assertThat(FactGuard.evidenceNo("[E12]")).isEqualTo(12);
        assertThat(FactGuard.evidenceNo("12")).isEqualTo(12);
        assertThat(FactGuard.evidenceNo("E12,E13")).isEqualTo(12);
        assertThat(FactGuard.evidenceNo("E")).isNull();
        assertThat(FactGuard.evidenceNo(null)).isNull();
    }

    @Test
    @DisplayName("两份文档 + 问题年份 2024 只有一份 period=2024 → 只留那份，不降置信")
    void keepsMatchingYear() {
        // doc 38（2024 年报）给 2024 的数，doc 7（2025 年报）给 2023 的对比数
        var r = guard.guard(List.of(fact("2024", "12,000", "E1"), fact("2023", "11,000", "E2")),
                List.of(block(1, 38), block(2, 7)), qu(2024, 2024));

        assertThat(r.facts()).hasSize(1);
        assertThat(r.facts().get(0).value()).isEqualTo("12,000");
        assertThat(r.confidenceCap()).isEqualTo("high");
    }

    @Test
    @DisplayName("两份文档、无问题年份、数值不同 → 全留 + low + 两条 warning + note")
    void keepsBothOnUnresolvedConflict() {
        var r = guard.guard(List.of(fact("2024", "12,000", "E1"), fact("2024", "11,000", "E2")),
                List.of(block(1, 38), block(2, 7)), null);

        assertThat(r.facts()).hasSize(2);
        assertThat(r.confidenceCap()).isEqualTo("low");
        assertThat(r.warnings()).contains("fact_multi_doc_conflict", "fact_value_mismatch");
        assertThat(r.note()).isNotBlank();
    }

    @Test
    @DisplayName("同一份文档的数值千分位差异不算冲突")
    void commaFormattingIsNotConflict() {
        var r = guard.guard(List.of(fact("2024", "12,000.00", "E1"), fact("2024", "12000.00", "E1")),
                List.of(block(1, 38)), qu(2024, 2024));

        assertThat(r.confidenceCap()).isEqualTo("high");
        assertThat(r.warnings()).isEmpty();
    }

    private static FactDto fact(String period, String value, String evidence) {
        return new FactDto("营业总收入", period, value, "元", "合并", evidence);
    }

    private static ContextBlock block(int no, long docId) {
        return new ContextBlock(no, 100 + no, 500L, docId, "报告", "第二节", "第二节", "正文",
                10, 10, "TEXT", null, null, null, null, 0.9, 10, 50, ContextBlock.ROLE_HIT);
    }

    private static QueryUnderstanding qu(int from, int to) {
        return new QueryUnderstanding(QueryType.EXACT_FACT, "q", List.of(), new int[]{from, to},
                List.of(), false, false, null, false, "TEST");
    }
}
