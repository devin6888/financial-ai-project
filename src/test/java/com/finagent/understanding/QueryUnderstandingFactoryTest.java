package com.finagent.understanding;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link QueryUnderstanding} 的构造入口（阶段 20 技术债 A1）。
 *
 * <p>这个类原来有 4 个「少几个参数」的兼容构造器（12/11/10/9 参）。删除它们的原因是
 * 位置传参在这里特别危险：{@code companyCodes} 与 {@code companyMentions} 都是
 * {@code List<String>}，错位不会编译报错，只会把公司名当成证券代码。
 * 现在只有两个入口：13 参记录构造器，以及 {@code of(type, query)} + 一族 {@code withXxx}。
 */
class QueryUnderstandingFactoryTest {

    @Test
    @DisplayName("of() 给出全部默认值：无实体、无年份、路由 RULE、不降级")
    void ofGivesDefaults() {
        QueryUnderstanding qu = QueryUnderstanding.of(QueryType.EXACT_FACT, "2025年营业收入");

        assertThat(qu.queryType()).isEqualTo(QueryType.EXACT_FACT);
        assertThat(qu.normalizedQuery()).isEqualTo("2025年营业收入");
        assertThat(qu.companyCodes()).isEmpty();
        assertThat(qu.companyMentions()).isEmpty();
        assertThat(qu.yearRange()).isNull();
        assertThat(qu.metrics()).isEmpty();
        assertThat(qu.docType()).isNull();
        assertThat(qu.route()).isEqualTo("RULE");
        assertThat(qu.degraded()).isFalse();
        assertThat(qu.inferredFromContext()).isFalse();
        assertThat(qu.needLongTermMemory()).isFalse();
        assertThat(qu.needMultiQuery()).isFalse();
        assertThat(qu.needDecompose()).isFalse();
    }

    @Test
    @DisplayName("withCompanyCodes 与 withCompanyMentions 各改各的，绝不串位")
    void codesAndMentionsDoNotMix() {
        QueryUnderstanding qu = QueryUnderstanding.of(QueryType.EXACT_FACT, "东方财富2025年营业收入")
                .withCompanyCodes(List.of("300059"))
                .withCompanyMentions(List.of("东方财富"));

        assertThat(qu.companyCodes()).containsExactly("300059");
        assertThat(qu.companyMentions()).containsExactly("东方财富");
        assertThat(qu.mentionsCompany()).isTrue();
        assertThat(qu.hasEntities()).isTrue();
    }

    @Test
    @DisplayName("withYears 拆两个数，withYearRange 收一个数组，两者等价")
    void yearWithersAreEquivalent() {
        QueryUnderstanding split = QueryUnderstanding.of(QueryType.COMPARISON, "q").withYears(2024, 2025);
        QueryUnderstanding array = QueryUnderstanding.of(QueryType.COMPARISON, "q")
                .withYearRange(new int[]{2024, 2025});

        assertThat(split.yearRange()).containsExactly(2024, 2025);
        assertThat(array.yearRange()).containsExactly(2024, 2025);
    }

    @Test
    @DisplayName("withXxx 只改一个字段，其余字段原样带着走")
    void withersKeepOtherFields() {
        QueryUnderstanding base = QueryUnderstanding.of(QueryType.EXACT_FACT, "q")
                .withCompanyCodes(List.of("300059"))
                .withMetrics(List.of("营业收入"))
                .withDocType("ANNUAL")
                .withRoute("LLM")
                .withDegraded(true);

        QueryUnderstanding changed = base.withNeedLongTermMemory(true);

        assertThat(changed.needLongTermMemory()).isTrue();
        assertThat(changed.queryType()).isEqualTo(QueryType.EXACT_FACT);
        assertThat(changed.companyCodes()).containsExactly("300059");
        assertThat(changed.metrics()).containsExactly("营业收入");
        assertThat(changed.docType()).isEqualTo("ANNUAL");
        assertThat(changed.route()).isEqualTo("LLM");
        assertThat(changed.degraded()).isTrue();
        // 不可变：改出来的是一份新对象，原对象不动
        assertThat(base.needLongTermMemory()).isFalse();
    }

    @Test
    @DisplayName("none() 是刻意的对照基线，与 failed() 必须能区分")
    void noneAndFailedAreDifferent() {
        assertThat(QueryUnderstanding.none("q").route()).isEqualTo("NONE");
        assertThat(QueryUnderstanding.none("q").degraded()).isFalse();
        assertThat(QueryUnderstanding.failed("q", "timeout").route()).isEqualTo("DEGRADED:timeout");
        assertThat(QueryUnderstanding.failed("q", "timeout").degraded()).isTrue();
    }
}
