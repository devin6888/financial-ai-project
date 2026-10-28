package com.finagent.understanding;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 指代消解的输入：槽位渲染 + 轮次压缩。 */
class TurnContextTest {

    @Test
    @DisplayName("全空 → isEmpty 且 render 为空串")
    void emptyContext() {
        assertThat(TurnContext.EMPTY.isEmpty()).isTrue();
        assertThat(TurnContext.EMPTY.render()).isEmpty();
        assertThat(TurnContext.EMPTY.companyLabel()).isNull();
        assertThat(TurnContext.EMPTY.yearLabel()).isNull();
    }

    @Test
    @DisplayName("只有年份 + 最近对话也算有上下文（「比去年增长多少」不需要公司槽位）")
    void yearOnlyIsNotEmpty() {
        TurnContext ctx = new TurnContext(null, null, 2025, 2025, List.of(), null,
                List.of(ContextTurn.of("东方财富2025年营收", "1200 亿")));

        assertThat(ctx.isEmpty()).isFalse();
        assertThat(ctx.hasCompany()).isFalse();
        assertThat(ctx.render()).contains("year=2025年").contains("用户：东方财富2025年营收");
    }

    @Test
    @DisplayName("渲染：公司 + 代码 + 年份 + 指标 + 文档 + 最近对话")
    void rendersAllSections() {
        TurnContext ctx = new TurnContext("300059", "东方财富", 2024, 2025, List.of(38L, 7L),
                "营业总收入", List.of(ContextTurn.of("q1", "a1")));

        String text = ctx.render();

        assertThat(text).contains("会话结构化上下文：company=东方财富（300059）");
        assertThat(text).contains("year=2024-2025年");
        assertThat(text).contains("metric=营业总收入");
        assertThat(text).contains("documents=[38, 7]");
        assertThat(text).contains("最近对话（从旧到新）：");
        assertThat(text).contains("用户：q1").contains("助手：a1");
    }

    @Test
    @DisplayName("只有代码没有名字时，公司标签退化成代码")
    void companyLabelFallsBackToCode() {
        TurnContext codeOnly = new TurnContext("300059", null, null, null, List.of(), null, List.of());
        TurnContext nameOnly = new TurnContext(null, "东方财富", null, null, List.of(), null, List.of());

        assertThat(codeOnly.companyLabel()).isEqualTo("300059");
        assertThat(nameOnly.companyLabel()).isEqualTo("东方财富");
    }

    @Test
    @DisplayName("年份标签：单年不写范围，跨年写 from-to")
    void yearLabelFormats() {
        assertThat(new TurnContext(null, null, 2025, null, List.of(), null, List.of()).yearLabel())
                .isEqualTo("2025年");
        assertThat(new TurnContext(null, null, 2024, 2025, List.of(), null, List.of()).yearLabel())
                .isEqualTo("2024-2025年");
    }

    @Test
    @DisplayName("轮次文本每侧截到 200 字，超长加省略号")
    void contextTurnTruncates() {
        String longText = "字".repeat(400);

        ContextTurn turn = ContextTurn.of(longText, longText);

        assertThat(turn.user()).hasSize(ContextTurn.MAX_CHARS + 1).endsWith("…");
        assertThat(turn.render()).contains("用户：").contains("助手：");
    }
}
