package com.finagent.understanding;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RuleBasedRouterTest {

    private final RuleBasedRouter router = new RuleBasedRouter(new MetricDictionary());

    @Test
    void routesExactFactWhenAllThreePresent() {
        var u = router.tryRoute("600519 2025年营业收入是多少");
        assertThat(u).isNotNull();
        assertThat(u.queryType()).isEqualTo(QueryType.EXACT_FACT);
        assertThat(u.companyCodes()).containsExactly("600519");
        assertThat(u.yearRange()).containsExactly(2025, 2025);
        assertThat(u.metrics()).contains("营业收入");
        assertThat(u.route()).isEqualTo("RULE");
    }

    @Test
    void returnsNullWhenYearMissing() {
        // §9.14 第三层：三者缺一即证据不足，必须交给 LLM
        assertThat(router.tryRoute("600519 营业收入是多少")).isNull();
    }

    @Test
    void returnsNullWhenMetricMissing() {
        assertThat(router.tryRoute("600519 2025年情况如何")).isNull();
    }

    @Test
    void returnsNullWhenCodeMissing() {
        assertThat(router.tryRoute("2025年营业收入是多少")).isNull();
    }

    @Test
    void doesNotTreatMoneyAsStockCode() {
        // 「11842000」里的 6 位数字不能被当成股票代码
        assertThat(router.tryRoute("2025年营业收入 11842000 元")).isNull();
    }

    @Test
    void prefersLongestMetricName() {
        var u = router.tryRoute("600519 2025年营业总收入");
        assertThat(u).isNotNull();
        assertThat(u.metrics().get(0)).isEqualTo("营业总收入");
    }

    @Test
    void capturesYearRangeFromComparison() {
        var u = router.tryRoute("600519 2023年到2025年营业收入变化");
        assertThat(u).isNotNull();
        assertThat(u.yearRange()).containsExactly(2023, 2025);
    }

    @Test
    void blankOrNullQueryReturnsNull() {
        assertThat(router.tryRoute(null)).isNull();
        assertThat(router.tryRoute("   ")).isNull();
    }

    @Test
    void acceptsAllAShareCodePrefixes() {
        for (String code : new String[]{"600519", "688111", "000001", "300059"}) {
            assertThat(router.tryRoute(code + " 2025年净利润")).as(code).isNotNull();
        }
    }
}
