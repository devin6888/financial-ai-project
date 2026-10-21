package com.finagent.chat;

import com.finagent.answer.AnswerResult;
import com.finagent.answer.AnswerService;
import com.finagent.context.ContextBuilder;
import com.finagent.context.ContextPackage;
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
    private ChatTraceWriter traceWriter;
    private ChatService service;

    @BeforeEach
    void setUp() {
        scopeResolver = mock(ScopeResolver.class);
        router = mock(RouterService.class);
        retriever = mock(HybridRetriever.class);
        contextBuilder = mock(ContextBuilder.class);
        answerService = mock(AnswerService.class);
        traceWriter = mock(ChatTraceWriter.class);
        service = new ChatService(scopeResolver, router, retriever, contextBuilder, answerService, traceWriter);

        when(scopeResolver.resolve(any())).thenReturn(scope());
        when(router.understand(anyString())).thenReturn(qu());
        when(retriever.retrieve(any(RetrievalRequest.class), any(QueryUnderstanding.class), any(ResolvedScope.class)))
                .thenReturn(new HybridRetriever.Result(List.of(), retrievalTrace()));
        when(contextBuilder.build(anyString(), any(QueryType.class), any(), any())).thenReturn(ContextPackage.empty());
        when(answerService.answer(anyString(), any(), any())).thenReturn(answer());
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
        when(answerService.answer(anyString(), any(), any())).thenReturn(
                new AnswerResult(true, "a", List.of(), List.of(), "high", null,
                        List.of("answer_without_citation"), 0));

        ChatResponse r = service.ask(new ChatRequest("q", null, "ASK"));

        assertThat(r.warnings()).contains("scope_over_soft_limit_6_gt_5", "context_truncated",
                "answer_without_citation");
    }

    @Test
    @DisplayName("空问题 → IllegalArgumentException，不碰作用域与模型")
    void blankQuestionRejected() {
        assertThatThrownBy(() -> service.ask(new ChatRequest("  ", null, "ASK")))
                .isInstanceOf(IllegalArgumentException.class);

        verify(scopeResolver, org.mockito.Mockito.never()).resolve(any());
    }

    // ------------------------------------------------------------ fixtures

    private static ResolvedScope scope() {
        return scope(List.of());
    }

    private static ResolvedScope scope(List<String> warnings) {
        DocMeta doc = new DocMeta(38L, 2L, "300059", "东方财富", 2024, "ANNUAL",
                LocalDate.of(2024, 1, 1), LocalDate.of(2024, 12, 31), 1, "东方财富2024年年度报告");
        return new ResolvedScope(ScopeKind.DOCUMENTS, List.of(doc), "scope-v1", "SESSION", warnings);
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
