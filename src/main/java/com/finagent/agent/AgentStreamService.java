package com.finagent.agent;

import com.finagent.answer.AnswerResult;
import com.finagent.chat.ChatRequest;
import com.finagent.chat.ChatResponse;
import com.finagent.chat.ChatStreamEvent;
import com.finagent.chat.ChatStreamSink;
import com.finagent.chat.ChatTrace;
import com.finagent.chat.ChatTraceWriter;
import com.finagent.chat.MemoryTrace;
import com.finagent.chat.ScopeInfo;
import com.finagent.chat.ScopeTrace;
import com.finagent.chat.TurnRecorder;
import com.finagent.citation.Citation;
import com.finagent.retrieval.RetrievalTrace;
import com.finagent.memory.Conversation;
import com.finagent.memory.ConversationContext;
import com.finagent.memory.ConversationContextService;
import com.finagent.memory.ConversationService;
import com.finagent.memory.MemoryAssembler;
import com.finagent.memory.MemoryContext;
import com.finagent.understanding.QueryUnderstanding;
import com.finagent.understanding.TurnContext;
import com.finagent.scope.ChatScope;
import com.finagent.scope.ScopeEmptyException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 把一次 Agent 运行翻译成 C15/C16 的 SSE 事件（阶段 11 Task 7）。
 *
 * <p>三种事件：{@code stage}（每个节点一次，phase 就是节点 id）、{@code delta}（answer 节点的流式回调，
 * 经 {@link AnswerStreamBridge} 按 runId 送回）、{@code approval} 或 {@code final}（二选一收尾）。
 *
 * <p>不发 {@code meta}：agent 的证据是随节点逐步到达的，伪造一个「一次性装配好的上下文」
 * 会让前端误以为它和普通问答同构（见 ST11 §16.2）。
 *
 * <p>2026-10-29（ST14 P1）：这一层开始承担「一轮问答的收尾」—— 图跑完把这一轮写进
 * {@code conversation_message}（原先只有直链写，图跑出来的对话在历史里是幽灵）。
 * 记忆注入也从这里进：图节点不查记忆表，只吃渲染好的 {@code memoryText}。
 */
@Service
public class AgentStreamService {

    private static final Logger log = LoggerFactory.getLogger(AgentStreamService.class);

    private final AgentRunService runs;
    private final AnswerStreamBridge bridge;
    private final ConversationService conversations;
    private final MemoryAssembler memoryAssembler;
    private final TurnRecorder turnRecorder;
    private final ChatTraceWriter traceWriter;
    private final AmbiguityDetector ambiguity;
    private final ConversationContextService conversationContexts;

    public AgentStreamService(AgentRunService runs, AnswerStreamBridge bridge,
                              ConversationService conversations, MemoryAssembler memoryAssembler,
                              TurnRecorder turnRecorder, ChatTraceWriter traceWriter,
                              AmbiguityDetector ambiguity,
                              ConversationContextService conversationContexts) {
        this.runs = runs;
        this.bridge = bridge;
        this.conversations = conversations;
        this.memoryAssembler = memoryAssembler;
        this.turnRecorder = turnRecorder;
        this.traceWriter = traceWriter;
        this.ambiguity = ambiguity;
        this.conversationContexts = conversationContexts;
    }

