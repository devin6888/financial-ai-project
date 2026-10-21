package com.finagent.retrieval;

import com.finagent.document.DocumentRepository;
import com.finagent.retrieval.rerank.RerankException;
import com.finagent.retrieval.rerank.RerankModel;
import com.finagent.retrieval.rerank.RerankResult;
import com.finagent.understanding.QueryType;
import com.finagent.understanding.QueryUnderstanding;
import com.finagent.understanding.RouterService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 编排逻辑的单元测试：三路（Dense / Sparse / Rerank）都是 mock，
 * 断言的是「编排决策」而不是「检索质量」—— 后者要真库，见 Task 9 的 e2e。
 */
class HybridRetrieverTest {

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

        when(rerank.modelName()).thenReturn("qwen3-rerank");
        when(documents.findCompanyIdByCode(anyString())).thenReturn(null);
        when(dense.retrieve(anyString(), any(), anyInt())).thenReturn(List.of());
        when(sparse.retrieve(anyString(), anyList(), any(), any(), anyInt())).thenReturn(List.of());
        // 默认精排 = 保持顺序，让「不关心 Rerank 的用例」只被编排逻辑影响
        when(rerank.rerank(anyString(), anyList(), anyInt())).thenAnswer(inv -> {
            List<String> docs = inv.getArgument(1);
            int n = Math.min(inv.<Integer>getArgument(2), docs.size());
            List<RerankResult> out = new ArrayList<>(n);
            for (int i = 0; i < n; i++) {
                out.add(new RerankResult(i, 1.0 - i * 0.01));
            }
            return out;
        });
    }

    // ------------------------------------------------------------ QU → Sparse 模式

    @Test
    @DisplayName("抽到实体 → EXACT 模式，实体作为 terms")
    void quEntitiesSelectExactMode() {
        QueryUnderstanding qu = qu(List.of(), null, List.of("营业收入"), "RULE");

        retriever.retrieve(req(true), qu);

        verify(sparse).retrieve(eq("2025年营业收入"), eq(List.of("营业收入")),
                eq(SparseMode.EXACT), any(), anyInt());
    }

    @Test
    @DisplayName("没抽到实体 → FTS_OR 模式，terms 为空")
    void quWithoutEntitiesSelectsFtsOr() {
        QueryUnderstanding qu = qu(List.of(), null, List.of(), "RULE");

        retriever.retrieve(req(true), qu);

        verify(sparse).retrieve(anyString(), eq(List.of()), eq(SparseMode.FTS_OR), any(), anyInt());
    }

    @Test
    @DisplayName("公司代码也进 EXACT 的 terms")
    void companyCodeJoinsExactTerms() {
        when(documents.findCompanyIdByCode("300059")).thenReturn(7L);
        QueryUnderstanding qu = qu(List.of("300059"), null, List.of("营业收入"), "RULE");
        // 给一条结果：否则空结果会触发 sparse_exact_empty_fallback，调用次数变 2
        when(sparse.retrieve(anyString(), anyList(), eq(SparseMode.EXACT), any(), anyInt()))
                .thenReturn(List.of(chunk(1, null, 1)));

        retriever.retrieve(req(true), qu);

        verify(sparse).retrieve(anyString(), eq(List.of("营业收入", "300059")),
                eq(SparseMode.EXACT), any(), anyInt());
    }

    // ------------------------------------------------------------ QU → 硬过滤

    @Test
    @DisplayName("单一公司代码 → 转成 company_id 过滤")
    void singleCompanyBecomesFilter() {
        when(documents.findCompanyIdByCode("300059")).thenReturn(7L);
        // 必须有结果：空结果会触发 qu_filter_empty_fallback，最后捕获到的就是放宽后的 null
        when(dense.retrieve(anyString(), any(), anyInt())).thenReturn(List.of(chunk(1, 1, null)));
        QueryUnderstanding qu = qu(List.of("300059"), null, List.of(), "RULE");

        retriever.retrieve(req(true), qu);

        assertThat(capturedFilters().companyId()).isEqualTo(7L);
    }

    @Test
    @DisplayName("两个公司代码 → 不过滤（对比类问题只留一家会答错）")
    void multiCompanySkipsFilter() {
        QueryUnderstanding qu = qu(List.of("300059", "600519"), null, List.of(), "RULE");

        HybridRetriever.Result r = retriever.retrieve(req(true), qu);

        assertThat(capturedFilters().companyId()).isNull();
        assertThat(r.trace().warnings()).contains("qu_multi_company_no_filter");
        verify(documents, never()).findCompanyIdByCode(anyString());
    }

    @Test
    @DisplayName("显式过滤条件优先于 QU")
    void explicitFilterWinsOverQu() {
        when(documents.findCompanyIdByCode("300059")).thenReturn(7L);
        QueryUnderstanding qu = qu(List.of("300059"), null, List.of(), "RULE");
        RetrievalRequest req = RetrievalRequest.of("2025年营业收入",
                new RetrievalFilters(null, 2L, null, null, null, null));

        retriever.retrieve(req, qu);

        assertThat(capturedFilters().companyId()).isEqualTo(2L);
        verify(documents, never()).findCompanyIdByCode(anyString());
    }

    @Test
    @DisplayName("代码查不到公司 → 不设过滤，避免静默 0 条")
    void unknownCompanyCodeSkipsFilter() {
        QueryUnderstanding qu = qu(List.of("999999"), null, List.of(), "RULE");

        HybridRetriever.Result r = retriever.retrieve(req(true), qu);

        assertThat(capturedFilters().companyId()).isNull();
        assertThat(r.trace().warnings()).contains("qu_company_not_found:999999");
    }

    @Test
    @DisplayName("单一年份 → doc_year 过滤")
    void singleYearBecomesFilter() {
        when(dense.retrieve(anyString(), any(), anyInt())).thenReturn(List.of(chunk(1, 1, null)));
        QueryUnderstanding qu = qu(List.of(), new int[]{2025, 2025}, List.of(), "RULE");

        retriever.retrieve(req(true), qu);

        assertThat(capturedFilters().docYear()).isEqualTo(2025);
    }

    @Test
    @DisplayName("年份区间 → 不过滤")
    void yearRangeSkipsFilter() {
        QueryUnderstanding qu = qu(List.of(), new int[]{2023, 2025}, List.of(), "RULE");

        HybridRetriever.Result r = retriever.retrieve(req(true), qu);

        assertThat(capturedFilters().docYear()).isNull();
        assertThat(r.trace().warnings()).contains("qu_year_range_no_filter");
    }

    // ------------------------------------------------------------ 融合

    @Test
    @DisplayName("两路都召回的同一 chunk 合并成一条，两个名次都保留")
    void dedupKeepsBothRanks() {
        when(dense.retrieve(anyString(), any(), anyInt()))
                .thenReturn(List.of(chunk(1, 1, null), chunk(2, 2, null)));
        when(sparse.retrieve(anyString(), anyList(), any(), any(), anyInt()))
                .thenReturn(List.of(chunk(1, null, 1), chunk(3, null, 2)));

        HybridRetriever.Result r = retriever.retrieve(req(true), qu(List.of(), null, List.of(), "RULE"));

        assertThat(r.trace().fusedCount()).isEqualTo(3);
        RetrievedChunk top = r.chunks().get(0);
        assertThat(top.chunkId()).isEqualTo(1);
        // 两路都排第一 → RRF 分最高；只在一路出现的拿不到这个分
        assertThat(top.denseRank()).isEqualTo(1);
        assertThat(top.sparseRank()).isEqualTo(1);
        assertThat(top.rrfScore()).isGreaterThan(r.chunks().get(1).rrfScore());
    }

    @Test
    @DisplayName("fusedCount 是截断前的条数，returned 才是最终条数")
    void fusedCountIsBeforeTruncation() {
        when(dense.retrieve(anyString(), any(), anyInt())).thenReturn(ranked(1, 30, true));
        when(sparse.retrieve(anyString(), anyList(), any(), any(), anyInt())).thenReturn(ranked(16, 30, false));

        HybridRetriever.Result r = retriever.retrieve(req(true), qu(List.of(), null, List.of(), "RULE"));

        assertThat(r.trace().fusedCount()).isEqualTo(45);
        assertThat(r.trace().returned()).isEqualTo(8);
        assertThat(r.chunks()).hasSize(8);
    }

    @Test
    @DisplayName("两路都没有候选 → 不调 Rerank，状态 SKIPPED")
    void emptyCandidatesIsSkipped() {
        HybridRetriever.Result r = retriever.retrieve(req(true), qu(List.of(), null, List.of(), "RULE"));

        assertThat(r.chunks()).isEmpty();
        assertThat(r.trace().rerankStatus()).isEqualTo(RerankStatus.SKIPPED);
        assertThat(r.trace().warnings()).contains("no_candidates");
        verify(rerank, never()).rerank(anyString(), anyList(), anyInt());
    }

    // ------------------------------------------------------------ Rerank

    @Test
    @DisplayName("Rerank 返回的下标决定最终顺序，分数落到 chunk 上")
    void rerankReordersAndScores() {
        when(dense.retrieve(anyString(), any(), anyInt()))
                .thenReturn(List.of(chunk(1, 1, null), chunk(2, 2, null), chunk(3, 3, null)));
        when(rerank.rerank(anyString(), anyList(), anyInt())).thenReturn(List.of(
                new RerankResult(2, 0.9), new RerankResult(0, 0.8), new RerankResult(1, 0.1)));

        HybridRetriever.Result r = retriever.retrieve(req(true), qu(List.of(), null, List.of(), "RULE"));

        assertThat(r.trace().rerankStatus()).isEqualTo(RerankStatus.APPLIED);
        assertThat(r.chunks()).extracting(RetrievedChunk::chunkId).containsExactly(3L, 1L, 2L);
        assertThat(r.chunks().get(0).rerankScore()).isEqualTo(0.9);
        assertThat(r.chunks().get(0).rerankScore()).isNotNaN();
    }

    @Test
    @DisplayName("Rerank 抛异常 → 回退 RRF 结果 + FAILED + 警告，不向上抛")
    void rerankFailureDegradesToRrf() {
        when(dense.retrieve(anyString(), any(), anyInt())).thenReturn(ranked(1, 5, true));
        when(rerank.rerank(anyString(), anyList(), anyInt()))
                .thenThrow(new RerankException("百炼 Rerank 返回 500"));

        HybridRetriever.Result r = retriever.retrieve(req(true), qu(List.of(), null, List.of(), "RULE"));

        assertThat(r.chunks()).hasSize(5);
        assertThat(r.trace().rerankStatus()).isEqualTo(RerankStatus.FAILED);
        assertThat(r.trace().rerankDegraded()).isTrue();
        assertThat(r.trace().warnings()).anyMatch(w -> w.startsWith("rerank_failed"));
        // 降级后保留的是 RRF 顺序，不是乱序
        assertThat(r.chunks()).extracting(RetrievedChunk::chunkId).containsExactly(1L, 2L, 3L, 4L, 5L);
    }

    @Test
    @DisplayName("useRerank=false → SKIPPED，不是 FAILED（没启用 ≠ 降级）")
    void rerankDisabledIsSkipped() {
        when(dense.retrieve(anyString(), any(), anyInt())).thenReturn(ranked(1, 10, true));

        HybridRetriever.Result r = retriever.retrieve(req(false), qu(List.of(), null, List.of(), "RULE"));

        assertThat(r.trace().rerankStatus()).isEqualTo(RerankStatus.SKIPPED);
        assertThat(r.trace().rerankDegraded()).isFalse();
        assertThat(r.chunks()).hasSize(8);
        verify(rerank, never()).rerank(anyString(), anyList(), anyInt());
    }

    // ------------------------------------------------------------ 渐进放松

    @Test
    @DisplayName("EXACT 命中 0 条 → 退回 FTS_OR，而不是让 Sparse 路白设")
    void exactEmptyFallsBackToFts() {
        when(dense.retrieve(anyString(), any(), anyInt())).thenReturn(List.of(chunk(1, 1, null)));
        when(sparse.retrieve(anyString(), anyList(), eq(SparseMode.EXACT), any(), anyInt()))
                .thenReturn(List.of());
        when(sparse.retrieve(anyString(), anyList(), eq(SparseMode.FTS_OR), any(), anyInt()))
                .thenReturn(List.of(chunk(5, null, 1)));

        HybridRetriever.Result r = retriever.retrieve(req(true),
                qu(List.of(), null, List.of("每股收益"), "RULE"));

        assertThat(r.trace().warnings()).contains("sparse_exact_empty_fallback");
        assertThat(r.trace().sparseHits()).isEqualTo(1);
        assertThat(r.trace().fusedCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("QU 过滤条件把结果清空 → 去掉 QU 部分重试一次")
    void quFilterEmptyFallsBackToUnfiltered() {
        when(documents.findCompanyIdByCode("300059")).thenReturn(7L);
        // 带 QU 过滤 → 空；去掉过滤 → 有结果。
        // 不用 argThat：它在已注册 stub 的情形下会被传入 null 触发 NPE
        when(dense.retrieve(anyString(), any(), anyInt())).thenAnswer(inv -> {
            RetrievalFilters f = inv.getArgument(1);
            return f.companyId() == null ? List.of(chunk(9, 1, null)) : List.of();
        });

        HybridRetriever.Result r = retriever.retrieve(req(true),
                qu(List.of("300059"), null, List.of(), "RULE"));

        assertThat(r.trace().warnings()).contains("qu_filter_empty_fallback");
        assertThat(r.chunks()).hasSize(1);
        assertThat(r.chunks().get(0).chunkId()).isEqualTo(9L);
    }

    // ------------------------------------------------------------ 其他

    @Test
    @DisplayName("QU 抛异常 → 按保守结果降级，trace 里能看出是 QU 的锅")
    void quExceptionDegrades() {
        when(router.understand(anyString())).thenThrow(new IllegalStateException("llm timeout"));

        HybridRetriever.Result r = retriever.retrieve(req(true));

        assertThat(r.trace().quDegraded()).isTrue();
        assertThat(r.trace().quRoute()).isEqualTo("DEGRADED:qu_exception");
        assertThat(r.trace().warnings()).contains("qu_failed: IllegalStateException");
        assertThat(r.trace().queryType()).isEqualTo(QueryType.SEMANTIC_EXPLANATION);
    }

    @Test
    @DisplayName("trace 带上 QU 的 queryType / route")
    void traceCarriesQuFields() {
        QueryUnderstanding qu = new QueryUnderstanding(QueryType.EXACT_FACT, "2025年营业收入",
                List.of(), new int[]{2025, 2025}, List.of("营业收入"), false, false, false, "LLM");

        HybridRetriever.Result r = retriever.retrieve(req(true), qu);

        assertThat(r.trace().queryType()).isEqualTo(QueryType.EXACT_FACT);
        assertThat(r.trace().quRoute()).isEqualTo("LLM");
        assertThat(r.trace().quDegraded()).isFalse();
        assertThat(r.trace().rerankModel()).isEqualTo("qwen3-rerank");
        assertThat(r.trace().query()).isEqualTo("2025年营业收入");
    }

    @Test
    @DisplayName("送 Rerank 的文本要带章节路径与标题")
    void rerankTextIncludesSectionAndTitle() {
        String text = HybridRetriever.rerankText(chunk(1, 1, null));

        assertThat(text).startsWith("第八节").contains("章节").contains("正文1");
    }

    @Test
    @DisplayName("无 QU 基线：route=NONE，Sparse 走 FTS_OR")
    void nullQuFallsBackToBaseline() {
        retriever.retrieve(req(true), null);

        verify(sparse).retrieve(anyString(), eq(List.of()), eq(SparseMode.FTS_OR), any(), anyInt());
    }

    // ------------------------------------------------------------ helpers

    private static RetrievalRequest req(boolean useRerank) {
        return new RetrievalRequest("2025年营业收入", null, 0, 0, 0, 0, useRerank);
    }

    private static QueryUnderstanding qu(List<String> companyIds, int[] years,
                                         List<String> metrics, String route) {
        return new QueryUnderstanding(QueryType.EXACT_FACT, "2025年营业收入", companyIds, years,
                metrics, false, false, false, route);
    }

    private static RetrievedChunk chunk(long id, Integer denseRank, Integer sparseRank) {
        return new RetrievedChunk(id, 1L, 7L, "TEXT", "第八节 财务报告", "章节" + id,
                "正文" + id, 1, 1, null, denseRank, sparseRank, 0.0, null);
    }

    /** 造 id 从 from 开始的 count 条，只占一路。 */
    private static List<RetrievedChunk> ranked(int from, int count, boolean denseSide) {
        List<RetrievedChunk> out = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            out.add(chunk(from + i, denseSide ? i + 1 : null, denseSide ? null : i + 1));
        }
        return out;
    }

    private RetrievalFilters capturedFilters() {
        ArgumentCaptor<RetrievalFilters> cap = ArgumentCaptor.forClass(RetrievalFilters.class);
        verify(dense, org.mockito.Mockito.atLeastOnce()).retrieve(anyString(), cap.capture(), anyInt());
        return cap.getValue();
    }
}
