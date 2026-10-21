package com.finagent.retrieval.rerank;

import java.util.List;
import java.util.stream.IntStream;

/**
 * 未配置 Rerank 时的兜底：<b>原样返回输入顺序</b>。
 *
 * <p>为什么需要它：没有它，未配置 key 的环境会因为缺少 RerankModel Bean 而启动失败；
 * 而 Rerank 只是可选增强，不该成为硬依赖。
 *
 * <p>分数用 {@link Double#NaN} 而不是编造的 0.0 —— 让 trace 能如实反映「这轮没精排」。
 */
public class NoopRerankModel implements RerankModel {

    /** 写进 trace 的模型名 */
    public static final String NAME = "none";

    @Override
    public String modelName() {
        return NAME;
    }

    @Override
    public List<RerankResult> rerank(String query, List<String> documents, int topN) {
        if (documents == null || documents.isEmpty() || topN <= 0) {
            return List.of();
        }
        int n = Math.min(topN, documents.size());
        return IntStream.range(0, n)
                .mapToObj(i -> new RerankResult(i, Double.NaN))
                .toList();
    }
}
