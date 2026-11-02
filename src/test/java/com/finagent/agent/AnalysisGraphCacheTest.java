package com.finagent.agent;

import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.GraphInput;
import org.bsc.langgraph4j.NodeOutput;
import org.bsc.langgraph4j.RunnableConfig;
import org.bsc.langgraph4j.checkpoint.MemorySaver;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 图缓存（2026-11-02）：同一个 {@link AnalysisGraph} 实例只编译一次，两张图各自独立。
 *
 * <p>为什么值得为「缓存」单独写测试：缓存一旦写错，症状都很隐蔽 ——
 * 用静态缓存会把测试里的 {@code FakeAnalysisNodes} / {@code MemorySaver} 带进生产；
 * 把两张图弄成同一张，人批就不会停了；双检锁少了 {@code volatile}，并发下可能拿到半构造的对象。
 * 这三件事都不会在单线程的功能测试里暴露。
 */
class AnalysisGraphCacheTest {

    private static AnalysisGraph newGraph() {
        return new AnalysisGraph(FakeAnalysisNodes.happy(), new MemorySaver(), 25);
    }

    @Test
    @DisplayName("fast 图只编译一次：重复取到的是同一个实例")
    void fastGraphIsCompiledOnce() {
        AnalysisGraph graph = newGraph();

        CompiledGraph<AnalysisState> first = graph.fastGraph();
        CompiledGraph<AnalysisState> second = graph.fastGraph();

        assertThat(second).isSameAs(first);
        assertThat(first.compileConfig.interruptsBefore()).isEmpty();
    }

    @Test
    @DisplayName("hitl 图只编译一次，且中断点仍是 approve")
    void hitlGraphIsCompiledOnce() {
        AnalysisGraph graph = newGraph();

        CompiledGraph<AnalysisState> first = graph.hitlGraph();

        assertThat(graph.hitlGraph()).isSameAs(first);
        assertThat(first.compileConfig.interruptsBefore()).containsExactly(AnalysisGraph.NODE_APPROVE);
    }

    @Test
    @DisplayName("两张图不是同一张：中断配置是编译期的，混用会让人批失效")
    void twoVariantsStayDistinct() {
        AnalysisGraph graph = newGraph();

        assertThat(graph.hitlGraph()).isNotSameAs(graph.fastGraph());
        assertThat(graph.hitlGraph().compileConfig.interruptsBefore()).isEqualTo(Set.of("approve"));
        assertThat(graph.fastGraph().compileConfig.interruptsBefore()).isEmpty();
    }

    @Test
    @DisplayName("缓存是实例级：另一个实例有自己的图（测试用假节点/内存 saver 不会污染生产）")
    void cacheIsPerInstance() {
        AnalysisGraph a = newGraph();
        AnalysisGraph b = newGraph();

        assertThat(b.fastGraph()).isNotSameAs(a.fastGraph());
        assertThat(b.hitlGraph()).isNotSameAs(a.hitlGraph());
    }

    @Test
    @DisplayName("warmUp() 把两张都编好，之后取到的还是这两张")
    void warmUpCompilesBothAndKeepsThem() {
        AnalysisGraph graph = newGraph();

        graph.warmUp();
        CompiledGraph<AnalysisState> fast = graph.fastGraph();
        CompiledGraph<AnalysisState> hitl = graph.hitlGraph();
        graph.warmUp();

        assertThat(graph.fastGraph()).isSameAs(fast);
        assertThat(graph.hitlGraph()).isSameAs(hitl);
    }

    @Test
    @DisplayName("mermaid() 复用缓存的那张图，不额外编译")
    void mermaidReusesCachedGraph() {
        AnalysisGraph graph = newGraph();
        CompiledGraph<AnalysisState> fast = graph.fastGraph();

        String first = graph.mermaid();
        String second = graph.mermaid();

        assertThat(first).contains("flowchart TD").contains(AnalysisGraph.NODE_APPROVE);
        assertThat(second).isEqualTo(first);                // 每次生成的是新字符串，内容必须一致
        assertThat(graph.fastGraph()).isSameAs(fast);       // 画图没有把缓存顶掉
    }

    @Test
    @DisplayName("缓存下来的图能被多次运行复用：每次运行的状态互不干扰")
    void cachedGraphRunsTwiceWithIndependentState() {
        AnalysisGraph graph = newGraph();
        CompiledGraph<AnalysisState> compiled = graph.fastGraph();

        String first = runOnce(compiled, "run-a", "东方财富2025年营业总收入是多少");
        String second = runOnce(compiled, "run-b", "东方财富2025年净利润是多少");

        assertThat(first).isNotBlank();
        assertThat(second).isNotBlank().isNotEqualTo(first);
    }

    @Test
    @DisplayName("并发取图：8 个线程拿到的是同一个实例（双检锁 + volatile）")
    void concurrentAccessYieldsOneInstance() throws Exception {
        AnalysisGraph graph = newGraph();
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Callable<CompiledGraph<AnalysisState>>> jobs = new ArrayList<>();
            for (int i = 0; i < 32; i++) {
                boolean fastVariant = i % 2 == 0;      // 循环变量不是 effectively final，先落成局部布尔
                jobs.add(() -> fastVariant ? graph.fastGraph() : graph.hitlGraph());
            }
            List<Future<CompiledGraph<AnalysisState>>> futures = pool.invokeAll(jobs);
            List<CompiledGraph<AnalysisState>> fast = new ArrayList<>();
            List<CompiledGraph<AnalysisState>> hitl = new ArrayList<>();
            for (int i = 0; i < futures.size(); i++) {
                (i % 2 == 0 ? fast : hitl).add(futures.get(i).get(10, TimeUnit.SECONDS));
            }
            assertThat(fast).allMatch(g -> g == fast.get(0));
            assertThat(hitl).allMatch(g -> g == hitl.get(0));
        } finally {
            pool.shutdownNow();
        }
        assertThat(graph.fastGraph()).isSameAs(graph.fastGraph());
    }

    /** 跑一遍并返回最终答案；threadId 不同 = 两次互不相干的运行。 */
    private static String runOnce(CompiledGraph<AnalysisState> compiled, String threadId, String question) {
        RunnableConfig cfg = RunnableConfig.builder().threadId(threadId).build();
        AnalysisState last = null;
        for (NodeOutput<AnalysisState> out : compiled.stream(
                GraphInput.args(Map.of(AnalysisState.QUESTION, question)), cfg)) {
            if (!out.isSTART() && !out.isEND()) {
                last = out.state();
            }
        }
        assertThat(last).isNotNull();
        return last.answer();
    }
}