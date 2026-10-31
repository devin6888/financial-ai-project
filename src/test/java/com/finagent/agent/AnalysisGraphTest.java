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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Task 3 的图骨架测试：用假节点验证拓扑、条件边与中断，不碰模型与数据库。
 *
 * <p>四个「必须有」的分支：直答路径、复杂路径（拆子问题）、证据不足改写后重试、引用校验不过回写；
 * 外加 HITL 停在 answer 前、空问题直接拒答、Mermaid 图与 ST11 §2.2 对齐。
 */
class AnalysisGraphTest {

    private static final RunnableConfig CFG = RunnableConfig.builder().threadId("test-run").build();

    private static List<String> run(CompiledGraph<AnalysisState> graph, String question) {
        List<String> nodes = new ArrayList<>();
        for (NodeOutput<AnalysisState> out : graph.stream(
                GraphInput.args(Map.of(AnalysisState.QUESTION, question)), CFG)) {
            if (!out.isSTART() && !out.isEND()) {
                nodes.add(out.node());
            }
        }
        return nodes;
    }

    private static AnalysisState finalState(CompiledGraph<AnalysisState> graph) {
        return graph.getState(CFG).state();
    }

    private AnalysisGraph graph(FakeAnalysisNodes nodes) {
        return new AnalysisGraph(nodes, new MemorySaver(), 25);
    }


    @Test
    @DisplayName("节点在自己的线程上重建 TenantContext：身份四键进状态，进节点有上下文、出节点清空")
    void nodesRebuildTenantContextFromState() {
        List<String> seen = new ArrayList<>();
        FakeAnalysisNodes fake = FakeAnalysisNodes.happy();
        // FakeAnalysisNodes 是 final，这里用委托而不是继承：只替换 understand，其余原样转发
        AnalysisNodes nodes = new AnalysisNodes() {
            @Override
            public Map<String, Object> understand(AnalysisState state) {
                var user = com.finagent.security.TenantContext.optional();
                seen.add(user.map(u -> u.tenantId() + "/" + u.userId() + "/" + u.username()).orElse("none"));
                return fake.understand(state);
            }

            @Override
            public Map<String, Object> plan(AnalysisState state) {
                return fake.plan(state);
            }

            @Override
            public Map<String, Object> retrieve(AnalysisState state) {
                return fake.retrieve(state);
            }

            @Override
            public Map<String, Object> verify(AnalysisState state) {
                return fake.verify(state);
            }

            @Override
            public Map<String, Object> rewrite(AnalysisState state) {
                return fake.rewrite(state);
            }

            @Override
            public Map<String, Object> compute(AnalysisState state) {
                return fake.compute(state);
            }

            @Override
            public Map<String, Object> answer(AnalysisState state) {
                return fake.answer(state);
            }

            @Override
            public Map<String, Object> citeCheck(AnalysisState state) {
                return fake.citeCheck(state);
            }

            @Override
            public Map<String, Object> respondInsufficient(AnalysisState state) {
                return fake.respondInsufficient(state);
            }
        };
        AnalysisGraph graph = new AnalysisGraph(nodes, new MemorySaver(), 25);
        Map<String, Object> init = Map.of(
                AnalysisState.QUESTION, "东方财富2025年营业总收入是多少",
                AnalysisState.TENANT_ID, 1L,
                AnalysisState.USER_ID, 1L,
                AnalysisState.USERNAME, "analyst",
                AnalysisState.ROLES, List.of("ANALYST"));

        for (NodeOutput<AnalysisState> out : graph.fastGraph().stream(GraphInput.args(init), CFG)) {
            if (out.isSTART() || out.isEND()) {
                continue;
            }
        }

        assertThat(seen).containsExactly("1/1/analyst");
        // 节点退出后必须清掉：否则线程池里的下一个任务会继承别人的身份（跨租户泄漏）
        assertThat(com.finagent.security.TenantContext.optional()).isEmpty();
    }


