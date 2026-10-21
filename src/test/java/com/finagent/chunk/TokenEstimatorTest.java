package com.finagent.chunk;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * token 估算的回归测试。
 *
 * <p>为什么需要估算而不引入 tokenizer：§8.2 用 token 数定义 Child（300~700）与 Parent 的粒度，
 * 但切块边界本来就允许误差（设计文档也说"优先按 paragraph 边界拆"）。
 * 引入一个真正的 tokenizer 只为切块边界，是过度依赖。
 */
class TokenEstimatorTest {

    @Test
    void countsChineseCharactersRoughlyOnePerToken() {
        // 中文按「一字约一 token」估（Qwen 系 tokenizer 的常见比例）
        assertThat(TokenEstimator.estimate("东方财富信息股份有限公司")).isBetween(10, 14);
    }

    @Test
    void countsAsciiWordsNotCharacters() {
        // 英文按词算，不能按字符
        assertThat(TokenEstimator.estimate("revenue 2025 annual report")).isBetween(4, 8);
    }

    @Test
    void handlesEmptyAndNull() {
        assertThat(TokenEstimator.estimate("")).isZero();
        assertThat(TokenEstimator.estimate(null)).isZero();
    }

    @Test
    void mixedTextAddsUp() {
        int a = TokenEstimator.estimate("营业收入");
        int b = TokenEstimator.estimate("revenue");
        // 评审 C12：初版断言 a+b+1 是错的 —— 空格不产生 token。
        // 实测 estimate("营业收入 revenue") = 5 = 4 + 1。
        assertThat(TokenEstimator.estimate("营业收入 revenue")).isEqualTo(a + b);
    }

    @Test
    void punctuationCountsAsOneTokenEach() {
        // 标点各算一个（真实财报里括号、顿号很密，忽略它们会低估长度）
        assertThat(TokenEstimator.estimate("（一）")).isEqualTo(3);   // 3 个字符：（ 一 ）
    }
}
