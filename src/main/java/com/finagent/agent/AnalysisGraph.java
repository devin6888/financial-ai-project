package com.finagent.agent;

import org.bsc.langgraph4j.CompileConfig;
import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.GraphRepresentation;
import org.bsc.langgraph4j.GraphStateException;
import org.bsc.langgraph4j.StateGraph;
import org.bsc.langgraph4j.action.AsyncEdgeAction;
import org.bsc.langgraph4j.action.AsyncNodeAction;
import org.bsc.langgraph4j.checkpoint.BaseCheckpointSaver;

import com.finagent.security.CurrentUser;
import com.finagent.security.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

/**
 * 七个业务节点 + 两个终态节点的图（契约 C17 §2.2）。
 *
 * <pre>
 * START → understand → plan ─┬─ DIRECT ──────▶ retrieve
 *                            ├─ DECOMPOSE ───▶ retrieve（节点内按 subQuestions 顺序循环）
 *                            └─ REFUSE ──────▶ respond_insufficient
 * retrieve → verify ─┬─ ENOUGH ─────────────▶ approve → compute → answer
 *                    ├─ RETRY（retryCount<1）▶ rewrite → retrieve
 *                    └─ INSUFFICIENT ───────▶ respond_insufficient
 * answer → cite_check ─┬─ 通过 ──▶ END（重写回 answer 时不再经过 compute，事实已在状态里）
 *                      └─ 不过且未重试 ──▶ answer（不经过 approve）
 * </pre>
 *
 * <p>为什么编两张图：{@code interruptsBefore} 是<b>编译期</b>配置，不能按请求开关，
 * 所以「要不要人批」只能编译期定 —— {@link #fastGraph()} 不中断，{@link #hitlGraph()} 在 {@code approve} 前停。
 *
 * <p>为什么中断点不是 {@code answer} 而是单独一个 {@code approve} 节点（ST11 §19）：{@code cite_check}
 * 发现引用问题时会沿回边重跑 {@code answer}，若中断点挂在 {@code answer} 上，每重试一次就再弹一次审批
 * （2026-10-29 真链路实测：复杂问题批了两次）。{@code approve} 只在 verify 通过后进入一次，重试不再经过它。
 *
 * <p><b>两张图在实例内各缓存一份</b>（2026-11-02）：{@code StateGraph} 是"装配件"而不是"运行数据"——
 * {@code CompiledGraph} 的五个字段（stateGraph / nodes / edges / processedData / compileConfig）全是
 * {@code final}，构造完就不再改；每次运行的变量走 {@code RunnableConfig(threadId)} 与检查点 saver，
 * 节点 lambda 也只从 state 里读身份。所以同一实例的图可以安全地被所有请求共享。
 *
 * <p>为什么要缓存：实测一次"建图 + 编译"是 0.1 ms、约 5 KB 临时对象，本身不慢，但三个理由让它值得缓存 ——
 * ① 图定义写错会在<b>每个请求</b>里抛 500，而不是启动期炸；
 * ② 原先有四处构建点（起、恢复、查状态、mermaid），谁也不知道哪张是"当前生效的"；
 * ③ 以后给节点套耗时统计/指标，不必在每次请求里重建包装。
 *
 * <p>缓存是<b>实例级</b>而不是静态：测试会用 {@code FakeAnalysisNodes} + {@code MemorySaver} 另建实例，
 * 静态缓存会把假节点带进生产。
 *
 * <p>本类不含任何业务逻辑，节点行为全在 {@link AnalysisNodes}。
 */
public final class AnalysisGraph {

    public static final String NODE_UNDERSTAND = "understand";
    public static final String NODE_PLAN = "plan";
    public static final String NODE_RETRIEVE = "retrieve";
    public static final String NODE_VERIFY = "verify";
    /** 人批落点：透传节点，只在 verify 通过后进入一次（ST11 §19）。 */
    public static final String NODE_APPROVE = "approve";
    public static final String NODE_REWRITE = "rewrite";
    /** 事实层：抽表内事实 + 算派生指标（ST16 Task 2），人批通过后才跑。 */
    public static final String NODE_COMPUTE = "compute";
    public static final String NODE_ANSWER = "answer";
    public static final String NODE_CITE_CHECK = "cite_check";
    public static final String NODE_INSUFFICIENT = "respond_insufficient";

    private static final String START = StateGraph.START;
    private static final String END = StateGraph.END;

    private static final Logger LOG = LoggerFactory.getLogger(AnalysisGraph.class);

    private final AnalysisNodes nodes;
    private final BaseCheckpointSaver saver;
    private final int recursionLimit;