    /**
     * 建 run 并立刻跑；返回 runId（已写进 approval / final 的载荷）。
     */
    public UUID startStream(ChatRequest request, ChatStreamSink sink) {
        String question = request.question().trim();
        // 首屏反馈（C16）：必须在任何耗时动作之前发，understand 节点要调一次 QU（约 4 秒）
        sink.send(ChatStreamEvent.stage(AnalysisGraph.NODE_UNDERSTAND, "理解问题", Map.of()));
        // 会话与记忆必须在图开跑之前装配好：节点跑在别的线程上，拿不到 TenantContext，
        // 也不该为了读记忆再查一遍库（C17 §17.1「节点只写自己的键」）。
        Conversation conversation = request.conversationId() == null ? null : conversations.requireOwned(request.conversationId());
        ChatScope scope = conversations.effectiveScope(request.scope(), conversation);
        MemoryContext memory = conversation == null ? MemoryContext.EMPTY : memoryAssembler.assembleMemory(conversation);

        // 只有「不明确」才开人批图（ST14 P3）：interruptsBefore 是编译期配置，必须开跑前定
        AmbiguityDetector.Decision decision = ambiguity.detect(scope, question);
        if (decision.ambiguous()) {
            log.info("本轮需要人工确认：{}（问题：{}）", decision.reasons(), question);
        }
        UUID runId = runs.create(question, scope, conversation == null ? null : conversation.id());
        bridge.memory(runId.toString(), memory);
        bridge.turnContext(runId.toString(), turnContext(conversation, memory));

        //🗝️主流程：runs.execute 执行LLM流程
        runWithDelta(runId, () -> runs.execute(runId, question,
                        scope,
                        conversation == null ? null : conversation.id(),
                        memory.text() == null ? "" : memory.text(),
                        decision.ambiguous(),
                        progress(sink)),
                sink,
                conversation,
                question
        );
        return runId;
    }

    /**
     * 非流式问答（P4 起 `/api/chat` 的唯一实现）：跑完一张图，返回标准 ChatResponse。
     *
     * <p>不走人批（没有通道把审批交给调用方），歧义在同步接口里只能靠 `sufficient=false` +
     * warnings 表达；需要确认理解的场景请用流式端点。
     */
    public ChatResponse ask(ChatRequest request) {
        String question = request.question().trim();
        Conversation conversation = request.conversationId() == null
                ? null : conversations.requireOwned(request.conversationId());
        ChatScope scope = conversations.effectiveScope(request.scope(), conversation);
        MemoryContext memory = conversation == null
                ? MemoryContext.EMPTY : memoryAssembler.assembleMemory(conversation);
        Long conversationId = conversation == null ? null : conversation.id();
        UUID runId = runs.create(question, scope, conversationId);
        bridge.memory(runId.toString(), memory);
        bridge.turnContext(runId.toString(), turnContext(conversation, memory));
        try {
            runs.execute(runId, question, scope, conversationId,
                    memory.text() == null ? "" : memory.text(), false, AgentProgress.NONE);
            AgentRunService.RunView view = runs.view(runId);
            // 空作用域是「没有可检索的东西」，不是「答了但不知道」：HTTP 契约要求 409（C11）
            if (isScopeEmpty(view)) {
                throw new ScopeEmptyException("当前账号在这个范围里没有可检索的文档",
                        view.warnings() == null ? List.of() : view.warnings());
            }
            applyTurn(conversation, runId);
            ChatResponse response = synthesize(runId, view, conversation);
            if (response.trace() != null) {
                traceWriter.write(response.trace());
            }
            return turnRecorder.record(conversation, question, response, null);
        } finally {
            bridge.clear(runId.toString());
        }
    }

    /**
     * 审批后继续；runId 已存在，delta 直接绑。
     */
    public void resumeStream(UUID runId, String decision, String note, ChatStreamSink sink) {
        Long conversationId = runs.conversationIdOf(runId);
        Conversation conversation = conversationId == null ? null : conversations.requireOwned(conversationId);
        String question = runs.questionOf(runId);
        runWithDelta(runId, () -> runs.resume(runId, decision, note, progress(sink)),
                sink, conversation, question);
    }

