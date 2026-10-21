package com.finagent.retrieval;

/**
 * Sparse 路的两种模式，由 Query Understanding 的结果决定。
 *
 * <p>为什么需要两种：<b>实测发现单靠分词行不通</b> ——
 * <ul>
 *   <li>「每股收益」被 zhparser 切成「收益」（每股 被标为代词后丢弃），
 *       全文检索命中 86 条，而精确子串只要 6 条</li>
 *   <li>自然语言长问题用 AND 语义（plainto_tsquery）会命中 <b>0 条</b></li>
 * </ul>
 */
public enum SparseMode {
    /**
     * 精确子串：QU 抽到了实体（指标名 / 公司代码）。
     *
     * <p>走 content ILIKE，<b>绕开分词器</b>，用 GIN(gin_trgm_ops) 索引。
     */
    EXACT,
    /**
     * 词级召回：QU 没抽到实体（退化成自然语言问题）。
     *
     * <p>先试 AND（精度高），命中 0 条再退到 OR（保召回）。
     */
    FTS_OR
}