    @Test
    @DisplayName("understand 判了不可答 → plan 直接去 respond_insufficient，不经过 retrieve")
    void refusalFromUnderstandSkipsRetrieval() {
        FakeAnalysisNodes fake = FakeAnalysisNodes.happy();
        AnalysisNodes nodes = new DelegatingNodes(fake) {
            @Override
            public Map<String, Object> understand(AnalysisState state) {
                return Map.of(
                        AnalysisState.PLAN, List.of("公司不在作用域 → 拒答"),
                        AnalysisState.REFUSE_REASON, "问题里的公司不在当前可检索范围内：五粮液");
            }
        };
        CompiledGraph<AnalysisState> app = new AnalysisGraph(nodes, new MemorySaver(), 25).fastGraph();

        List<String> nodesRun = run(app, "五粮液2025年净利润是多少");

        assertThat(nodesRun).containsExactly("understand", "plan", "respond_insufficient");
        // 拒答文案由 respondInsufficient 决定（这里用的是假节点，不校验文案）；
        // 真实文案在 RealAnalysisNodesTest 与真链路里验证
        assertThat(finalState(app).status()).isEqualTo(AgentRun.STATUS_REJECTED);
    }

    @Test
    @DisplayName("retrieve 阶段才发现作用域为空 → 直接拒答，不经过 verify / rewrite")
    void refusalFromRetrieveSkipsVerifyAndRewrite() {
        FakeAnalysisNodes fake = FakeAnalysisNodes.happy();
        AnalysisNodes nodes = new DelegatingNodes(fake) {
            @Override
            public Map<String, Object> retrieve(AnalysisState state) {
                return Map.of(
                        AnalysisState.HITS, List.of(),
                        AnalysisState.EVIDENCE_COUNT, 0,
                        AnalysisState.WARNINGS, List.of("scope_empty"),
                        AnalysisState.REFUSE_REASON, "知识库中没有当前账号可检索的文档");
            }
        };
        CompiledGraph<AnalysisState> app = new AnalysisGraph(nodes, new MemorySaver(), 25).fastGraph();

        List<String> nodesRun = run(app, "东方财富2025年营业总收入是多少");

        assertThat(nodesRun).containsExactly("understand", "plan", "retrieve", "respond_insufficient");
        assertThat(finalState(app).status()).isEqualTo(AgentRun.STATUS_REJECTED);
    }

    /** 只覆写需要改的那个节点，其余转发给假节点。 */
    private static class DelegatingNodes implements AnalysisNodes {
        private final AnalysisNodes delegate;

        DelegatingNodes(AnalysisNodes delegate) {
            this.delegate = delegate;
        }

        @Override
        public Map<String, Object> understand(AnalysisState state) {
            return delegate.understand(state);
        }

        @Override
        public Map<String, Object> plan(AnalysisState state) {
            return delegate.plan(state);
        }

        @Override
        public Map<String, Object> retrieve(AnalysisState state) {
            return delegate.retrieve(state);
        }

        @Override
        public Map<String, Object> verify(AnalysisState state) {
            return delegate.verify(state);
        }

        @Override
        public Map<String, Object> rewrite(AnalysisState state) {
            return delegate.rewrite(state);
        }

        @Override
        public Map<String, Object> compute(AnalysisState state) {
            return delegate.compute(state);
        }

        @Override
        public Map<String, Object> answer(AnalysisState state) {
            return delegate.answer(state);
        }

        @Override
        public Map<String, Object> citeCheck(AnalysisState state) {
            return delegate.citeCheck(state);
        }

        @Override
        public Map<String, Object> respondInsufficient(AnalysisState state) {
            return delegate.respondInsufficient(state);
        }
    }

    @Test
    @DisplayName("直答路径：understand → plan → retrieve → verify → approve → compute → answer → cite_check → END")
    void directPathRunsToEnd() {
        CompiledGraph<AnalysisState> app = graph(FakeAnalysisNodes.happy()).fastGraph();

        List<String> nodes = run(app, "东方财富2025年营业总收入是多少");

        assertThat(nodes).containsExactly("understand", "plan", "retrieve", "verify",
                "approve", "compute", "answer", "cite_check");
        AnalysisState state = finalState(app);
        assertThat(state.status()).isEqualTo(AgentRun.STATUS_DONE);
        assertThat(state.answer()).startsWith("fake-answer:").contains("[E1]");
        assertThat(state.subQuestions()).isEmpty();
        assertThat(state.hits()).hasSize(3);
        assertThat(state.evidenceCount()).isEqualTo(3);
        // 硬规则 3：原问永不被改写
        assertThat(state.question()).isEqualTo("东方财富2025年营业总收入是多少");
    }

