package com.finagent.answer;

import com.finagent.context.ContextPackage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Prompt 结构：背景信息在证据之前，且没有背景时不出现该段。 */
class AnswerPromptsTest {

    @Test
    @DisplayName("有背景 → 背景整段在【证据】之前")
    void backgroundGoesBeforeEvidence() {
        String prompt = AnswerPrompts.user("那净利润呢？", ContextPackage.empty(),
                "【背景信息 · 不可引用】\n用户：2025 年营业总收入是多少？");

        assertThat(prompt.indexOf("【问题】")).isLessThan(prompt.indexOf("【背景信息"));
        assertThat(prompt.indexOf("【背景信息")).isLessThan(prompt.indexOf("【证据】"));
        assertThat(prompt).contains("那净利润呢？");
    }

    @Test
    @DisplayName("没有背景 → 不出现「背景信息」字样，也不留空行占位")
    void noBackgroundNoSection() {
        String prompt = AnswerPrompts.user("q", ContextPackage.empty(), null);

        assertThat(prompt).doesNotContain("背景信息");
        assertThat(prompt).contains("【问题】").contains("【证据】");
    }

    @Test
    @DisplayName("空白背景当成没有")
    void blankBackgroundTreatedAsAbsent() {
        assertThat(AnswerPrompts.user("q", ContextPackage.empty(), "   "))
                .doesNotContain("背景信息");
    }
}
