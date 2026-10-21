package com.finagent.retrieval;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * 跨文档检索的配额保底（§9.1「证据多样性」）。
 *
 * <p><b>要解决的问题</b>：两份年报的语义相关度差得多时，RRF 融合后截 50 条进精排，
 * 弱的那份会被整份挤掉 —— 于是「对比 2024 与 2025 的营收」只拿到一份报告的证据，
 * 答案只能编。跨文档评测实测：无约束时 14/14 题都混合两份报告，
 * 一旦某份分数整体偏低就会滑到「只有一份」。
 *
 * <p><b>为什么必须在 Rerank 之前</b>：Rerank 是唯一能跨文档比较分数的环节。
 * 配额的意义是「保证每份文档都有样本进入精排」；放到精排之后，
 * 弱文档的候选已经被截掉了，再配也配不出来。
 *
 * <p><b>为什么是 ceil(limit / N)</b>：50 条 / 2 份 = 25 条，正好是「一半名额保底、
 * 一半按分数竞争」。配额给小了，弱文档只有 3~5 条，精排无法从中挑出真正相关的那块；
 * 给大了（比如 40），强文档就只剩 10 条，强证据反而被挤掉。
 */
public final class DocumentQuotaBalancer {

    private DocumentQuotaBalancer() {
    }

    /**
     * 按文档配额保留候选：每份文档先保底 {@code ceil(limit / 实际出现的文档数)} 条，剩余名额按融合顺序补。
     *
     * @param fused          RRF 融合后的候选（已按融合分降序）
     * @param limit          进精排的条数上限（fusionLimit）
     * @param documentCount  作用域内的文档数；&lt; 2 表示不启用配额
     * @return 去重后的候选，长度不超过 limit；不需要配额时原样返回
     */
    public static List<RetrievedChunk> apply(List<RetrievedChunk> fused, int limit, int documentCount) {
        if (fused == null || fused.isEmpty()) {
            return List.of();
        }
        if (limit <= 0 || documentCount < 2 || fused.size() <= limit) {
            return fused;
        }

        // 按融合顺序分组：组内顺序 = RRF 名次，所以「每份取前 quota 条」= 取该份融合分最高的那些
        Map<Long, List<RetrievedChunk>> byDocument = new LinkedHashMap<>();
        for (RetrievedChunk c : fused) {
            byDocument.computeIfAbsent(c.documentId(), k -> new ArrayList<>()).add(c);
        }
        // 实际只有一份文档有候选时，配额没有意义（另一份压根没召回，不是被截断）
        if (byDocument.size() < 2) {
            return fused;
        }

        int quota = (int) Math.ceil((double) limit / byDocument.size());
        LinkedHashSet<Long> picked = new LinkedHashSet<>();
        List<RetrievedChunk> out = new ArrayList<>(limit);

        for (List<RetrievedChunk> group : byDocument.values()) {
            // 必须带 out.size() < limit 这个条件：ceil(50/3) = 17，三份就是 51 条，
            // 超出 limit 一条 —— 只靠 quota 是压不住的（实测 51 vs 50 才发现的）。
            for (int i = 0; i < Math.min(quota, group.size()) && out.size() < limit; i++) {
                RetrievedChunk c = group.get(i);
                if (picked.add(c.chunkId())) {
                    out.add(c);
                }
            }
        }

        // 某份不足 quota 时名额让给其它文档：按原融合顺序补，不重复取同一条
        if (out.size() < limit) {
            for (RetrievedChunk c : fused) {
                if (out.size() >= limit) {
                    break;
                }
                if (picked.add(c.chunkId())) {
                    out.add(c);
                }
            }
        }
        return out;
    }
}
