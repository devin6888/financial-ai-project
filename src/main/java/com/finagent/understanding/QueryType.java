package com.finagent.understanding;

/**
 * 查询类型（§9.2.1）。
 *
 * <p>四类不是拍脑袋分的，而是按<b>证据在语料里的分布形态</b>推出的：
 * <ul>
 *   <li>点状（单点）→ ExactFact：答案就是一个数值或一条定义</li>
 *   <li>团状（局部连续）→ SemanticExplanation：答案需要相邻若干段落连起来读</li>
 *   <li>离散多点（跨维度）→ Comparison：证据分布在不相邻位置</li>
 *   <li>链状（需迭代发现）→ ComplexAnalysis：后一步检索依赖前一步结论</li>
 * </ul>
 *
 * <p>最后一类不是「另一种策略」，而是<b>单轮 RAG 的能力边界</b> —— 它是多跳问题，
 * 单轮结构上做不到，应交给 Agent 编排。
 */
public enum QueryType {
    EXACT_FACT,
    SEMANTIC_EXPLANATION,
    COMPARISON,
    COMPLEX_ANALYSIS;

    /**
     * 把 LLM 返回的字符串转成枚举。
     *
     * <p>非法值返回 {@code null} 而不是抛异常 —— 由调用方决定「降级」还是「丢弃该字段」，
     * 因为 §9.14 要求字段级容错，不该让一个坏枚举炸掉整个结果。
     */
    public static QueryType parse(String raw) {
        if (raw == null) {
            return null;
        }
        try {
            return valueOf(raw.strip().toUpperCase().replace('-', '_'));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
