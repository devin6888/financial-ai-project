package com.finagent.retrieval;

import com.finagent.document.DocumentRepository;
import com.finagent.retrieval.rerank.RerankModel;
import com.finagent.retrieval.rerank.RerankResult;
import com.finagent.scope.DocMeta;
import com.finagent.scope.ResolvedScope;
import com.finagent.scope.ScopeKind;
import com.finagent.understanding.QueryType;
import com.finagent.understanding.QueryUnderstanding;
import com.finagent.understanding.RouterService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 阶段 5 新增的「作用域 × QU」交互规则。
 *
 * <p>这一层全是编排决策（谁的过滤条件优先、什么时候丢弃、回退时保留什么），
 * 三路都 mock 掉才能把决策单独钉死；真实检索效果见 ScopeFilteringTest。
 */
class HybridRetrieverScopeTest {

    private RouterService router;
    private DocumentRepository documents;
    private DenseRetriever dense;
    private SparseRetriever sparse;
    private RerankModel rerank;
    private HybridRetriever retriever;

    @BeforeEach
    void setUp() {
        router = mock(RouterService.class);
        documents = mock(DocumentRepository.class);
        dense = mock(DenseRetriever.class);
        sparse = mock(SparseRetriever.class);
        rerank = mock(RerankModel.class);
        retriever = new HybridRetriever(router, documents, dense, sparse, rerank);

        // 走 retrieveInScope 的用例不会显式传 QU，QU 由 RouterService 产出 —— mock 必须返回非 null
        when(router.understand(anyString())).thenReturn(QueryUnderstanding.none("2025年营业收入"));
        when(rerank.modelName()).thenReturn("qwen3-rerank");
        when(documents.findCompanyIdByCode(anyString())).thenReturn(null);
        when(dense.retrieve(anyString(), any(), anyInt())).thenReturn(List.of(chunk(1, 7L)));
        when(sparse.retrieve(anyString(), anyList(), any(), any(), anyInt())).thenReturn(List.of());
        when(rerank.rerank(anyString(), anyList(), anyInt())).thenAnswer(inv -> {
            List<String> docs = inv.getArgument(1);
            List<RerankResult> out = new ArrayList<>();
            for (int i = 0; i < Math.min(inv.<Integer>getArgument(2), docs.size()); i++) {
                out.add(new RerankResult(i, 1.0 - i * 0.01));
            }
            return out;
        });
    }

    // ------------------------------------------------------------ 作用域 → 过滤条件

    @Test
    @DisplayName("作用域的文档集合覆盖调用方没指定的文档条件")
    void scopeFillsDocumentIds() {
        retriever.retrieveInScope(req(), scope(7L, 38L));

        assertThat(lastFilters().documentIds()).containsExactly(7L, 38L);
    }

    @Test
    @DisplayName("调用方指定的文档与作用域取交集，并留下收窄警告")
    void callerDocumentsIntersectScope() {
        RetrievalRequest req = new RetrievalRequest("2025年营业收入",
                RetrievalFilters.ofDocuments(List.of(7L, 38L)), 0, 0, 0, 0, true);

        HybridRetriever.Result r = retriever.retrieveInScope(req, scope(7L));

        assertThat(lastFilters().documentIds()).containsExactly(7L);
        assertThat(r.trace().warnings()).contains("scope_narrowed_caller_documents");
    }

    @Test
    @DisplayName("调用方的文档与作用域完全不相交 → 空集哨兵，绝不退化成全库")
    void disjointCallerDocumentsBecomeEmptySet() {
        RetrievalRequest req = new RetrievalRequest("2025年营业收入",
                RetrievalFilters.ofDocuments(List.of(38L)), 0, 0, 0, 0, true);

        retriever.retrieveInScope(req, scope(7L));

        assertThat(lastFilters().documentIds()).containsExactly(HybridRetriever.EMPTY_DOCUMENT_SET);
    }

    @Test
    @DisplayName("CORPUS 作用域不把全部 id 拼进 IN —— 那会让优化器放弃索引")
    void corpusScopeKeepsFiltersEmpty() {
        ResolvedScope corpus = ResolvedScope.of(ScopeKind.CORPUS,
                List.of(meta(7L, 2025), meta(38L, 2024)), ResolvedScope.REASON_SESSION, List.of());

        retriever.retrieveInScope(req(), corpus);

        assertThat(lastFilters().documentIds()).isEmpty();
    }

    // ------------------------------------------------------------ QU 越界

