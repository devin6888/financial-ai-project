package com.finagent.answer;

import com.finagent.context.ContextPackage;
import com.finagent.understanding.AnswerShape;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;

import static org.assertj.core.api.Assertions.assertThat;

/** 提示词装配（ST16 Task 1）：骨架必须出现在 SYSTEM，形态要求只在该出现的时候出现。 */
class AnswerPromptsTest {

    @Test
    @DisplayName("SYSTEM 里有四段骨架，并且明确「没有对应形态就省略」")
    void systemContainsSkeleton() {
        assertThat(AnswerPrompts.SYSTEM)
                .contains("答案骨架")
                .contains("① 结论").contains("② 数据").contains("③ 归因").contains("④ 口径与缺口")
                .contains("不要为了凑结构编归因")
                // 段落标签是现在唯一能让四段「看得见」的手段：markdown 标题要等前端渲染器支持
                .contains("「结论：」「数据：」「归因：」「口径：」")
                .contains("四个段标签是骨架的一部分")
                .contains("### 小标题")
                .contains("表格最多 6 列、12 行");
    }

    @Test
    @DisplayName("没有形态 → 不生成【回答要求】段")
    void emptyShapesRenderNothing() {
        assertThat(AnswerPrompts.requirements(EnumSet.noneOf(AnswerShape.class))).isEmpty();
        assertThat(AnswerPrompts.requirements(null)).isEmpty();
    }

    @Test
    @DisplayName("归因/结构/对比各生成一条要求，段末留空行")
    void requirementsPerShape() {
        String text = AnswerPrompts.requirements(
                EnumSet.of(AnswerShape.ATTRIBUTION, AnswerShape.BREAKDOWN, AnswerShape.COMPARISON));

        assertThat(text).startsWith("【回答要求】").endsWith("\n\n");
        assertThat(text).contains("必须回答「为什么」").contains("占比").contains("两个期间并列");
    }

    @Test
    @DisplayName("【回答要求】在【证据】之前（模型先知道怎么写，再读证据）")
    void requirementsComeBeforeEvidence() {
        String prompt = AnswerPrompts.user("为什么增长", ContextPackage.empty(), null,
                EnumSet.of(AnswerShape.ATTRIBUTION));

        assertThat(prompt.indexOf("【回答要求】")).isBetween(0, prompt.indexOf("【证据】"));
        assertThat(prompt).doesNotContain("%s");
    }

    @Test
    @DisplayName("没有形态时提示词里没有空的【回答要求】占位，也没有裸的 %s")
    void noRequirementsBlockWhenNoShape() {
        String prompt = AnswerPrompts.user("2025年营业总收入是多少", ContextPackage.empty());

        assertThat(prompt).doesNotContain("【回答要求】");
        assertThat(prompt).doesNotContain("%s");
    }

    @Test
    @DisplayName("SYSTEM 禁止模型自己做加减乘除（比率只能来自【已计算事实】）")
    void systemForbidsSelfArithmetic() {
        assertThat(AnswerPrompts.SYSTEM)
                .contains("禁止自己把两个证据数字相加、相减或相除")
                .contains("只能来自【已计算事实】");
    }
}
