package com.finagent.agent;

import org.bsc.langgraph4j.RunnableConfig;
import org.bsc.langgraph4j.action.AsyncNodeActionWithConfig;
import org.bsc.langgraph4j.action.InterruptibleAction;
import org.bsc.langgraph4j.action.InterruptionMetadata;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * 人批落点（阶段 22）：<b>运行时</b>决定要不要停，而不是靠编译期 {@code interruptsBefore}。
 *
 * <p>为什么能这么做：LangGraph4j 1.9.7 的 {@code NodeHooks.applyActionWithHooksHandlingInterruption}
 * 会先看节点动作是不是 {@link InterruptibleAction}，是就先调 {@code interrupt(nodeId, state, config)}；
 * 返回的 {@code Optional} 非空 → 直接中断，{@code apply} 一次都不执行；返回空 → 照常执行。
 * 库自带的 {@code AgentEx.ApprovalNodeAction} 就是同一套写法。
 *
 * <p><b>半句话不能少</b>：判断里必须带上 {@code decision().isBlank()}。恢复时库会<b>重新进入</b>这个节点、
 * 再调一次 {@code interrupt()}（2026-11-03 用 {@code target/probe/DynInterruptProbe} 实测：
 * 首次 {@code interruptCalls=1/applyCalls=0}，恢复后 {@code interruptCalls=2/applyCalls=1}）；
 * 只看 {@code approvalAllowed} 的话，用户点「通过」后会再停一次，永远出不来。
 *
 * <p>不停的时候它就是一次空 {@code Map.of()}，微秒级；换来的是「一张图 + 一个决策点」，
 * 不再需要 {@code fast} / {@code hitl} 两张编译产物（旧设计见 {@link AnalysisGraph} 的注释）。
 */
public final class ApprovalGate implements AsyncNodeActionWithConfig<AnalysisState>, InterruptibleAction<AnalysisState> {

    /** 中断原因，进 {@code InterruptionMetadata.reason()}；排障时能在检查点里看到为什么停。 */
    public static final String REASON_AMBIGUOUS = "ambiguous_scope_or_year";

    @Override
    public CompletableFuture<Map<String, Object>> apply(AnalysisState state, RunnableConfig config) {
        // 透传：审批结果已经由 resume 写进 state（DECISION / DECISION_NOTE），这里不需要改任何键
        return CompletableFuture.completedFuture(Map.of());
    }

    @Override
    public Optional<InterruptionMetadata<AnalysisState>> interrupt(String nodeId, AnalysisState state,
                                                                   RunnableConfig config) {
        if (!state.approvalAllowed()) {
            return Optional.empty();       // 同步接口：没有通道把审批交出去，绝不能停
        }
        if (state.approvalReasons().isEmpty()) {
            return Optional.empty();       // 问题没说清什么，正常出答案（阶段 22：不再「每个问题都弹」）
        }
        if (!state.decision().isBlank()) {
            return Optional.empty();       // 已经批过（APPROVE / EDIT）：恢复进来就是接着跑，不能再停
        }
        return Optional.of(InterruptionMetadata.builder(nodeId, state).reason(REASON_AMBIGUOUS).build());
    }
}
