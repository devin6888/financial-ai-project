package com.finagent.retrieval;

import com.finagent.scope.ChatScope;
import com.finagent.scope.ResolvedScope;
import com.finagent.scope.ScopeResolver;
import com.finagent.understanding.QueryType;
import com.finagent.understanding.QueryUnderstanding;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.junit.jupiter.api.Tag;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 作用域在真实库上的效果：过滤条件到底进没进 SQL、回退时有没有越界。
 *
 * <p>为什么必须对真库跑一遍：ANY(?) 的 List 绑定、doc_type 过滤、空集哨兵
 * 这些全在 SQL 层，mock 掉仓储等于什么都没验。
 *
 * <p>库里两份文档：7 = 东方财富 2025 年报（654 CHILD），38 = 东方财富 2024 年报（630 CHILD）。
 */
@SpringBootTest
@ActiveProfiles("test")
@Tag("e2e")
class ScopeFilteringTest {

    private static final long DOC_2025 = 7L;
    private static final long DOC_2024 = 38L;

    @Autowired
    private ScopeResolver scopeResolver;

    @Autowired
    private HybridRetriever retriever;

    @Autowired
    private DenseRetriever dense;

    @Test
    @DisplayName("ofDocuments([7,38]) 能执行，且两份文档都有命中（钉死 ANY(?) 的 List 绑定）")
    void anyBindingReturnsBothDocuments() {
        List<RetrievedChunk> hits = dense.retrieve("分季度主要财务数据",
                RetrievalFilters.ofDocuments(List.of(DOC_2025, DOC_2024)), 50);

        assertThat(hits).isNotEmpty();
        Set<Long> docs = hits.stream().map(RetrievedChunk::documentId).collect(Collectors.toSet());
        assertThat(docs).isSubsetOf(Set.of(DOC_2025, DOC_2024));
        assertThat(docs).contains(DOC_2025, DOC_2024);
    }

    @Test
    @DisplayName("作用域=2025 年报，QU 抽出 2024 → 年份条件被丢弃，结果仍全部来自 2025 年报")
    void quYearOutOfScopeKeepsScope() {
        ResolvedScope scope = scopeResolver.resolve(ChatScope.documents(DOC_2025));
        QueryUnderstanding qu = manualQu(new int[]{2024, 2024});

        HybridRetriever.Result r = retriever.retrieve(RetrievalRequest.of("东方财富2024年营业收入是多少"),
                qu, scope);

        assertThat(r.trace().warnings()).contains("qu_year_out_of_scope:2024");
        assertThat(r.chunks()).isNotEmpty();
        assertThat(r.chunks()).allSatisfy(c -> assertThat(c.documentId()).isEqualTo(DOC_2025));
    }

    @Test
    @DisplayName("作用域=2024 年报，QU 抽出 2025 → 结果只会来自 2024，绝不返回 2025 的块")
    void scope2024NeverLeaks2025() {
        ResolvedScope scope = scopeResolver.resolve(ChatScope.documents(DOC_2024));
        QueryUnderstanding qu = manualQu(new int[]{2025, 2025});

        HybridRetriever.Result r = retriever.retrieve(RetrievalRequest.of("东方财富2025年营业收入是多少"),
                qu, scope);

        assertThat(r.trace().warnings()).contains("qu_year_out_of_scope:2025");
        assertThat(r.chunks()).allSatisfy(c -> assertThat(c.documentId()).isEqualTo(DOC_2024));
    }

    @Test
    @DisplayName("作用域确实收窄了：同一问题带 scope 时只返回该文档的块")
    void scopeNarrowsResults() {
        ResolvedScope scope = scopeResolver.resolve(ChatScope.documents(DOC_2024));

        HybridRetriever.Result r = retriever.retrieveInScope(RetrievalRequest.of("分季度主要财务数据"), scope);

        assertThat(r.chunks()).isNotEmpty();
        assertThat(r.chunks()).allSatisfy(c -> assertThat(c.documentId()).isEqualTo(DOC_2024));
    }

    @Test
    @DisplayName("无作用域 = 阶段 4 行为：跨两份文档照常检索（回归）")
    void noScopeKeepsPhase4Behaviour() {
        HybridRetriever.Result r = retriever.retrieve(RetrievalRequest.of("分季度主要财务数据"));

        assertThat(r.chunks()).isNotEmpty();
        assertThat(r.trace().warnings()).noneMatch(w -> w.startsWith("no_evidence_in_scope"));
    }

    @Test
    @DisplayName("docType=Q3 在库里没有数据 → 0 条且不抛异常")
    void docTypeWithoutDataReturnsEmpty() {
        RetrievalFilters q3 = new RetrievalFilters(null, null, null, "Q3", null, null);

        assertThat(dense.retrieve("分季度主要财务数据", q3, 10)).isEmpty();
        assertThat(dense.retrieve("分季度主要财务数据",
                new RetrievalFilters(null, null, null, "ANNUAL", null, null), 10)).isNotEmpty();
    }

    @Test
    @DisplayName("作用域内确实无证据 → 空结果 + no_evidence_in_scope，不放开作用域")
    void noEvidenceInsideScope() {
        ResolvedScope scope = scopeResolver.resolve(ChatScope.documents(DOC_2024));
        RetrievalRequest req = new RetrievalRequest("分季度主要财务数据",
                new RetrievalFilters(null, null, null, null, "第九章不存在的章节", null),
                0, 0, 0, 0, true);

        HybridRetriever.Result r = retriever.retrieveInScope(req, scope);

        assertThat(r.chunks()).isEmpty();
        assertThat(r.trace().warnings()).contains("no_evidence_in_scope");
    }

    /** 手造 QU：作用域规则的验证不该依赖 LLM 每次是否抽出同一个年份。 */
    private static QueryUnderstanding manualQu(int[] years) {
        return new QueryUnderstanding(QueryType.EXACT_FACT, "东方财富营业收入", List.of("300059"),
                years, List.of("营业收入"), false, false, null, false, "TEST");
    }
}
