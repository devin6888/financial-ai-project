package com.finagent.chat;

import com.finagent.answer.AnswerResult;
import com.finagent.answer.AnswerService;
import com.finagent.answer.PeriodLabeler;
import com.finagent.context.ContextBuilder;
import com.finagent.context.ContextPackage;
import com.finagent.memory.Conversation;
import com.finagent.memory.ConversationContextService;
import com.finagent.memory.ConversationService;
import com.finagent.memory.MemoryAssembler;
import com.finagent.memory.MemoryContext;
import com.finagent.retrieval.HybridRetriever;
import com.finagent.retrieval.RerankStatus;
import com.finagent.retrieval.RetrievalRequest;
import com.finagent.retrieval.RetrievalTrace;
import com.finagent.scope.ChatScope;
import com.finagent.scope.DocMeta;
import com.finagent.scope.ResolvedScope;
import com.finagent.scope.ScopeKind;
import com.finagent.scope.ScopeResolver;
import com.finagent.understanding.QueryType;
import com.finagent.understanding.QueryUnderstanding;
import com.finagent.understanding.RouterService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 编排：作用域 → 检索 → 上下文 → 答案，以及 trace 的拼装。 */
class ChatServiceTest {

    private ScopeResolver scopeResolver;
    private RouterService router;
    private HybridRetriever retriever;
    private ContextBuilder contextBuilder;
    private AnswerService answerService;
    private PeriodLabeler periodLabeler;
    private ChatTraceWriter traceWriter;
    private ConversationService conversations;
    private MemoryAssembler memoryAssembler;
    private ConversationContextService conversationContexts;
    private com.finagent.memory.ConversationSummarizer summarizer;
    private com.finagent.memory.LongTermMemoryService longTermMemory;
    private com.finagent.memory.MemoryGate memoryGate;
    private com.finagent.memory.LongTermMemoryRecaller memoryRecaller;
    private ChatService service;

    @BeforeEach
    void setUp() {
        scopeResolver = mock(ScopeResolver.class);
        router = mock(RouterService.class);
        retriever = mock(HybridRetriever.class);
        contextBuilder = mock(ContextBuilder.class);
        answerService = mock(AnswerService.class);
        periodLabeler = mock(PeriodLabeler.class);
        traceWriter = mock(ChatTraceWriter.class);
        conversations = mock(ConversationService.class);
        // 真实实现：请求带 scope 就用请求的，否则用会话配置
        when(conversations.effectiveScope(any(), any())).thenAnswer(inv -> {
            Object requested = inv.getArgument(0);
            if (requested != null) {
                return requested;
            }
            Conversation session = inv.getArgument(1);
            return session == null ? null : session.scope();
        });
        memoryAssembler = mock(MemoryAssembler.class);
        when(memoryAssembler.assemble(any())).thenReturn(MemoryContext.EMPTY);
        conversationContexts = mock(ConversationContextService.class);
        summarizer = mock(com.finagent.memory.ConversationSummarizer.class);
        longTermMemory = mock(com.finagent.memory.LongTermMemoryService.class);
        when(longTermMemory.maybeRecordCommand(anyString(), any())).thenReturn(java.util.Optional.empty());
        memoryGate = mock(com.finagent.memory.MemoryGate.class);
        memoryRecaller = mock(com.finagent.memory.LongTermMemoryRecaller.class);
        service = new ChatService(scopeResolver, router, retriever, contextBuilder, answerService,
                periodLabeler, traceWriter, conversations, memoryAssembler, conversationContexts,
                summarizer, longTermMemory, memoryGate, memoryRecaller);

        when(periodLabeler.label(any(), any(), any())).thenReturn(PeriodLabeler.Result.EMPTY);

        when(scopeResolver.resolve(any())).thenReturn(scope());
        when(router.understand(anyString())).thenReturn(qu());
        when(router.understand(anyString(), any())).thenReturn(qu());
        when(retriever.retrieve(any(RetrievalRequest.class), any(QueryUnderstanding.class), any(ResolvedScope.class)))
                .thenReturn(new HybridRetriever.Result(List.of(), retrievalTrace()));
        when(contextBuilder.build(anyString(), any(QueryType.class), any(), any())).thenReturn(ContextPackage.empty());
        when(answerService.answer(anyString(), any(), any(), any())).thenReturn(answer());
    }