    /** 编译好的两张图（懒加载 + 双检锁）。{@code volatile} 是双检锁成立的前提。 */
    private volatile CompiledGraph<AnalysisState> cachedFast;
    private volatile CompiledGraph<AnalysisState> cachedHitl;

    /**
     * @param saver          检查点存储；{@code null} 表示不持久化（只能用于一次性测试）
     * @param recursionLimit 防死循环；正常路径最多 8 个节点，默认给 25（库默认值）足够
     */
    public AnalysisGraph(AnalysisNodes nodes, BaseCheckpointSaver saver, int recursionLimit) {
        this.nodes = nodes;
        this.saver = saver;
        this.recursionLimit = recursionLimit;
    }

    /** 不中断的图：简单问题（或测试）用它。同一个实例只编译一次。 */
    public CompiledGraph<AnalysisState> fastGraph() {
        CompiledGraph<AnalysisState> cached = cachedFast;
        if (cached == null) {
            synchronized (this) {
                cached = cachedFast;
                if (cached == null) {
                    cached = build(false);
                    cachedFast = cached;
                }
            }
        }
        return cached;
    }

    /** 在 {@code approve} 前停住等人批的图（每次运行只停一次，ST11 §19）。同一个实例只编译一次。 */
    public CompiledGraph<AnalysisState> hitlGraph() {
        CompiledGraph<AnalysisState> cached = cachedHitl;
        if (cached == null) {
            synchronized (this) {
                cached = cachedHitl;
                if (cached == null) {
                    cached = build(true);
                    cachedHitl = cached;
                }
            }
        }
        return cached;
    }

    /**
     * 启动期预热：把两张图都编译一遍。
     *
     * <p>存在的意义不是"更快"，而是<b>把图定义的错误提前到启动</b>：少一条边、节点名拼错、条件边的目标不存在，
     * 现在会在 Spring 建 bean 时就抛 {@code IllegalStateException}，而不是等第一个用户提问才 500。
     */
    public void warmUp() {
        fastGraph();
        hitlGraph();
    }

    /** Mermaid 文本（贴进文档/PR，代替 LangGraph4j Studio）。用缓存的那张，不额外编译。 */
    public String mermaid() {
        GraphRepresentation rep = fastGraph().getGraph(GraphRepresentation.Type.MERMAID, "AnalysisGraph");
        return rep.content();
    }

    /**
     * 把节点包一层身份重建。
     *
     * <p>为什么必须包：LangGraph4j 在自己的线程上执行节点，{@code TenantContext}（ThreadLocal）过不去，
     * 节点里第一次查库就会抛 {@code UnauthorizedException: 缺少认证上下文}。身份在 {@code execute()} 时
     * 写进状态（TENANT_ID / USER_ID / USERNAME / ROLES），这里进入前 {@code set}、退出后 {@code clear}。
     *
     * <p>状态里没有身份（老测试、或纯规则节点）时照常执行，不做假设。
     */
    private AsyncNodeAction<AnalysisState> node(Function<AnalysisState, Map<String, Object>> action) {
        return state -> {
            CurrentUser user = identity(state);
            if (user != null) {
                TenantContext.set(user);
            }
            try {
                return CompletableFuture.completedFuture(action.apply(state));
            } finally {
                TenantContext.clear();
            }
        };
    }

    private static CurrentUser identity(AnalysisState state) {
        if (state.tenantId() <= 0 || state.userId() <= 0) {
            return null;
        }
        Set<String> roles = new LinkedHashSet<>(state.roles());
        if (roles.isEmpty()) {
            roles.add("VIEWER");
        }
        return new CurrentUser(state.userId(), state.tenantId(), state.username(), state.username(), roles);
    }

