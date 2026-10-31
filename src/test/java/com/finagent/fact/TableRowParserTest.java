package com.finagent.fact;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TableRowParserTest {

    /** 真实块 7246（2025 年报「主营业务分行业/分产品/分地区」表）的正文，两组合并表头。 */
    private static final String MAIN_TABLE = """
            单位：元
            |  | 2025年 | 2025年 | 2025年 | 2024年 | 同比增减 |
            | --- | --- | --- | --- | --- | --- |
            |  | 金额 | 占营业总收入比重 | 金额 | 占营业总收入比重 |  |
            | 营业总收入合计 | 16,067,741,039.51 | 100% | 11,604,343,090.16 | 100% | 38.46% |
            | 分行业 |  |  |  |  |  |
            | 证券业 | 12,535,471,036.46 | 78.02% | 8,493,612,633.32 | 73.19% | 47.59% |
            | 信息技术服务业 | 3,527,509,133.65 | 21.95% | 3,105,168,705.16 | 26.76% | 13.60% |
            | 分产品 |  |  |  |  |  |
            | 证券服务 | 12,535,471,036.46 | 78.02% | 8,493,612,633.32 | 73.19% | 47.59% |
            | 金融数据服务 | 240,327,781.69 | 1.50% | 191,981,245.64 | 1.65% | 25.18% |
            """;

    /** 真实块 7216（主要会计数据）：中途换一段表头（2025年末 / 2024年末）。 */
    private static final String TWO_SEGMENTS = """
            |  | 2025年 | 2024年 | 本年比上年 增减 | 2023年 |
            | --- | --- | --- | --- | --- |
            | 营业总收入（元） | 16,067,741,039.51 | 11,604,343,090.16 | 38.46% | 11,081,439,282.37 |
            | 基本每股收益（元/股） | 0.7650 | 0.6087 | 25.68% | 0.5168 |
            |  | 2025年末 | 2024年末 | 本年末比上 年末增减 | 2023年末 |
            | 资产总额（元） | 392,933,744,976.20 | 306,369,357,347.30 | 28.25% | 239,578,320,552.16 |
            """;

    @Test
    @DisplayName("合并表头：2025 的金额写进 2025 列，2024 的金额写进 2024 列（不许错位）")
    void mergedHeaderKeepsYearsStraight() {
        List<TableRowParser.Row> rows = TableRowParser.parse(MAIN_TABLE);

        TableRowParser.Row total = rows.stream()
                .filter(r -> r.label().startsWith("营业总收入合计"))
                .findFirst().orElseThrow();
        assertThat(total.amounts().get(2025).value()).isEqualByComparingTo(new BigDecimal("16067741039.51"));
        assertThat(total.amounts().get(2024).value()).isEqualByComparingTo(new BigDecimal("11604343090.16"));
        assertThat(total.percents().get(2025).value()).isEqualByComparingTo(new BigDecimal("100"));
        assertThat(total.growth().value()).isEqualByComparingTo(new BigDecimal("38.46"));
        // 原始字面量要保留（公式与事实表照抄证据里的数字）
        assertThat(total.amounts().get(2025).raw()).isEqualTo("16,067,741,039.51");
    }

    @Test
    @DisplayName("分段标签（分行业 / 分产品）写进 group，行标签本身不带前缀")
    void keepsSectionAsGroup() {
        List<TableRowParser.Row> rows = TableRowParser.parse(MAIN_TABLE);

        assertThat(rows.stream().filter(r -> r.label().equals("证券业")).findFirst().orElseThrow().group())
                .isEqualTo("分行业");
        assertThat(rows.stream().filter(r -> r.label().equals("证券服务")).findFirst().orElseThrow().group())
                .isEqualTo("分产品");
        assertThat(rows.stream().filter(r -> r.isTotal()).count()).isEqualTo(1);
    }

    @Test
    @DisplayName("第二段表头重设列映射：2025年末 的资产总额进 2025 列，且仍能算出同比")
    void secondHeaderResetsColumns() {
        List<TableRowParser.Row> rows = TableRowParser.parse(TWO_SEGMENTS);

        TableRowParser.Row revenue = rows.stream().filter(r -> r.label().startsWith("营业总收入")).findFirst().orElseThrow();
        assertThat(revenue.amounts()).containsKeys(2025, 2024, 2023);
        assertThat(revenue.amounts().get(2023).value()).isEqualByComparingTo(new BigDecimal("11081439282.37"));
        assertThat(revenue.growth().value()).isEqualByComparingTo(new BigDecimal("38.46"));
        assertThat(revenue.latestYear()).isEqualTo(2025);
        assertThat(revenue.previousYear()).isEqualTo(2024);

        TableRowParser.Row assets = rows.stream().filter(r -> r.label().startsWith("资产总额")).findFirst().orElseThrow();
        assertThat(assets.amounts().get(2025).value()).isEqualByComparingTo(new BigDecimal("392933744976.20"));
        assertThat(assets.growth().value()).isEqualByComparingTo(new BigDecimal("28.25"));
    }

    @Test
    @DisplayName("表头认不准（重复年份又没有金额/占比子表头）时整行丢弃，绝不猜列")
    void ambiguousHeaderDropsRows() {
        String table = """
                |  | 2025年 | 2025年 |
                | --- | --- | --- |
                | 营业总收入（元） | 16,067,741,039.51 | 11,604,343,090.16 |
                """;

        assertThat(TableRowParser.parse(table)).isEmpty();
    }

    @Test
    @DisplayName("单元格字面量：千分位、百分号、括号负数都认，横线与「不适用」当空")
    void parsesCellLiterals() {
        assertThat(TableRowParser.number("1,234.5")).isEqualByComparingTo(new BigDecimal("1234.5"));
        assertThat(TableRowParser.number("38.46%")).isEqualByComparingTo(new BigDecimal("38.46"));
        assertThat(TableRowParser.number("(1,234)")).isEqualByComparingTo(new BigDecimal("-1234"));
        assertThat(TableRowParser.number("-10,222,245,645.14")).isEqualByComparingTo(new BigDecimal("-10222245645.14"));
        assertThat(TableRowParser.number("-")).isNull();
        assertThat(TableRowParser.number("—")).isNull();
        assertThat(TableRowParser.number("/")).isNull();
        assertThat(TableRowParser.number("不适用")).isNull();
        assertThat(TableRowParser.number("")).isNull();
        // 带单位后缀的不是纯数字：宁可不抽，也不猜它是元还是万亿
        assertThat(TableRowParser.number("24.24 万亿")).isNull();
    }

    @Test
    @DisplayName("非表格内容（正文段落）解析为空，不抛异常")
    void nonTableContentYieldsNothing() {
        assertThat(TableRowParser.parse("公司营业总收入同比增长 38.46%，主要来自证券业务。")).isEmpty();
        assertThat(TableRowParser.parse("")).isEmpty();
        assertThat(TableRowParser.parse(null)).isEmpty();
    }
}
