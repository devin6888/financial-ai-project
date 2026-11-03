package com.finagent.agent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 「这不是问年报」的判定（阶段 22）。核心要求是**宁可放过、不可错杀**：
 * 只有「完全没有财务信号 + 没有文档词 + 有对话/界面用词」三条同时成立才判跑题。
 */
class OffTopicDetectorTest {

    private static boolean offTopic(String question) {
        return OffTopicDetector.isOffTopic(question, List.of(), List.of(), null);
    }

    @Test
    @DisplayName("问系统本身：判跑题（就是这次投诉的那句）")
    void interfaceQuestionIsOffTopic() {
        assertThat(offTopic("我看每次回答后面都有一个对应的【数值】表格，是程序拼上去的吗")).isTrue();
        assertThat(offTopic("你刚才为什么停了那么久")).isTrue();
        assertThat(offTopic("你好")).isTrue();
    }

    @Test
    @DisplayName("有指标词就不算跑题：哪怕没有年份、没有公司")
    void metricKeepsItOnTopic() {
        assertThat(OffTopicDetector.isOffTopic("净利润是多少", List.of("净利润"), List.of(), null)).isFalse();
    }

    @Test
    @DisplayName("有年份 / 公司 / 文档词，都不算跑题")
    void financialSignalsKeepItOnTopic() {
        assertThat(offTopic("2024年营业收入怎么样")).isFalse();          // 年份
        assertThat(offTopic("东方财富的年报里怎么说")).isFalse();         // 文档词
        assertThat(OffTopicDetector.isOffTopic("这个业务怎么样", List.of(), List.of("东方财富"), null))
                .isFalse();
    }

    @Test
    @DisplayName("没有对话/界面词就不判：拿不准的宁可不拦")
    void uncertainQuestionsAreNotOffTopic() {
        assertThat(offTopic("经营情况怎么样")).isFalse();
        assertThat(offTopic("最近过得怎么样")).isFalse();
        assertThat(offTopic("")).isFalse();
    }

    @Test
    @DisplayName("拒答文案带固定前缀：respondInsufficient 靠它切换成「能力边界」而不是「证据不足」")
    void reasonHasStablePrefix() {
        assertThat(OffTopicDetector.reason()).startsWith(OffTopicDetector.REASON_PREFIX);
        assertThat(OffTopicDetector.reason()).contains("年报");
    }
}
