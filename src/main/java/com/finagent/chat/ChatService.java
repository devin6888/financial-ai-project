package com.finagent.chat;

import com.finagent.answer.AnswerResult;
import com.finagent.answer.AnswerService;
import com.finagent.answer.PeriodLabeler;
import com.finagent.context.ContextBuilder;
import com.finagent.context.ContextPackage;
import com.finagent.memory.Conversation;
import com.finagent.memory.ConversationContext;
import com.finagent.memory.ConversationService;
import com.finagent.memory.ConversationContextService;
import com.finagent.memory.ConversationSummarizer;
import com.finagent.memory.LongTermMemoryRecaller;
import com.finagent.memory.LongTermMemoryService;
import com.finagent.memory.MemoryAssembler;
import com.finagent.memory.MemoryContext;
import com.finagent.memory.MemoryGate;
import com.finagent.retrieval.HybridRetriever;
import com.finagent.retrieval.RetrievalProgress;
import com.finagent.retrieval.RetrievalRequest;
import com.finagent.retrieval.RetrievalTrace;
import com.finagent.scope.ChatScope;
import com.finagent.scope.DocMeta;
import com.finagent.scope.ResolvedScope;
import com.finagent.scope.ScopeResolver;
import com.finagent.understanding.CompanyResolver;
import com.finagent.understanding.QueryType;
import com.finagent.understanding.QueryUnderstanding;
import com.finagent.understanding.RouterService;
import com.finagent.understanding.TurnContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 问答编排：作用域 → QU → 检索 → 上下文 → 答案。
 *
 * <p>顺序不能换：作用域必须在 QU 之前解析（QU 的 company/year 要与作用域核对，
 * 越界的推断不能把范围撑大）；上下文必须在答案之前装配（答案只认 [E1..En] 编号）。
 *
 * <p>2026-10-28（阶段 12）为流式输出拆成三段：
 * {@link #prepare} 准备（检索 + 上下文，流式时先发 meta 事件）、
 * {@link #compose} 收口（期间标注 + 结构化上下文 + trace + 响应对象）、
 * 两个入口 {@link #ask} / {@link #askStreaming}。
 * <b>流式与非流式共用 prepare 与 compose</b>，所以两条路径的警告、trace、落库行为不可能分叉。
 */
@Service
public class ChatService {

    private static final Logger log = LoggerFactory.getLogger(ChatService.class);

    private final ScopeResolver scopeResolver;
    private final RouterService router;
    private final HybridRetriever retriever;
    private final ContextBuilder contextBuilder;
    private final AnswerService answerService;
    private final PeriodLabeler periodLabeler;
    private final ChatTraceWriter traceWriter;
    private final ConversationService conversations;
    private final MemoryAssembler memoryAssembler;
    private final ConversationContextService conversationContexts;
    private final ConversationSummarizer summarizer;
    private final LongTermMemoryService memories;
    private final MemoryGate memoryGate;
    private final LongTermMemoryRecaller memoryRecaller;

    public ChatService(ScopeResolver scopeResolver, RouterService router, HybridRetriever retriever,
                       ContextBuilder contextBuilder, AnswerService answerService,
                       PeriodLabeler periodLabeler, ChatTraceWriter traceWriter,
                       ConversationService conversations, MemoryAssembler memoryAssembler,
                       ConversationContextService conversationContexts,
                       ConversationSummarizer summarizer, LongTermMemoryService memories,
                       MemoryGate memoryGate, LongTermMemoryRecaller memoryRecaller) {
        this.scopeResolver = scopeResolver;
        this.router = router;
        this.retriever = retriever;
        this.contextBuilder = contextBuilder;
        this.answerService = answerService;
        this.periodLabeler = periodLabeler;
        this.traceWriter = traceWriter;
        this.conversations = conversations;
        this.memoryAssembler = memoryAssembler;
        this.conversationContexts = conversationContexts;
        this.summarizer = summarizer;
        this.memories = memories;
        this.memoryGate = memoryGate;
        this.memoryRecaller = memoryRecaller;
    }

    // ------------------------------------------------------------ 两个入口

    /** 非流式入口（阶段 5 起一直用它，也是评测与 e2e 的基准路径）。 */
    public ChatResponse ask(ChatRequest request) {
        Prepared p = prepare(request);
        if (p.refusal() != null) {
            return finishTurn(p.conversation(), p.question(), p.refusal(), p.scope());
        }
        long answerStart = System.currentTimeMillis();
        AnswerResult answer = answerService.answer(p.question(), p.qu(), p.pkg(), p.memory());
        long answerMs = System.currentTimeMillis() - answerStart;
        return finishTurn(p.conversation(), p.question(), compose(p, answer, answerMs), p.scope());
    }

    /**
     * 流式入口：与 {@link #ask} 完全相同的准备与收口，只把「答案生成」这一段换成增量回调。
     *
     * <p>事件顺序（契约 C15）：meta → delta* → final；拒答时只有 final（没有证据可展示）。
     * 生成异常不在这里吞：那由 controller 转成 error 事件；而「证据不足」是
     * {@link AnswerService} 的正常业务结果，会照常走到 final。
     */
    public ChatResponse askStreaming(ChatRequest request, ChatStreamSink sink) {
        // 第一件事不是准备，而是告诉前端「我收到了」。这一行让首屏反馈从 7.9 秒降到百毫秒级（契约 C16）。
        // 此时连 prepare 都还没进 —— 参数校验、会话归属、作用域解析都还没做，所以文案只能是「正在理解问题」。
        // 回复1
        sink.send(ChatStreamEvent.stage(ChatStreamEvent.PHASE_UNDERSTANDING, "正在理解问题", Map.of()));

        Prepared p = prepare(request, sink);
        if (p.refusal() != null) {
            ChatResponse response = finishTurn(p.conversation(), p.question(), p.refusal(), p.scope());
            sink.send(ChatStreamEvent.finalEvent(response));
            return response;
        }
        sink.send(ChatStreamEvent.meta(p.traceId(), p.scope(), p.pkg()));

        // 证据已经在 meta 里发给前端了，这里只补一句「接下来在干什么」
        sink.send(ChatStreamEvent.stage(ChatStreamEvent.PHASE_GENERATING, "正在生成答案",
                Map.of("evidence", p.pkg().blocks().size(), "contextTokens", p.pkg().tokenCount())));
        long answerStart = System.currentTimeMillis();
        AnswerResult answer = answerService.answerStreaming(p.question(), p.qu(), p.pkg(), p.memory(),
                delta -> sink.send(ChatStreamEvent.delta(delta)));
        long answerMs = System.currentTimeMillis() - answerStart;
        ChatResponse response = finishTurn(p.conversation(), p.question(),
                compose(p, answer, answerMs), p.scope());
        sink.send(ChatStreamEvent.finalEvent(response));
        return response;
    }

    // ------------------------------------------------------------ 准备阶段

    /**
     * 准备阶段的全部中间态。
     *
     * <p>为什么把 {@code traceId} 也放进来：meta 事件要在生成前就把它发给前端，
     * 而 trace 原本是收口时才创建的 —— 提前生成、两处共用，才不会出现
     * 「前端 traceId 与落盘 trace 对不上」这种排查噩梦。
     */
    private record Prepared(String traceId, Conversation conversation, String question,
                            ResolvedScope scope, MemoryContext memory, QueryUnderstanding qu,
                            ContextPackage pkg, long contextMs, long quMs, List<String> quWarnings,
                            List<String> memoryWarnings, List<String> retrievalWarnings,
                            RetrievalTrace retrievalTrace, ChatResponse refusal) { }

    /** 非流式入口用：不需要进度事件。 */
    private Prepared prepare(ChatRequest request) {
        return prepare(request, null);
    }

    /**
     * 准备阶段（流式时带进度回调）。
     *
     * @param stages 进度事件出口；非流式传 {@code null}。它只收 stage，不收 meta/final ——
     *               后两者由 {@link #askStreaming} 自己发，避免两处都能发导致顺序错乱
     */
    private Prepared prepare(ChatRequest request, ChatStreamSink stages) {
        String question = request == null ? "" : request.question();
        if (question.isBlank()) {
            throw new IllegalArgumentException("question 不能为空");
        }

        // 会话：先做归属校验（不是自己的一律 404），再用会话配置兜底作用域。
        // 顺序不能反：先解析作用域的话，别人会话里的文档范围会被当成自己的授权来用。
        Conversation conversation = request.conversationId() == null ? null : conversations.requireOwned(request.conversationId());
        ChatScope requestedScope = conversations.effectiveScope(request.scope(), conversation);
        ResolvedScope scope = scopeResolver.resolve(requestedScope);
        // 背景信息在「归属校验之后」装配：别人的会话连读都不该读
        MemoryContext memory = conversation == null ? MemoryContext.EMPTY : memoryAssembler.assembleMemory(conversation);

        // 指代消解：结构化槽位 + 最近几轮。没有会话（或会话里什么都没有）时
        // 走单轮重载 —— 「无会话」与「阶段 8 的行为」必须是同一条代码路径，不是「大概等价」。
        // QU 是整条链路最慢的一段（一次 LLM 调用，实测约 2.9 秒），单独计时才能回答
        // 「首字 7.9 秒里 QU 占多少」这种问题（阶段 13 新增，落进 ChatTrace.quMs）
        long quStart = System.currentTimeMillis();
        TurnContext turnContext = turnContext(conversation, memory);
        QueryUnderstanding qu = turnContext.isEmpty() ? router.understand(question) : router.understand(question, turnContext);
        long quMs = System.currentTimeMillis() - quStart;

        // 公司落地（ST9 §16）：模型只被允许「抄原文里的公司名」，代码一律由 CompanyResolver 查库得到。
        // 顺序很重要：① validate 已保证 mentions 是原文子串、codes 是原文里真出现的 6 位数字；
        // ② 这里再把名字对到作用域内的 company 行。模型因此<b>没有编造代码的通道</b>。
        List<String> quWarnings = new ArrayList<>();
        CompanyResolver.Resolution company = CompanyResolver.resolve(
                qu.companyCodes(), qu.companyMentions(), scope);
        if (company.hasMatch()) {
            qu = qu.withCompanyCodes(company.codes());
        }
        quWarnings.addAll(company.warnings());
        // 问题里没点名公司，但作用域里只有一家 → 用它补（确定性推断，不是模型猜）。
        // 为什么不补不行：评测集与真实提问常见「2024年营业总收入是多少？」，公司由作用域隐含；
        // 不补的话 Sparse 精确模式会丢掉公司这个词，命中面变宽（实测召回率不变，但精度预算被稀释）。
        if (qu.companyCodes() == null || qu.companyCodes().isEmpty()) {
            List<String> scopeCodes = scopeCompanyCodes(scope);
            if (scopeCodes.size() == 1) {
                qu = qu.withCompanyCodes(scopeCodes);
                quWarnings.add("company_from_scope");
            }
        }
        // 点名了范围外的公司 → 拒答。2026-10-22 评测抓到：问「五粮液2024年营业收入」、范围只有
        // 东方财富年报，系统把东方财富的营收当答案返回（数值对、主体错，比乱答更难发现）。
        if (company.blocked()) {
            return new Prepared(UUID.randomUUID().toString(), conversation, question, scope, memory, qu,
                    ContextPackage.empty(), 0, quMs, List.copyOf(quWarnings), List.of(), List.of(), null,
                    refuseUnresolvedCompanies(scope, company.unresolved()));
        }

        // 长期记忆：Gate 通过才召回（不通过连库都不查；库里没有记忆也不调 embedding）。
        // 必须在 QU 之后 —— 闸门有一半输入是 QU 的 needLongTermMemory。
        List<String> memoryWarnings = new ArrayList<>();
        if (memoryGate.shouldRecall(question, qu)) {
            try {
                MemoryAssembler.WithLongTerm merged =
                        memoryAssembler.withLongTerm(memory, memoryRecaller.recall(question));
                memory = merged.context();
                if (merged.instructionOverflow()) {
                    memoryWarnings.add("instruction_overflow");
                }
            } catch (RuntimeException e) {
                log.error("长期记忆召回失败，本轮忽略", e);
                memoryWarnings.add("memory_recall_failed");
            }
        }

        // 检索用「可独立检索」的查询：追问（「那净利润呢」）只有补全成
        // 「东方财富（300059） 2025年 那净利润呢」才会命中正确段落。
        // 答案 Prompt 仍用用户原话 —— 那是用户真正问的。
        String retrievalQuery = qu.normalizedQuery() == null || qu.normalizedQuery().isBlank()
                ? question : qu.normalizedQuery();
        if (stages != null) {
            // 作用域已经解析完，可以告诉用户「在多少份文档里找」
            stages.send(ChatStreamEvent.stage(ChatStreamEvent.PHASE_RETRIEVING, "正在检索证据",
                    Map.of("documents", scope == null ? 0 : scope.size())));
        }
        HybridRetriever.Result retrieval = retriever.retrieve(RetrievalRequest.of(retrievalQuery), qu, scope,
                stageProgress(stages));
        QueryType queryType = retrieval.trace() == null ? qu.queryType() : retrieval.trace().queryType();

        long contextStart = System.currentTimeMillis();
        ContextPackage pkg = contextBuilder.build(question, queryType, retrieval.chunks(), scope);
        long contextMs = System.currentTimeMillis() - contextStart;

        List<String> retrievalWarnings = retrieval.trace() == null ? List.of() : retrieval.trace().warnings();
        return new Prepared(UUID.randomUUID().toString(), conversation, question, scope, memory, qu,
                pkg, contextMs, quMs, List.copyOf(quWarnings), List.copyOf(memoryWarnings),
                retrievalWarnings, retrieval.trace(), null);
    }

    /**
     * 把「流式进度出口」包成检索层能用的回调（阶段 13，契约 C16）。
     *
     * <p>只转发 rerank 一个阶段：dense / sparse / fuse 都属于用户眼里的「检索中」，
     * 每段都发一次只会让状态行闪。没有 stages（非流式）时直接返回空实现。
     */
    private static RetrievalProgress stageProgress(ChatStreamSink stages) {
        if (stages == null) {
            return RetrievalProgress.NONE;
        }
        return (phase, count) -> {
            if (RetrievalProgress.PHASE_RERANK.equals(phase)) {
                stages.send(ChatStreamEvent.stage(ChatStreamEvent.PHASE_RERANKING, "正在精排证据",
                        Map.of("candidates", count)));
            }
        };
    }

    // ------------------------------------------------------------ 收口阶段

    /**
     * 把「准备好的中间态 + 答案」合成最终响应：期间标注、结构化上下文、警告合并、trace 落盘。
     *
     * <p>流式与非流式都走这里，保证同一个问题在两种模式下 warnings / trace / 落库结果一致。
     */
    private ChatResponse compose(Prepared p, AnswerResult answer, long answerMs) {
        // 期间标注：引用来自「别的年份的报告」时必须留痕（阶段 5 遗留，见 ST6 Task 4）
        PeriodLabeler.Result period = periodLabeler.label(p.qu(), answer.citations(), p.scope());

        // 结构化上下文：回答成功后才落库（本轮检索用的是「上一轮的状态」，
        // 落库失败不能影响这一轮的答案 —— 只加一条警告）
        List<String> contextWarnings = applyContext(p.conversation(), p.qu(), p.scope());

        List<String> warnings = mergeWarnings(p.scope().warnings(),
                resolutionWarnings(p.qu()), p.quWarnings(), p.memoryWarnings(),
                p.retrievalWarnings(),
                p.pkg().warnings(), answer.warnings(), period.warnings(), contextWarnings);
        String note = mergeNote(answer.note(), period.note());

        ChatTrace trace = new ChatTrace(p.traceId(), p.retrievalTrace(),
                new ScopeTrace(p.scope().kind(), p.scope().documentIds(), p.scope().resolverVersion(),
                        p.scope().effectiveReason(), false),
                MemoryTrace.from(p.memory()),
                p.pkg().blocks().size(), p.pkg().tokenCount(), answer.citations().size(),
                answer.citationsDropped(), p.contextMs(), answerMs, p.quMs(), warnings);
        traceWriter.write(trace);

        log.info("问答完成：trace={} blocks={} tokens={} citations={} confidence={} "
                        + "cost={}ms（理解 {} / 检索 {} / 装配 {} / 生成 {}）",
                trace.traceId(), p.pkg().blocks().size(), p.pkg().tokenCount(), answer.citations().size(),
                answer.confidence(), trace.totalMs(), p.quMs(),
                p.retrievalTrace() == null ? 0 : p.retrievalTrace().totalMs(), p.contextMs(), answerMs);

        return new ChatResponse(answer.sufficient(), answer.answer(), answer.facts(),
                answer.citations(), answer.confidence(), note,
                new ScopeInfo(new ScopeInfo.SessionScope(p.scope().kind(), p.scope().documentIds()),
                        new ScopeInfo.EffectiveScope(p.scope().kind(), p.scope().documentIds(),
                                p.scope().effectiveReason()),
                        p.scope().documentIds(), p.scope().resolverVersion()),
                warnings, trace, null);
    }
    /**
     * 把会话里已确认的槽位与短期窗口的配对消息装成 QU 的入参。
     *
     * <p>两条信息来源不同、作用也不同：结构化槽位是「确定的事实」（可以直接补进查询），
     * 最近几轮是「模型看懂指代的原料」。缺一个都会让「那净利润呢」补不出来。
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

    /** 补全过就留痕：trace 里要能看出「这一轮的公司/年份是推出来的，不是用户说的」。 */
    private static List<String> resolutionWarnings(QueryUnderstanding qu) {
        return qu != null && qu.inferredFromContext()
                ? List.of("query_inferred_from_context") : List.of();
    }

    private List<String> applyContext(Conversation conversation, QueryUnderstanding qu,
                                      ResolvedScope scope) {
        if (conversation == null) {
            return List.of();
        }
        try {
            conversationContexts.applyTurn(conversation, qu, scope);
            return List.of();
        } catch (RuntimeException e) {
            log.error("会话 {} 结构化上下文落库失败", conversation.id(), e);
            return List.of("context_persist_failed");
        }
    }

    /**
     * 一轮的收尾：先落库，再试着更新摘要。
     *
     * <p>顺序不能反：摘要要包含这一轮的内容，落库之后才看得见；
     * 摘要失败也不能影响已经生成好的答案 —— 只加一条 {@code summary_failed}，
     * 指针不动，下一轮自然重试（ST9 §11.2 规则 1）。
     */
    private ChatResponse finishTurn(Conversation conversation, String question,
                                    ChatResponse response, ResolvedScope scope) {
        ChatResponse persisted = persist(conversation, question, response, scope);
        // 显式命令（「以后…」「记住：…」）在收尾时落库：不改这一轮的回答，失败也只加警告。
        // 有没有会话都要记 —— 偏好不属于某一次会话。
        ChatResponse withMemory = recordMemoryCommand(question, persisted);
        if (conversation == null) {
            return withMemory;
        }
        try {
            summarizer.maybeSummarize(conversation);
            return withMemory;
        } catch (RuntimeException e) {
            log.error("会话 {} 摘要更新失败，本轮忽略", conversation.id(), e);
            List<String> warnings = new ArrayList<>(withMemory.warnings());
            warnings.add("summary_failed");
            return withMemory.withWarnings(List.copyOf(warnings));
        }
    }

    /**
     * 识别并写入显式记忆命令。
     *
     * <p>服务层自己会判「记忆开关 / 有没有身份 / 像不像命令」，三种情况都返回空 ——
     * 所以这里不需要额外条件，也不该因为「本来可以写一条记忆」去污染响应。
     */
    private ChatResponse recordMemoryCommand(String question, ChatResponse response) {
        try {
            memories.maybeRecordCommand(question, response.trace() == null ? null : response.trace().traceId());
            return response;
        } catch (RuntimeException e) {
            log.error("显式记忆写入失败，本轮忽略", e);
            List<String> warnings = new ArrayList<>(response.warnings());
            warnings.add("memory_write_failed");
            return response.withWarnings(List.copyOf(warnings));
        }
    }

    /**
     * 落库这一轮问答（会话问答才有）。
     *
     * <p>为什么失败只加警告不抛：答案已经生成、trace 已经落盘，为了一条会话记录让用户
     * 看到 500 是更差的选择。前端看到 {@code conversation_persist_failed} 可以提示
     * 「这轮不会出现在历史里」。
     */
    private ChatResponse persist(Conversation conversation, String question, ChatResponse response,
                                 ResolvedScope scope) {
        if (conversation == null) {
            return response;
        }
        try {
            long messageId = conversations.recordTurn(conversation, question, response, scope);
            return response.withConversation(conversation.id(), messageId);
        } catch (RuntimeException e) {
            log.error("会话 {} 落库失败，本轮答案不落库", conversation.id(), e);
            List<String> warnings = new ArrayList<>(response.warnings());
            warnings.add("conversation_persist_failed");
            return response.withWarnings(List.copyOf(warnings)).withConversation(conversation.id(), null);
        }
    }

    /** 作用域里出现过的公司代码（去重保序）。空集=没约束，单元素=可以当默认值。 */
    private static List<String> scopeCompanyCodes(ResolvedScope scope) {
        if (scope == null || scope.documents() == null) {
            return List.of();
        }
        return scope.documents().stream()
                .map(DocMeta::companyCode)
                .filter(c -> c != null && !c.isBlank())
                .distinct()
                .toList();
    }

    /**
     * 问题点名了范围外的公司 → 拒答（ST9 §16.3 第 5 条）。
     *
     * <p>为什么不能「当没看见」继续检索：阶段 6 评测抓到「问五粮液答东方财富」—— 丢掉公司约束后，
     * 检索会把别家公司的数字当成答案返回，数值自身对、主体错，比拒答更难发现。
     *
     * @param names 用户原话里的公司名（直接回显用户自己的措辞，不涉及库里任何数据）
     */
    private ChatResponse refuseUnresolvedCompanies(ResolvedScope scope, List<String> names) {
        String joined = String.join("、", names);
        String answer = "本轮研究范围（" + scope.summary() + "）内没有 " + joined
                + " 的报告，无法回答该问题。请调整研究范围，或加入含该公司的文档后重问。";
        List<String> warnings = new ArrayList<>(scope.warnings());
        // warnings 只放稳定码：它会给程序/日志消费，不该把用户提问里的公司名带出去
        warnings.add("company_unresolved");

        ChatTrace trace = new ChatTrace(UUID.randomUUID().toString(), null,
                new ScopeTrace(scope.kind(), scope.documentIds(), scope.resolverVersion(),
                        scope.effectiveReason(), false),
                MemoryTrace.EMPTY,
                0, 0, 0, 0, 0, 0, warnings);
        traceWriter.write(trace);
        log.info("公司不在研究范围内，拒答：{}", names);

        return new ChatResponse(false, answer, List.of(), List.of(),
                AnswerResult.CONFIDENCE_INSUFFICIENT,
                "问题涉及的公司不在研究范围内，未检索、未生成答案。",
                new ScopeInfo(new ScopeInfo.SessionScope(scope.kind(), scope.documentIds()),
                        new ScopeInfo.EffectiveScope(scope.kind(), scope.documentIds(), scope.effectiveReason()),
                        scope.documentIds(), scope.resolverVersion()),
                warnings, trace, null);
    }

    private static String mergeNote(String a, String b) {
        if (a == null || a.isBlank()) {
            return b == null || b.isBlank() ? null : b;
        }
        if (b == null || b.isBlank()) {
            return a;
        }
        return a + "；" + b;
    }

    private static List<String> mergeWarnings(List<String>... groups) {
        LinkedHashSet<String> all = new LinkedHashSet<>();
        for (List<String> group : groups) {
            if (group != null) {
                all.addAll(group);
            }
        }
        return List.copyOf(new ArrayList<>(all));
    }
}
