package com.finagent.memory;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 渲染：顺序固定、空层省略、禁令句永远在末尾。 */
class MemoryPromptRendererTest {

    private final MemoryPromptRenderer renderer = new MemoryPromptRenderer();

    @Test
    @DisplayName("四层齐 → 顺序：上下文 → 摘要 → 最近对话 → 长期偏好 → 禁令")
    void rendersAllSectionsInOrder() {
        String text = renderer.render(new MemorySections(
                "company=300059（东方财富），year=2025",
                "前两轮问了营业收入与净利润。",
                List.of(message(ConversationMessage.ROLE_USER, "那毛利率呢？"),
                        message(ConversationMessage.ROLE_ASSISTANT, "2025 年毛利率 60.1% [E2]")),
                List.of("回答先给结论，再给证据")));

        assertThat(text).startsWith(MemoryPromptRenderer.HEADER);
        assertThat(text.indexOf("会话上下文")).isLessThan(text.indexOf("此前对话摘要"));
        assertThat(text.indexOf("此前对话摘要")).isLessThan(text.indexOf("最近对话"));
        assertThat(text.indexOf("最近对话")).isLessThan(text.indexOf("长期偏好"));
        assertThat(text).contains("用户：那毛利率呢？");
        assertThat(text).contains("助手：2025 年毛利率 60.1% [E2]");
        assertThat(text).endsWith(MemoryPromptRenderer.FOOTER);
    }

    @Test
    @DisplayName("只有短期 → 不出现别的层标题")
    void omitsEmptySectionsEntirely() {
        String text = renderer.render(new MemorySections(null, null,
                List.of(message(ConversationMessage.ROLE_USER, "第一个问题")), List.of()));

        assertThat(text).doesNotContain("会话上下文");
        assertThat(text).doesNotContain("此前对话摘要");
        assertThat(text).doesNotContain("长期偏好");
        assertThat(text).contains("最近对话（从旧到新）：");
    }

    @Test
    @DisplayName("全空 → null（Prompt 里整段消失，不留占位）")
    void blankSectionsRenderNothing() {
        assertThat(renderer.render(MemorySections.EMPTY)).isNull();
        assertThat(renderer.render(null)).isNull();
    }

    @Test
    @DisplayName("禁令句一定在，且一定在最后（不参与裁剪）")
    void footerIsAlwaysLast() {
        String text = renderer.render(new MemorySections(null, null,
                List.of(message(ConversationMessage.ROLE_USER, "q")), List.of()));

        assertThat(text).contains("不能作为答案依据");
        assertThat(text.lastIndexOf(MemoryPromptRenderer.FOOTER))
                .isEqualTo(text.length() - MemoryPromptRenderer.FOOTER.length());
    }

    private static ConversationMessage message(String role, String content) {
        return new ConversationMessage(1L, 1L, role, content, null, "{}", "trace", 5,
                OffsetDateTime.parse("2026-10-27T12:00:00+08:00"));
    }
}
