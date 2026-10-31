package com.finagent.understanding;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 答案形态的规则判定（ST16 Task 1）：只验词表与跨年补判，不启动 Spring、不调模型。 */
class AnswerShapeTest {

    @Test
    @DisplayName("问「为什么」→ 归因")
    void detectsAttributionFromWhy() {
        assertThat(AnswerShape.detect("为什么2025年营业收入增长这么快"))
                .containsExactly(AnswerShape.ATTRIBUTION);
    }

    @Test
    @DisplayName("问「主要原因」→ 归因（只有名词「原因」也算）")
    void detectsAttributionFromReason() {
        assertThat(AnswerShape.detect("营业收入增长的主要原因是什么"))
                .contains(AnswerShape.ATTRIBUTION);
    }

    @Test
    @DisplayName("问结构 → 结构")
    void detectsBreakdown() {
        assertThat(AnswerShape.detect("2025年各业务板块收入结构如何"))
                .containsExactly(AnswerShape.BREAKDOWN);
    }

    @Test
    @DisplayName("问同比 → 对比")
    void detectsComparisonFromWords() {
        assertThat(AnswerShape.detect("营业总收入同比变化多少"))
                .containsExactly(AnswerShape.COMPARISON);
    }

    @Test
    @DisplayName("「变化」出现在名词短语里不算对比：应对行业变化的措施问的是措施")
    void changeInNounPhraseIsNotComparison() {
        // 2026-10-31 评测 Q18：判成对比后，提示词要求「两期并列给变化量」，模型直接拒答
        assertThat(AnswerShape.detect("2024年公司应对行业变化的措施是什么？", new int[]{2024, 2024}))
                .isEmpty();
        assertThat(AnswerShape.detect("2024年收入有什么变化", new int[]{2024, 2024}))
                .containsExactly(AnswerShape.COMPARISON);
        assertThat(AnswerShape.detect("2024年员工人数变化了多少", new int[]{2024, 2024}))
                .containsExactly(AnswerShape.COMPARISON);
    }

    @Test
    @DisplayName("跨年（2024→2025）即使没有「对比」两个字也判对比")
    void detectsComparisonFromYearRange() {
        assertThat(AnswerShape.detect("2024年和2025年的营业总收入", new int[]{2024, 2025}))
                .containsExactly(AnswerShape.COMPARISON);
    }

    @Test
    @DisplayName("单一年份不因为 range 触发对比")
    void singleYearDoesNotAddComparison() {
        assertThat(AnswerShape.detect("2025年营业总收入", new int[]{2025, 2025})).isEmpty();
    }

    @Test
    @DisplayName("纯取数没有形态；「增长」本身不是触发词")
    void plainQuestionHasNoShape() {
        assertThat(AnswerShape.detect("2025年营业总收入是多少")).isEmpty();
        assertThat(AnswerShape.detect("营业总收入增长了多少")).isEmpty();
    }

    @Test
    @DisplayName("多形态并存：结构 + 归因 + 跨年对比")
    void combinesShapes() {
        EnumSet<AnswerShape> shapes = AnswerShape.detect(
                "分析2024年和2025年收入结构及原因", new int[]{2024, 2025});

        assertThat(shapes).containsExactlyInAnyOrder(
                AnswerShape.ATTRIBUTION, AnswerShape.BREAKDOWN, AnswerShape.COMPARISON);
    }

    @Test
    @DisplayName("空问题、null range 都不抛异常")
    void toleratesMissingInput() {
        assertThat(AnswerShape.detect(null, null)).isEmpty();
        assertThat(AnswerShape.detect("  ", new int[]{2024, 2025})).contains(AnswerShape.COMPARISON);
        assertThat(AnswerShape.detect("为什么", new int[]{2025})).contains(AnswerShape.ATTRIBUTION);
    }

    @Test
    @DisplayName("状态里存名字、读回来还是同一组；坏值跳过")
    void namesRoundTrip() {
        EnumSet<AnswerShape> shapes = EnumSet.of(AnswerShape.ATTRIBUTION, AnswerShape.COMPARISON);

        List<String> names = AnswerShape.names(shapes);

        assertThat(names).containsExactly("ATTRIBUTION", "COMPARISON");
        assertThat(AnswerShape.parse(names)).isEqualTo(shapes);
        assertThat(AnswerShape.parse(List.of("attribution", "NOPE", ""))).containsExactly(AnswerShape.ATTRIBUTION);
        assertThat(AnswerShape.names(EnumSet.noneOf(AnswerShape.class))).isEmpty();
    }
}
