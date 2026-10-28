package com.finagent.chat;

import com.finagent.answer.AnswerResult;
import com.finagent.answer.AnswerService;
import com.finagent.answer.PeriodLabeler;
import com.finagent.context.ContextBlock;
import com.finagent.context.ContextBuilder;
import com.finagent.context.ContextPackage;
import com.finagent.memory.Conversation;
import com.finagent.memory.ConversationContextService;
import com.finagent.memory.ConversationService;
import com.finagent.memory.ConversationSummarizer;
import com.finagent.memory.LongTermMemoryRecaller;
import com.finagent.memory.LongTermMemoryService;
import com.finagent.memory.MemoryAssembler;
import com.finagent.memory.MemoryGate;
import com.finagent.retrieval.HybridRetriever;
import com.finagent.retrieval.RerankStatus;
import com.finagent.retrieval.RetrievalProgress;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 阶段 13（契约 C16）：流式进度事件的顺序与边界。
 *
 * <p>三条断言值得单独存在，因为它们都在「用户看不见后端」的地方出错：
 * ① 精排事件必须由检索层真的进入 rerank 时才发（不能提前发）；
 * ② 没有候选时不发 reranking（否则状态行会骗人）；
 * ③ stage 的 payload 里绝不能出现答案文本或证据正文。
 */
class ChatServiceStageTest {

    private ScopeResolver scopeResolver;
    private RouterService router;
    private HybridRetriever retriever;
    private ContextBuilder contextBuilder;
    private AnswerService answerService;
    private PeriodLabeler periodLabeler;
    private ChatService service;

    @BeforeEach
    void setUp() {
        scopeResolver = mock(ScopeResolver.class);
        router = mock(RouterService.class);
        retriever = mock(HybridRetriever.class);
        contextBuilder = mock(ContextBuilder.class);
        answerService = mock(AnswerService.class);
        periodLabeler = mock(PeriodLabeler.class);
        ConversationService conversations = mock(ConversationService.class);
        when(conversations.effectiveScope(any(), any())).thenAnswer(inv -> inv.getArgument(0));
        MemoryAssembler memoryAssembler = mock(MemoryAssembler.class);
        ConversationContextService conversationContexts = mock(ConversationContextService.class);
        ConversationSummarizer summarizer = mock(ConversationSummarizer.class);
        LongTermMemoryService memories = mock(LongTermMemoryService.class);
        when(memories.maybeRecordCommand(anyString(), any())).thenReturn(Optional.empty());
        MemoryGate gate = mock(MemoryGate.class);
        LongTermMemoryRecaller recaller = mock(LongTermMemoryRecaller.class);
        service = new ChatService(scopeResolver, router, retriever, contextBuilder, answerService,
                periodLabeler, mock(ChatTraceWriter.class), conversations, memoryAssembler,
                conversationContexts, summarizer, memories, gate, recaller);

        when(scopeResolver.resolve(any())).thenReturn(scope());
        when(router.understand(anyString())).thenReturn(qu());
        when(router.understand(anyString(), any())).thenReturn(qu());
        when(periodLabeler.label(any(), any(), any())).thenReturn(PeriodLabeler.Result.EMPTY);
        when(gate.shouldRecall(anyString(), any())).thenReturn(false);
        when(contextBuilder.build(anyString(), any(QueryType.class), any(), any()))
                .thenReturn(new ContextPackage("营业总收入 16,067,741,039.51", List.of(block()), 120, List.of(), 1, 0));
        when(answerService.answerStreaming(anyString(), any(), any(), any(), any()))
                .thenAnswer(inv -> {
                    @SuppressWarnings("unchecked")
                    java.util.function.Consumer<String> onDelta = inv.getArgument(4);
                    onDelta.accept("营业总收入 16,067,741,039.51 元 [E1]");
                    return new AnswerResult(true, "营业总收入 16,067,741,039.51 元 [E1]", List.of(),
                            List.of(), "high", null, List.of(), 0);
                });
    }

    @Test
    @DisplayName("正常路径：understanding → retrieving → reranking → meta → generating → delta → final")
    void fullStageOrder() {
        stubRetrieval(true, 8);
        List<ChatStreamEvent> events = new ArrayList<>();

        service.askStreaming(new ChatRequest("2025年营业总收入", ChatScope.documents(38L), "ASK"), events::add);

        assertThat(events).extracting(ChatStreamEvent::event).containsExactly(
                ChatStreamEvent.STAGE, ChatStreamEvent.STAGE, ChatStreamEvent.STAGE,
                ChatStreamEvent.META, ChatStreamEvent.STAGE, ChatStreamEvent.DELTA, ChatStreamEvent.FINAL);
        assertThat(phases(events)).containsExactly(
                ChatStreamEvent.PHASE_UNDERSTANDING, ChatStreamEvent.PHASE_RETRIEVING,
                ChatStreamEvent.PHASE_RERANKING, ChatStreamEvent.PHASE_GENERATING);
    }

