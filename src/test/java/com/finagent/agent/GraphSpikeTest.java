package com.finagent.agent;

import org.bsc.langgraph4j.CompileConfig;
import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.GraphInput;
import org.bsc.langgraph4j.NodeOutput;
import org.bsc.langgraph4j.RunnableConfig;
import org.bsc.langgraph4j.StateGraph;
import org.bsc.langgraph4j.action.AsyncEdgeAction;
import org.bsc.langgraph4j.action.AsyncNodeAction;
import org.bsc.langgraph4j.checkpoint.MemorySaver;
import org.bsc.langgraph4j.state.AgentState;
import org.bsc.langgraph4j.state.Channel;
import org.bsc.langgraph4j.state.Channels;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 阶段 11 Task 1 的 spike：**只用最小图验证库能不能用**，不碰数据库、不启 Spring。
 *
 * <p>七个断言见 docs/superpowers/plans/ST11-2026-10-28-11-agent-workflow.md §12.2 与 §12.5。
 * 其中「中断是否抛异常」一条被实测推翻：1.9.7 的 stream 路径<span>不抛</span>异常，
 * 而是静静停在中断节点前，靠 StateSnapshot.nextNodeId() 判断停在哪（详见 §12.5）。
 */
class GraphSpikeTest {

    /** 只含 JDK 类型的状态：String / List / Integer。C17 硬规则 1 的来源。 */
    static final class SpikeState extends AgentState {

        static final Map<String, Channel<?>> SCHEMA = Map.of(
                "question", Channels.base(() -> ""),
                "steps", Channels.appender(ArrayList::new),
                "score", Channels.base((Integer oldV, Integer newV) -> oldV + newV, () -> 0));

        SpikeState(Map<String, Object> data) {
            super(data);
        }

        String question() {
            return this.value("question", "");
        }

        @SuppressWarnings("unchecked")
        List<String> steps() {
            return this.value("steps", List.of());
        }

        int score() {
            return this.value("score", 0);
        }
    }

    private static List<String> realNodes(Iterable<NodeOutput<SpikeState>> outputs) {
        List<String> nodes = new ArrayList<>();
        for (NodeOutput<SpikeState> out : outputs) {
            if (!out.isSTART() && !out.isEND()) {
                nodes.add(out.node());
            }
        }
        return nodes;
    }

    @Test
    @DisplayName("断言 1+2：默认序列化器是 JDK 的，不依赖 Jackson 2")
    void defaultSerializerIsJdkBased() throws Exception {
        StateGraph<SpikeState> graph = new StateGraph<>(SpikeState.SCHEMA, SpikeState::new)
                .addNode("only", AsyncNodeAction.node_async(state -> Map.of()))
                .addEdge(StateGraph.START, "only")
                .addEdge("only", StateGraph.END);

        String serializer = graph.getStateSerializer().getClass().getName();
        assertThat(serializer).contains("StateSerializer");
        // 关键断言：默认序列化器不是 Jackson 的（Boot 4 只有 Jackson 3，用它必 NoClassDefFoundError）
        assertThat(serializer).doesNotContain("Jackson");
        assertThat(graph.getStateFactory()).isNotNull();
        assertThat(graph.getChannels()).containsKeys("question", "steps", "score");
    }

    @Test
    @DisplayName("断言 3：stream 每个节点推一次，另带 __START__ / __END__ 两个哨兵")
    void streamEmitsNodeOutputs() throws Exception {
        CompiledGraph<SpikeState> app = new StateGraph<>(SpikeState.SCHEMA, SpikeState::new)
                .addNode("first", AsyncNodeAction.node_async(state -> Map.of("steps", List.of("first"))))
                .addNode("second", AsyncNodeAction.node_async(state -> Map.of("steps", List.of("second"))))
                .addEdge(StateGraph.START, "first")
                .addEdge("first", "second")
                .addEdge("second", StateGraph.END)
                .compile();

        List<NodeOutput<SpikeState>> outputs = new ArrayList<>();
        for (NodeOutput<SpikeState> out : app.stream(GraphInput.args(Map.of("question", "q")),
                RunnableConfig.builder().threadId("t1").build())) {
            outputs.add(out);
        }

        // 实测：2 个业务节点 + START 哨兵 + END 哨兵 = 4 条
        assertThat(outputs).hasSize(4);
        assertThat(outputs.get(0).isSTART()).isTrue();
        assertThat(outputs.get(outputs.size() - 1).isEND()).isTrue();
        assertThat(realNodes(outputs)).containsExactly("first", "second");
        assertThat(outputs.get(outputs.size() - 1).state().steps()).containsExactly("first", "second");
    }