    @Test
    @DisplayName("复杂路径：plan 拆出 2 个子问题，retrieve 节点内顺序检索两次（命中 6 条）")
    void complexPathDecomposes() {
        CompiledGraph<AnalysisState> app = graph(FakeAnalysisNodes.happy()).fastGraph();

        run(app, "复杂：对比东方财富2024与2025年的营业总收入");

        AnalysisState state = finalState(app);
        assertThat(state.queryType()).isEqualTo("COMPLEX_ANALYSIS");
        assertThat(state.subQuestions()).containsExactly("营业总收入是多少", "同比增速是多少");
        assertThat(state.plan()).hasSize(3);
        // retrieve 只跑一次，节点内按子问题循环 → 2 × 3 = 6 条
        assertThat(state.hits()).hasSize(6);
    }

    @Test
    @DisplayName("证据不足：rewrite 一次后回 retrieve，第二次拿到证据 → 继续出答案")
    void insufficientRewritesOnceThenRecovers() {
        CompiledGraph<AnalysisState> app = graph(new FakeAnalysisNodes(false, true, 0)).fastGraph();

        List<String> nodes = run(app, "东方财富2025年营业总收入是多少");

        assertThat(nodes).containsExactly("understand", "plan", "retrieve", "verify",
                "rewrite", "retrieve", "verify", "approve", "compute", "answer", "cite_check");
        AnalysisState state = finalState(app);
        assertThat(state.retryCount()).isEqualTo(1);
        assertThat(state.rewrittenQuery()).endsWith("（改写）");
        assertThat(state.hits()).hasSize(3);
        assertThat(state.status()).isEqualTo(AgentRun.STATUS_DONE);
    }

