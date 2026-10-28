package com.finagent.memory;

import com.finagent.understanding.QueryType;
import com.finagent.understanding.QueryUnderstanding;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** 召回闸门：该召回才召回，不该召回时一次外部调用都不多花。 */
class MemoryGateTest {

    private static final QueryUnderstanding FACTS = new QueryUnderstanding(
            QueryType.EXACT_FACT, "东方财富2025年营业收入", java.util.List.of("300059"),
            new int[]{2025, 2025}, java.util.List.of("营业收入"), false, false, null, false, "LLM");

    @Test
    @DisplayName("词典命中：「以后」")
    void keywordHit() {
        assertThat(gate(true).shouldRecall("以后回答先给结论", FACTS)).isTrue();
    }

    @Test
    @DisplayName("词典命中：「格式」「偏好」「习惯」「照旧」都能触发")
    void multipleKeywords() {
        MemoryGate gate = gate(true);
        assertThat(gate.shouldRecall("按以前的格式回答", FACTS)).isTrue();
        assertThat(gate.shouldRecall("按我的偏好来", FACTS)).isTrue();
        assertThat(gate.shouldRecall("沿用我的习惯", FACTS)).isTrue();
        assertThat(gate.shouldRecall("照旧", FACTS)).isTrue();
    }

    @Test
    @DisplayName("QU 字段命中：needLongTermMemory=true 也召回")
    void llmFlagHit() {
        QueryUnderstanding flagged = new QueryUnderstanding(QueryType.SEMANTIC_EXPLANATION, "q",
                java.util.List.of(), null, java.util.List.of(), false, false, null, false, "LLM",
                false, true);
        assertThat(gate(true).shouldRecall("我平时怎么问的", flagged)).isTrue();
    }

    @Test
    @DisplayName("默认跳过：纯事实问答不召回")
    void factQuestionSkipped() {
        assertThat(gate(true).shouldRecall("东方财富2025年营业收入是多少", FACTS)).isFalse();
    }

    @Test
    @DisplayName("上下文充分跳过：有公司+年份+指标的追问不召回")
    void selfContainedFollowUpSkipped() {
        assertThat(gate(true).shouldRecall("那它2025年的净利润呢", FACTS)).isFalse();
    }

    @Test
    @DisplayName("空问题 / null 不召回")
    void blankQuestionSkipped() {
        assertThat(gate(true).shouldRecall("", FACTS)).isFalse();
        assertThat(gate(true).shouldRecall(null, FACTS)).isFalse();
        assertThat(gate(true).shouldRecall("   ", null)).isFalse();
    }

    @Test
    @DisplayName("开关关闭：即使命中词典也不召回")
    void gateDisabled() {
        MemoryGate gate = gate(false);
        assertThat(gate.shouldRecall("以后回答先给结论", FACTS)).isFalse();
    }

    @Test
    @DisplayName("记忆总开关关闭：一律不召回")
    void memoryDisabled() {
        MemoryGate gate = new MemoryGate(props(false, true));
        assertThat(gate.shouldRecall("以后回答先给结论", FACTS)).isFalse();
    }

    @Test
    @DisplayName("qu 为 null 时只看词典（不 NPE）")
    void nullQuStillUsesKeywords() {
        assertThat(gate(true).shouldRecall("按以前的格式", null)).isTrue();
        assertThat(gate(true).shouldRecall("营业收入多少", null)).isFalse();
    }

    private static MemoryGate gate(boolean gateEnabled) {
        return new MemoryGate(props(true, gateEnabled));
    }

    private static MemoryProperties props(boolean enabled, boolean gateEnabled) {
        return new MemoryProperties(enabled, 8000, 6, 0.15, 0.10, 0.05, 0.05, 0.40, 600, 3, 12,
                0.6, 8, gateEnabled);
    }
}
