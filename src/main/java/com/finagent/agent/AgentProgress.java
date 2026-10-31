package com.finagent.agent;

/**
 * 节点进度回调（阶段 11 Task 7）。
 *
 * <p>只在节点确实跑完之后回调一次，参数是该节点的输出状态 —— 这样「检索到 6 条」这种数字
 * 是测出来的，不是提前猜的。
 */
@FunctionalInterface
public interface AgentProgress {

    AgentProgress NONE = (node, state) -> {
    };

    void onNode(String node, AnalysisState state);
}