    @Test
    @DisplayName("改写后仍然没有证据：respond_insufficient 终态，答案是可读文案（C6 第 5 条）")
    void stillInsufficientAfterRewriteRefuses() {
        CompiledGraph<AnalysisState> app = graph(new FakeAnalysisNodes(false, false, 0)).fastGraph();

        List<String> nodes = run(app, "东方财富2050年营业总收入是多少");

        assertThat(nodes).containsExactly("understand", "plan", "retrieve", "verify",
                "rewrite", "retrieve", "verify", "respond_insufficient");
        AnalysisState state = finalState(app);
        assertThat(state.status()).isEqualTo(AgentRun.STATUS_REJECTED);
        assertThat(state.answer()).contains("证据不足");
        assertThat(state.retryCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("引用校验不过：回 answer 重写一次，第二次通过后结束")
    void citeCheckLoopsBackOnce() {
        CompiledGraph<AnalysisState> app = graph(new FakeAnalysisNodes(true, true, 1)).fastGraph();

        List<String> nodes = run(app, "东方财富2025年营业总收入是多少");

        assertThat(nodes).containsExactly("understand", "plan", "retrieve", "verify",
                "approve", "compute", "answer", "cite_check", "answer", "cite_check");
        AnalysisState state = finalState(app);
        assertThat(state.citationIssues()).isEmpty();
        assertThat(state.citeAttempts()).isEqualTo(2);
    }

    @Test
    @DisplayName("引用校验连续两次不过：不再回写，直接放弃（不无限循环）")
    void citeCheckGivesUpAfterOneRetry() {
        CompiledGraph<AnalysisState> app = graph(new FakeAnalysisNodes(true, true, 5)).fastGraph();

        List<String> nodes = run(app, "东方财富2025年营业总收入是多少");

        assertThat(nodes).containsExactly("understand", "plan", "retrieve", "verify",
                "approve", "compute", "answer", "cite_check", "answer", "cite_check");
        assertThat(finalState(app).citationIssues()).isNotEmpty();
    }

    @Test
    @DisplayName("空问题：plan 直接判 REFUSE，不走检索")
    void blankQuestionRefuses() {
        CompiledGraph<AnalysisState> app = graph(FakeAnalysisNodes.happy()).fastGraph();

        List<String> nodes = run(app, "   ");

        assertThat(nodes).containsExactly("understand", "plan", "respond_insufficient");
        assertThat(finalState(app).hits()).isEmpty();
    }

    @Test
    @DisplayName("HITL：停在 approve 前（不抛异常），resume 带 APPROVE 后跑完")
    void hitlStopsBeforeApproveThenResumes() {
        CompiledGraph<AnalysisState> app = graph(FakeAnalysisNodes.happy()).hitlGraph();

        List<String> firstRun = run(app, "复杂：对比东方财富两年营业总收入");

        assertThat(firstRun).containsExactly("understand", "plan", "retrieve", "verify");
        assertThat(app.getState(CFG).nextNodeId()).isEqualTo("approve");
        assertThat(finalState(app).answer()).isEmpty();

        List<String> secondRun = new ArrayList<>();
        for (NodeOutput<AnalysisState> out : app.stream(
                GraphInput.resume(Map.of(AnalysisState.DECISION, "APPROVE")), CFG)) {
            if (!out.isSTART() && !out.isEND()) {
                secondRun.add(out.node());
            }
        }

        assertThat(secondRun).containsExactly("approve", "compute", "answer", "cite_check");
        AnalysisState state = finalState(app);
        assertThat(state.decision()).isEqualTo("APPROVE");
        assertThat(state.status()).isEqualTo(AgentRun.STATUS_DONE);
        assertThat(state.answer()).startsWith("fake-answer:");
    }

    @Test
    @DisplayName("引用校验不过的自动重试不再弹审批：一次人批，重试一路跑完（ST11 §19）")
    void citeRetryAfterApprovalDoesNotAskAgain() {
        CompiledGraph<AnalysisState> app = graph(new FakeAnalysisNodes(true, true, 1)).hitlGraph();

        List<String> firstRun = run(app, "复杂：对比东方财富两年营业总收入");
        assertThat(firstRun).containsExactly("understand", "plan", "retrieve", "verify");
        assertThat(app.getState(CFG).nextNodeId()).isEqualTo("approve");

        List<String> secondRun = new ArrayList<>();
        for (NodeOutput<AnalysisState> out : app.stream(
                GraphInput.resume(Map.of(AnalysisState.DECISION, "APPROVE")), CFG)) {
            if (!out.isSTART() && !out.isEND()) {
                secondRun.add(out.node());
            }
        }

        // 关键：cite_check 失败 → 回 answer 重写，但不会再停在 approve（一次运行只批一次）
        assertThat(secondRun).containsExactly("approve", "compute", "answer", "cite_check", "answer", "cite_check");
        assertThat(finalState(app).citeAttempts()).isEqualTo(2);
        assertThat(finalState(app).status()).isEqualTo(AgentRun.STATUS_DONE);
    }

    @Test
    @DisplayName("EDIT 的 note 会进答案（人工补充要点）")
    void editNoteShapesTheAnswer() {
        CompiledGraph<AnalysisState> app = graph(FakeAnalysisNodes.happy()).hitlGraph();
        run(app, "复杂：对比东方财富两年营业总收入");

        for (NodeOutput<AnalysisState> ignored : app.stream(
                GraphInput.resume(Map.of(AnalysisState.DECISION, "EDIT",
                        AnalysisState.DECISION_NOTE, "补一句同比增速")), CFG)) {
            // 只为推进图，断言在最后的状态上做
        }

        assertThat(finalState(app).answer()).contains("补一句同比增速");
    }

    @Test
    @DisplayName("Mermaid 图：九个节点都在，与 ST11 §2.2 对齐（不引 Studio）")
    void mermaidListsEveryNode() {
        String mermaid = graph(FakeAnalysisNodes.happy()).mermaid();

        // 实测 1.9.7 的 MermaidGenerator 输出头是 flowchart TD（不是 graph TD），前面还有 YAML 标题块
        assertThat(mermaid).contains("flowchart TD");
        assertThat(mermaid).contains("title: AnalysisGraph");
        assertThat(mermaid).contains("understand", "plan", "retrieve", "verify", "approve", "rewrite",
                "compute", "answer", "cite_check", "respond_insufficient");
        // 落盘一份，贴进 ST11 文档（不引 LangGraph4j Studio）
        try {
            java.nio.file.Files.writeString(java.nio.file.Path.of("target/analysis-graph.mmd"), mermaid);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("写 target/analysis-graph.mmd 失败", e);
        }
    }
}