package com.finagent.agent;

import com.finagent.scope.ChatScope;
import com.finagent.security.CurrentUser;
import com.finagent.security.TenantContext;
import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.GraphInput;
import org.bsc.langgraph4j.NodeOutput;
import org.bsc.langgraph4j.RunnableConfig;
import org.bsc.langgraph4j.state.StateSnapshot;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Agent 运行的编排：落库 → 跑图 → 每步留痕 → 终态 → （可选）停下等人批 → 恢复。
 *
 * <p>本类不含业务判断：路线由 plan 节点的条件边决定，证据够不够由 verify 决定，
 * 要不要人批由调用方传进来的 hitl 决定（= queryType 是 COMPLEX_ANALYSIS 且 props.hitl()）。
 * 它只做四件事：发 runId、把每个 NodeOutput 记成一步、把终态写回 agent_run、把结果交出去。
 *
 * <p>中断不抛异常（spike 断言 7 被推翻的那条）：流自然结束，靠 getState(cfg).nextNodeId()
 * 判断是不是停在 answer 前面等人。所以「停下」只是一次普通的 return。
 */
@Service
public class AgentRunService {

    /** 人批在时间线上的节点名（不是图里的节点，只是 agent_run_step.node 的一个取值）。 */
    public static final String NODE_APPROVAL = "approval";

    private final AnalysisGraph graph;
    private final AgentRunRepository runs;
    private final AgentProperties props;

    public AgentRunService(AnalysisGraph graph, AgentRunRepository runs, AgentProperties props) {
        this.graph = graph;
        this.runs = runs;
        this.props = props;
    }

    /** 不需要人批的运行（等价于 hitl = false）。 */
    public Result run(String question, ChatScope scope, Long conversationId, String memoryText) {
        return run(question, scope, conversationId, memoryText, false);
    }

    /**
     * 跑一次 Agent。
     *
     * @param hitl       是否在 answer 前停下等人批（用 hitlGraph()）
     * @param memoryText 已渲染的记忆段（可空）；由调用方（ChatService）先装配好，节点不查记忆表
     */
    public Result run(String question, ChatScope scope, Long conversationId, String memoryText, boolean hitl) {
        return execute(create(question, scope, conversationId), question, scope, conversationId,
                memoryText, hitl, AgentProgress.NONE);
    }

    /**
     * 只建 run（不跑图）。流式端点需要先拿到 runId 才能把 delta 绑上
     * （{@link AnswerStreamBridge} 是按 runId 找回调的），所以拆成两步。
     */
    public UUID create(String question, ChatScope scope, Long conversationId) {
        // P4 起图是唯一执行引擎：`/api/chat` 也走这里，所以「未启用」不再拦。
        // `finagent.agent.enabled` 保留为历史开关（AgentController 的调试端点仍读它）。
        CurrentUser user = TenantContext.require();
        UUID runId = UUID.randomUUID();
        runs.create(runId, user.tenantId(), user.userId(), conversationId, question);
        return runId;
    }

    /** 跑一次已经建好的 run；{@code progress} 在每节点完成后回调（给 SSE 的 stage）。
     * @param hitl 是否需要等真人审批
     */
    public Result execute(UUID runId, String question, ChatScope scope, Long conversationId,
                          String memoryText, boolean hitl, AgentProgress progress) {
        requireOwned(runId);
        CurrentUser user = TenantContext.require();

        Map<String, Object> init = new LinkedHashMap<>(ScopeMapper.initialScope(scope));
        init.put(AnalysisState.RUN_ID, runId.toString());
        init.put(AnalysisState.QUESTION, question);
        init.put(AnalysisState.CONVERSATION_ID, conversationId == null ? 0L : conversationId);
        init.put(AnalysisState.MEMORY_TEXT, memoryText == null ? "" : memoryText);
        // 身份快照：节点在自己的线程上跑，ThreadLocal 不跟过去（AnalysisState 里有说明）
        CurrentUser actor = TenantContext.require();
        init.put(AnalysisState.TENANT_ID, actor.tenantId());
        init.put(AnalysisState.USER_ID, actor.userId());
        init.put(AnalysisState.USERNAME, actor.username() == null ? "" : actor.username());
        init.put(AnalysisState.ROLES, new java.util.ArrayList<>(actor.roles() == null ? java.util.Set.of() : actor.roles()));

        //🗝️主流程： 构建 langGraph图
        CompletedGraph picked = hitl ? hitl() : fast();

        //🗝️主流程：开始执行LLM
        Execution execution = executeStream(picked.compiled(), GraphInput.args(init), config(runId),
                user, runId, 1, progress);
        return settle(execution, user, runId);
    }