    private void runWithDelta(UUID runId, java.util.function.Supplier<AgentRunService.Result> work,
                              ChatStreamSink sink, Conversation conversation, String question) {
        String key = runId.toString();
        bridge.bind(key, text -> sink.send(ChatStreamEvent.delta(text)));
        try {
            AgentRunService.Result result = work.get();
            AgentRunService.RunView view = runs.view(runId);
            if (result.waitingForApproval()) {
                sink.send(ChatStreamEvent.approval(approvalPayload(view)));
                return;
            }
            // 会话结构化上下文在「这一轮确实答完了」之后才落库：半路失败或停在审批
            // 都不该改写会话状态（C17 的会话状态只在成功收尾时前进）
            applyTurn(conversation, runId);
            // 落库必须在发 final 之前：前端拿到 messageId 才能把这一轮挂进历史
            ChatResponse answerResponse = synthesize(runId, view, conversation);
            if (answerResponse.trace() != null) {
                traceWriter.write(answerResponse.trace());
            }
            ChatResponse recorded = turnRecorder.record(conversation, question, answerResponse, null);
            if (isScopeEmpty(view)) {
                // 「一份都看不到」与 chat 链路的 409 SCOPE_EMPTY 对齐。
                // SSE 的头已经发出去了，改不了 HTTP 状态码，所以用 error 事件承载同一个语义。
                sink.send(ChatStreamEvent.error("SCOPE_EMPTY",
                        view.answer() == null || view.answer().isBlank()
                                ? "当前作用域内没有可检索的文档"
                                : view.answer()));
            } else {
                sink.send(ChatStreamEvent.finalData(finalPayload(view, recorded)));
            }
        } finally {
            bridge.clear(key);
        }
    }

    /**
     * 指代消解的输入：会话已确认的结构化槽位 + 装配记忆时顺手读出的最近几轮。
     *
     * <p>槽位来自 {@code conversation_context}（上一轮收尾时写入），最近几轮来自记忆装配 ——
     * 两者必须来自<b>同一次读取</b>，否则 Prompt 里的背景信息与消解依据可能对不上。
     *
     * <p>没有会话（单轮问答）时返回 {@link TurnContext#EMPTY}，QU 走单轮路径，行为与阶段 8 一致。
     */
    private TurnContext turnContext(Conversation conversation, MemoryContext memory) {
        if (conversation == null) {
            return TurnContext.EMPTY;
        }
        ConversationContext structured = conversationContexts.find(conversation.id()).orElse(null);
        if (structured == null) {
            return new TurnContext(null, null, null, null, List.of(), null, memory.recentTurns());
        }
        return new TurnContext(structured.companyCode(), structured.companyName(),
                structured.yearFrom(), structured.yearTo(), structured.documentIds(),
                structured.currentMetric(), memory.recentTurns());
    }

    /**
     * 一轮成功之后，把本轮的实体合并进会话结构化上下文（「那净利润呢」下一轮才有主语）。
     *
     * <p>用的是 understand 节点登记在 bridge 里的 QU 与解析后的作用域：
     * 落库要的是「这轮实际用了什么」，而不是这里重新推一遍。
     * 失败只记日志：上下文没更新不影响已经给出的答案，下一轮最多再推一次。
     */
    private void applyTurn(Conversation conversation, UUID runId) {
        if (conversation == null) {
            return;
        }
        QueryUnderstanding qu = bridge.query(runId.toString());
        com.finagent.scope.ResolvedScope scope = bridge.scope(runId.toString());
        if (qu == null || scope == null) {
            return;
        }
        try {
            conversationContexts.applyTurn(conversation, qu, scope);
        } catch (RuntimeException e) {
            log.warn("会话 {} 结构化上下文落库失败：{}", conversation.id(), e.toString());
        }
    }