    @Test
    @DisplayName("进度事件带的是数字不是正文：retrieving.documents / reranking.candidates / generating.evidence")
    void stagePayloadCarriesCountsOnly() {
        stubRetrieval(true, 8);
        List<ChatStreamEvent> events = new ArrayList<>();

        service.askStreaming(new ChatRequest("2025年营业总收入", ChatScope.documents(38L), "ASK"), events::add);

        Map<?, ?> retrieving = (Map<?, ?>) events.get(1).data();
        Map<?, ?> reranking = (Map<?, ?>) events.get(2).data();
        Map<?, ?> generating = (Map<?, ?>) events.get(4).data();
        assertThat(detail(retrieving)).containsEntry("documents", 1);
        assertThat(detail(reranking)).containsEntry("candidates", 8);
        assertThat(detail(generating)).containsEntry("evidence", 1).containsEntry("contextTokens", 120);
        // 契约 C16 第 1 条：stage 不得携带答案或证据正文
        for (ChatStreamEvent e : events) {
            if (!ChatStreamEvent.STAGE.equals(e.event())) {
                continue;
            }
            String rendered = String.valueOf(e.data());
            assertThat(rendered).doesNotContain("16,067,741,039.51");
            @SuppressWarnings("unchecked")
            Set<String> keys = (Set<String>) ((Map<?, ?>) e.data()).keySet();
            assertThat(keys).containsExactlyInAnyOrder("phase", "label", "detail");
        }
    }

    @Test
    @DisplayName("没有候选可精排时不发 reranking（发了就是骗人）")
    void noCandidatesMeansNoRerankStage() {
        stubRetrieval(false, 0);
        List<ChatStreamEvent> events = new ArrayList<>();

        service.askStreaming(new ChatRequest("2025年营业总收入", ChatScope.documents(38L), "ASK"), events::add);

        assertThat(phases(events)).containsExactly(
                ChatStreamEvent.PHASE_UNDERSTANDING, ChatStreamEvent.PHASE_RETRIEVING,
                ChatStreamEvent.PHASE_GENERATING);
    }

    @Test
    @DisplayName("非流式入口不发任何 stage（没有 sink）")
    void nonStreamingSendsNoStages() {
        stubRetrieval(true, 8);
        when(answerService.answer(anyString(), any(), any(), any()))
                .thenReturn(new AnswerResult(true, "答", List.of(), List.of(), "high", null, List.of(), 0));

        ChatResponse response = service.ask(new ChatRequest("2025年营业总收入", ChatScope.documents(38L), "ASK"));

        assertThat(response.answer()).isEqualTo("答");
        assertThat(response.trace().quMs()).isGreaterThanOrEqualTo(0);
    }

    // ------------------------------------------------------------ helpers

    /** 桩出 4 参重载；withRerank 为真时模拟「检索层真的进入精排」。 */
    private void stubRetrieval(boolean withRerank, int candidates) {
        when(retriever.retrieve(any(RetrievalRequest.class), any(QueryUnderstanding.class),
                any(ResolvedScope.class), any(RetrievalProgress.class)))
                .thenAnswer(inv -> {
                    RetrievalProgress progress = inv.getArgument(3);
                    progress.onPhase("dense", 0);
                    if (withRerank) {
                        progress.onPhase(RetrievalProgress.PHASE_RERANK, candidates);
                    }
                    return new HybridRetriever.Result(List.of(), retrievalTrace());
                });
    }

    private static List<String> phases(List<ChatStreamEvent> events) {
        List<String> out = new ArrayList<>();
        for (ChatStreamEvent e : events) {
            if (ChatStreamEvent.STAGE.equals(e.event())) {
                out.add(String.valueOf(((Map<?, ?>) e.data()).get("phase")));
            }
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> detail(Map<?, ?> stage) {
        Object d = stage.get("detail");
        return d instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
    }

    private static ResolvedScope scope() {
        DocMeta doc = new DocMeta(38L, 2L, "300059", "东方财富", 2024, "ANNUAL",
                LocalDate.of(2024, 1, 1), LocalDate.of(2024, 12, 31), 1, "东方财富2024年年度报告");
        return ResolvedScope.of(ScopeKind.DOCUMENTS, List.of(doc), ResolvedScope.REASON_SESSION, List.of());
    }

    private static QueryUnderstanding qu() {
        return new QueryUnderstanding(QueryType.EXACT_FACT, "q", List.of("300059"), new int[]{2024, 2024},
                List.of("营业总收入"), false, false, null, false, "LLM");
    }

    private static ContextBlock block() {
        return new ContextBlock(1, 7216L, 500L, 7L, "东方财富2025年年度报告",
                "第二节 > 六、主要会计数据", "六、主要会计数据", "营业总收入 16,067,741,039.51",
                7, 7, "TABLE", "主要会计数据", "元", null, null, 0.9, 10, 100,
                ContextBlock.ROLE_HIT);
    }

    private static RetrievalTrace retrievalTrace() {
        return new RetrievalTrace("q", QueryType.EXACT_FACT, "LLM", false, 30, 10, 40, 8,
                RerankStatus.APPLIED, "qwen3-rerank", 190, 4, 1, 250, List.of());
    }
}
