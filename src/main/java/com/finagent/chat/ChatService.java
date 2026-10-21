package com.finagent.chat;

import com.finagent.answer.AnswerResult;
import com.finagent.answer.AnswerService;
import com.finagent.context.ContextBuilder;
import com.finagent.context.ContextPackage;
import com.finagent.retrieval.HybridRetriever;
import com.finagent.retrieval.RetrievalRequest;
import com.finagent.scope.ResolvedScope;
import com.finagent.scope.ScopeResolver;
import com.finagent.understanding.QueryType;
import com.finagent.understanding.QueryUnderstanding;
import com.finagent.understanding.RouterService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;

/**
 * 问答编排：作用域 → QU → 检索 → 上下文 → 答案。
 *
 * <p>顺序不能换：作用域必须在 QU 之前解析（QU 的 company/year 要与作用域核对，
 * 越界的推断不能把范围撑大）；上下文必须在答案之前装配（答案只认 [E1..En] 编号）。
 */
@Service
public class ChatService {

    private static final Logger log = LoggerFactory.getLogger(ChatService.class);

    private final ScopeResolver scopeResolver;
    private final RouterService router;
    private final HybridRetriever retriever;
    private final ContextBuilder contextBuilder;
    private final AnswerService answerService;
    private final ChatTraceWriter traceWriter;

    public ChatService(ScopeResolver scopeResolver, RouterService router, HybridRetriever retriever,
                       ContextBuilder contextBuilder, AnswerService answerService,
                       ChatTraceWriter traceWriter) {
        this.scopeResolver = scopeResolver;
        this.router = router;
        this.retriever = retriever;
        this.contextBuilder = contextBuilder;
        this.answerService = answerService;
        this.traceWriter = traceWriter;
    }

    public ChatResponse ask(ChatRequest request) {
        String question = request == null ? "" : request.question();
        if (question.isBlank()) {
            throw new IllegalArgumentException("question 不能为空");
        }

        ResolvedScope scope = scopeResolver.resolve(request.scope());
        QueryUnderstanding qu = router.understand(question);

        HybridRetriever.Result retrieval = retriever.retrieve(RetrievalRequest.of(question), qu, scope);
        QueryType queryType = retrieval.trace() == null ? qu.queryType() : retrieval.trace().queryType();

        long contextStart = System.currentTimeMillis();
        ContextPackage pkg = contextBuilder.build(question, queryType, retrieval.chunks(), scope);
        long contextMs = System.currentTimeMillis() - contextStart;

        long answerStart = System.currentTimeMillis();
        AnswerResult answer = answerService.answer(question, qu, pkg);
        long answerMs = System.currentTimeMillis() - answerStart;

        List<String> warnings = mergeWarnings(scope.warnings(),
                retrieval.trace() == null ? List.of() : retrieval.trace().warnings(),
                pkg.warnings(), answer.warnings());

        ChatTrace trace = new ChatTrace(UUID.randomUUID().toString(), retrieval.trace(),
                new ScopeTrace(scope.kind(), scope.documentIds(), scope.resolverVersion(),
                        scope.effectiveReason(), false),
                pkg.blocks().size(), pkg.tokenCount(), answer.citations().size(),
                answer.citationsDropped(), contextMs, answerMs, warnings);
        traceWriter.write(trace);

        log.info("问答完成：trace={} blocks={} tokens={} citations={} confidence={} cost={}ms",
                trace.traceId(), pkg.blocks().size(), pkg.tokenCount(), answer.citations().size(),
                answer.confidence(), trace.totalMs());

        return new ChatResponse(answer.sufficient(), answer.answer(), answer.facts(), answer.citations(),
                answer.confidence(), answer.note(),
                new ScopeInfo(new ScopeInfo.SessionScope(scope.kind(), scope.documentIds()),
                        new ScopeInfo.EffectiveScope(scope.kind(), scope.documentIds(), scope.effectiveReason()),
                        scope.documentIds(), scope.resolverVersion()),
                warnings, trace, null);
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
