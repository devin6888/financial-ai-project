package com.finagent.agent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 「行情口径指标」的判定：QU 抽出来的这些指标不代表「要看年报」。
 *
 * <p>背景（阶段 23 冒烟）：问「东方财富股价」时 QU 把「股价」抽成 metrics，
 * understand 于是判成混合问题 → 先检索年报 → 检不到 → 证据不足拒答。
 */
class MarketMetricFilterTest {

    @Test
    @DisplayName("股价/市值/估值倍数/换手/成交 算行情口径")
    void marketMetrics() {
        assertThat(RealAnalysisNodes.isMarketMetric("股价")).isTrue();
        assertThat(RealAnalysisNodes.isMarketMetric("总市值")).isTrue();
        assertThat(RealAnalysisNodes.isMarketMetric("市盈率TTM")).isTrue();
        assertThat(RealAnalysisNodes.isMarketMetric("市净率")).isTrue();
        assertThat(RealAnalysisNodes.isMarketMetric("换手率")).isTrue();
        assertThat(RealAnalysisNodes.isMarketMetric("成交额")).isTrue();
        assertThat(RealAnalysisNodes.isMarketMetric("涨跌幅")).isTrue();
        assertThat(RealAnalysisNodes.isMarketMetric("PE")).isTrue();
    }

    @Test
    @DisplayName("财报指标不算行情口径（否则混合问题会被误判成纯行情）")
    void reportMetrics() {
        assertThat(RealAnalysisNodes.isMarketMetric("营业总收入")).isFalse();
        assertThat(RealAnalysisNodes.isMarketMetric("归属于上市公司股东的净利润")).isFalse();
        assertThat(RealAnalysisNodes.isMarketMetric("毛利率")).isFalse();
        assertThat(RealAnalysisNodes.isMarketMetric(null)).isFalse();
    }
}