    @Test
    @DisplayName("断言 4：追加通道累加、覆盖通道替换、自定义 reducer 累加")
    void channelsMergeAsDeclared() throws Exception {
        CompiledGraph<SpikeState> app = new StateGraph<>(SpikeState.SCHEMA, SpikeState::new)
                .addNode("one", AsyncNodeAction.node_async(state -> Map.of(
                        "steps", List.of("one"),
                        "score", 1,
                        "question", "改写后")))
                .addNode("two", AsyncNodeAction.node_async(state -> Map.of(
                        "steps", List.of("two"),
                        "score", 2)))
                .addEdge(StateGraph.START, "one")
                .addEdge("one", "two")
                .addEdge("two", StateGraph.END)
                .compile();

        SpikeState last = app.invoke(Map.of("question", "原始问题"),
                RunnableConfig.builder().threadId("t2").build()).orElseThrow();

        assertThat(last.steps()).containsExactly("one", "two");
        assertThat(last.score()).isEqualTo(3);
        assertThat(last.question()).isEqualTo("改写后");
    }

    @Test
    @DisplayName("断言 5：条件边按返回值分流")
    void conditionalEdgeRoutes() throws Exception {
        CompiledGraph<SpikeState> app = new StateGraph<>(SpikeState.SCHEMA, SpikeState::new)
                .addNode("plan", AsyncNodeAction.node_async(state -> Map.of()))
                .addNode("direct", AsyncNodeAction.node_async(state -> Map.of("steps", List.of("direct"))))
                .addNode("deep", AsyncNodeAction.node_async(state -> Map.of("steps", List.of("deep"))))
                .addEdge(StateGraph.START, "plan")
                .addConditionalEdges("plan",
                        AsyncEdgeAction.edge_async(state -> state.question().contains("复杂") ? "deep" : "direct"),
                        Map.of("deep", "deep", "direct", "direct"))
                .addEdge("direct", StateGraph.END)
                .addEdge("deep", StateGraph.END)
                .compile();

        SpikeState deep = app.invoke(Map.of("question", "复杂分析"),
                RunnableConfig.builder().threadId("t3").build()).orElseThrow();
        SpikeState direct = app.invoke(Map.of("question", "简单事实"),
                RunnableConfig.builder().threadId("t4").build()).orElseThrow();

        assertThat(deep.steps()).containsExactly("deep");
        assertThat(direct.steps()).containsExactly("direct");
    }

    @Test
    @DisplayName("断言 6：recursionLimit 挡住死循环")
    void recursionLimitStopsLoop() throws Exception {
        CompiledGraph<SpikeState> app = new StateGraph<>(SpikeState.SCHEMA, SpikeState::new)
                .addNode("a", AsyncNodeAction.node_async(state -> Map.of()))
                .addNode("b", AsyncNodeAction.node_async(state -> Map.of()))
                .addEdge(StateGraph.START, "a")
                .addEdge("a", "b")
                .addEdge("b", "a")
                .compile(CompileConfig.builder().recursionLimit(6).build());

        assertThatThrownBy(() -> app.invoke(Map.of("question", "loop"),
                RunnableConfig.builder().threadId("t5").build()))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    @DisplayName("断言 7（关键）：中断是「静静停下 + nextNodeId」，resume 带参能继续并跑完")
    void interruptThenResume() throws Exception {
        MemorySaver saver = new MemorySaver();
        CompiledGraph<SpikeState> app = new StateGraph<>(SpikeState.SCHEMA, SpikeState::new)
                .addNode("draft", AsyncNodeAction.node_async(state -> Map.of("steps", List.of("draft"))))
                .addNode("answer", AsyncNodeAction.node_async(state -> Map.of(
                        "steps", List.of("answer:" + state.value("decision", "none")))))
                .addEdge(StateGraph.START, "draft")
                .addEdge("draft", "answer")
                .addEdge("answer", StateGraph.END)
                .compile(CompileConfig.builder()
                        .checkpointSaver(saver)
                        .interruptsBefore(Set.of("answer"))
                        .releaseThread(false)   // 不加这条，跑完 END 之后 getState 会报 Missing Checkpoint!
                        .build());

        RunnableConfig cfg = RunnableConfig.builder().threadId("t6").build();

        List<String> firstRun = realNodes(app.stream(GraphInput.args(Map.of("question", "q")), cfg)::iterator);

        // 实测 1.9.7：不抛 GraphInterruptException，而是停在 answer 之前
        assertThat(firstRun).containsExactly("draft");
        assertThat(app.getState(cfg).nextNodeId()).isEqualTo("answer");
        assertThat(app.getState(cfg).state().steps()).containsExactly("draft");

        List<String> secondRun = realNodes(
                app.stream(GraphInput.resume(Map.of("decision", "APPROVE")), cfg)::iterator);

        assertThat(secondRun).containsExactly("answer");
        SpikeState finalState = app.getState(cfg).state();
        assertThat(finalState.steps()).containsExactly("draft", "answer:APPROVE");
        // resume 的 Map 会被合并进状态（HITL 传 decision 就靠这个）
        assertThat(finalState.data()).containsEntry("decision", "APPROVE");
    }
}