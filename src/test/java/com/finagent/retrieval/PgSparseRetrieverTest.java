package com.finagent.retrieval;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Sparse 双模式的集成测试（真库）。
 *
 * <p>不需要调用 embedding API —— Sparse 路是纯 SQL。
 *
 * <p>命名不用 *IT（surefire 默认不含），否则永远不会在 mvn test 里跑。
 */
@Tag("db")
@SpringBootTest
@ActiveProfiles("test")
class PgSparseRetrieverTest {

    private static final long DOC = 7L;
    private static final RetrievalFilters F = RetrievalFilters.ofDocument(DOC);

    /** 用 [A] 标出会命中 0 条的写法，便于排查。 */
    @Autowired private PgSparseRetriever sparse;

    // ---------------- EXACT ----------------

    @Test
    void exactModeKeepsPerShareEarningsWhole() {
        // 实测：分词后命中 86 条，精确子串只有 6 条 —— 差 14 倍
        var hits = sparse.retrieve("每股收益", List.of("每股收益"), SparseMode.EXACT, F, 30);

        assertThat(hits).isNotEmpty();
        assertThat(hits).hasSizeLessThanOrEqualTo(6);
        assertThat(hits).allSatisfy(c -> assertThat(c.content()).contains("每股收益"));
    }

    @Test
    void exactModeRanksByHitCount() {
        // 命中两个实体的应排在只命中一个的前面。
        // 注意不能断言「每条都含每股收益」—— EXACT 是多词 OR，只含净利润的也会进来。
        var hits = sparse.retrieve("每股收益 净利润",
                List.of("每股收益", "净利润"), SparseMode.EXACT, F, 20);

        assertThat(hits).isNotEmpty();
        assertThat(hits).allSatisfy(c -> assertThat(
                c.content().contains("每股收益") || c.content().contains("净利润")).isTrue());

        // 排序契约：命中数必须单调不增
        int prev = Integer.MAX_VALUE;
        for (var c : hits) {
            int n = 0;
            if (c.content().contains("每股收益")) {
                n++;
            }
            if (c.content().contains("净利润")) {
                n++;
            }
            assertThat(n).as("命中数应单调不增").isLessThanOrEqualTo(prev);
            prev = n;
        }
    }

    @Test
    void exactModeEmptyTermsReturnsEmpty() {
        assertThat(sparse.retrieve("任意", List.of(), SparseMode.EXACT, F, 10)).isEmpty();
        assertThat(sparse.retrieve("任意", null, SparseMode.EXACT, F, 10)).isEmpty();
        // 全是空白也要过滤掉，不能拼出 '%%' 把全表拉进来
        assertThat(sparse.retrieve("任意", List.of("  "), SparseMode.EXACT, F, 10)).isEmpty();
    }

    @Test
    void exactModeMatchesCompanyName() {
        // 公司名是 Sparse 路的典型用途
        var hits = sparse.retrieve("东方财富", List.of("东方财富"), SparseMode.EXACT, F, 10);
        assertThat(hits).isNotEmpty();
        assertThat(hits).allSatisfy(c -> assertThat(c.content()).contains("东方财富"));
    }

    // ---------------- FTS_OR 的两种语义 ----------------

    @Test
    void andSemanticsReturnsZeroForNaturalLanguageQuestion() {
        // 这条是「为什么必须回退到 OR」的直接证据
        var and = sparse.runFts("2025年公司面临的主要风险有哪些", F, 10, false);
        assertThat(and).as("AND 语义对自然语言长问题应为空").isEmpty();
    }

    @Test
    void orSemanticsRecoversCandidatesForTheSameQuestion() {
        var or = sparse.runFts("2025年公司面临的主要风险有哪些", F, 30, true);
        assertThat(or).as("OR 语义应能召回").isNotEmpty();
        assertThat(or).hasSizeLessThanOrEqualTo(30);
    }

    @Test
    void shortQueryWorksUnderAndSemantics() {
        // 短查询用 AND 是好的（精度高），不该触发回退
        var and = sparse.runFts("净利润", F, 10, false);
        assertThat(and).isNotEmpty();
    }

    @Test
    void fallbackKicksInOnLongQuestion() {
        // 端到端：长问题最终必须返回结果（内部走了 AND -> OR 回退）
        var hits = sparse.retrieve("2025年公司面临的主要风险有哪些",
                List.of(), SparseMode.FTS_OR, F, 10);

        assertThat(hits).isNotEmpty();
        assertThat(hits).extracting(RetrievedChunk::sparseRank)
                .containsExactlyElementsOf(java.util.stream.IntStream
                        .rangeClosed(1, hits.size()).boxed().toList());
    }

    // ---------------- 过滤 ----------------

    @Test
    void filtersApplyToBothModes() {
        var prefix = new RetrievalFilters(List.of(DOC), null, null, null, "第一节", null);

        var exact = sparse.retrieve("释义", List.of("释义"), SparseMode.EXACT, prefix, 10);
        assertThat(exact).isNotEmpty();
        assertThat(exact).allSatisfy(c -> assertThat(c.sectionPath()).startsWith("第一节"));

        var fts = sparse.retrieve("释义", List.of(), SparseMode.FTS_OR, prefix, 10);
        assertThat(fts).allSatisfy(c -> assertThat(c.sectionPath()).startsWith("第一节"));
    }

    @Test
    void unknownDocumentYieldsNoHits() {
        var none = RetrievalFilters.ofDocument(999_999L);
        assertThat(sparse.retrieve("净利润", List.of("净利润"), SparseMode.EXACT, none, 10)).isEmpty();
        assertThat(sparse.retrieve("净利润", List.of(), SparseMode.FTS_OR, none, 10)).isEmpty();
    }
}
