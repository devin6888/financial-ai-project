package com.finagent.fact;

import com.finagent.context.ContextBlock;
import com.finagent.understanding.QueryType;
import com.finagent.understanding.QueryUnderstanding;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class FactCollectorTest {

    private static final String TABLE = """
            |  | 2025年 | 2024年 | 同比增减 |
            | --- | --- | --- | --- |
            | 营业总收入合计 | 16,067,741,039.51 | 11,604,343,090.16 | 38.46% |
            | 证券业 | 12,535,471,036.46 | 8,493,612,633.32 | 47.59% |
            """;

    private static ContextBlock block(int no, long chunkId, String type, String content) {
        return new ContextBlock(no, chunkId, 600L, 7L, "东方财富2025年年度报告", "第三节 § 主营业务分析",
                "主营业务分析", content, 11, 13, type, null, "元", null, null,
                0.9, 42, 300, ContextBlock.ROLE_HIT);
    }

    private static QueryUnderstanding qu(List<String> metrics) {
        return new QueryUnderstanding(QueryType.EXACT_FACT, "东方财富2025年营业总收入是多少", List.of("300059"),
                new int[]{2025, 2025}, metrics, false, false, null, false, "TEST", false, false, List.of());
    }

    @Test
    @DisplayName("表里有问题点名的指标：整张表都抽（分项是归因需要的，不能只留合计）")
    void collectsWholeTableWhenMetricMatched() {
        FactCollector.Result r = FactCollector.collect(
                List.of(block(2, 7246L, "TABLE", TABLE)), qu(List.of("营业总收入")));

        assertThat(r.facts()).anyMatch(f -> f.metric().equals("营业总收入合计") && f.period().equals("2025"));
        assertThat(r.facts()).anyMatch(f -> f.metric().equals("证券业"));
        assertThat(r.facts()).anyMatch(f -> f.metric().equals("证券业") && f.period().equals("2025vs2024"));
        assertThat(r.facts()).allMatch(f -> f.evidenceNo() == 2 && f.chunkId() == 7246L);
        assertThat(r.warnings()).isEmpty();
    }

    @Test
    @DisplayName("正文块不抽事实：正文里的数字没有列语义，强行抽必然错")
    void skipsNonTableBlocks() {
        FactCollector.Result r = FactCollector.collect(
                List.of(block(1, 7238L, "TEXT", "营业总收入 160.68 亿元，同比增长 38.46%")),
                qu(List.of("营业总收入")));

        assertThat(r.facts()).isEmpty();
        assertThat(r.warnings()).isEmpty();
    }

    @Test
    @DisplayName("表里没有点名的指标也没有合计行：整张表跳过")
    void skipsUnrelatedTable() {
        String table = """
                |  | 2025年 | 2024年 |
                | --- | --- | --- |
                | 员工人数 | 5,560 | 5,100 |
                """;

        assertThat(FactCollector.collect(List.of(block(1, 9999L, "TABLE", table)), qu(List.of("营业总收入"))).facts())
                .isEmpty();
    }

    @Test
    @DisplayName("表格形状不支持却含指标名：留 facts_table_skipped 警告（让人知道不是没查，是没抽到）")
    void warnsWhenTableUnparsed() {
        FactCollector.Result r = FactCollector.collect(
                List.of(block(3, 8888L, "TABLE", "营业总收入 16,067,741,039.51 元")),
                qu(List.of("营业总收入")));

        assertThat(r.facts()).isEmpty();
        assertThat(r.warnings()).contains("facts_table_skipped:8888");
    }

    @Test
    @DisplayName("没有点名指标：所有能解析的表都抽")
    void collectsEverythingWhenNoMetricAsked() {
        assertThat(FactCollector.collect(List.of(block(1, 7246L, "TABLE", TABLE)), qu(List.of())).facts())
                .isNotEmpty();
    }

    @Test
    @DisplayName("上限 60 条：大表不会把提示词撑爆")
    void capsReportedFacts() {
        StringBuilder table = new StringBuilder("|  | 2025年 | 2024年 |\n| --- | --- | --- |\n");
        for (int i = 0; i < 40; i++) {
            table.append("| 分项").append(i).append(" | ").append(1000 + i).append(" | ").append(900 + i)
                    .append(" |\n");
        }
        List<ContextBlock> blocks = new ArrayList<>();
        blocks.add(block(1, 7000L, "TABLE", table.toString()));

        assertThat(FactCollector.collect(blocks, qu(List.of())).facts()).hasSize(FactCollector.MAX_REPORTED);
    }
}