    /**
     * 人工审批后恢复运行。
     *
     * @param decision APPROVE / EDIT / REJECT
     * @param note     EDIT 的修改意见、REJECT 的驳回理由；APPROVE 可空
     */
    public Result resume(UUID runId, String decision, String note) {
        return resume(runId, decision, note, AgentProgress.NONE);
    }

    /** 同 {@link #resume(UUID, String, String)}，额外回调节点进度（恢复时也会推 stage）。 */
    public Result resume(UUID runId, String decision, String note, AgentProgress progress) {
        // P4 起没有「未启用」这条路径：图是唯一引擎，resume 也必须在默认配置下可用
        // （否则聊天流弹的确认卡点「通过」会直接 500）
        CurrentUser user = TenantContext.require();
        AgentRun run = requireOwned(runId);

        if (run.finished()) {
            // 幂等：前端重试、用户连点两下都不该生成两份答案
            return result(runId, user.tenantId(), stateOf(runId));
        }
        if (!AgentRun.STATUS_WAITING_APPROVAL.equals(run.status())) {
            throw new IllegalStateException("运行尚未进入等待审批（当前状态 " + run.status() + "）");
        }
        assertNotTimedOut(run);

        String normalized = normalizeDecision(decision);
        String trimmed = note == null ? "" : note.trim();
        if (AgentRun.DECISION_EDIT.equals(normalized) && trimmed.isEmpty()) {
            throw new IllegalArgumentException("EDIT 需要给出修改意见");
        }
        if (AgentRun.DECISION_REJECT.equals(normalized) && trimmed.isEmpty()) {
            throw new IllegalArgumentException("REJECT 需要给出驳回理由");
        }

        runs.recordDecision(runId, user.tenantId(), normalized, trimmed.isEmpty() ? null : trimmed);
        int seq = runs.nextSeq(runId);
        if (AgentRun.DECISION_REJECT.equals(normalized)) {
            runs.markRejected(runId, user.tenantId(), trimmed);
            runs.appendStep(runId, user.tenantId(), seq, NODE_APPROVAL, AgentRunStep.STATUS_OK,
                    "人工驳回：" + shorten(trimmed, 40), 0);
            return result(runId, user.tenantId(), stateOf(runId));
        }

        String label = AgentRun.DECISION_EDIT.equals(normalized) ? "人工修改意见：" : "人工批准";
        runs.appendStep(runId, user.tenantId(), seq++, NODE_APPROVAL, AgentRunStep.STATUS_OK,
                (trimmed.isEmpty() ? label : label + shorten(trimmed, 40)), 0);

        Map<String, Object> patch = new LinkedHashMap<>();
        patch.put(AnalysisState.DECISION, normalized);
        patch.put(AnalysisState.DECISION_NOTE, trimmed);
        Execution execution = executeStream(hitl().compiled(), GraphInput.resume(patch), config(runId),
                user, runId, seq, progress);
        return settle(execution, user, runId);
    }

    public AgentRun requireOwned(UUID runId) {
        CurrentUser user = TenantContext.require();
        return runs.find(runId, user.tenantId())
                .filter(run -> run.userId() == user.userId())
                .orElseThrow(() -> new AgentRunNotFoundException(runId));
    }

    /** 这次运行绑在哪个会话（没绑返回 null）；收尾落库要用。 */
    public Long conversationIdOf(UUID runId) {
        return requireOwned(runId).conversationId();
    }

    /** 这次运行的原问（收尾落库写 USER 消息用；原问永不改写，见 C17 §17.1）。 */
    public String questionOf(UUID runId) {
        return requireOwned(runId).question();
    }

    public List<AgentRunStep> steps(UUID runId) {
        CurrentUser user = TenantContext.require();
        return runs.steps(runId, user.tenantId());
    }

    // ------------------------------------------------------------------ 内部

    private record CompletedGraph(CompiledGraph<AnalysisState> compiled) {
    }

    private CompletedGraph fast() {
        return new CompletedGraph(graph.fastGraph());
    }

    private CompletedGraph hitl() {
        return new CompletedGraph(graph.hitlGraph());
    }

    private static RunnableConfig config(UUID runId) {
        return RunnableConfig.builder().threadId(runId.toString()).build();
    }

    /** 一次流的结果：最后状态、最后节点、已用的最大步序、下一步是谁、是否异常收场。 */
    private record Execution(AnalysisState last, String lastNode, int seq, String nextNode, String error) {
    }

