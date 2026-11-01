package com.finagent.understanding;

import com.finagent.context.ContextBlock;
import com.finagent.context.EvidenceProfile;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 章节计划（阶段 17，Task 1）：形态 + 证据画像 → 候选章节。
 *
 * <p>这一组用例守的是「闸门」：章节什么时候<b>允许</b>出现，而不是它写得好不好。
 * 版式好不好看只能人审，但「证据里没有费用表却给了一节成本费用」是能在单测里钉死的。
 */
class AnswerSectionPlanTest {

    private static ContextBlock block(int no, String title, String content, String type) {
        return new ContextBlock(no, 100 + no, 1, 7, "东方财富2024年年度报告",
                "第三节 管理层讨论与分析 § 四、主营业务分析", title, content, 11, 11,
                type, null, "元", null, null, 0.5, no, 100, ContextBlock.ROLE_HIT);
    }

    private static List<ContextBlock> fullEvidence() {
        return List.of(
                block(1, "一、营业总收入", "营业总收入 11,604,343,090.16 元，同比 4.72%", "TEXT"),
                block(2, "分行业", "| 行业 | 2024年 | 2023年 |\n| --- | --- | --- |", "TABLE"),
                block(3, "核心业务", "股基交易额 6.2 万亿元，天天基金上线 160 家基金公司", "TEXT"),
                block(4, "费用", "| 项目 | 2024年 | 同比 |\n| 销售费用 | 5.1 亿 | 3% |\n| 管理费用 | 12 亿 | 5% |",
                        "TABLE"));
    }

    @Test
    @DisplayName("概览 + 全套证据 → 五节，顺序固定")
    void overviewWithFullEvidence() {
        AnswerSectionPlan plan = AnswerSectionPlan.of(Set.of(AnswerShape.OVERVIEW),
                EvidenceProfile.of(fullEvidence()), fullEvidence());

        assertThat(plan.titles()).containsExactly(
                "营业总收入总体情况", "分行业与分产品结构", "核心业务经营数据", "成本与费用情况", "总结");
        assertThat(plan.numberedTitles().get(0)).isEqualTo("一、营业总收入总体情况");
        assertThat(plan.requirement()).contains("【回答要求】").contains("概览").contains("最后一段用「> 注：…」引用块");
    }

    @Test
    @DisplayName("闸门 1：证据里没有费用表 → 不出现「成本与费用」")
    void costSectionNeedsCostTable() {
        List<ContextBlock> noCost = List.of(
                block(1, "一、营业总收入", "营业总收入 11,604,343,090.16 元", "TEXT"),
                block(2, "核心业务", "股基交易额 6.2 万亿元", "TEXT"));

        AnswerSectionPlan plan = AnswerSectionPlan.of(Set.of(AnswerShape.OVERVIEW),
                EvidenceProfile.of(noCost), noCost);

        assertThat(plan.titles()).doesNotContain("成本与费用情况");
    }

    @Test
    @DisplayName("闸门 1 的另一半：成本词在正文里但块不是表格 → 也不算费用表")
    void costWordsInParagraphAreNotCostTable() {
        List<ContextBlock> prose = List.of(block(1, "经营讨论", "公司销售费用同比增长 3%", "TEXT"));

        assertThat(EvidenceProfile.of(prose).hasCostTable()).isFalse();
        assertThat(AnswerSectionPlan.of(Set.of(AnswerShape.OVERVIEW), EvidenceProfile.of(prose), prose).titles())
                .doesNotContain("成本与费用情况");
    }

    @Test
    @DisplayName("闸门 1 的第三个半边：表里只有一个费用科目（如只有营业成本）→ 也不算费用表")
    void singleCostAccountIsNotCostTable() {
        List<ContextBlock> oneWord = List.of(block(1, "分行业成本",
                "| 项目 | 2024年 | 营业成本 |\n| --- | --- | --- |\n| 证券业 | 84.94 亿 | 4.95 亿 |", "TABLE"));

        assertThat(EvidenceProfile.of(oneWord).hasCostTable()).isFalse();
    }

    @Test
    @DisplayName("闸门 5：问句没有分析意图（只要一个数）→ 空计划，哪怕形态是 COMPARISON")
    void questionWithoutAnalysisIntentGetsNoPlan() {
        Set<AnswerShape> shapes = Set.of(AnswerShape.COMPARISON);

        assertThat(AnswerSectionPlan.of(shapes, EvidenceProfile.of(fullEvidence()), fullEvidence(),
                "2025年营业总收入同比增长多少").isEmpty()).isTrue();
        // 同一批证据，问句带分析意图 → 有计划（对比表 + 分项说明）
        assertThat(AnswerSectionPlan.of(shapes, EvidenceProfile.of(fullEvidence()), fullEvidence(),
                "分析下 2025 年营业总收入增长的原因").isEmpty()).isFalse();
    }

    @Test
    @DisplayName("闸门 2：没有经营指标 → 不出现「核心业务经营数据」")
    void operationSectionNeedsMetrics() {
        List<ContextBlock> plain = List.of(block(1, "一、营业总收入", "营业总收入 11,604,343,090.16 元", "TEXT"));

        AnswerSectionPlan plan = AnswerSectionPlan.of(Set.of(AnswerShape.OVERVIEW),
                EvidenceProfile.of(plain), plain);

        assertThat(plan.titles()).doesNotContain("核心业务经营数据");
    }

