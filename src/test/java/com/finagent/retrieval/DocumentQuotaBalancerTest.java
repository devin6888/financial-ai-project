package com.finagent.retrieval;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 配额保底的算术与边界。
 *
 * <p>这里只测纯函数，不碰数据库 —— 配额的语义是「保证每份文档都有名额」，
 * 用谁的数据都一样，用真库反而会让断言依赖语料的具体分布。
 */
class DocumentQuotaBalancerTest {

    @Test
    @DisplayName("2 份文档、limit=50、候选 60 → 每份至少 25 条")
    void twoDocumentsGetHalfEach() {
        List<RetrievedChunk> fused = interleave(2, 30, 30);

        List<RetrievedChunk> out = DocumentQuotaBalancer.apply(fused, 50, 2);

        assertThat(out).hasSize(50);
        assertThat(perDocument(out)).allSatisfy((doc, n) -> assertThat(n).isGreaterThanOrEqualTo(25));
    }

    @Test
    @DisplayName("3 份文档、limit=50 → 每份至少 16 条，且至少两份拿到 17 条")
    void threeDocumentsGetAThirdEach() {
        List<RetrievedChunk> fused = interleave(3, 40, 40, 40);

        List<RetrievedChunk> out = DocumentQuotaBalancer.apply(fused, 50, 3);

        // 计划里写的是「每份至少 17」，但那在算术上不成立：ceil(50/3) = 17，17 × 3 = 51 > 50，
        // 必然有一份被 limit 切掉一条。真实可保证的下界是 floor(limit / N) = 16。
        assertThat(out).hasSize(50);
        assertThat(perDocument(out).values()).allSatisfy(n -> assertThat(n).isGreaterThanOrEqualTo(16));
        assertThat(perDocument(out).values().stream().filter(n -> n >= 17).count()).isGreaterThanOrEqualTo(2);
    }

    @Test
    @DisplayName("某份只有 3 条 → 不重复填，名额让给其它文档")
    void smallDocumentDoesNotStealSlots() {
        List<RetrievedChunk> fused = new ArrayList<>();
        fused.addAll(group(7, 30));
        fused.addAll(group(38, 3));

        List<RetrievedChunk> out = DocumentQuotaBalancer.apply(fused, 50, 2);

        assertThat(out).hasSize(33);   // 全部候选都装下，不会凭空造出 50 条
        assertThat(perDocument(out)).containsEntry(38L, 3L);
        assertThat(perDocument(out).get(38L)).isEqualTo(3);
        assertThat(out).extracting(RetrievedChunk::chunkId).doesNotHaveDuplicates();
    }

    @Test
    @DisplayName("候选本来就少于 limit → 原样返回（不重排）")
    void fewerCandidatesThanLimitReturnsAsIs() {
        List<RetrievedChunk> fused = interleave(2, 10, 10);

        assertThat(DocumentQuotaBalancer.apply(fused, 50, 2)).isSameAs(fused);
    }

    @Test
    @DisplayName("单文档不启用配额")
    void singleDocumentUntouched() {
        List<RetrievedChunk> fused = group(7, 60);

        assertThat(DocumentQuotaBalancer.apply(fused, 50, 1)).isSameAs(fused);
    }

    @Test
    @DisplayName("输出无重复 chunkId，长度不超过 limit")
    void noDuplicatesAndWithinLimit() {
        List<RetrievedChunk> fused = interleave(2, 30, 30);

        List<RetrievedChunk> out = DocumentQuotaBalancer.apply(fused, 50, 2);

        assertThat(out).extracting(RetrievedChunk::chunkId).doesNotHaveDuplicates();
        assertThat(out.size()).isLessThanOrEqualTo(50);
    }

    @Test
    @DisplayName("作用域说 2 份但实际只有 1 份有候选 → 不做配额（另一份是没召回，不是被截断）")
    void onlyOneDocumentHasCandidates() {
        List<RetrievedChunk> fused = group(7, 60);

        assertThat(DocumentQuotaBalancer.apply(fused, 50, 2)).isSameAs(fused);
    }

    // ------------------------------------------------------------ helpers

    /** 交错排列：模拟 RRF 之后两份文档的分数此起彼伏。 */
    private static List<RetrievedChunk> interleave(long docs, int... counts) {
        List<List<RetrievedChunk>> groups = new ArrayList<>();
        long[] ids = {7L, 38L, 99L};
        for (int d = 0; d < docs; d++) {
            groups.add(group(ids[d], counts[d]));
        }
        List<RetrievedChunk> out = new ArrayList<>();
        int max = java.util.Arrays.stream(counts).max().orElse(0);
        for (int i = 0; i < max; i++) {
            for (List<RetrievedChunk> g : groups) {
                if (i < g.size()) {
                    out.add(g.get(i));
                }
            }
        }
        return out;
    }

    private static List<RetrievedChunk> group(long documentId, int count) {
        List<RetrievedChunk> out = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            out.add(new RetrievedChunk(documentId * 1000 + i, 1L, documentId, "TEXT",
                    "第八节", "标题", "正文", 1, 1, null, i + 1, null, 0.0, null));
        }
        return out;
    }

    private static Map<Long, Long> perDocument(List<RetrievedChunk> chunks) {
        return chunks.stream().collect(Collectors.groupingBy(RetrievedChunk::documentId, Collectors.counting()));
    }
}
