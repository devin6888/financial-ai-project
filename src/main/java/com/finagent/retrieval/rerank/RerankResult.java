package com.finagent.retrieval.rerank;

/**
 * 一条重排结果。
 *
 * @param index          指向输入 documents 的下标
 * @param relevanceScore 相关性分数；<b>NaN 表示「这一轮没做精排」</b>（Noop 实现）。
 *                       用 NaN 而不是编造的 0.0，是为了让 trace 如实反映情况 ——
 *                       0.0 会被读成「模型认为完全不相关」，那是另一件事。
 */
public record RerankResult(int index, double relevanceScore) {
}
