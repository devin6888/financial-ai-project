package com.finagent.fact;

import com.finagent.understanding.AnswerShape;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class MetricCalculatorTest {

    private static final long CHUNK = 7246L;

    private static ReportedFact amount(String metric, String period, String value) {
        return new ReportedFact(metric, period, new BigDecimal(value.replace(",", "")),
                ReportedFact.UNIT_YUAN, value, CHUNK, 2);
    }

    private static ReportedFact percent(String metric, String period, String value) {
        return new ReportedFact(metric, period, new BigDecimal(value), ReportedFact.UNIT_PERCENT,
                value + "%", CHUNK, 2);
    }

    /** 7246 的最小复刻：合计 + 两个分行业（含披露的同比与占比）。 */
    private static List<ReportedFact> mainTable() {
        List<ReportedFact> facts = new ArrayList<>();
        facts.add(amount("营业总收入合计", "2025", "16,067,741,039.51"));
        facts.add(amount("营业总收入合计", "2024", "11,604,343,090.16"));
        facts.add(percent("营业总收入合计", "2025vs2024", "38.46"));
        facts.add(amount("分行业/证券业", "2025", "12,535,471,036.46"));
        facts.add(amount("分行业/证券业", "2024", "8,493,612,633.32"));
        facts.add(percent("分行业/证券业", "2025", "78.02"));
        facts.add(percent("分行业/证券业", "2024", "73.19"));
        facts.add(percent("分行业/证券业", "2025vs2024", "47.59"));
        facts.add(amount("分行业/信息技术服务业", "2025", "3,527,509,133.65"));
        facts.add(amount("分行业/信息技术服务业", "2024", "3,105,168,705.16"));
        facts.add(percent("分行业/信息技术服务业", "2025", "21.95"));
        facts.add(percent("分行业/信息技术服务业", "2024", "26.76"));
        facts.add(percent("分行业/信息技术服务业", "2025vs2024", "13.60"));
        return facts;
    }

    private static ComputedFact byName(List<ComputedFact> facts, String name) {
        return facts.stream().filter(f -> f.metric().equals(name)).findFirst().orElseThrow();
    }

    @Test
    @DisplayName("同比增长率：与披露的 38.46% 对得上，note 写明一致，公式里是原始数字")
    void computesYoyAndConfirmsDisclosure() {
        MetricCalculator.Result r = MetricCalculator.compute(mainTable(), Set.of());

        ComputedFact yoy = byName(r.facts(), "营业总收入合计同比增长率");
        assertThat(yoy.value()).isEqualByComparingTo(new BigDecimal("38.46"));
        assertThat(yoy.unit()).isEqualTo("%");
        assertThat(yoy.note()).isEqualTo("与证据披露的 38.46% 一致");
        assertThat(yoy.formula()).contains("16,067,741,039.51").contains("11,604,343,090.16");
        assertThat(yoy.evidence()).isEqualTo("E2");
        assertThat(r.warnings()).isEmpty();

        // 增长额也由系统算（模型自己减会触发「数字不在证据里」告警）
        ComputedFact delta = byName(r.facts(), "营业总收入合计增长额");
        assertThat(delta.value()).isEqualByComparingTo(new BigDecimal("4463397949.35"));
        assertThat(delta.unit()).isEqualTo("元");
        assertThat(delta.formula()).isEqualTo("16,067,741,039.51 − 11,604,343,090.16");
    }

    @Test
    @DisplayName("结构占比 / 占比变动 / 收入贡献度：只在结构或归因类问题下才算")
    void structureOnlyForBreakdownOrAttribution() {
        List<ComputedFact> plain = MetricCalculator.compute(mainTable(), Set.of()).facts();
        assertThat(plain).noneMatch(f -> f.metric().contains("结构占比"));
        assertThat(plain).noneMatch(f -> f.metric().contains("贡献度"));

        MetricCalculator.Result withStructure =
                MetricCalculator.compute(mainTable(), Set.of(AnswerShape.ATTRIBUTION), List.of("营业总收入"));
        List<ComputedFact> facts = withStructure.facts();
        assertThat(byName(facts, "分行业/证券业结构占比").value()).isEqualByComparingTo(new BigDecimal("78.02"));
        assertThat(byName(facts, "分行业/证券业占比变动").value()).isEqualByComparingTo(new BigDecimal("4.83"));
        assertThat(byName(facts, "分行业/证券业占比变动").unit()).isEqualTo("百分点");
        assertThat(byName(facts, "分行业/证券业收入贡献度").value()).isEqualByComparingTo(new BigDecimal("90.56"));
    }

    @Test
    @DisplayName("预算分配：问题点名的「营业总收入同比增长率」与「证券业贡献度」不会被十几条同比挤掉")
    void priorityKeepsAskedMetricAndContribution() {
        List<ReportedFact> facts = new ArrayList<>(mainTable());
        for (int i = 0; i < 12; i++) {
            String metric = "分行业/其他" + i;
            facts.add(amount(metric, "2025", String.valueOf(100_000_000 + i)));
            facts.add(amount(metric, "2024", String.valueOf(90_000_000 + i)));
        }

        List<ComputedFact> out = MetricCalculator.compute(facts, Set.of(AnswerShape.ATTRIBUTION),
                List.of("营业总收入")).facts();

        assertThat(out).hasSize(8);
        assertThat(out.get(0).metric()).isEqualTo("营业总收入合计同比增长率");
        assertThat(out).anyMatch(f -> f.metric().equals("分行业/证券业收入贡献度"));
    }

    @Test
    @DisplayName("上期为 0：不算同比，留 compute_skipped:zero_base")
    void zeroBaseIsSkipped() {
        List<ReportedFact> facts = List.of(
                amount("研发投入", "2025", "100,000,000"),
                amount("研发投入", "2024", "0"));

        MetricCalculator.Result r = MetricCalculator.compute(facts, Set.of());

        assertThat(r.facts()).isEmpty();
        assertThat(r.warnings()).contains("compute_skipped:zero_base:研发投入");
    }

    @Test
    @DisplayName("与披露值冲突：放弃计算值（披露优先），留 compute_conflict")
    void conflictDropsComputedValue() {
        List<ReportedFact> facts = List.of(
                amount("营业总收入合计", "2025", "16,067,741,039.51"),
                amount("营业总收入合计", "2024", "11,604,343,090.16"),
                percent("营业总收入合计", "2025vs2024", "41.00"));

        MetricCalculator.Result r = MetricCalculator.compute(facts, Set.of());

        assertThat(r.facts()).isEmpty();
        assertThat(r.warnings()).contains("compute_conflict:营业总收入合计");
    }

    @Test
    @DisplayName("只有一期数据：不出同比（算不出来就不算，不拿单期硬凑）")
    void singlePeriodYieldsNothing() {
        List<ReportedFact> facts = List.of(amount("研发投入", "2025", "100,000,000"));

        assertThat(MetricCalculator.compute(facts, Set.of()).facts()).isEmpty();
    }

    @Test
    @DisplayName("合计增量为 0：贡献度跳过，避免除零")
    void zeroDeltaSkipsContribution() {
        List<ReportedFact> facts = List.of(
                amount("营业总收入合计", "2025", "1000000"),
                amount("营业总收入合计", "2024", "1000000"),
                amount("分行业/证券业", "2025", "600000"),
                amount("分行业/证券业", "2024", "500000"));

        MetricCalculator.Result r = MetricCalculator.compute(facts, Set.of(AnswerShape.BREAKDOWN));

        assertThat(r.facts()).noneMatch(f -> f.metric().contains("贡献度"));
        assertThat(r.warnings()).anyMatch(w -> w.startsWith("compute_skipped:zero_delta:"));
    }

    @Test
    @DisplayName("负基数：同比用绝对值做分母（-100 → -50 是 +50%，不是 -50%）")
    void negativeBaseUsesAbsoluteDenominator() {
        List<ReportedFact> facts = List.of(
                amount("归母净利润", "2025", "-50"),
                amount("归母净利润", "2024", "-100"));

        ComputedFact yoy = byName(MetricCalculator.compute(facts, Set.of()).facts(), "归母净利润同比增长率");

        assertThat(yoy.value()).isEqualByComparingTo(new BigDecimal("50.00"));
        assertThat(yoy.formula()).contains("|-100|");
    }

    @Test
    @DisplayName("空输入：返回空结果，不抛异常")
    void emptyInput() {
        assertThat(MetricCalculator.compute(List.of(), Set.of()).facts()).isEmpty();
        assertThat(MetricCalculator.compute(null, null).facts()).isEmpty();
    }

    @Test
    @DisplayName("同一个算式只留一条：主表「营业总收入合计」与利润表「一、营业总收入」是同一个数")
    void duplicateMathKeptOnce() {
        List<ReportedFact> facts = new ArrayList<>(mainTable());
        facts.add(amount("一、营业总收入", "2025", "16,067,741,039.51"));
        facts.add(amount("一、营业总收入", "2024", "11,604,343,090.16"));

        List<ComputedFact> out = MetricCalculator.compute(facts, Set.of()).facts();

        assertThat(out).filteredOn(f -> f.metric().endsWith("同比增长率") && f.metric().contains("营业总收入")).hasSize(1);
        assertThat(out).noneMatch(f -> f.metric().startsWith("一、"));
    }
}
