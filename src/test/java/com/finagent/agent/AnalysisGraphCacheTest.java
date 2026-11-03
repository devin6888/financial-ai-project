package com.finagent.agent;

import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.GraphInput;
import org.bsc.langgraph4j.NodeOutput;
import org.bsc.langgraph4j.RunnableConfig;
import org.bsc.langgraph4j.checkpoint.MemorySaver;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 图缓存（2026-11-02；阶段 22 改成只有一张）：同一个 {@link AnalysisGraph} 实例只编译一次。
 *
 * <p>为什么值得为「缓存」单独写测试：缓存一旦写错，症状都很隐蔽 ——
 * 用静态缓存会把测试里的 {@code FakeAnalysisNodes} / {@code MemorySaver} 带进生产；
 * 双检锁少了 {@code volatile}，并发下可能拿到半构造的对象。这两件事都不会在单线程的功能测试里暴露。
 *
 * <p>阶段 22 删掉了「fast / hitl 两张图」这条：人批不再是编译期开关，而是状态里的
 * {@code approvalAllowed}，由 {@link ApprovalGate} 在运行时判断。所以这里多了一条反向断言 ——
 * 一张图既能跑通、也能停住。
 */
class AnalysisGraphCacheTest {

    private static AnalysisGraph newGraph() {
        return new AnalysisGraph(FakeAnalysisNodes.happy(), new MemorySaver(), 25);
    }

    @Test
    @DisplayName("图只编译一次：重复取到的是同一个实例，且没有编译期 interruptsBefore")
    void graphIsCompiledOnce() {
        AnalysisGraph graph = newGraph();

        CompiledGraph<AnalysisState> first = graph.graph();

        assertThat(graph.graph()).isSameAs(first);
        // 阶段 22：中断改由 ApprovalGate 运行时决定，编译期配置必须为空（否则每次都会停）
        assertThat(first.compileConfig.interruptsBefore()).isEmpty();
    }

    @Test
    @DisplayName("缓存是实例级：另一个实例有自己的图（测试用假节点/内存 saver 不会污染生产）")
    void cacheIsPerInstance() {
        AnalysisGraph a = newGraph();
        AnalysisGraph b = newGraph();

        assertThat(b.graph()).isNotSameAs(a.graph());
    }

    @Test
    @DisplayName("warmUp() 把图编好，之后取到的还是这一张")
    void warmUpCompilesOnceAndKeepsIt() {
        AnalysisGraph graph = newGraph();

        graph.warmUp();
        CompiledGraph<AnalysisState> first = graph.graph();
        graph.warmUp();

        assertThat(graph.graph()).isSameAs(first);
    }

    @Test
    @DisplayName("mermaid() 复用缓存的那张图，不额外编译")
    void mermaidReusesCachedGraph() {
        AnalysisGraph graph = newGraph();
        CompiledGraph<AnalysisState> cached = graph.graph();

        String first = graph.mermaid();
        String second = graph.mermaid();

        assertThat(first).contains("flowchart TD").contains(AnalysisGraph.NODE_APPROVE);
        assertThat(second).isEqualTo(first);              // 每次生成的是新字符串，内容必须一致
        assertThat(graph.graph()).isSameAs(cached);       // 画图没有把缓存顶掉
    }

    @Test
    @DisplayName("缓存下来的图能被多次运行复用：每次运行的状态互不干扰")
    void cachedGraphRunsTwiceWithIndependentState() {
        AnalysisGraph graph = newGraph();
        CompiledGraph<AnalysisState> compiled = graph.graph();

        String first = runOnce(compiled, "run-a", "东方财富2025年营业总收入是多少", false);
        String second = runOnce(compiled, "run-b", "东方财富2025年净利润是多少", false);

        assertThat(first).isNotBlank();
        assertThat(second).isNotBlank().isNotEqualTo(first);
    }

    @Test
    @DisplayName("同一张图既能跑通也能停住：停不停只看状态里的 approvalAllowed")
    void oneGraphServesBothModes() {
        AnalysisGraph graph = newGraph();
        CompiledGraph<AnalysisState> compiled = graph.graph();

        runOnce(compiled, "no-approval", "东方财富2025年营业总收入是多少", false);
        assertThat(compiled.getState(cfg("no-approval")).nextNodeId()).isEqualTo("__END__");

        runOnce(compiled, "with-approval", "东方财富2025年营业总收入是多少", true);
        assertThat(compiled.getState(cfg("with-approval")).nextNodeId())
                .isEqualTo(AnalysisGraph.NODE_APPROVE);
        // 停在 approve 前：答案还没生成
        assertThat(compiled.getState(cfg("with-approval")).state().answer()).isEmpty();
    }

    @Test
    @DisplayName("并发取图：8 个线程拿到的是同一个实例（双检锁 + volatile）")
    void concurrentAccessYieldsOneInstance() throws Exception {
        AnalysisGraph graph = newGraph();
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Callable<CompiledGraph<AnalysisState>>> jobs = new ArrayList<>();
            for (int i = 0; i < 32; i++) {
                jobs.add(() -> graph.graph());
            }
            List<CompiledGraph<AnalysisState>> got = new ArrayList<>();
            for (Future<CompiledGraph<AnalysisState>> f : pool.invokeAll(jobs)) {
                got.add(f.get(10, TimeUnit.SECONDS));
            }
            assertThat(got).allMatch(g -> g == got.get(0));
        } finally {
            pool.shutdownNow();
        }
        assertThat(graph.graph()).isSameAs(graph.graph());
    }

    private static RunnableConfig cfg(String threadId) {
        return RunnableConfig.builder().threadId(threadId).build();
    }

    /** 跑一遍并返回最终答案；threadId 不同 = 两次互不相干的运行。 */
    private static String runOnce(CompiledGraph<AnalysisState> compiled, String threadId, String question,
                                  boolean approvalAllowed) {
        Map<String, Object> init = new LinkedHashMap<>();
        init.put(AnalysisState.QUESTION, question);
        init.put(AnalysisState.APPROVAL_ALLOWED, approvalAllowed);
        init.put(AnalysisState.APPROVAL_REASONS, approvalAllowed ? List.of("year_not_specified") : List.of());
        AnalysisState last = null;
        for (NodeOutput<AnalysisState> out : compiled.stream(GraphInput.args(init), cfg(threadId))) {
            if (!out.isSTART() && !out.isEND()) {
                last = out.state();
            }
        }
        assertThat(last).isNotNull();
        return last.answer();
    }
}