    @Test
    @DisplayName("正常编排 → 响应字段齐全，trace 落盘一次")
    void orchestrates() {
        ChatResponse r = service.ask(new ChatRequest("2024年营业总收入", ChatScope.documents(38L), "ASK"));

        assertThat(r.trace().traceId()).isNotBlank();
        assertThat(r.trace().retrieval()).isNotNull();
        assertThat(r.trace().scope().documentIds()).containsExactly(38L);
        assertThat(r.scope().resolvedDocumentIds()).containsExactly(38L);
        assertThat(r.trace().citationsDropped()).isEqualTo(1);
        verify(traceWriter).write(any(ChatTrace.class));
    }

    @Test
    @DisplayName("两次请求 traceId 不同")
    void traceIdsAreUnique() {
        String a = service.ask(new ChatRequest("q1", null, "ASK")).trace().traceId();
        String b = service.ask(new ChatRequest("q2", null, "ASK")).trace().traceId();

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    @DisplayName("四段 warning 合并去重")
    void mergesWarnings() {
        when(scopeResolver.resolve(any())).thenReturn(scope(List.of("scope_over_soft_limit_6_gt_5")));
        when(contextBuilder.build(anyString(), any(QueryType.class), any(), any()))
                .thenReturn(new ContextPackage("文本", List.of(), 10, List.of("context_truncated"), 1, 0));
        when(answerService.answer(anyString(), any(), any(), any())).thenReturn(
                new AnswerResult(true, "a", List.of(), List.of(), "high", null,
                        List.of("answer_without_citation"), 0));

        ChatResponse r = service.ask(new ChatRequest("q", null, "ASK"));

        assertThat(r.warnings()).contains("scope_over_soft_limit_6_gt_5", "context_truncated",
                "answer_without_citation");
    }

    @Test
    @DisplayName("期间标注的 warning 与 note 会并入响应")
    void mergesPeriodLabeling() {
        when(periodLabeler.label(any(), any(), any())).thenReturn(
                new PeriodLabeler.Result(List.of("evidence_period_mismatch:2023@2024"), "部分证据来自 2024 年年度报告的上年同期对比列，数值口径请核对期间。"));
        when(answerService.answer(anyString(), any(), any(), any())).thenReturn(
                new AnswerResult(true, "a", List.of(), List.of(), "high", "现有 note", List.of(), 0));

        ChatResponse r = service.ask(new ChatRequest("2023年营业总收入", ChatScope.documents(38L), "ASK"));

        assertThat(r.warnings()).contains("evidence_period_mismatch:2023@2024");
        assertThat(r.note()).startsWith("现有 note").contains("对比列");
    }

    @Test
    @DisplayName("问的公司不在作用域 → 拒答，且不检索、不调模型")
    void refusesOutsideCompany() {
        when(router.understand(anyString())).thenReturn(new QueryUnderstanding(QueryType.SEMANTIC_EXPLANATION,
                "五粮液2024年营业收入", List.of("000858"), new int[]{2024, 2024}, List.of("营业收入"),
                false, false, null, false, "LLM"));

        ChatResponse r = service.ask(new ChatRequest("五粮液2024年营业收入是多少？", ChatScope.documents(38L), "NEVER"));

        assertThat(r.sufficient()).isFalse();
        assertThat(r.confidence()).isEqualTo("insufficient");
        assertThat(r.warnings()).contains("company_out_of_scope:000858");
        assertThat(r.answer()).contains("000858");
        verify(retriever, org.mockito.Mockito.never())
                .retrieve(any(RetrievalRequest.class), any(QueryUnderstanding.class), any(ResolvedScope.class));
    }

    @Test
    @DisplayName("空问题 → IllegalArgumentException，不碰作用域与模型")
    void blankQuestionRejected() {
        assertThatThrownBy(() -> service.ask(new ChatRequest("  ", null, "ASK")))
                .isInstanceOf(IllegalArgumentException.class);

        verify(scopeResolver, org.mockito.Mockito.never()).resolve(any());
    }

    @Test
    @DisplayName("带 conversationId：先校验归属，答完后落库并回传 messageId")
    void conversationTurnIsPersisted() {
        Conversation session = new Conversation(5L, 1L, 1L, "会话", ScopeKind.CORPUS,
                ChatScope.corpus(), Conversation.STATUS_ACTIVE, null, null);
        when(conversations.requireOwned(5L)).thenReturn(session);
        when(conversations.recordTurn(any(), anyString(), any(), any())).thenReturn(99L);

        ChatResponse r = service.ask(new ChatRequest("2024年营业总收入", ChatScope.documents(38L), "ASK", 5L));

        verify(conversations).requireOwned(5L);
        verify(memoryAssembler).assemble(session);
        verify(conversationContexts).applyTurn(org.mockito.ArgumentMatchers.eq(session), any(), any());
        verify(conversations).recordTurn(org.mockito.ArgumentMatchers.eq(session),
                org.mockito.ArgumentMatchers.eq("2024年营业总收入"), any(), any());
        assertThat(r.conversationId()).isEqualTo(5L);
        assertThat(r.messageId()).isEqualTo(99L);
    }

    @Test
    @DisplayName("落库失败 → 答案照常返回，只多一条警告")
    void persistFailureKeepsAnswer() {
        Conversation session = new Conversation(5L, 1L, 1L, "会话", ScopeKind.CORPUS,
                ChatScope.corpus(), Conversation.STATUS_ACTIVE, null, null);
        when(conversations.requireOwned(5L)).thenReturn(session);
        when(conversations.recordTurn(any(), anyString(), any(), any()))
                .thenThrow(new IllegalStateException("db down"));

        ChatResponse r = service.ask(new ChatRequest("q", null, "ASK", 5L));

        assertThat(r.answer()).isNotBlank();
        assertThat(r.conversationId()).isEqualTo(5L);
        assertThat(r.messageId()).isNull();
        assertThat(r.warnings()).contains("conversation_persist_failed");
    }

    @Test
    @DisplayName("不带 conversationId：行为与阶段 8 完全一致（不碰会话表）")
    void withoutConversationNothingIsPersisted() {
        service.ask(new ChatRequest("q", null, "ASK"));

        verify(conversations, org.mockito.Mockito.never()).requireOwned(any());
        verify(conversations, org.mockito.Mockito.never()).recordTurn(any(), anyString(), any(), any());
        verify(memoryAssembler, org.mockito.Mockito.never()).assemble(any());
        verify(conversationContexts, org.mockito.Mockito.never()).applyTurn(any(), any(), any());
    }

    @Test
    @DisplayName("追问：把会话槽位与最近几轮交给 QU")
    void followUpPassesContextToRouter() {
        Conversation session = new Conversation(5L, 1L, 1L, "会话", ScopeKind.CORPUS,
                ChatScope.corpus(), Conversation.STATUS_ACTIVE, null, null);
        when(conversations.requireOwned(5L)).thenReturn(session);
        when(conversationContexts.find(5L)).thenReturn(java.util.Optional.of(
                new com.finagent.memory.ConversationContext(5L, 1L, 2L, "300059", "东方财富",
                        2025, 2025, List.of(38L), "营业总收入", "{}", null)));
        when(memoryAssembler.assemble(session)).thenReturn(new MemoryContext("背景", MemoryContext.Stats.EMPTY,
                List.of(new com.finagent.understanding.ContextTurn("东方财富2025年营业收入是多少", "1200 亿 [E1]"))));

        service.ask(new ChatRequest("那净利润呢？", null, "ASK", 5L));

        verify(router).understand(org.mockito.ArgumentMatchers.eq("那净利润呢？"),
                org.mockito.ArgumentMatchers.argThat(ctx -> ctx != null && !ctx.isEmpty()
                        && "300059".equals(ctx.companyCode())
                        && ctx.yearFrom() == 2025
                        && "营业总收入".equals(ctx.currentMetric())
                        && ctx.recent().size() == 1));
    }

    @Test
    @DisplayName("检索用 normalizedQuery，答案提示词仍用用户原话")
    void retrievalUsesNormalizedQuery() {
        Conversation session = new Conversation(5L, 1L, 1L, "会话", ScopeKind.CORPUS,
                ChatScope.corpus(), Conversation.STATUS_ACTIVE, null, null);
        when(conversations.requireOwned(5L)).thenReturn(session);
        when(conversationContexts.find(5L)).thenReturn(java.util.Optional.of(
                new com.finagent.memory.ConversationContext(5L, 1L, 2L, "300059", "东方财富",
                        2025, 2025, List.of(38L), "营业总收入", "{}", null)));
        when(router.understand(anyString(), any())).thenReturn(new QueryUnderstanding(
                QueryType.EXACT_FACT, "东方财富（300059） 2025年 那净利润呢", List.of("300059"),
                new int[]{2025, 2025}, List.of("净利润"), false, false, null, false, "LLM", true));

        ChatResponse r = service.ask(new ChatRequest("那净利润呢", null, "ASK", 5L));

        verify(retriever).retrieve(org.mockito.ArgumentMatchers.argThat(
                        req -> "东方财富（300059） 2025年 那净利润呢".equals(req.query())),
                any(QueryUnderstanding.class), any(ResolvedScope.class));
        assertThat(r.warnings()).contains("query_inferred_from_context");
        verify(answerService).answer(org.mockito.ArgumentMatchers.eq("那净利润呢"), any(), any(), any());
    }

    @Test
    @DisplayName("没有会话 → TurnContext 为空，QU 走单轮重载")
    void withoutConversationRouterRunsSingleTurn() {
        service.ask(new ChatRequest("2024年营业总收入", null, "ASK"));

        verify(router).understand("2024年营业总收入");
        verify(router, org.mockito.Mockito.never()).understand(anyString(), any());
        verify(conversationContexts, org.mockito.Mockito.never()).find(any(Long.class));
    }

    @Test
    @DisplayName("显式命令：收尾时尝试写入记忆，但不改这一轮的回答")
    void explicitCommandIsRecordedAtEndOfTurn() {
        when(longTermMemory.maybeRecordCommand(anyString(), any()))
                .thenReturn(java.util.Optional.of(memory()));

        ChatResponse response = service.ask(new ChatRequest("以后回答先给结论", null, null, null));

        verify(longTermMemory).maybeRecordCommand(org.mockito.ArgumentMatchers.contains("以后回答先给结论"), any());
        assertThat(response.warnings()).doesNotContain("memory_write_failed");
    }

    @Test
    @DisplayName("记忆写入失败：答案照常返回，只多一条 memory_write_failed")
    void memoryFailureDoesNotBreakAnswer() {
        when(longTermMemory.maybeRecordCommand(anyString(), any()))
                .thenThrow(new IllegalStateException("db down"));

        ChatResponse response = service.ask(new ChatRequest("以后回答先给结论", null, null, null));

        assertThat(response.answer()).isEqualTo("证据不足");
        assertThat(response.warnings()).contains("memory_write_failed");
    }

    @Test
    @DisplayName("闸门命中：召回长期记忆并重新装配 Prompt（不改答案）")
    void gateHitRecallsLongTermMemory() {
        when(memoryGate.shouldRecall(anyString(), any())).thenReturn(true);
        java.util.List<com.finagent.memory.LongTermMemory> items = java.util.List.of(memory());
        when(memoryRecaller.recall(anyString())).thenReturn(items);
        when(memoryAssembler.withLongTerm(any(), any())).thenReturn(
                new com.finagent.memory.MemoryAssembler.WithLongTerm(
                        new MemoryContext("背景+偏好",
                                new MemoryContext.Stats(2, 0, 12, 1, true)), false));

        ChatResponse response = service.ask(new ChatRequest("以后回答按什么格式", null, null, null));

        verify(memoryRecaller).recall(org.mockito.ArgumentMatchers.contains("以后回答按什么格式"));
        verify(memoryAssembler).withLongTerm(any(), any());
        assertThat(response.trace().memory().gateHit()).isTrue();
        assertThat(response.trace().memory().longTermItems()).isEqualTo(1);
        verify(answerService).answer(anyString(), any(), any(),
                org.mockito.ArgumentMatchers.argThat(m -> m instanceof MemoryContext mc
                        && mc.text().contains("背景+偏好")));
        assertThat(response.warnings()).doesNotContain("instruction_overflow", "memory_recall_failed");
    }

    @Test
    @DisplayName("闸门未命中：一次召回都不做（连库都不查）")
    void gateMissSkipsRecall() {
        when(memoryGate.shouldRecall(anyString(), any())).thenReturn(false);

        service.ask(new ChatRequest("2024年营业总收入", null, null, null));

        verify(memoryRecaller, org.mockito.Mockito.never()).recall(anyString());
        verify(memoryAssembler, org.mockito.Mockito.never()).withLongTerm(any(), any());
    }

    @Test
    @DisplayName("召回失败：答案照常返回，只多一条 memory_recall_failed")
    void recallFailureDoesNotBreakAnswer() {
        when(memoryGate.shouldRecall(anyString(), any())).thenReturn(true);
        when(memoryRecaller.recall(anyString())).thenThrow(new IllegalStateException("embedding down"));

        ChatResponse response = service.ask(new ChatRequest("以后回答按什么格式", null, null, null));

        assertThat(response.answer()).isEqualTo("证据不足");
        assertThat(response.warnings()).contains("memory_recall_failed");
    }

    @Test
    @DisplayName("INSTRUCTION 超预算：加 instruction_overflow 警告但不丢弃")
    void instructionOverflowWarns() {
        when(memoryGate.shouldRecall(anyString(), any())).thenReturn(true);
        when(memoryRecaller.recall(anyString())).thenReturn(java.util.List.of(memory()));
        when(memoryAssembler.withLongTerm(any(), any())).thenReturn(
                new com.finagent.memory.MemoryAssembler.WithLongTerm(
                        new MemoryContext("背景+超长偏好", MemoryContext.Stats.EMPTY), true));

        ChatResponse response = service.ask(new ChatRequest("以后回答按什么格式", null, null, null));

        assertThat(response.warnings()).contains("instruction_overflow");
    }

    private static com.finagent.memory.LongTermMemory memory() {
        return new com.finagent.memory.LongTermMemory(1L, 1L, "USER", 1L, "INSTRUCTION", "answer.style",
                "回答先给结论", null, "EXPLICIT_COMMAND", "以后回答先给结论", 1.0, 3, "ACTIVE", 1,
                null, null, 0, null, null);
    }

    // ------------------------------------------------------------ fixtures

    private static ResolvedScope scope() {
        return scope(List.of());
    }

    private static ResolvedScope scope(List<String> warnings) {
        DocMeta doc = new DocMeta(38L, 2L, "300059", "东方财富", 2024, "ANNUAL",
                LocalDate.of(2024, 1, 1), LocalDate.of(2024, 12, 31), 1, "东方财富2024年年度报告");
        return ResolvedScope.of(ScopeKind.DOCUMENTS, List.of(doc), ResolvedScope.REASON_SESSION, warnings);
    }

    private static QueryUnderstanding qu() {
        return new QueryUnderstanding(QueryType.EXACT_FACT, "q", List.of("300059"), new int[]{2024, 2024},
                List.of("营业总收入"), false, false, null, false, "LLM");
    }

    private static RetrievalTrace retrievalTrace() {
        return new RetrievalTrace("q", QueryType.EXACT_FACT, "LLM", false, 30, 10, 40, 8,
                RerankStatus.APPLIED, "qwen3-rerank", 190, 4, 1, 250, List.of("doc_quota_balanced_1_docs"));
    }

    private static AnswerResult answer() {
        return new AnswerResult(false, "证据不足", List.of(), List.of(), "insufficient", null,
                List.of(), 1);
    }
}