    /**
     * 把图的结果折成一个 {@link ChatResponse}（P2 起它是 `final` 里的主要载荷）。
     *
     * <p>优先用 answer 节点登记在 {@link AnswerStreamBridge} 里的完整 {@link AnswerResult}：
     * 那里有 facts / citations / confidence / note / 被丢弃的引用数，是直链与评分脚本看到的那一份。
     * 假节点（e2e 不调模型）没有这份结果，回落到「证据 → 引用」的最简映射，测试仍能跑。
     *
     * <p>trace 的 `contextBlocks / contextTokens` 这里填 0：装配发生在 answer 节点内部，
     * 图没有把它回传（要回传得给状态加键，而状态必须可序列化 —— 见 bridge 的注释）。
     */
    private ChatResponse synthesize(UUID runId, AgentRunService.RunView view, Conversation conversation) {
        AnswerResult result = bridge.result(runId.toString());
        boolean sufficient = result != null
                ? result.sufficient() : !AgentRun.STATUS_REJECTED.equals(view.status());
        String text = result != null ? result.answer() : view.answer();
        List<AnswerResult.Fact> facts = result == null ? List.of() : result.facts();
        List<Citation> citations = result == null ? citationsOf(view) : result.citations();
        String confidence = result != null ? result.confidence()
                : (sufficient ? AnswerResult.CONFIDENCE_HIGH : AnswerResult.CONFIDENCE_INSUFFICIENT);
        String note = result == null ? null : result.note();

        ScopeInfo scopeInfo = conversation == null ? null : new ScopeInfo(
                new ScopeInfo.SessionScope(conversation.scope().kind(), conversation.scope().documentIds()),
                new ScopeInfo.EffectiveScope(conversation.scope().kind(), conversation.scope().documentIds(), "AGENT"),
                conversation.scope().documentIds(), null);
        ChatTrace trace = trace(runId, view, conversation, citations,
                result == null ? 0 : result.citationsDropped());
        return new ChatResponse(sufficient, text, facts, citations, confidence, note, scopeInfo,
                view.warnings(), trace, null);
    }

    /**
     * 假节点没有 AnswerResult 时的引用：只够「历史里能看到引了哪些块」，bbox 等一律 null。
     */
    private static List<Citation> citationsOf(AgentRunService.RunView view) {
        List<Citation> citations = new ArrayList<>();
        for (Map<String, Object> item : view.evidence()) {
            citations.add(new Citation(asLong(item.get("documentId")), null, asLong(item.get("chunkId")),
                    asInt(item.get("page")), asInt(item.get("page")), null, null,
                    asString(item.get("type")), null, null, null, asString(item.get("sectionPath"))));
        }
        return citations;
    }

    /**
     * trace：用运行自己的 runId 当 traceId（同一个 id 在日志、agent_run、SSE 里都能对上）。
     *
     * <p>分段耗时取自 {@code agent_run_step.duration_ms}：understand = 查询理解，
     * answer = 生成（cite_check 回边会重算一次，按累加算，如实反映花了多久）。
     */
    private ChatTrace trace(UUID runId, AgentRunService.RunView view, Conversation conversation,
                            List<Citation> citations, int citationsDropped) {
        ScopeTrace scope = conversation == null ? null : new ScopeTrace(conversation.scope().kind(),
                conversation.scope().documentIds(), null, "AGENT", false);
        RetrievalTrace retrieval = bridge.retrievalTrace(runId.toString());
        return new ChatTrace(runId.toString(), retrieval, scope, bridge.memoryTrace(runId.toString()), 0, 0,
                citations.size(), citationsDropped, 0, stepMs(view, AnalysisGraph.NODE_ANSWER),
                stepMs(view, AnalysisGraph.NODE_UNDERSTAND), view.warnings());
    }

    private static long stepMs(AgentRunService.RunView view, String node) {
        return view.steps().stream()
                .filter(step -> node.equals(step.node()))
                .mapToLong(step -> step.durationMs() == null ? 0 : step.durationMs())
                .sum();
    }

    private static long asLong(Object raw) {
        return raw instanceof Number n ? n.longValue() : 0L;
    }

    private static Integer asInt(Object raw) {
        return raw instanceof Number n ? n.intValue() : null;
    }

    private static String asString(Object raw) {
        return raw instanceof String s ? s : null;
    }

    /**
     * 拒答原因是「作用域为空」时才升级成 SCOPE_EMPTY；其它拒答（主体越界、证据不足）仍是 final。
     */
    private static boolean isScopeEmpty(AgentRunService.RunView view) {
        return AgentRun.STATUS_REJECTED.equals(view.status())
               && view.warnings() != null
               && view.warnings().contains("scope_empty");
    }