    /**
     *  🗝️ 核心业务流程
     * 构建业务执行流程图
     * @param hitl 是否需要真人审批
     * @return
     */
    private CompiledGraph<AnalysisState> build(boolean hitl) {
        StateGraph<AnalysisState> graph;
        try {
            graph = new StateGraph<>(AnalysisState.SCHEMA, AnalysisState::new)
                    .addNode(NODE_UNDERSTAND, node(nodes::understand))
                    .addNode(NODE_PLAN, node(nodes::plan))
                    .addNode(NODE_RETRIEVE, node(nodes::retrieve))
                    .addNode(NODE_VERIFY, node(nodes::verify))
                    .addNode(NODE_APPROVE, node(state -> Map.of()))
                    .addNode(NODE_REWRITE, node(nodes::rewrite))
                    .addNode(NODE_COMPUTE, node(nodes::compute))
                    .addNode(NODE_ANSWER, node(nodes::answer))
                    .addNode(NODE_CITE_CHECK, node(nodes::citeCheck))
                    .addNode(NODE_INSUFFICIENT, node(nodes::respondInsufficient))
                    .addEdge(START, NODE_UNDERSTAND)
                    .addEdge(NODE_UNDERSTAND, NODE_PLAN)
                    // plan 的三值分流：问题为空**或主体/范围已被判为不可答** → 直接拒答；
                    // 有子问题 → 逐子问题检索；否则普通检索。
                    // 注意第二条：refuseReason 是 understand 节点写下的结论（公司不在作用域），
                    // 不把它接进边里，结论就只是「备注」，图会照常检索 —— 2026-10-29 的事故（五粮液答成东方财富）。
                    .addConditionalEdges(NODE_PLAN, AsyncEdgeAction.edge_async(state -> {
                        if (state.question() == null || state.question().isBlank()
                                || !state.refuseReason().isBlank()) {
                            return "refuse";
                        }
                        return state.subQuestions().isEmpty() ? "direct" : "decompose";
                    }), Map.of(
                            "direct", NODE_RETRIEVE,
                            "decompose", NODE_RETRIEVE,
                            "refuse", NODE_INSUFFICIENT))
                    // retrieve 之后先看「检索阶段是否已经判了不可答」（作用域为空只会在这一步暴露）
                    .addConditionalEdges(NODE_RETRIEVE, AsyncEdgeAction.edge_async(state ->
                            state.refuseReason().isBlank() ? "verify" : "refuse"), Map.of(
                            "verify", NODE_VERIFY,
                            "refuse", NODE_INSUFFICIENT))
                    // verify 的三条出口：够 → answer；不够但还能改写一次 → rewrite；否则拒答
                    .addConditionalEdges(NODE_VERIFY, AsyncEdgeAction.edge_async(state -> {
                        if (!state.refuseReason().isBlank()) {
                            return "insufficient";
                        }
                        if (!state.insufficient()) {
                            return "enough";
                        }
                        return state.retryCount() < 1 ? "retry" : "insufficient";
                    }), Map.of(
                            "enough", NODE_APPROVE,
                            "retry", NODE_REWRITE,
                            "insufficient", NODE_INSUFFICIENT))
                    // approve 是唯一的人在环落点：过了它就一路跑到 final，cite_check 重试不再打扰人
                    // compute 放在 approve 之后：审批不过时不该白算一遍事实与派生指标
                    .addEdge(NODE_APPROVE, NODE_COMPUTE)
                    .addEdge(NODE_COMPUTE, NODE_ANSWER)
                    .addEdge(NODE_REWRITE, NODE_RETRIEVE)
                    .addEdge(NODE_ANSWER, NODE_CITE_CHECK)
                    // cite_check 的两条出口：通过 → 结束；不过且只改过一次 → 回 answer 重写
                    .addConditionalEdges(NODE_CITE_CHECK, AsyncEdgeAction.edge_async(state -> {
                        if (state.citationIssues().isEmpty()) {
                            return "ok";
                        }
                        return state.citeAttempts() < 2 ? "rewrite" : "give_up";
                    }), Map.of(
                            "ok", END,
                            "rewrite", NODE_ANSWER,
                            "give_up", END))
                    .addEdge(NODE_INSUFFICIENT, END);
        } catch (GraphStateException e) {
            // 图定义写错是编程错误，不是运行时业务失败：包成 IllegalStateException 让启动期就炸
            throw new IllegalStateException("Agent 图定义有误：" + e.getMessage(), e);
        }

        CompileConfig.Builder config = CompileConfig.builder().recursionLimit(recursionLimit);
        if (saver != null) {
            // releaseThread(false) 必须给：库默认 true，跑到 END 就释放线程，之后 getState 抛
            // IllegalStateException: Missing Checkpoint! —— 而我们要能查最终状态、也要能重启后续跑
            config.checkpointSaver(saver).releaseThread(false);
        }
        if (hitl) {
            config.interruptsBefore(Set.of(NODE_APPROVE));
        }
        try {
            CompiledGraph<AnalysisState> compiled = graph.compile(config.build());
            LOG.info("Agent 图已编译：{}（recursionLimit={}，interruptsBefore={}，检查点={}）",
                    hitl ? "hitl" : "fast", recursionLimit,
                    hitl ? NODE_APPROVE : "无",
                    saver == null ? "无" : saver.getClass().getSimpleName());
            return compiled;
        } catch (GraphStateException e) {
            throw new IllegalStateException("Agent 图编译失败：" + e.getMessage(), e);
        }
    }
}