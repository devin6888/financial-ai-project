package com.finagent.agent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 规则式行情意图：只验「判不判」与「判成快照还是历史」，不验公司解析。
 *
 * <p>刻意包含的反例（年报问题）比正例更重要：误判会把「2025 年营收」抢到行情支路，
 * 那种错误用户能立刻看出来，但很难查。
 */
class MarketIntentDetectorTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "东方财富现在股价多少",
            "300059 最新价",
            "东方财富的总市值是多少",
            "东方财富市盈率和市净率",
            "东方财富今天涨跌幅",
            "东方财富现在的行情怎么样"
    })
    @DisplayName("问快照：命中且走 get_quote")
    void quoteIntent(String question) {
        MarketIntent intent = MarketIntentDetector.detect(question);
        assertThat(intent.matched()).isTrue();
        assertThat(intent.history()).isFalse();
        assertThat(intent.tool()).isEqualTo("get_quote");
    }

    @Test
    @DisplayName("问走势：命中且走 get_history，天数从问题里取")
    void historyIntent() {
        MarketIntent intent = MarketIntentDetector.detect("东方财富最近 5 个交易日走势");
        assertThat(intent.matched()).isTrue();
        assertThat(intent.history()).isTrue();
        assertThat(intent.days()).isEqualTo(5);
        assertThat(intent.tool()).isEqualTo("get_history");
    }

    @Test
    @DisplayName("「近两周」折算成 14 天，「近一个月」30 天")
    void weekAndMonthUnits() {
        assertThat(MarketIntentDetector.detect("东方财富近 2 周走势").days()).isEqualTo(14);
        assertThat(MarketIntentDetector.detect("东方财富近 1 个月走势").days()).isEqualTo(30);
    }

    @Test
    @DisplayName("天数上限 120：模型/用户给 999 天也不会把上游打爆")
    void daysClamped() {
        assertThat(MarketIntentDetector.detect("东方财富近 999 天走势").days()).isEqualTo(120);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "东方财富2025年营业总收入是多少",
            "东方财富2025年净利润同比增长多少",
            "东方财富的年报里员工总数是多少",
            "帮我总结一下这份年报"
    })
    @DisplayName("年报问题不算行情")
    void reportQuestionsAreNotMarket(String question) {
        assertThat(MarketIntentDetector.detect(question).matched()).isFalse();
    }

    @Test
    @DisplayName("「市值」+ 年报语境但带走势词 → 仍算行情（历史序列）")
    void reportContextWithHistoryWords() {
        MarketIntent intent = MarketIntentDetector.detect("结合年报说说东方财富今年以来的股价走势");
        assertThat(intent.matched()).isTrue();
        assertThat(intent.history()).isTrue();
    }

    @Test
    @DisplayName("空问题与 null 一律不命中")
    void blank() {
        assertThat(MarketIntentDetector.detect(null)).isEqualTo(MarketIntent.NONE);
        assertThat(MarketIntentDetector.detect("  ")).isEqualTo(MarketIntent.NONE);
    }

    @Test
    @DisplayName("问题里的 6 位代码能抠出来；超过 6 位的数字不算")
    void codeInQuestion() {
        assertThat(MarketIntentDetector.codeInQuestion("300059 现在多少钱")).isEqualTo("300059");
        assertThat(MarketIntentDetector.codeInQuestion("订单号 1234567")).isNull();
        assertThat(MarketIntentDetector.codeInQuestion("没有代码")).isNull();
    }
}