    @Test
    @DisplayName("闸门 3：候选不足三节时不加「总结」")
    void summaryNeedsAtLeastThreeSections() {
        List<ContextBlock> plain = List.of(block(1, "一、营业总收入", "营业总收入 11,604,343,090.16 元", "TEXT"));

        AnswerSectionPlan plan = AnswerSectionPlan.of(Set.of(AnswerShape.COMPARISON),
                EvidenceProfile.of(plain), plain);

        assertThat(plan.titles()).containsExactly("期间对比", "分项说明");
    }

    @Test
    @DisplayName("闸门 4：形态空 + 画像空 → 空计划（回到一段话 + 注）")
    void emptyShapesAndProfileGiveEmptyPlan() {
        AnswerSectionPlan plan = AnswerSectionPlan.of(Set.of(), EvidenceProfile.EMPTY, List.of());

        assertThat(plan.isEmpty()).isTrue();
        assertThat(plan.requirement()).isEmpty();
        assertThat(AnswerSectionPlan.empty().isEmpty()).isTrue();
    }

    @Test
    @DisplayName("结构类：只要结构表与分项说明，不硬凑总结")
    void breakdownWithoutOverview() {
        AnswerSectionPlan plan = AnswerSectionPlan.of(Set.of(AnswerShape.BREAKDOWN),
                EvidenceProfile.of(fullEvidence()), fullEvidence());

        assertThat(plan.titles()).containsExactly("结构与占比", "分项说明");
    }

    @Test
    @DisplayName("归因类：驱动因素在前，有经营指标才补经营数据")
    void attributionStructure() {
        List<ContextBlock> noMetrics = List.of(block(1, "一、营业总收入", "营业总收入同比增长 4.72%", "TEXT"));

        assertThat(AnswerSectionPlan.of(Set.of(AnswerShape.ATTRIBUTION),
                EvidenceProfile.of(fullEvidence()), fullEvidence()).titles())
                .containsExactly("增长驱动因素", "核心业务经营数据");
        assertThat(AnswerSectionPlan.of(Set.of(AnswerShape.ATTRIBUTION),
                EvidenceProfile.of(noMetrics), noMetrics).titles())
                .containsExactly("增长驱动因素");
    }

    @Test
    @DisplayName("上限 5 节：形态全开 + 证据齐全也只给五节")
    void capsAtFiveSections() {
        Set<AnswerShape> all = Set.of(AnswerShape.OVERVIEW, AnswerShape.BREAKDOWN,
                AnswerShape.ATTRIBUTION, AnswerShape.COMPARISON, AnswerShape.COST);

        AnswerSectionPlan plan = AnswerSectionPlan.of(all, EvidenceProfile.of(fullEvidence()), fullEvidence());

        assertThat(plan.sections()).hasSize(5);
    }

    @Test
    @DisplayName("evidenceNos：命中关键词的块给出编号，且编号落在上下文范围内")
    void evidenceNosPointIntoContext() {
        AnswerSectionPlan plan = AnswerSectionPlan.of(Set.of(AnswerShape.OVERVIEW),
                EvidenceProfile.of(fullEvidence()), fullEvidence());

        AnswerSectionPlan.Section cost = plan.sections().stream()
                .filter(s -> "cost".equals(s.id())).findFirst().orElseThrow();
        AnswerSectionPlan.Section operation = plan.sections().stream()
                .filter(s -> "operation".equals(s.id())).findFirst().orElseThrow();

        assertThat(cost.evidenceNos()).containsExactly("E4");
        assertThat(operation.evidenceNos()).containsExactly("E3");
        assertThat(cost.evidenceNos()).allSatisfy(no ->
                assertThat(Integer.parseInt(no.substring(1))).isBetween(1, fullEvidence().size()));
    }

    @Test
    @DisplayName("evidenceNos：没有对应块时为空列表，不写「无」")
    void evidenceNosEmptyWhenNoMatch() {
        List<String> nos = AnswerSectionPlan.evidenceNos(List.of("客户数", "保有规模"), fullEvidence()
                .subList(0, 1));

        assertThat(nos).isEmpty();
    }

    @Test
    @DisplayName("状态往返：toMap → fromRows 还原出同一批章节与同一段要求")
    void rowsRoundTrip() {
        AnswerSectionPlan plan = AnswerSectionPlan.of(Set.of(AnswerShape.OVERVIEW),
                EvidenceProfile.of(fullEvidence()), fullEvidence());

        List<Map<String, Object>> rows = plan.sections().stream()
                .map(AnswerSectionPlan.Section::toMap).toList();
        AnswerSectionPlan back = AnswerSectionPlan.fromRows(rows, Set.of(AnswerShape.OVERVIEW));

        assertThat(back.titles()).isEqualTo(plan.titles());
        assertThat(back.requirement()).isEqualTo(plan.requirement());
        assertThat(back.sections().get(0).evidenceNos()).isEqualTo(plan.sections().get(0).evidenceNos());
        assertThat(AnswerSectionPlan.fromRows(List.of(), Set.of()).isEmpty()).isTrue();
    }
}
