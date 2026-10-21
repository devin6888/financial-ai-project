package com.finagent.understanding;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 报告类型识别的顺序与边界。
 *
 * <p>核心风险只有一个：中文里「半年报」包含「年报」、「半年度报告」包含「年度报告」。
 * 匹配顺序错了不会报错，只会让过滤条件落到错误的文档上 —— 而半年报与年报的报告期差半年。
 */
class DocTypesTest {

    @Test
    @DisplayName("半年报优先于年报（否则「半年报」会被识别成年报）")
    void semiAnnualBeatsAnnual() {
        assertThat(DocTypes.detect("东方财富2024年半年报营业收入")).isEqualTo(DocTypes.SEMI_ANNUAL);
        assertThat(DocTypes.detect("2024年半年度报告")).isEqualTo(DocTypes.SEMI_ANNUAL);
        assertThat(DocTypes.detect("2024年中报")).isEqualTo(DocTypes.SEMI_ANNUAL);
    }

    @Test
    @DisplayName("一季报 / 三季报各自独立，且都不含季度合并成 QUARTERLY 的歧义")
    void quarterlyTypes() {
        assertThat(DocTypes.detect("2025年一季报")).isEqualTo(DocTypes.Q1);
        assertThat(DocTypes.detect("2025年第一季度营业收入")).isEqualTo(DocTypes.Q1);
        assertThat(DocTypes.detect("2025年三季报")).isEqualTo(DocTypes.Q3);
        assertThat(DocTypes.detect("2025年第三季度")).isEqualTo(DocTypes.Q3);
    }

    @Test
    @DisplayName("年报的两种说法都认")
    void annualPhrases() {
        assertThat(DocTypes.detect("2025年年报")).isEqualTo(DocTypes.ANNUAL);
        assertThat(DocTypes.detect("2025年年度报告")).isEqualTo(DocTypes.ANNUAL);
    }

    @Test
    @DisplayName("没提类型就返回 null，不替用户默认成年报")
    void noKeywordMeansNull() {
        assertThat(DocTypes.detect("2025年营业收入是多少")).isNull();
        assertThat(DocTypes.detect("")).isNull();
        assertThat(DocTypes.detect(null)).isNull();
    }

    @Test
    @DisplayName("只有四个合法值，旧枚举值一律不合法")
    void validValues() {
        assertThat(DocTypes.isValid("ANNUAL")).isTrue();
        assertThat(DocTypes.isValid("SEMI_ANNUAL")).isTrue();
        assertThat(DocTypes.isValid("Q1")).isTrue();
        assertThat(DocTypes.isValid("Q3")).isTrue();
        assertThat(DocTypes.isValid("ANNUAL_REPORT")).isFalse();
        assertThat(DocTypes.isValid("QUARTERLY_REPORT")).isFalse();
        assertThat(DocTypes.isValid(null)).isFalse();
    }
}
