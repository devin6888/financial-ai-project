package com.finagent.retrieval;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DenseRetriever：SQL 拼接的单测 + 真库真向量的集成验证。
 *
 * <p>命名不用 *IT（surefire 默认不含），否则永远不会在 mvn test 里跑。
 */
@Tag("db")
@SpringBootTest
@ActiveProfiles("test")
class DenseRetrieverTest {

    @Autowired private DenseRetriever dense;

    // ---------- appendFilters：纯逻辑，不碰库 ----------

    @Test
    void appendFiltersAddsNothingWhenAllNull() {
        StringBuilder sql = new StringBuilder("WHERE 1=1");
        List<Object> args = new ArrayList<>();
        DenseRetriever.appendFilters(sql, args, RetrievalFilters.none());
        assertThat(sql.toString()).isEqualTo("WHERE 1=1");
        assertThat(args).isEmpty();
    }

    @Test
    void appendFiltersAddsEachConditionInOrder() {
        StringBuilder sql = new StringBuilder();
        List<Object> args = new ArrayList<>();
        DenseRetriever.appendFilters(sql, args,
                new RetrievalFilters(List.of(7L, 38L), 1L, 2025, "ANNUAL", "第八节", 2));

        // 文档集合说明：ANY(?) 绑 List 在 PG 驱动上直接报「无法推测 SQL 类型」，
        // 所以走的是 IN (?,?,...) 占位符（见 ST5 偏差登记）
        assertThat(sql.toString())
                .contains("dc.document_id IN (?,?)")
                .contains("d.company_id = ?")
                .contains("d.doc_year = ?")
                .contains("d.doc_type = ?")
                .contains("dc.section_path LIKE ?");
        assertThat(args).containsExactly(7L, 38L, 1L, 2025, "ANNUAL", "第八节%");
    }

    @Test
    void blankSectionPrefixIsIgnored() {
        StringBuilder sql = new StringBuilder();
        List<Object> args = new ArrayList<>();
        DenseRetriever.appendFilters(sql, args,
                new RetrievalFilters(null, null, null, null, "   ", null));
        assertThat(sql.toString()).isEmpty();
        assertThat(args).isEmpty();
    }

    // ---------- 真库真向量 ----------

    @Test
    void retrievesActiveChildrenOrderedBySimilarity() {
        var hits = dense.retrieve("净利润", RetrievalFilters.ofDocument(7L), 30);

        assertThat(hits).hasSize(30);
        // 名次必须是 1..30 连续
        assertThat(hits).extracting(RetrievedChunk::denseRank)
                .containsExactlyElementsOf(java.util.stream.IntStream.rangeClosed(1, 30)
                        .boxed().toList());
        // 全部是 CHILD，且都带内容
        assertThat(hits).allSatisfy(c -> {
            assertThat(c.chunkId()).isPositive();
            assertThat(c.content()).isNotBlank();
            assertThat(c.sparseRank()).isNull();
        });
    }

    @Test
    void respectsSectionPathPrefix() {
        var hits = dense.retrieve("营业收入", 
                new RetrievalFilters(List.of(7L), null, null, null, "第八节", null), 10);

        assertThat(hits).isNotEmpty();
        assertThat(hits).allSatisfy(c ->
                assertThat(c.sectionPath()).startsWith("第八节"));
    }

    @Test
    void unknownDocumentYieldsNoHits() {
        assertThat(dense.retrieve("净利润", RetrievalFilters.ofDocument(999_999L), 10)).isEmpty();
    }
}