    @Test
    @DisplayName("QU 抽出的公司在作用域外 → 丢弃该条件，不让它把结果清空")
    void quCompanyOutOfScopeDropped() {
        when(documents.findCompanyIdByCode("600519")).thenReturn(99L);
        QueryUnderstanding qu = qu(List.of("600519"), null, List.of(), null, "RULE");

        HybridRetriever.Result r = retriever.retrieve(req(), qu, scope(7L));

        assertThat(lastFilters().companyId()).isNull();
        assertThat(r.trace().warnings()).contains("qu_company_out_of_scope:600519");
    }

    @Test
    @DisplayName("QU 抽出的年份在作用域外 → 丢弃（专治「问 2023 但库里只有 24/25」）")
    void quYearOutOfScopeDropped() {
        QueryUnderstanding qu = qu(List.of(), new int[]{2024, 2024}, List.of(), null, "RULE");

        HybridRetriever.Result r = retriever.retrieve(req(), qu, scope(7L));   // 7 是 2025 年报

        assertThat(lastFilters().docYear()).isNull();
        assertThat(r.trace().warnings()).contains("qu_year_out_of_scope:2024");
    }

    @Test
    @DisplayName("QU 抽出的年份在作用域内 → 正常当硬过滤")
    void quYearInScopeApplied() {
        QueryUnderstanding qu = qu(List.of(), new int[]{2024, 2024}, List.of(), null, "RULE");

        retriever.retrieve(req(), qu, scope(38L));

        assertThat(lastFilters().docYear()).isEqualTo(2024);
    }

    @Test
    @DisplayName("QU 的报告类型：无作用域时直接生效")
    void quDocTypeAppliedWithoutScope() {
        QueryUnderstanding qu = qu(List.of(), null, List.of(), "Q1", "RULE");

        retriever.retrieve(req(), qu);

        assertThat(lastFilters().docType()).isEqualTo("Q1");
    }

    @Test
    @DisplayName("QU 的报告类型不在作用域里 → 丢弃")
    void quDocTypeOutOfScopeDropped() {
        QueryUnderstanding qu = qu(List.of(), null, List.of(), "Q1", "RULE");

        HybridRetriever.Result r = retriever.retrieve(req(), qu, scope(7L));   // 7 是 ANNUAL

        assertThat(lastFilters().docType()).isNull();
        assertThat(r.trace().warnings()).contains("qu_doc_type_out_of_scope:Q1");
    }

    // ------------------------------------------------------------ 回退不越界

    @Test
    @DisplayName("QU 条件导致 0 条时，回退只清 QU 部分，文档集合保留")
    void fallbackKeepsDocumentIds() {
        QueryUnderstanding qu = qu(List.of(), new int[]{2025, 2025}, List.of(), null, "RULE");
        when(dense.retrieve(anyString(), any(), anyInt())).thenAnswer(inv -> {
            RetrievalFilters f = inv.getArgument(1);
            return f.docYear() == null ? List.of(chunk(1, 7L)) : List.of();
        });

        HybridRetriever.Result r = retriever.retrieve(req(), qu, scope(7L));

        assertThat(r.trace().warnings()).contains("qu_filter_empty_fallback");
        RetrievalFilters relaxed = lastFilters();
        assertThat(relaxed.docYear()).isNull();
        assertThat(relaxed.documentIds()).containsExactly(7L);
    }

    @Test
    @DisplayName("放松到只剩作用域仍然 0 条 → 返回空 + no_evidence_in_scope，不放开作用域")
    void noEvidenceInsideScope() {
        when(dense.retrieve(anyString(), any(), anyInt())).thenReturn(List.of());
        when(sparse.retrieve(anyString(), anyList(), any(), any(), anyInt())).thenReturn(List.of());

        HybridRetriever.Result r = retriever.retrieve(req(), qu(List.of(), null, List.of("营业收入"), null, "RULE"),
                scope(7L));

        assertThat(r.chunks()).isEmpty();
        assertThat(r.trace().warnings()).contains("no_evidence_in_scope");
        assertThat(lastFilters().documentIds()).containsExactly(7L);
    }

    // ------------------------------------------------------------ 配额触发条件

    @Test
    @DisplayName("判为 COMPARISON 且作用域 ≥2 份 → 启用配额，并记进 trace")
    void quotaEnabledForComparison() {
        when(dense.retrieve(anyString(), any(), anyInt())).thenReturn(interleaved(7L, 38L, 30, 30));
        QueryUnderstanding qu = new QueryUnderstanding(QueryType.COMPARISON, "对比两年营业收入",
                List.of(), null, List.of("营业收入"), false, false, null, false, "TEST");

        HybridRetriever.Result r = retriever.retrieve(req(), qu, scope(7L, 38L));

        assertThat(r.trace().warnings()).contains("doc_quota_balanced_2_docs");
    }

