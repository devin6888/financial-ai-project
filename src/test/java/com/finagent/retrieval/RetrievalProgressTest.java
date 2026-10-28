package com.finagent.retrieval;

import com.finagent.document.DocumentRepository;
import com.finagent.retrieval.rerank.RerankModel;
import com.finagent.retrieval.rerank.RerankResult;
import com.finagent.understanding.QueryType;
import com.finagent.understanding.QueryUnderstanding;
import com.finagent.understanding.RouterService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 阶段 13（契约 C16）：检索层的进度回调只在真的进入某一段时才触发。
 *
 * <p>为什么要在这一层测：ChatService 看不到「精排开始」这件事，
 * 它只拿到一个回调；如果回调点埋错（比如提前埋到方法入口），
 * 前端会出现「正在精排」但其实还在密集检索 —— 用户看到的状态就是假的。
 */
class RetrievalProgressTest {

    private DenseRetriever dense;
    private SparseRetriever sparse;
    private RerankModel rerank;
    private HybridRetriever retriever;

    @BeforeEach
    void setUp() {
        RouterService router = mock(RouterService.class);
        DocumentRepository documents = mock(DocumentRepository.class);
        dense = mock(DenseRetriever.class);
        sparse = mock(SparseRetriever.class);
        rerank = mock(RerankModel.class);
        retriever = new HybridRetriever(router, documents, dense, sparse, rerank);

        when(rerank.modelName()).thenReturn("qwen3-rerank");
        when(documents.findCompanyIdByCode(anyString())).thenReturn(null);
        when(dense.retrieve(anyString(), any(), anyInt())).thenReturn(List.of());
        when(sparse.retrieve(anyString(), anyList(), any(), any(), anyInt())).thenReturn(List.of());
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

    @Test
    @DisplayName("有候选且启用精排：dense → sparse → fuse → rerank，且 rerank 带候选数")
    void reportsFourPhases() {
        when(dense.retrieve(anyString(), any(), anyInt())).thenReturn(ranked(1, 2, true));
        when(sparse.retrieve(anyString(), anyList(), any(), any(), anyInt())).thenReturn(ranked(10, 1, false));
        List<String> phases = new ArrayList<>();
        List<Integer> counts = new ArrayList<>();

        retriever.retrieve(req(true), qu(), null, (phase, count) -> {
            phases.add(phase);
            counts.add(count);
        });

        assertThat(phases).containsExactly("dense", "sparse", "fuse", RetrievalProgress.PHASE_RERANK);
        // 融合后 3 条候选（2 dense + 1 sparse，无重复）
        assertThat(counts.get(3)).isEqualTo(3);
    }

    @Test
    @DisplayName("关掉精排（评测用）：不报 rerank 阶段")
    void noRerankPhaseWhenDisabled() {
        when(dense.retrieve(anyString(), any(), anyInt())).thenReturn(ranked(1, 2, true));
        List<String> phases = new ArrayList<>();

        retriever.retrieve(req(false), qu(), null, (phase, count) -> phases.add(phase));

        assertThat(phases).doesNotContain(RetrievalProgress.PHASE_RERANK);
    }

    @Test
    @DisplayName("两路都没有候选：不报 rerank 阶段（没东西可精排）")
    void noRerankPhaseWhenNoCandidates() {
        List<String> phases = new ArrayList<>();

        retriever.retrieve(req(true), qu(), null, (phase, count) -> phases.add(phase));

        assertThat(phases).doesNotContain(RetrievalProgress.PHASE_RERANK);
    }

    @Test
    @DisplayName("不传 progress 也不炸（阶段 13 之前的所有调用点）")
    void nullProgressIsSafe() {
        when(dense.retrieve(anyString(), any(), anyInt())).thenReturn(ranked(1, 2, true));

        HybridRetriever.Result r = retriever.retrieve(req(true), qu(), null, null);

        assertThat(r.chunks()).isNotEmpty();
    }

    // ------------------------------------------------------------ helpers

    private static RetrievalRequest req(boolean useRerank) {
        return new RetrievalRequest("2025年营业收入", null, 0, 0, 0, 0, useRerank);
    }

    private static QueryUnderstanding qu() {
        return new QueryUnderstanding(QueryType.EXACT_FACT, "2025年营业收入", List.of(),
                new int[]{2025, 2025}, List.of("营业收入"), false, false, false, "LLM");
    }

    private static List<RetrievedChunk> ranked(int from, int count, boolean denseSide) {
        List<RetrievedChunk> out = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            long id = from + i;
            out.add(new RetrievedChunk(id, 1L, 7L, "TEXT", "第八节 财务报告", "章节" + id,
                    "正文" + id, 1, 1, null, denseSide ? i + 1 : null, denseSide ? null : i + 1, 0.0, null));
        }
        return out;
    }
}
