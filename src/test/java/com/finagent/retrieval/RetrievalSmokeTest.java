package com.finagent.retrieval;

import com.finagent.understanding.QueryUnderstanding;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 冒烟评测：10 个真实问题，看排序对不对（阶段 6 评测集的雏形）。
 *
 * <p><b>刻意不写「top-1 必须命中 X」这类断言</b>（§9.10 的 Step 3）：现在是 0 条标注数据，
 * 自动断言只会得到一个自欺欺人的绿灯 —— 把「我认为对的」写成「系统必须的」。
 * 所以本类只守不变量（每条都要有结果、trace 与结果一致、结果都来自同一份文档），
 * 相关性由人看着 top-3 判，结论写进 docs/阶段4-检索报告.md。
 *
 * <p>跑法（默认被 pom 的 excludedGroups 排除）：
 *
 * <pre>
 * mvn -B "-Dsurefire.excludedGroups=" "-Dgroups=e2e" "-Dtest=RetrievalSmokeTest" test
 * </pre>
 */
@SpringBootTest
@ActiveProfiles("test")
@Tag("e2e")
class RetrievalSmokeTest {

    private static final long DOC = 7L;
    private static final RetrievalFilters F = RetrievalFilters.ofDocument(DOC);

    /** 三类问题的代表：数值/事实、精确词、语义/解释。 */
    private static final List<String> QUESTIONS = List.of(
            "2025年营业收入是多少",
            "公司2025年的净利润是多少",
            "东方财富2025年每股收益是多少",
            "东方财富的公司注册地址在哪里",
            "公司的股票代码和注册资本",
            "东方财富的法定代表人是谁",
            "公司面临的主要风险有哪些",
            "公司的业务模式是怎样的",
            "研发投入情况如何",
            "公司未来的发展战略是什么");

    @Autowired private HybridRetriever retriever;

    @Test
    void printTop3WithAndWithoutQueryUnderstanding() {
        System.out.println("=== SMOKE BEGIN ===");
        int i = 0;
        for (String q : QUESTIONS) {
            i++;
            var req = RetrievalRequest.of(q, F);

            // 两遍：一遍走 QU，一遍显式关掉（qu == null），其余参数完全相同
            var withQu = retriever.retrieve(req);
            var noQu = retriever.retrieve(req, (QueryUnderstanding) null);

            print(i, q, "QU   ", withQu);
            print(i, q, "NO-QU", noQu);

            // 不变量：两遍都必须有结果，且 trace 与结果自洽
            assertThat(withQu.chunks()).as("第 " + i + " 题（有 QU）不应为空").isNotEmpty();
            assertThat(noQu.chunks()).as("第 " + i + " 题（无 QU）不应为空").isNotEmpty();
            assertThat(withQu.trace().returned()).isEqualTo(withQu.chunks().size());
            assertThat(withQu.chunks()).allSatisfy(c -> assertThat(c.documentId()).isEqualTo(DOC));
        }
        System.out.println("=== SMOKE END ===");
    }

    private static void print(int idx, String q, String mode, HybridRetriever.Result r) {
        var t = r.trace();
        System.out.println("SMOKE|" + idx + "|" + mode + "|" + q + "|" + t.summary()
                + "|dense=" + t.denseMs() + "ms sparse=" + t.sparseMs() + "ms fuse=" + t.fuseMs()
                + "ms rerank=" + t.rerankMs() + "ms");
        List<RetrievedChunk> top = r.chunks().subList(0, Math.min(3, r.chunks().size()));
        for (int k = 0; k < top.size(); k++) {
            RetrievedChunk c = top.get(k);
            String text = c.content() == null ? "" : c.content().replace('\n', ' ');
            if (text.length() > 110) {
                text = text.substring(0, 110);
            }
            System.out.println("SMOKE|" + idx + "|" + mode + "|#" + (k + 1)
                    + "|id=" + c.chunkId()
                    + "|" + c.chunkType()
                    + "|p" + c.pageStart()
                    + "|dense=" + c.denseRank() + " sparse=" + c.sparseRank()
                    + " rrf=" + String.format("%.4f", c.rrfScore())
                    + " rerank=" + (c.rerankScore() == null ? "-" : String.format("%.3f", c.rerankScore()))
                    + "|" + c.sectionPath()
                    + "|" + text);
        }
    }
}
