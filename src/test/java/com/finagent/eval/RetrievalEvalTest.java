package com.finagent.eval;

import com.finagent.retrieval.HybridRetriever;
import com.finagent.retrieval.RetrievalFilters;
import com.finagent.retrieval.RetrievalRequest;
import com.finagent.retrieval.RetrievedChunk;
import com.finagent.scope.ResolvedScope;
import com.finagent.scope.ScopeResolver;
import com.finagent.understanding.RouterService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 检索层评测（Task 2）。
 *
 * <p>同时跑两条道：
 * <ul>
 *   <li><b>QU</b>：生产路径，问题先过 Query Understanding，年份/公司会变成过滤条件；</li>
 *   <li><b>NO-QU</b>：只带作用域 + 原始问题，作为消融基线。</li>
 * </ul>
 * 两条道的差值就是「QU 值不值得多花一次 LLM 调用」的直接证据（Task 7 的输入）。
 *
 * <p>不在这里断言门槛：门槛统一放 {@link EvalGateTest}，避免「跑一次评测改一处阈值」。
 */
@SpringBootTest
@ActiveProfiles("test")
@Tag("e2e")
class RetrievalEvalTest {

    @Autowired
    private HybridRetriever retriever;

    @Autowired
    private ScopeResolver scopeResolver;

    @Autowired
    private RouterService router;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    @DisplayName("30 题逐条打印召回名次，并汇总 Recall@5/10、DocRecall@10、MRR")
    void evaluateRetrieval() {
        List<GoldenSet.Item> items = GoldenSet.load();
        List<RetrievalMetrics.Case> withQu = new ArrayList<>();
        List<RetrievalMetrics.Case> withoutQu = new ArrayList<>();

        for (GoldenSet.Item item : items) {
            if (!item.answerable()) {
                continue;
            }
            ResolvedScope scope = scopeResolver.resolve(item.toChatScope());
            RetrievalRequest req = new RetrievalRequest(item.question(),
                    RetrievalFilters.ofDocuments(item.documentIds()), 30, 30, 50, 10, true);

            var qu = router.understand(item.question());
            HybridRetriever.Result rQu = retriever.retrieve(req, qu, scope);
            HybridRetriever.Result rNo = retriever.retrieveInScope(req, scope);

            // 证据 id 是历史版本固定的；重切后 id 会变，先换算成当前 ACTIVE 的等价块
            Set<Long> evidence = EvidenceResolver.resolveActiveIds(jdbc, item.evidenceChunkIds());
            int rankQu = firstHitRank(rQu.chunks(), evidence);
            int rankNo = firstHitRank(rNo.chunks(), evidence);
            List<Long> docsQu = distinctDocs(rQu.chunks());
            List<Long> docsNo = distinctDocs(rNo.chunks());

            withQu.add(new RetrievalMetrics.Case(item.id(), item.type(), rankQu, docsQu, item.expectedDocIds()));
            withoutQu.add(new RetrievalMetrics.Case(item.id(), item.type(), rankNo, docsNo, item.expectedDocIds()));

            System.out.printf("EVAL-RET|%s|%s|quRank=%d|noQuRank=%d|quDocs=%d|noQuDocs=%d|quWarn=%s|qu=%s/%s%n",
                    item.id(), item.type(), rankQu, rankNo, docsQu.size(), docsNo.size(),
                    rQu.trace() == null ? List.of() : rQu.trace().warnings(),
                    qu.queryType(), qu.route());
        }

        RetrievalMetrics.Summary sQu = RetrievalMetrics.summarize(withQu);
        RetrievalMetrics.Summary sNo = RetrievalMetrics.summarize(withoutQu);
        System.out.println("EVAL-RET-SUM|QU|" + sQu.line());
        System.out.println("EVAL-RET-SUM|NO-QU|" + sNo.line());
        System.out.println("EVAL-RET-SUM|QU-BYTYPE|" + byType(withQu));
        System.out.println("EVAL-RET-SUM|NO-QU-BYTYPE|" + byType(withoutQu));

        try {
            java.nio.file.Files.createDirectories(java.nio.file.Path.of("target"));
            java.nio.file.Files.writeString(java.nio.file.Path.of("target/eval-retrieval.json"),
                    "{\"qu\":" + sQu.toJson() + ",\"noQu\":" + sNo.toJson() + "}");
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }

        assertThat(sQu.cases()).isEqualTo(27);
        assertThat(withQu.stream().filter(c -> c.rank() > 0).count())
                .as("24 道可答题里至少 20 道能被检索到证据（低于这个数说明检索整体坏了）")
                .isGreaterThanOrEqualTo(20);
    }

    private static String byType(List<RetrievalMetrics.Case> cases) {
        StringBuilder sb = new StringBuilder();
        for (String type : List.of("METRIC", "NARRATIVE", "COMPARISON")) {
            List<RetrievalMetrics.Case> sub = cases.stream().filter(c -> c.type().equals(type)).toList();
            RetrievalMetrics.Summary s = RetrievalMetrics.summarize(sub);
            if (!sb.isEmpty()) {
                sb.append("; ");
            }
            sb.append(type).append(": ").append(s.line());
        }
        return sb.toString();
    }

    private static int firstHitRank(List<RetrievedChunk> chunks, Set<Long> evidence) {
        for (int i = 0; i < chunks.size(); i++) {
            if (evidence.contains(chunks.get(i).chunkId())) {
                return i + 1;
            }
        }
        return 0;
    }

    private static List<Long> distinctDocs(List<RetrievedChunk> chunks) {
        Set<Long> docs = new LinkedHashSet<>();
        for (RetrievedChunk c : chunks) {
            docs.add(c.documentId());
        }
        return List.copyOf(docs);
    }
}
