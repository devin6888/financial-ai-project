package com.finagent.agent;

import org.bsc.langgraph4j.RunnableConfig;
import org.bsc.langgraph4j.action.InterruptionMetadata;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 人批闸门的判定表（阶段 22）：停不停的唯一依据是状态，没有任何编译期开关。
 *
 * <p>三个条件缺一不可：**允许**（同步接口恒 false）、**有问题**（understand 判出了 reasons）、
 * **还没批过**（恢复时会重新进入节点，再问一次）。
 */
class ApprovalGateTest {

    private static final RunnableConfig CFG = RunnableConfig.builder().threadId("gate-test").build();

    private final ApprovalGate gate = new ApprovalGate();

    private static AnalysisState state(boolean allowed, List<String> reasons, String decision) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put(AnalysisState.APPROVAL_ALLOWED, allowed);
        data.put(AnalysisState.APPROVAL_REASONS, reasons);
        data.put(AnalysisState.DECISION, decision);
        return new AnalysisState(data);
    }

    @Test
    @DisplayName("没被允许人批：永不停（同步接口、大多数测试走这条）")
    void neverInterruptsWhenNotAllowed() {
        Optional<InterruptionMetadata<AnalysisState>> meta =
                gate.interrupt("approve", state(false, List.of("year_not_specified"), ""), CFG);

        assertThat(meta).isEmpty();
    }

    @Test
    @DisplayName("问题没有歧义：不停（阶段 22 前这里会停，等于每个问题都收一次过路费）")
    void neverInterruptsWhenNothingIsAmbiguous() {
        assertThat(gate.interrupt("approve", state(true, List.of(), ""), CFG)).isEmpty();
    }

    @Test
    @DisplayName("允许 + 有歧义 + 还没批过：停在 approve 前，带上原因")
    void interruptsBeforeFirstDecision() {
        Optional<InterruptionMetadata<AnalysisState>> meta =
                gate.interrupt("approve", state(true, List.of("year_not_specified"), ""), CFG);

        assertThat(meta).isPresent();
        assertThat(meta.get().nodeId()).isEqualTo("approve");
        assertThat(meta.get().reason()).contains(ApprovalGate.REASON_AMBIGUOUS);
    }

    @Test
    @DisplayName("已经批过（APPROVE / EDIT）：恢复进来必须接着跑，不能再停一次")
    void doesNotInterruptAgainAfterDecision() {
        assertThat(gate.interrupt("approve", state(true, List.of("year_not_specified"), "APPROVE"), CFG))
                .isEmpty();
        assertThat(gate.interrupt("approve", state(true, List.of("year_not_specified"), "EDIT"), CFG))
                .isEmpty();
    }

    @Test
    @DisplayName("不停的时候 apply 是透传：不改状态里的任何键")
    void applyIsPassThrough() throws Exception {
        assertThat(gate.apply(state(true, List.of("year_not_specified"), ""), CFG).get()).isEmpty();
    }
}
