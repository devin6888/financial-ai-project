package com.finagent.understanding;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** 指标词典的同义组：用户嘴里的写法与财报表头的写法要能对上。 */
class MetricDictionaryTest {

    @Test
    @DisplayName("归母净利润 → 展开成表头写法「归属于上市公司股东的净利润」")
    void expandsProfitAlias() {
        assertThat(MetricDictionary.variants("归母净利润"))
                .contains("归属于上市公司股东的净利润", "归属于母公司股东的净利润");
    }

    @Test
    @DisplayName("员工总数 → 展开成「在职员工的数量合计」")
    void expandsEmployeeAlias() {
        assertThat(MetricDictionary.variants("员工总数")).contains("在职员工的数量合计");
    }

    @Test
    @DisplayName("词典里没有的口语词原样返回，不当成空")
    void unknownMetricKeptAsIs() {
        assertThat(MetricDictionary.variants("人均创收")).containsExactly("人均创收");
    }

    @Test
    @DisplayName("不做上下位词：净利润与归母净利润是两个口径，不能互相展开")
    void doesNotMergeHierarchy() {
        assertThat(MetricDictionary.variants("净利润")).containsExactly("净利润");
        assertThat(new MetricDictionary().variantsOf("归母净利润")).doesNotContain("扣除非经常性损益的净利润");
    }

    @Test
    @DisplayName("空值不炸")
    void toleratesBlank() {
        assertThat(MetricDictionary.variants(null)).isEmpty();
        assertThat(MetricDictionary.variants(" ")).isEmpty();
    }
}