    private static AgentProgress progress(ChatStreamSink sink) {
        return (node, state) -> {
            // approve 在人批事件里已经表达过，再发一条 stage 只会让状态行闪一下（ST11 §19）
            if (AnalysisGraph.NODE_APPROVE.equals(node)) {
                return;
            }
            // understand 已经开跑前就发过（首屏不能等一次 LLM），这里不重复
            if (AnalysisGraph.NODE_UNDERSTAND.equals(node)) {
                return;
            }
            sink.send(stageEvent(node, state));
            // 证据先行（C16）：verify 之后命中集合已定，模型还没开始生成
            if (AnalysisGraph.NODE_VERIFY.equals(node)) {
                sink.send(ChatStreamEvent.metaEvidence(state.runId(), state.hits(), 0));
            }
        };
    }

    /**
     * 节点完成 → 一条 stage。label 由后端给，前端不自己编（C16 第 1 条）。
     */
    static ChatStreamEvent stageEvent(String node, AnalysisState state) {
        String label;
        Map<String, Object> detail = new LinkedHashMap<>();
        switch (node) {
            case AnalysisGraph.NODE_UNDERSTAND -> {
                label = "理解问题";
                detail.put("queryType", state.queryType());
            }
            case AnalysisGraph.NODE_PLAN -> {
                label = "规划检索路径";
                boolean decompose = !state.subQuestions().isEmpty();
                detail.put("route", decompose ? "DECOMPOSE" : "DIRECT");
                detail.put("subQuestions", state.subQuestions().size());
            }
            case AnalysisGraph.NODE_RETRIEVE -> {
                label = "检索证据";
                detail.put("hits", state.evidenceCount());
                // 实际检索路数 = 车道数（整句 + 子问题 + 年份 + 章节，见 C19 §19.3 / C24）
                detail.put("queries", state.lanes().isEmpty() ? 1 : state.lanes().size());
                detail.put("lanes", state.lanes());
            }
            case AnalysisGraph.NODE_VERIFY -> {
                label = state.insufficient() ? "证据不足" : "核对证据";
                detail.put("insufficient", state.insufficient());
            }
            case AnalysisGraph.NODE_REWRITE -> {
                label = "改写检索式";
                detail.put("retry", state.retryCount());
            }
            case AnalysisGraph.NODE_COMPUTE -> {
                label = "整理事实与派生指标";
                long computed = state.facts().stream()
                        .filter(row -> AnswerResult.ORIGIN_COMPUTED.equals(row.get("origin")))
                        .count();
                detail.put("facts", state.facts().size());
                detail.put("computed", (int) computed);
            }
            case AnalysisGraph.NODE_ANSWER -> label = "生成答案";
            case AnalysisGraph.NODE_CITE_CHECK -> {
                label = "校验引用";
                detail.put("citations", state.citationCount());
            }
            case AnalysisGraph.NODE_INSUFFICIENT -> label = "证据不足，拒答";
            default -> label = node;
        }
        return ChatStreamEvent.stage(node, label, detail);
    }

    static Map<String, Object> approvalPayload(AgentRunService.RunView view) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("runId", view.runId().toString());
        data.put("status", view.status());
        data.put("plan", view.plan());
        data.put("evidence", view.evidence());
        data.put("steps", view.steps());
        return data;
    }

    static Map<String, Object> finalPayload(AgentRunService.RunView view, ChatResponse recorded) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("runId", view.runId().toString());
        data.put("status", view.status());
        data.put("answer", view.answer() == null ? "" : view.answer());
        data.put("warnings", view.warnings());
        data.put("plan", view.plan());
        data.put("evidence", view.evidence());
        data.put("steps", view.steps());
        data.put("currentNode", view.currentNode());
        data.put("decision", view.decision());
        data.put("decisionNote", view.decisionNote());
        data.put("error", view.error());
        // P2 起 final 里嵌一份标准 ChatResponse：前端不必再从 evidence 硬拼引用（C18）
        if (recorded != null) {
            data.put("response", recorded);
        }
        // 收尾落库的回执：前端拿到这两个才能把这一轮挂进历史（P2 会把它们并进 ChatResponse）
        if (recorded != null && recorded.conversationId() != null) {
            data.put("conversationId", recorded.conversationId());
            data.put("messageId", recorded.messageId());
        }
        return data;
    }
}
