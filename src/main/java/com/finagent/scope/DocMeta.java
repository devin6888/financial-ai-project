package com.finagent.scope;

import java.time.LocalDate;

/**
 * 一份可检索文档的元数据快照。
 *
 * <p>为什么要 snapshot 而不是每次回查：作用域的解析结果会进 trace 与答案响应，
 * 一旦后来的请求改了 title 或重新解析导致 chunk 版本变化，历史记录就对不上了。
 * 快照里带 chunkVersion 正是为了这个 —— 引用要能指回「当时那一版」。
 *
 * @param chunkVersion 当前 active_chunk_version；解析中或失败时不存在，因此解析阶段已过滤
 */
public record DocMeta(long id, Long companyId, String companyCode, String companyName,
                      Integer docYear, String docType, LocalDate periodStart, LocalDate periodEnd,
                      int chunkVersion, String title) {

    /** 供日志与警告展示，例如「东方财富2024年年度报告（2024）」。 */
    public String label() {
        String name = title == null || title.isBlank() ? ("document#" + id) : title;
        return docYear == null ? name : name + "（" + docYear + "）";
    }

    /** 这份文档的期间是否覆盖某一年（用于判断「本期证据」）。 */
    public boolean coversYear(int year) {
        return docYear != null && docYear == year;
    }
}
