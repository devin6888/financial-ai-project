package com.finagent.agent;

import com.finagent.answer.AnswerResult;
import com.finagent.answer.AnswerService;
import com.finagent.citation.CitationValidator;
import com.finagent.context.ContextBuilder;
import com.finagent.context.ContextBlock;
import com.finagent.context.ContextPackage;
import com.finagent.fact.FactPack;
import com.finagent.memory.MemoryContext;
import com.finagent.retrieval.HybridRetriever;
import com.finagent.retrieval.RetrievalProgress;
import com.finagent.retrieval.RetrievalRequest;
import com.finagent.retrieval.RetrievedChunk;
import com.finagent.scope.ResolvedScope;
import com.finagent.scope.ScopeEmptyException;
import com.finagent.scope.ScopeKind;
import com.finagent.scope.ScopeResolver;
import com.finagent.understanding.QueryType;
import com.finagent.understanding.QueryUnderstanding;
import com.finagent.understanding.RouterService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Task 4 的节点单测：全部外部依赖都是 mock，只验「节点对状态做了什么」。
 *
 * <p>真库、真检索、真模型的验证在 {@code AgentRunServiceDbTest} 与 Task 9 的 e2e 里做 ——
 * 这里刻意不启动 Spring，失败时能一眼看出是逻辑错还是环境错。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RealAnalysisNodesTest {

    @Mock private RouterService router;
    @Mock private ScopeResolver scopes;
    @Mock private HybridRetriever retriever;
    @Mock private ContextBuilder contextBuilder;
    @Mock private AnswerService answers;
    @Mock private CitationValidator citations;
    @Mock private com.finagent.chunk.ChunkRepository chunks;
    @Mock private com.finagent.memory.MemoryGate memoryGate;
    @Mock private com.finagent.memory.LongTermMemoryRecaller longTermRecaller;
    @Mock private com.finagent.memory.MemoryAssembler memoryAssembler;

    private AnswerStreamBridge bridge;
    private RealAnalysisNodes nodes;
    private ResolvedScope scope;

    @BeforeEach
    void setUp() {
        bridge = new AnswerStreamBridge();
        nodes = new RealAnalysisNodes(router, scopes, retriever, contextBuilder, answers, citations,
                bridge, new AgentProperties(true, 2, 4, 25, true, 30), chunks,
                memoryGate, longTermRecaller, memoryAssembler, new com.finagent.answer.PeriodLabeler());
        scope = ResolvedScope.of(ScopeKind.CORPUS, List.of(
                new com.finagent.scope.DocMeta(7L, 7L, "300059", "东方财富", 2025, "ANNUAL",
                        null, null, 3, "东方财富2025年年度报告")),
                ResolvedScope.REASON_SESSION, List.of());
        when(scopes.resolve(any())).thenReturn(scope);
        when(contextBuilder.build(anyString(), any(), anyList(), any()))
                .thenReturn(ContextPackage.empty());
    }

    private static QueryUnderstanding qu(QueryType type, List<String> codes, List<String> metrics) {
        return new QueryUnderstanding(type, "东方财富2025年营业总收入是多少", codes,
                new int[]{2025, 2025}, metrics, false, false, null, false, "LLM",
                false, false, List.of("东方财富"));
    }

    private static AnalysisState state(Map<String, Object> overrides) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put(AnalysisState.QUESTION, "东方财富2025年营业总收入是多少");
        data.put(AnalysisState.RUN_ID, "run-1");
        data.putAll(ScopeMapper.initialScope(com.finagent.scope.ChatScope.corpus()));
        data.putAll(overrides);
        return new AnalysisState(data);
    }


    @Test
    @DisplayName("已判不可答时 retrieve 不查库（避免把错主体的证据带进装配）")
    void retrieveSkipsWhenAlreadyRefused() {
        AnalysisState state = state(Map.of(AnalysisState.REFUSE_REASON, "问题里的公司不在当前可检索范围内：五粮液"));

        Map<String, Object> patch = nodes.retrieve(state);

        assertThat(patch.get(AnalysisState.HITS)).asList().isEmpty();
        verify(retriever, never()).retrieve(any(), any(), any(), any());
    }

    @Test
    @DisplayName("作用域为空 → 写成 scope_empty 警告与拒答原因，不再只是「没命中」")
    void retrieveRecordsScopeEmptyAsRefusal() {
        when(scopes.resolve(any())).thenThrow(
                new ScopeEmptyException("知识库中没有当前账号可检索的文档", List.of("scope_corpus_empty")));
        AnalysisState state = state(Map.of());

        Map<String, Object> patch = nodes.retrieve(state);

        assertThat(patch.get(AnalysisState.REFUSE_REASON)).asString().contains("当前账号可检索");
        assertThat(String.valueOf(patch.get(AnalysisState.WARNINGS))).contains("scope_empty");
        verify(retriever, never()).retrieve(any(), any(), any(), any());
    }

    @Test
    @DisplayName("understand：写 queryType / metrics / 公司代码，并把「检索范围」写进计划")
    void understandWritesStructuredFields() {
        when(router.understand(anyString()))
                .thenReturn(qu(QueryType.EXACT_FACT, List.of("300059"), List.of("营业总收入")));

        Map<String, Object> patch = nodes.understand(state(Map.of()));

        assertThat(patch).containsEntry(AnalysisState.QUERY_TYPE, "EXACT_FACT");
        assertThat(patch).containsEntry(AnalysisState.METRICS, List.of("营业总收入"));
        assertThat(patch).containsEntry(AnalysisState.COMPANY_CODES, List.of("300059"));
        assertThat(String.valueOf(patch.get(AnalysisState.PLAN))).contains("检索范围");
        assertThat(patch).doesNotContainKey(AnalysisState.REFUSE_REASON);
    }

    @Test
    @DisplayName("understand：公司不在作用域 → refuseReason 非空，plan 里写明要拒答")
    void understandRefusesCompanyOutsideScope() {
        when(router.understand(anyString()))
                .thenReturn(qu(QueryType.EXACT_FACT, List.of(), List.of("营业总收入")));
        // qu 没有代码但模型抽到了「五粮液」→ CompanyResolver 在作用域里找不到 → blocked
        when(router.understand(anyString())).thenReturn(new QueryUnderstanding(
                QueryType.EXACT_FACT, "五粮液2025年营业总收入", List.of(), new int[]{2025, 2025},
                List.of("营业总收入"), false, false, null, false, "LLM", false, false,
                List.of("五粮液")));

        Map<String, Object> patch = nodes.understand(state(Map.of()));

        assertThat(String.valueOf(patch.get(AnalysisState.REFUSE_REASON))).contains("五粮液");
        assertThat(String.valueOf(patch.get(AnalysisState.PLAN))).contains("拒答");
    }

    @Test
    @DisplayName("understand：作用域解析为空 → 直接拒答，不再检索")
    void understandRefusesEmptyScope() {
        when(scopes.resolve(any())).thenThrow(new ScopeEmptyException("知识库中没有当前账号可检索的文档", List.of("scope_acl_filtered_all")));
        when(router.understand(anyString())).thenReturn(qu(QueryType.EXACT_FACT, List.of(), List.of()));

        Map<String, Object> patch = nodes.understand(state(Map.of()));

        assertThat(String.valueOf(patch.get(AnalysisState.REFUSE_REASON))).contains("检索");
    }


    @Test
    @DisplayName("understand：有会话上下文时走带上下文的 QU，并把「上文补全」写进 warnings")
    void understandUsesTurnContextAndFlagsInference() {
        bridge.turnContext("run-1", new com.finagent.understanding.TurnContext(
                "300059", "东方财富", 2025, 2025, List.of(7L), "营业总收入", List.of()));
        when(router.understand(anyString(), any())).thenReturn(new QueryUnderstanding(
                QueryType.EXACT_FACT, "东方财富2025年营业总收入", List.of("300059"), new int[]{2025, 2025},
                List.of("营业总收入"), false, false, null, false, "LLM", true, false,
                List.of("东方财富")));

        Map<String, Object> patch = nodes.understand(state(Map.of()));

        assertThat(String.valueOf(patch.get(AnalysisState.WARNINGS))).contains("query_inferred_from_context");
        verify(router).understand(anyString(), any());
        // 收尾落库要用：QU 与解析后的作用域都要登记进 bridge
        assertThat(bridge.query("run-1")).isNotNull();
        assertThat(bridge.scope("run-1")).isNotNull();
    }

    @Test
    @DisplayName("understand：闸门命中就召回长期记忆 —— 新会话第一句（短期窗口为空）也要召回")
    void understandRecallsLongTermMemoryWhenGateHits() {
        when(router.understand(anyString()))
                .thenReturn(qu(QueryType.EXACT_FACT, List.of("300059"), List.of("营业总收入")));
        when(memoryGate.shouldRecall(anyString(), any())).thenReturn(true);
        com.finagent.memory.LongTermMemory instruction = new com.finagent.memory.LongTermMemory(
                1L, 1L, com.finagent.memory.LongTermMemory.SUBJECT_USER, 1L,
                com.finagent.memory.LongTermMemory.TYPE_INSTRUCTION, "analysis.must_cite",
                "每个结论都要标注出处", null, "USER_EXPLICIT", null, 1.0, 5, "ACTIVE", 1,
                null, null, 0, null, null);
        when(longTermRecaller.recall(anyString())).thenReturn(List.of(instruction));
        com.finagent.memory.MemoryContext merged = new com.finagent.memory.MemoryContext(
                "用户偏好：每个结论都要标注出处",
                new com.finagent.memory.MemoryContext.Stats(0, 0, 0, 1, true), List.of());
        when(memoryAssembler.withLongTerm(any(), anyList()))
                .thenReturn(new com.finagent.memory.MemoryAssembler.WithLongTerm(merged, false));

        Map<String, Object> patch = nodes.understand(state(Map.of()));

        assertThat(patch).containsEntry(AnalysisState.MEMORY_TEXT, "用户偏好：每个结论都要标注出处");
        assertThat(String.valueOf(patch.get(AnalysisState.PLAN))).contains("召回长期记忆 1 条");
        assertThat(bridge.memoryTrace("run-1").gateHit()).isTrue();
        assertThat(bridge.memoryTrace("run-1").longTermItems()).isEqualTo(1);
    }

    @Test
    @DisplayName("understand：召回抛异常也不让这一轮失败，只留 memory_recall_failed")
    void understandKeepsGoingWhenRecallFails() {
        when(router.understand(anyString()))
                .thenReturn(qu(QueryType.EXACT_FACT, List.of("300059"), List.of("营业总收入")));
        when(memoryGate.shouldRecall(anyString(), any())).thenReturn(true);
        when(longTermRecaller.recall(anyString())).thenThrow(new IllegalStateException("embedding down"));

        Map<String, Object> patch = nodes.understand(state(Map.of()));

        assertThat(String.valueOf(patch.get(AnalysisState.WARNINGS))).contains("memory_recall_failed");
        assertThat(patch).doesNotContainKey(AnalysisState.MEMORY_TEXT);
    }

    @Test
    @DisplayName("understand：闸门没命中就不召回，也不花一次 embedding")
    void understandSkipsRecallWhenGateMisses() {
        when(router.understand(anyString()))
                .thenReturn(qu(QueryType.EXACT_FACT, List.of("300059"), List.of("营业总收入")));
        when(memoryGate.shouldRecall(anyString(), any())).thenReturn(false);

        nodes.understand(state(Map.of()));

        verify(longTermRecaller, never()).recall(anyString());
    }

    @Test
    @DisplayName("plan：复杂问题按连接词拆子问题；非复杂问题不拆")
    void planDecomposesOnlyComplexQuestions() {
        Map<String, Object> complex = nodes.plan(state(Map.of(
                AnalysisState.QUERY_TYPE, "COMPLEX_ANALYSIS",
                AnalysisState.QUESTION, "分析东方财富2025年的营业总收入和净利润变化")));

        assertThat((List<?>) complex.get(AnalysisState.SUB_QUESTIONS)).hasSize(2);

        Map<String, Object> simple = nodes.plan(state(Map.of(AnalysisState.QUERY_TYPE, "EXACT_FACT")));
        assertThat(simple).doesNotContainKey(AnalysisState.SUB_QUESTIONS);
    }

    @Test
    @DisplayName("plan：每个片段都补上主体与年份，片段里已有的不重复补（ST15 §3.1）")
    void planMakesFragmentsSelfContained() {
        Map<String, Object> patch = nodes.plan(state(Map.of(
                AnalysisState.QUERY_TYPE, "COMPLEX_ANALYSIS",
                AnalysisState.COMPANY_MENTIONS, List.of("东方财富"),
                AnalysisState.YEAR_FROM, 2025,
                AnalysisState.YEAR_TO, 2025,
                AnalysisState.QUESTION,
                "分析东方财富2025年营业收入增长的主要原因，并结合净利润、毛利率的变化说明盈利能力")));

        assertThat((List<String>) patch.get(AnalysisState.SUB_QUESTIONS)).containsExactly(
                // 第一段自带主体与年份 → 不再补，否则会出现两次「东方财富2025年」
                "分析东方财富2025年营业收入增长的主要原因",
                // 第二段原本是「并结合净利润」：剥连接词 + 补主体与年份
                "东方财富 2025年 净利润",
                "东方财富 2025年 毛利率的变化说明盈利能力");
    }

    @Test
    @DisplayName("plan：没有公司名字面时只补年份，不补空串")
    void planUsesYearOnlyWhenMentionsMissing() {
        Map<String, Object> patch = nodes.plan(state(Map.of(
                AnalysisState.QUERY_TYPE, "COMPLEX_ANALYSIS",
                AnalysisState.YEAR_FROM, 2025,
                AnalysisState.YEAR_TO, 2025,
                AnalysisState.QUESTION, "分析2025年营业收入和净利润的变化")));

        assertThat((List<String>) patch.get(AnalysisState.SUB_QUESTIONS)).containsExactly(
                "分析2025年营业收入",
                "2025年 净利润的变化");
    }

    @Test
    @DisplayName("retrieve：整句 + 每个子问题各检索一次，按 chunkId 去重")
    void retrieveDeduplicatesAcrossSubQuestions() {
        when(retriever.retrieve(any(RetrievalRequest.class), any(), any(), any(RetrievalProgress.class)))
                .thenReturn(new HybridRetriever.Result(List.of(chunk(101, 0.9), chunk(102, 0.8)), null))
                .thenReturn(new HybridRetriever.Result(List.of(chunk(102, 0.8), chunk(103, 0.7)), null));

        Map<String, Object> patch = nodes.retrieve(state(Map.of(
                AnalysisState.SUB_QUESTIONS, List.of("营业总收入", "净利润"))));

        List<?> hits = (List<?>) patch.get(AnalysisState.HITS);
        assertThat(hits).hasSize(3);
        assertThat(patch).containsEntry(AnalysisState.EVIDENCE_COUNT, 3);
        // 整句 1 次 + 子问题 2 次（ST15 §3.2：整句永远占一路）
        verify(retriever, org.mockito.Mockito.times(3))
                .retrieve(any(RetrievalRequest.class), any(), any(), any(RetrievalProgress.class));
    }

    @Test
    @DisplayName("retrieve：整句永远是第一路，子问题跟在后面（ST15 §3.2）")
    void retrieveQueriesWholeQuestionFirst() {
        when(retriever.retrieve(any(RetrievalRequest.class), any(), any(), any(RetrievalProgress.class)))
                .thenReturn(new HybridRetriever.Result(List.of(chunk(101, 0.9)), null));

        nodes.retrieve(state(Map.of(AnalysisState.SUB_QUESTIONS, List.of("营业总收入", "净利润"))));

        ArgumentCaptor<RetrievalRequest> captor = ArgumentCaptor.forClass(RetrievalRequest.class);
        verify(retriever, org.mockito.Mockito.times(3))
                .retrieve(captor.capture(), any(), any(), any(RetrievalProgress.class));
        assertThat(captor.getAllValues().get(0).query()).isEqualTo("东方财富2025年营业总收入是多少");
        assertThat(captor.getAllValues().get(1).query()).isEqualTo("营业总收入");
        assertThat(captor.getAllValues().get(2).query()).isEqualTo("净利润");
    }

    @Test
    @DisplayName("retrieve：同一块在多路里出现，保留最高分而不是先到先得")
    void retrieveKeepsHighestScoreOnDuplicate() {
        when(retriever.retrieve(any(RetrievalRequest.class), any(), any(), any(RetrievalProgress.class)))
                .thenReturn(new HybridRetriever.Result(List.of(chunk(101, 0.4)), null))
                .thenReturn(new HybridRetriever.Result(List.of(chunk(101, 0.9)), null));

        Map<String, Object> patch = nodes.retrieve(state(Map.of(
                AnalysisState.SUB_QUESTIONS, List.of("营业总收入"))));

        List<?> hits = (List<?>) patch.get(AnalysisState.HITS);
        assertThat(hits).hasSize(1);
        assertThat(((Map<?, ?>) hits.get(0)).get("score")).isEqualTo(0.9);
    }

    @Test
    @DisplayName("verify：条数不够判不足；条数够但目标指标没出现也判不足")
    void verifyUsesCountAndMetric() {
        Map<String, Object> one = nodes.verify(state(Map.of(
                AnalysisState.HITS, List.of(hit(101, "第三节 § 主营业务", "营业总收入 120.8 亿元")),
                AnalysisState.EVIDENCE_COUNT, 1,
                AnalysisState.METRICS, List.of("营业总收入"))));
        assertThat(one).containsEntry(AnalysisState.INSUFFICIENT, true);

        Map<String, Object> enough = nodes.verify(state(Map.of(
                AnalysisState.HITS, List.of(
                        hit(101, "第三节 § 主营业务", "营业总收入 120.8 亿元"),
                        hit(102, "第三节 § 主营业务", "营业总收入 120.8 亿元")),
                AnalysisState.EVIDENCE_COUNT, 2,
                AnalysisState.METRICS, List.of("营业总收入"))));
        assertThat(enough).containsEntry(AnalysisState.INSUFFICIENT, false);

        Map<String, Object> wrongMetric = nodes.verify(state(Map.of(
                AnalysisState.HITS, List.of(
                        hit(101, "第二节 § 公司简介", "公司注册地址为上海"),
                        hit(102, "第二节 § 公司简介", "公司员工人数 6403 人")),
                AnalysisState.EVIDENCE_COUNT, 2,
                AnalysisState.METRICS, List.of("营业总收入"))));
        assertThat(wrongMetric).containsEntry(AnalysisState.INSUFFICIENT, true);
    }

    @Test
    @DisplayName("verify：指标用口语写法（归母净利润 / 员工总数）→ 表头写法命中即算有证据")
    void verifyMatchesMetricAliases() {
        // 2026-10-31 评测：文件全对，只是「用户怎么说」与「表头怎么写」不同，被判成没有证据
        Map<String, Object> profit = nodes.verify(state(Map.of(
                AnalysisState.HITS, List.of(
                        hit(101, "第二节 § 主要会计数据", "归属于上市公司股东的净利润 9,610,120,743.64"),
                        hit(102, "第二节 § 主要会计数据", "营业总收入 11,604,343,090.16")),
                AnalysisState.EVIDENCE_COUNT, 2,
                AnalysisState.METRICS, List.of("归母净利润"))));
        assertThat(profit).containsEntry(AnalysisState.INSUFFICIENT, false);

        Map<String, Object> employee = nodes.verify(state(Map.of(
                AnalysisState.HITS, List.of(
                        hit(103, "第四节 § 员工情况", "在职员工的数量合计 5,560"),
                        hit(104, "第四节 § 员工情况", "其中：技术人员 1,226")),
                AnalysisState.EVIDENCE_COUNT, 2,
                AnalysisState.METRICS, List.of("员工总数"))));
        assertThat(employee).containsEntry(AnalysisState.INSUFFICIENT, false);
    }

    @Test
    @DisplayName("answer：引用来自别的年份的报告 → 补期间告警与说明，答案与引用原样保留")
    void answerFlagsPeriodMismatch() {
        when(answers.answerStreaming(anyString(), any(), any(), any(), any(), any()))
                .thenReturn(new AnswerResult(true, "2023年营业总收入 110.5 亿元 [E1]", List.of(),
                        List.of(new com.finagent.citation.Citation(7L, 1L, 101L, 60, 60, null, null,
                                "TEXT", null, null, "东方财富2025年年度报告", "第三节")),
                        AnswerResult.CONFIDENCE_HIGH, null, List.of(), 0));

        Map<String, Object> patch = nodes.answer(state(Map.of(
                AnalysisState.QUESTION, "2023年营业总收入是多少",
                AnalysisState.YEAR_FROM, 2023,
                AnalysisState.YEAR_TO, 2023,
                AnalysisState.HITS, List.of(hit(101, "第三节", "营业总收入 110.5 亿元")),
                AnalysisState.EVIDENCE_COUNT, 1)));

        List<String> warnings = (List<String>) patch.get(AnalysisState.WARNINGS);
        assertThat(warnings).contains("evidence_period_mismatch:2023@2025");
        AnswerResult viaBridge = bridge.result("run-1");
        assertThat(viaBridge.answer()).isEqualTo("2023年营业总收入 110.5 亿元 [E1]");
        assertThat(viaBridge.note()).contains("2025").contains("对比列");
    }

    @Test
    @DisplayName("rewrite：把指标词拼进检索式，retryCount 置 1")
    void rewriteAppendsMetrics() {
        Map<String, Object> patch = nodes.rewrite(state(Map.of(
                AnalysisState.METRICS, List.of("营业总收入", "净利润"))));

        assertThat(String.valueOf(patch.get(AnalysisState.REWRITTEN_QUERY)))
                .startsWith("东方财富2025年营业总收入是多少").contains("营业总收入").contains("净利润");
        assertThat(patch).containsEntry(AnalysisState.RETRY_COUNT, 1);
    }

    @Test
    @DisplayName("answer：调 answerStreaming，delta 通过桥按 runId 送出去")
    void answerStreamsThroughBridge() {
        when(answers.answerStreaming(anyString(), any(), any(), any(), any(), any()))
                .thenAnswer(inv -> {
                    java.util.function.Consumer<String> sink = inv.getArgument(5);
                    sink.accept("营业总收入 ");
                    sink.accept("120.8 亿元 [E1]");
                    return new AnswerResult(true, "营业总收入 120.8 亿元 [E1]", List.of(), List.of(),
                            AnswerResult.CONFIDENCE_HIGH, null, List.of(), 0);
                });
        List<String> seen = new ArrayList<>();
        bridge.bind("run-1", seen::add);

        Map<String, Object> patch = nodes.answer(state(Map.of(
                AnalysisState.HITS, List.of(hit(101, "第三节", "营业总收入 120.8 亿元")),
                AnalysisState.EVIDENCE_COUNT, 1)));

        assertThat(seen).containsExactly("营业总收入 ", "120.8 亿元 [E1]");
        assertThat(String.valueOf(patch.get(AnalysisState.ANSWER))).contains("120.8");
        assertThat(patch).containsEntry(AnalysisState.STATUS, AgentRun.STATUS_DONE);
        verify(answers).answerStreaming(eq("东方财富2025年营业总收入是多少"), any(),
                any(ContextPackage.class), any(MemoryContext.class), any(FactPack.class), any());
    }

    @Test
    @DisplayName("cite_check：编号越界 → 记 citations_dropped 问题；正常 → 记条数")
    void citeCheckRecordsIssues() {
        when(citations.validate(anyList(), anyList()))
                .thenReturn(new CitationValidator.Result(List.of(), List.of(3), List.of()));

        Map<String, Object> dropped = nodes.citeCheck(state(Map.of(
                AnalysisState.ANSWER, "答案 [E1][E3]",
                AnalysisState.HITS, List.of(hit(101, "第三节", "正文")),
                AnalysisState.EVIDENCE_COUNT, 1)));

        assertThat((List<?>) dropped.get(AnalysisState.CITATION_ISSUES)).isNotEmpty();

        when(citations.validate(anyList(), anyList()))
                .thenReturn(new CitationValidator.Result(List.of(), List.of(), List.of()));
        Map<String, Object> clean = nodes.citeCheck(state(Map.of(
                AnalysisState.ANSWER, "答案 [E1]",
                AnalysisState.HITS, List.of(hit(101, "第三节", "正文")),
                AnalysisState.EVIDENCE_COUNT, 1)));

        assertThat((List<?>) clean.get(AnalysisState.CITATION_ISSUES)).isEmpty();
    }

    @Test
    @DisplayName("respond_insufficient：有原因时回显原因，没原因时给通用文案（C6 第 5 条三件套）")
    void respondInsufficientHasReadableText() {
        Map<String, Object> withReason = nodes.respondInsufficient(state(Map.of(
                AnalysisState.REFUSE_REASON, "问题里的公司不在当前可检索范围内：五粮液")));
        assertThat(String.valueOf(withReason.get(AnalysisState.ANSWER))).contains("五粮液");
        // 主体越界与「证据不够」是两回事：前者永远重试不出结果（ST11 §18）
        assertThat(String.valueOf(withReason.get(AnalysisState.WARNINGS))).contains("company_unresolved");
        assertThat(withReason).containsEntry(AnalysisState.STATUS, AgentRun.STATUS_REJECTED);

        Map<String, Object> generic = nodes.respondInsufficient(state(Map.of()));
        assertThat(String.valueOf(generic.get(AnalysisState.ANSWER))).contains("证据不足");
        assertThat(String.valueOf(generic.get(AnalysisState.WARNINGS)))
                .contains(AnswerResult.WARNING_EVIDENCE_INSUFFICIENT);

        // 作用域为空（understand/retrieve 打的标记）要透传成 scope_empty，供流层发 SCOPE_EMPTY
        Map<String, Object> scopeEmpty = nodes.respondInsufficient(state(Map.of(
                AnalysisState.REFUSE_REASON, "知识库中没有当前账号可检索的文档",
                AnalysisState.WARNINGS, List.of("scope_empty"))));
        assertThat(String.valueOf(scopeEmpty.get(AnalysisState.WARNINGS))).contains("scope_empty");
    }

    @Test
    @DisplayName("verify：问原因却只召回财务附注 → 判证据不完整，并留 coverage_missing:attribution")
    void verifyFlagsMissingAttributionEvidence() {
        AnalysisState askWhy = state(Map.of(
                AnalysisState.QUESTION, "分析2024年和2025年营业总收入增长的原因",
                AnalysisState.METRICS, List.of("营业总收入"),
                AnalysisState.HITS, List.of(
                        hit(101, "第十节 财务报告 § 合并利润表", "营业总收入 160.68 亿元"),
                        hit(102, "第十节 财务报告 § 附注", "营业总收入 116.04 亿元"))));

        Map<String, Object> patch = nodes.verify(askWhy);

        assertThat(patch).containsEntry(AnalysisState.INSUFFICIENT, true);
        assertThat(String.valueOf(patch.get(AnalysisState.WARNINGS)))
                .contains(RealAnalysisNodes.WARNING_COVERAGE_ATTRIBUTION);
    }

    @Test
    @DisplayName("verify：同样的附注证据，但问题没问原因 → 不判不完整（不回归）")
    void verifyKeepsPlainQuestionSufficient() {
        AnalysisState plain = state(Map.of(
                AnalysisState.METRICS, List.of("营业总收入"),
                AnalysisState.HITS, List.of(
                        hit(101, "第十节 财务报告 § 合并利润表", "营业总收入 160.68 亿元"),
                        hit(102, "第十节 财务报告 § 附注", "营业总收入 116.04 亿元"))));

        assertThat(nodes.verify(plain)).containsEntry(AnalysisState.INSUFFICIENT, false);
    }

    @Test
    @DisplayName("verify：命中管理层讨论或出现经营驱动指标 → 算覆盖")
    void verifyAcceptsDriverEvidence() {
        AnalysisState bySection = state(Map.of(
                AnalysisState.QUESTION, "营业总收入增长的原因",
                AnalysisState.METRICS, List.of("营业总收入"),
                AnalysisState.HITS, List.of(
                        hit(101, "第三节 管理层讨论与分析 § 一、经营情况讨论与分析", "营业总收入 160.68 亿元"),
                        hit(102, "第十节 财务报告 § 附注", "营业总收入 116.04 亿元"))));
        AnalysisState byTerm = state(Map.of(
                AnalysisState.QUESTION, "营业总收入增长的原因",
                AnalysisState.METRICS, List.of("营业总收入"),
                AnalysisState.HITS, List.of(
                        hit(101, "第十节 财务报告 § 附注", "营业总收入 160.68 亿元"),
                        hit(102, "第十节 财务报告 § 附注", "手续费及佣金净收入 91.00 亿元"))));

        assertThat(nodes.verify(bySection)).containsEntry(AnalysisState.INSUFFICIENT, false);
        assertThat(nodes.verify(byTerm)).containsEntry(AnalysisState.INSUFFICIENT, false);
    }

    @Test
    @DisplayName("verify：改写之后（retryCount=1）不再判「缺归因」，避免变成整题拒答")
    void verifySkipsAttributionAfterRewrite() {
        AnalysisState afterRetry = state(Map.of(
                AnalysisState.QUESTION, "营业总收入增长的原因",
                AnalysisState.METRICS, List.of("营业总收入"),
                AnalysisState.RETRY_COUNT, 1,
                AnalysisState.HITS, List.of(
                        hit(101, "第十节 财务报告 § 合并利润表", "营业总收入 160.68 亿元"),
                        hit(102, "第十节 财务报告 § 附注", "营业总收入 116.04 亿元"))));

        assertThat(nodes.verify(afterRetry)).containsEntry(AnalysisState.INSUFFICIENT, false);
    }

    @Test
    @DisplayName("rewrite：归因类问题在检索式里补驱动关键词")
    void rewriteAppendsAttributionBoost() {
        Map<String, Object> patch = nodes.rewrite(state(Map.of(
                AnalysisState.QUESTION, "营业总收入增长的原因",
                AnalysisState.METRICS, List.of("营业总收入"))));

        assertThat(String.valueOf(patch.get(AnalysisState.REWRITTEN_QUERY)))
                .contains("营业总收入").contains("交易额").contains("驱动");
        assertThat(patch).containsEntry(AnalysisState.RETRY_COUNT, 1);
    }

    @Test
    @DisplayName("plan：把答案形态写进状态与计划（跨年 → 归因 + 对比）")
    void planWritesAnswerShapes() {
        Map<String, Object> patch = nodes.plan(state(Map.of(
                AnalysisState.QUESTION, "2024年和2025年营业总收入增长的主要原因",
                AnalysisState.YEAR_FROM, 2024,
                AnalysisState.YEAR_TO, 2025)));

        List<String> shapes = (List<String>) patch.get(AnalysisState.ANSWER_SHAPES);
        assertThat(shapes).containsExactlyInAnyOrder("ATTRIBUTION", "COMPARISON");
        assertThat(String.valueOf(patch.get(AnalysisState.PLAN))).contains("答案形态");
    }

    // ---------------------------------------------------------------- fixtures

    private static RetrievedChunk chunk(long id, double score) {
        return new RetrievedChunk(id, id / 100, 7, "TEXT", "第三节 § 主营业务", "标题",
                "正文", 60, 60, "", 1, 1, 0.5, score);
    }

    private static Map<String, Object> hit(long chunkId, String sectionPath, String snippet) {
        Map<String, Object> hit = new LinkedHashMap<>();
        hit.put("chunkId", chunkId);
        hit.put("parentId", 1L);
        hit.put("documentId", 7L);
        hit.put("page", 60);
        hit.put("sectionPath", sectionPath);
        hit.put("type", "TEXT");
        hit.put("score", 0.9);
        hit.put("snippet", snippet);
        return hit;
    }

    // ---------------------------------------------------------------- compute（ST16 Task 2）

    private static final String MAIN_TABLE = """
            单位：元
            |  | 2025年 | 2025年 | 2025年 | 2024年 | 同比增减 |
            | --- | --- | --- | --- | --- | --- |
            |  | 金额 | 占营业总收入比重 | 金额 | 占营业总收入比重 |  |
            | 营业总收入合计 | 16,067,741,039.51 | 100% | 11,604,343,090.16 | 100% | 38.46% |
            | 分行业 |  |  |  |  |  |
            | 证券业 | 12,535,471,036.46 | 78.02% | 8,493,612,633.32 | 73.19% | 47.59% |
            """;

    @Test
    @DisplayName("compute：抽表内事实并算同比，写进状态（已报事实 + 已计算事实两段）")
    void computeWritesFactsAndComputedMetrics() {
        when(contextBuilder.build(anyString(), any(), anyList(), any()))
                .thenReturn(new ContextPackage("[E2] 主营业务分行业表", List.of(tableBlock(2, 7246L, MAIN_TABLE)),
                        900, List.of(), 0, 0));

        Map<String, Object> patch = nodes.compute(state(Map.of(
                AnalysisState.METRICS, List.of("营业总收入"),
                AnalysisState.ANSWER_SHAPES, List.of("ATTRIBUTION"),
                AnalysisState.HITS, List.of(hit(7246, "第三节", "主营业务分行业")),
                AnalysisState.EVIDENCE_COUNT, 1)));

        List<Map<String, Object>> rows = rows(patch);
        assertThat(rows).isNotEmpty();
        assertThat(rows).anyMatch(r -> AnswerResult.ORIGIN_REPORTED.equals(r.get("origin"))
                && "营业总收入合计".equals(r.get("metric")) && "160.68 亿元".equals(r.get("display")));
        assertThat(rows).anyMatch(r -> AnswerResult.ORIGIN_COMPUTED.equals(r.get("origin"))
                && String.valueOf(r.get("metric")).contains("营业总收入合计同比增长率")
                && "38.46".equals(r.get("value")));
        // no 连续：从 1 开始，不跳号
        assertThat(rows.get(0).get("no")).isEqualTo(1);
    }

    @Test
    @DisplayName("compute：没有表格块时写空清单，且不加警告（不是错误，只是没数可抽）")
    void computeWritesEmptyListWithoutTable() {
        Map<String, Object> patch = nodes.compute(state(Map.of()));

        assertThat(rows(patch)).isEmpty();
        assertThat(patch).doesNotContainKey(AnalysisState.WARNINGS);
    }

    @Test
    @DisplayName("answer：状态里的事实被打包成 FactPack 传给 AnswerService（提示词里能见到【已报事实】）")
    void answerPassesFactPack() {
        when(answers.answerStreaming(anyString(), any(), any(), any(), any(), any()))
                .thenReturn(new AnswerResult(true, "营业总收入 160.68 亿元 [E1]", List.of(), List.of(),
                        AnswerResult.CONFIDENCE_HIGH, null, List.of(), 0));

        nodes.answer(state(Map.of(
                AnalysisState.FACTS, List.of(factRow(1, "营业总收入", "2025", "16067741039.51")),
                AnalysisState.HITS, List.of(hit(101, "第三节", "营业总收入 120.8 亿元")),
                AnalysisState.EVIDENCE_COUNT, 1)));

        org.mockito.ArgumentCaptor<FactPack> captor =
                org.mockito.ArgumentCaptor.forClass(FactPack.class);
        verify(answers).answerStreaming(anyString(), any(), any(), any(), captor.capture(), any());
        assertThat(captor.getValue().prompt()).contains("【已报事实】").contains("16067741039.51");
    }

    private static ContextBlock tableBlock(int no, long chunkId, String content) {
        return new ContextBlock(no, chunkId, 600L, 7L, "东方财富2025年年度报告", "第三节 § 主营业务分析",
                "主营业务分析", content, 11, 13, "TABLE", null, "元", null, null,
                0.9, 42, 300, ContextBlock.ROLE_HIT);
    }

    private static Map<String, Object> factRow(int no, String metric, String period, String value) {
        Map<String, Object> row = new java.util.LinkedHashMap<>();
        row.put("no", no);
        row.put("metric", metric);
        row.put("period", period);
        row.put("value", value);
        row.put("unit", "元");
        row.put("scope", "");
        row.put("evidence", "E1");
        row.put("origin", AnswerResult.ORIGIN_REPORTED);
        row.put("formula", "");
        row.put("display", "160.68 亿元");
        row.put("note", "");
        return row;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> rows(Map<String, Object> patch) {
        Object value = patch.get(AnalysisState.FACTS);
        return value == null ? List.of() : (List<Map<String, Object>>) value;
    }

    // ---------------------------------------------------------------- 车道（ST16 Task 3）

    private static RetrievedChunk chunkIn(long id, long documentId, double score) {
        return new RetrievedChunk(id, id / 100, documentId, "TEXT", "第三节 § 主营业务", "标题",
                "正文", 60, 60, "", 1, 1, 0.5, score);
    }

    /** 作用域里同时有 2025 与 2024 两份年报。 */
    private void scopeWithTwoYears() {
        when(scopes.resolve(any())).thenReturn(ResolvedScope.of(ScopeKind.CORPUS, List.of(
                new com.finagent.scope.DocMeta(7L, 7L, "300059", "东方财富", 2025, "ANNUAL",
                        null, null, 3, "东方财富2025年年度报告"),
                new com.finagent.scope.DocMeta(38L, 7L, "300059", "东方财富", 2024, "ANNUAL",
                        null, null, 1, "东方财富2024年年度报告")),
                ResolvedScope.REASON_SESSION, List.of()));
    }

    @Test
    @DisplayName("retrieve：跨年问题每年加一路，车道里能看到 year:2025 / year:2024")
    void retrieveAddsYearLanesForCrossYearQuestion() {
        scopeWithTwoYears();
        when(retriever.retrieve(any(RetrievalRequest.class), any(), any(), any(RetrievalProgress.class)))
                .thenAnswer(inv -> {
                    RetrievalRequest req = inv.getArgument(0);
                    Integer year = req.filters().docYear();
                    if (year != null && year == 2024) {
                        return new HybridRetriever.Result(List.of(chunkIn(201, 38, 0.8)), null);
                    }
                    return new HybridRetriever.Result(List.of(chunkIn(101, 7, 0.9)), null);
                });

        Map<String, Object> patch = nodes.retrieve(state(Map.of(
                AnalysisState.YEAR_FROM, 2024, AnalysisState.YEAR_TO, 2025)));

        assertThat((List<String>) patch.get(AnalysisState.LANES))
                .containsExactly("full", "year:2025", "year:2024");
        // 两年各拿到一块，不触发补跑
        assertThat((List<?>) patch.get(AnalysisState.HITS)).hasSize(2);
        assertThat(patch).doesNotContainKey(AnalysisState.WARNINGS);
    }

    @Test
    @DisplayName("retrieve：某年一条都没命中时补跑一轮（topK 更大），补到就不再告警")
    void retrieveRetriesOnceWhenYearMissing() {
        scopeWithTwoYears();
        java.util.Map<Integer, Integer> callsByYear = new java.util.HashMap<>();
        when(retriever.retrieve(any(RetrievalRequest.class), any(), any(), any(RetrievalProgress.class)))
                .thenAnswer(inv -> {
                    RetrievalRequest req = inv.getArgument(0);
                    Integer year = req.filters().docYear();
                    if (year != null && year == 2024) {
                        int seen = callsByYear.merge(2024, 1, Integer::sum);
                        return seen == 1
                                ? new HybridRetriever.Result(List.of(), null)
                                : new HybridRetriever.Result(List.of(chunkIn(201, 38, 0.8)), null);
                    }
                    return new HybridRetriever.Result(List.of(chunkIn(101, 7, 0.9)), null);
                });

        Map<String, Object> patch = nodes.retrieve(state(Map.of(
                AnalysisState.YEAR_FROM, 2024, AnalysisState.YEAR_TO, 2025)));

        // 2024 检索了两次：第一轮 4 条，补跑 8 条
        assertThat(callsByYear.get(2024)).isEqualTo(2);
        assertThat((List<?>) patch.get(AnalysisState.HITS)).hasSize(2);
        // 补跑会留痕，但不再报缺失
        assertThat((List<String>) patch.get(AnalysisState.WARNINGS))
                .contains("year_lane_retried")
                .doesNotContain("year_coverage_missing:2024");
    }

    @Test
    @DisplayName("retrieve：补完仍缺该年 → 记 year_coverage_missing:2024，但不拒答")
    void retrieveWarnsWhenYearStillMissing() {
        scopeWithTwoYears();
        when(retriever.retrieve(any(RetrievalRequest.class), any(), any(), any(RetrievalProgress.class)))
                .thenAnswer(inv -> {
                    RetrievalRequest req = inv.getArgument(0);
                    if (req.filters().docYear() != null && req.filters().docYear() == 2024) {
                        return new HybridRetriever.Result(List.of(), null);
                    }
                    return new HybridRetriever.Result(List.of(chunkIn(101, 7, 0.9)), null);
                });

        Map<String, Object> patch = nodes.retrieve(state(Map.of(
                AnalysisState.YEAR_FROM, 2024, AnalysisState.YEAR_TO, 2025)));

        assertThat((List<String>) patch.get(AnalysisState.WARNINGS))
                .contains("year_coverage_missing:2024")
                .doesNotContain("year_coverage_missing:2025");
        assertThat(patch).doesNotContainKey(AnalysisState.REFUSE_REASON);
    }
}
