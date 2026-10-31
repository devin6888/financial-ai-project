package com.finagent.agent;

import com.finagent.retrieval.RetrievalFilters;
import com.finagent.retrieval.RetrievalRequest;

/**
 * 一条检索车道：一次带定向过滤的检索。
 *
 * <p><b>为什么把「一路检索」从字符串升级成车道</b>：ST15 的多路只是「多问几句」，所有路共享同一套过滤条件
 * （作用域 + QU 的年份）。跨年问题因此变成「不加年份过滤」—— 能不能拿到 2024 年报的段落，
 * 取决于配额均衡器有没有顺手带进来，这是运气不是设计（ST16 §3.1）。
 * 车道的区别只有两点：<b>文本</b> 与 <b>过滤条件</b>，两者一起决定这一路召回什么。
 *
 * @param id            车道标识，进 trace 的 detail.lanes（full / sub:0 / year:2024 / attr）
 * @param text          检索文本（整句、子问题或整句本身）
 * @param docYear       非空时只在这个年份的文档里检索
 * @param sectionPrefix 非空时只在章节路径以此开头的块里检索（如「第三节」）
 * @param topK          这一路要几条（Rerank 之后的条数）
 */
record RetrievalLane(String id, String text, Integer docYear, String sectionPrefix, int topK) {

    /** 整句的车道标识。 */
    static final String ID_FULL = "full";
    /** 归因/结构车道的标识（章节定向）。 */
    static final String ID_SECTION = "attr";

    static RetrievalLane full(String text) {
        return new RetrievalLane(ID_FULL, text, null, null, RetrievalRequest.DEFAULT_TOP_RERANK);
    }

    static RetrievalLane sub(int index, String text) {
        return new RetrievalLane("sub:" + index, text, null, null, RetrievalRequest.DEFAULT_TOP_RERANK);
    }

    static RetrievalLane year(int year, String text, int topK) {
        return new RetrievalLane("year:" + year, text, year, null, topK);
    }

    static RetrievalLane section(String text, int topK) {
        return new RetrievalLane(ID_SECTION, text, null, RetrievalLanes.SECTION_PREFIX, topK);
    }

    boolean hasFilter() {
        return docYear != null || (sectionPrefix != null && !sectionPrefix.isBlank());
    }

    /**
     * 转成检索请求。
     *
     * <p>注意 topK 放在 topRerank 上：车道的条数是「这一路最终给我几条证据」，
     * 而 Dense/Sparse 的召回宽度是全局配额，不该被车道改小。
     */
    RetrievalRequest toRequest() {
        RetrievalFilters filters = hasFilter()
                ? new RetrievalFilters(null, null, docYear, null, sectionPrefix, null)
                : RetrievalFilters.none();
        return new RetrievalRequest(text, filters, 0, 0, 0, topK, true);
    }
}