    @Test
    @DisplayName("年份跨年（2024..2025）也算跨文档问题")
    void quotaEnabledForCrossYear() {
        when(dense.retrieve(anyString(), any(), anyInt())).thenReturn(interleaved(7L, 38L, 30, 30));
        QueryUnderstanding qu = new QueryUnderstanding(QueryType.SEMANTIC_EXPLANATION, "两年营收变化",
                List.of(), new int[]{2024, 2025}, List.of(), false, false, null, false, "TEST");

        HybridRetriever.Result r = retriever.retrieve(req(), qu, scope(7L, 38L));

        assertThat(r.trace().warnings()).contains("doc_quota_balanced_2_docs");
    }

    @Test
    @DisplayName("单文档问题即使作用域有多份也不套配额（只会把无关块塞进精排）")
    void quotaDisabledForSingleDocumentQuestion() {
        when(dense.retrieve(anyString(), any(), anyInt())).thenReturn(interleaved(7L, 38L, 30, 30));
        QueryUnderstanding qu = new QueryUnderstanding(QueryType.EXACT_FACT, "2025年营业收入",
                List.of(), new int[]{2025, 2025}, List.of("营业收入"), false, false, null, false, "TEST");

        HybridRetriever.Result r = retriever.retrieve(req(), qu, scope(7L, 38L));

        assertThat(r.trace().warnings()).noneMatch(w -> w.startsWith("doc_quota_balanced_"));
    }

    @Test
    @DisplayName("作用域只有一份文档 → 配额必不启用")
    void quotaDisabledForSingleDocumentScope() {
        QueryUnderstanding comparison = new QueryUnderstanding(QueryType.COMPARISON, "对比",
                List.of(), null, List.of(), false, false, null, false, "TEST");

        assertThat(HybridRetriever.quotaEnabled(comparison, scope(7L))).isFalse();
        assertThat(HybridRetriever.quotaEnabled(comparison, scope(7L, 38L))).isTrue();
    }

    // ------------------------------------------------------------ helpers

    /** 两份文档交错排列的候选，模拟 RRF 后分数此起彼伏。 */
    private static List<RetrievedChunk> interleaved(long a, long b, int countA, int countB) {
        List<RetrievedChunk> out = new ArrayList<>();
        for (int i = 0; i < Math.max(countA, countB); i++) {
            if (i < countA) {
                out.add(chunk(a * 1000 + i, a));
            }
            if (i < countB) {
                out.add(chunk(b * 1000 + i, b));
            }
        }
        return out;
    }

    private static RetrievalRequest req() {
        return new RetrievalRequest("2025年营业收入", null, 0, 0, 0, 0, true);
    }

    private static QueryUnderstanding qu(List<String> companies, int[] years, List<String> metrics,
                                        String docType, String route) {
        return new QueryUnderstanding(QueryType.EXACT_FACT, "2025年营业收入", companies, years, metrics,
                false, false, docType, false, route);
    }

    private static ResolvedScope scope(Long... ids) {
        List<DocMeta> metas = new ArrayList<>();
        for (Long id : ids) {
            metas.add(meta(id, id == 38L ? 2024 : 2025));
        }
        return ResolvedScope.of(ScopeKind.DOCUMENTS, metas, ResolvedScope.REASON_SESSION, List.of());
    }

    private static DocMeta meta(long id, int year) {
        return new DocMeta(id, 1L, "300059", "东方财富", year, "ANNUAL",
                LocalDate.of(year, 1, 1), LocalDate.of(year, 12, 31), 1,
                "东方财富" + year + "年年度报告");
    }

    private static RetrievedChunk chunk(long id, long documentId) {
        return new RetrievedChunk(id, 1L, documentId, "TEXT", "第八节 财务报告", "章节" + id,
                "正文" + id, 1, 1, null, 1, null, 0.0, null);
    }

    /** 最后一次送给 Dense 的过滤条件（回退时会有两次，取第二次即放松后的）。 */
    private RetrievalFilters lastFilters() {
        ArgumentCaptor<RetrievalFilters> cap = ArgumentCaptor.forClass(RetrievalFilters.class);
        verify(dense, atLeastOnce()).retrieve(anyString(), cap.capture(), anyInt());
        return cap.getValue();
    }
}
