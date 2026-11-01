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
    @DisplayName("SYSTEM 用「结论段 + ## 章节 + 注段」三承诺，四段标签已经作废（C25）")
    void systemUsesSectionSkeleton() {
        assertThat(AnswerPrompts.SYSTEM)
                .contains("第一段就是结论")
                .contains("## 一、标题")
                .contains("最后一段是「> 注：…」引用块")
                .contains("引用块是这一段唯一允许的格式标记")
                .contains("这是唯一保留的段标签")
                // 阶段 17：四段标签不许再出现在 SYSTEM 里，否则模型照旧写「结论：」「数据：」
                .doesNotContain("「结论：」「数据：」「归因：」「口径：」")
                .doesNotContain("四个段标签是骨架的一部分")
                .contains("### 小标题")
                .contains("表格最多 6 列、12 行");
    }

    @Test
    @DisplayName("SYSTEM 的三条风格约束（篇幅、不注水、不模板化）")
    void systemHasStyleRules() {
        assertThat(AnswerPrompts.SYSTEM)
                .contains("按信息量控制篇幅")
                .contains("不为凑结构编内容")
                .contains("避免机械模板化措辞");
    }

    @Test
    @DisplayName("有章节计划时【回答要求】按计划逐节展开，并带「优先看 E#」")
    void requirementsFromPlan() {
        com.finagent.context.ContextBlock block = new com.finagent.context.ContextBlock(
                4, 104L, 1L, 7L, "东方财富2024年年度报告", "第三节 § 四、主营业务分析", "费用",
                "| 项目 | 2024年 |\n| 销售费用 | 2,345,678,901.23 |\n| 管理费用 | 1,234,567,890.12 |",
                11, 11, "TABLE", null, "元", null, null,
                0.9, 4, 100, com.finagent.context.ContextBlock.ROLE_HIT);
        java.util.List<com.finagent.context.ContextBlock> blocks = java.util.List.of(block);
        com.finagent.understanding.AnswerSectionPlan plan = com.finagent.understanding.AnswerSectionPlan.of(
                EnumSet.of(AnswerShape.OVERVIEW), com.finagent.context.EvidenceProfile.of(blocks), blocks);

        String text = AnswerPrompts.requirements(EnumSet.of(AnswerShape.OVERVIEW), plan);

        assertThat(text).contains("【回答要求】").contains("概览").contains("一、营业总收入总体情况")
                .contains("成本与费用情况").contains("优先看 E4").endsWith("\n\n");
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