    /**
     * 跑一段流（首次运行或恢复共用）。
     *
     * @param startSeq 这一步从几开始编号（首次是 1，恢复时接着 nextSeq）
     */
    private Execution executeStream(CompiledGraph<AnalysisState> compiled, GraphInput input, RunnableConfig cfg,
                                   CurrentUser user, UUID runId, int startSeq, AgentProgress progress) {
        AnalysisState last = null;
        String lastNode = null;
        int seq = startSeq - 1;
        long lastAt = System.nanoTime();
        try {
            //🗝️主流程： compiled.stream 执行LLM流程
            for (NodeOutput<AnalysisState> out : compiled.stream(input, cfg)) {
                if (out.isSTART() || out.isEND()) {
                    continue;   // 库会多推两个哨兵（spike 断言 3）
                }
                long now = System.nanoTime();
                int durationMs = (int) Math.min(Integer.MAX_VALUE, (now - lastAt) / 1_000_000L);
                lastAt = now;
                last = out.state();
                // approve 是控制流锚点（人批落点），不是业务步骤：不记时间线、不改 current_node，
                // 否则最终时间线会出现三行都在说「人工审批」（approval SKIPPED / approval OK / approve）。
                if (AnalysisGraph.NODE_APPROVE.equals(out.node())) {
                    continue;
                }
                lastNode = out.node();
                runs.updateCurrentNode(runId, user.tenantId(), out.node());
                runs.appendStep(runId, user.tenantId(), ++seq, out.node(),
                        AgentRunStep.STATUS_OK, summary(out.node(), last), durationMs);
                if (progress != null) {
                    progress.onNode(out.node(), last);
                }
            }
        } catch (RuntimeException e) {
            return new Execution(last, lastNode, seq, null, e.getClass().getSimpleName() + ": " + e.getMessage());
        }
        return new Execution(last, lastNode, seq, nextNode(compiled, cfg), null);
    }

    /** 停在哪个节点前面；没有检查点时返回 null（releaseThread(false) 保证跑完仍在）。 */
    private static String nextNode(CompiledGraph<AnalysisState> compiled, RunnableConfig cfg) {
        try {
            StateSnapshot<AnalysisState> snapshot = compiled.getState(cfg);
            return snapshot == null ? null : snapshot.nextNodeId();
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** 把一段流的结果写成终态：等人批 / 拒答 / 完成 / 失败。 */
    private Result settle(Execution execution, CurrentUser user, UUID runId) {
        if (execution.error() != null) {
            runs.markFailed(runId, user.tenantId(), execution.error());
            return result(runId, user.tenantId(), execution.last());
        }
        if (execution.last() == null) {
            runs.markFailed(runId, user.tenantId(), "图没有产出任何节点");
            return result(runId, user.tenantId(), null);
        }
        // 人批的落点是 approve 而不是 answer：cite_check 失败会沿回边重跑 answer，
        // 中断点若挂在 answer 上就会每重试一次再弹一次审批（ST11 §19）。
        if (AnalysisGraph.NODE_APPROVE.equals(execution.nextNode())) {
            runs.markWaitingApproval(runId, user.tenantId(), execution.nextNode(),
                    planJson(execution.last()));
            runs.appendStep(runId, user.tenantId(), execution.seq() + 1, NODE_APPROVAL,
                    AgentRunStep.STATUS_SKIPPED, "等待人工审批", 0);
            return result(runId, user.tenantId(), execution.last());
        }
        if (AgentRun.STATUS_REJECTED.equals(execution.last().status())) {
            runs.markRejected(runId, user.tenantId(), execution.last().answer());
        } else {
            runs.markDone(runId, user.tenantId(), execution.lastNode());
        }
        return result(runId, user.tenantId(), execution.last());
    }

    private void assertNotTimedOut(AgentRun run) {
        OffsetDateTime updatedAt = run.updatedAt();
        if (updatedAt == null || props.approvalTimeoutMinutes() <= 0) {
            return;
        }
        if (updatedAt.plusMinutes(props.approvalTimeoutMinutes()).isBefore(OffsetDateTime.now())) {
            runs.markFailed(run.id(), run.tenantId(), "approval_timeout");
            throw new IllegalStateException("审批已超时（超过 " + props.approvalTimeoutMinutes()
                    + " 分钟），本次运行已标记失败；不会自动通过");
        }
    }

    private static String normalizeDecision(String decision) {
        String value = decision == null ? "" : decision.trim().toUpperCase(Locale.ROOT);
        if (!AgentRun.DECISION_APPROVE.equals(value)
                && !AgentRun.DECISION_EDIT.equals(value)
                && !AgentRun.DECISION_REJECT.equals(value)) {
            throw new IllegalArgumentException("未知的审批决定：" + decision + "（只接受 APPROVE / EDIT / REJECT）");
        }
        return value;
    }

    /** 停下来时写进 plan_json 的东西：审批的人要看「它打算怎么做」。 */
    static String planJson(AnalysisState state) {
        StringBuilder sb = new StringBuilder(32);
        sb.append('{').append((char) 34).append("steps").append((char) 34).append(':').append('[');
        List<String> steps = state.plan();
        for (int i = 0; i < steps.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append('"').append(escape(steps.get(i))).append('"');
        }
        return sb.append("]}").toString();
    }

    /** 极小的 JSON 字符串转义；只用于 plan_json，不引第三方。 */
    private static String escape(String raw) {
        if (raw == null) {
            return "";
        }
        String bs = String.valueOf((char) 92);
        StringBuilder sb = new StringBuilder(raw.length() + 8);
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            switch (c) {
                case 34 -> sb.append(bs).append('"');
                case 92 -> sb.append(bs).append(bs);
                case 10 -> sb.append(bs).append('n');
                case 13 -> sb.append(bs).append('r');
                case 9 -> sb.append(bs).append('t');
                default -> {
                    if (c < 32) {
                        sb.append(bs).append('u').append(String.format("%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.toString();
    }

    private AnalysisState stateOf(UUID runId) {
        try {
            StateSnapshot<AnalysisState> snapshot = graph.hitlGraph().getState(config(runId));
            return snapshot == null ? null : snapshot.state();
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** 查询一次运行的当前状态（刷新页面 / 重启后用）。 */
    public RunView view(UUID runId) {
        CurrentUser user = TenantContext.require();
        AgentRun run = requireOwned(runId);
        AnalysisState state = stateOf(runId);
        return new RunView(run.id(), run.status(), run.currentNode(), run.question(),
                state == null ? List.of() : state.plan(),
                run.decision(), run.decisionNote(), run.error(),
                state == null ? "" : state.answer(),
                state == null ? List.of() : state.warnings(),
                state == null ? List.of() : evidence(state),
                runs.steps(runId, user.tenantId()));
    }

    /** 已经拿到的证据（给审批卡看「它依据什么」）；最多 8 条，避免 plan_json 那么大的响应。 */
    private static List<Map<String, Object>> evidence(AnalysisState state) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> hit : state.hits()) {
            if (out.size() >= 8) {
                break;
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("chunkId", hit.get("chunkId"));
            item.put("documentId", hit.get("documentId"));
            item.put("page", hit.get("page"));
            item.put("sectionPath", hit.get("sectionPath"));
            item.put("type", hit.get("type"));
            item.put("score", hit.get("score"));
            out.add(item);
        }
        return out;
    }

    private Result result(UUID runId, long tenantId, AnalysisState state) {
        AgentRun run = runs.find(runId, tenantId).orElseThrow();
        return new Result(runId, run.status(),
                state == null ? "" : state.answer(),
                state == null ? List.of() : state.warnings(),
                runs.steps(runId, tenantId),
                run.currentNode());
    }

    /** 时间线上的一句话；不参与任何判定，只为人看。 */
    private static String summary(String node, AnalysisState state) {
        return switch (node) {
            case AnalysisGraph.NODE_UNDERSTAND -> "问题已结构化：" + state.queryType();
            case AnalysisGraph.NODE_PLAN -> state.subQuestions().isEmpty()
                    ? "单轮检索" : "拆成 " + state.subQuestions().size() + " 个子问题";
            case AnalysisGraph.NODE_RETRIEVE -> "召回 " + state.evidenceCount() + " 条证据";
            case AnalysisGraph.NODE_VERIFY -> state.insufficient() ? "证据不足" : "证据充足";
            case AnalysisGraph.NODE_REWRITE -> "改写检索式后重试";
            case AnalysisGraph.NODE_COMPUTE -> "事实 " + state.facts().size() + " 条（含派生指标）";
            case AnalysisGraph.NODE_ANSWER -> "答案已生成（" + state.answer().length() + " 字）";
            case AnalysisGraph.NODE_CITE_CHECK -> "引用校验：" + state.citationCount() + " 条通过";
            case AnalysisGraph.NODE_INSUFFICIENT -> "拒答：" + shorten(state.answer(), 30);
            default -> node;
        };
    }

    private static String shorten(String text, int max) {
        if (text == null) {
            return "";
        }
        return text.length() <= max ? text : text.substring(0, max) + "…";
    }

    /** 一次运行的结果（给 REST / 测试用）。 */
    public record Result(UUID runId, String status, String answer, List<String> warnings,
                         List<AgentRunStep> steps, String currentNode) {

        public boolean rejected() {
            return AgentRun.STATUS_REJECTED.equals(status);
        }

        public boolean waitingForApproval() {
            return AgentRun.STATUS_WAITING_APPROVAL.equals(status);
        }
    }

    /** 给 REST / SSE 的只读视图（不暴露 tenantId / userId）。 */
    public record RunView(UUID runId, String status, String currentNode, String question,
                          List<String> plan, String decision, String decisionNote, String error,
                          String answer, List<String> warnings,
                          List<Map<String, Object>> evidence, List<AgentRunStep> steps) {
    }
}
