package com.finagent.retrieval;

import java.util.List;

/**
 * 检索的硬过滤条件（§9.1：结构化过滤先于相似度）。
 *
 * <p>所有字段可空 —— 为空的项不加条件。
 *
 * <p><b>为什么 documentId 改成了 documentIds</b>：阶段 5 引入了作用域，
 * 「本轮只看这两份年报」是常态（A 模式 / B 模式解析后都是一组 id）。
 * 单值字段没法表达，只能退化成「多跑几次再合并」—— 那会让 RRF 的名次失去意义。
 *
 * <p><b>为什么不加 periodFrom / periodTo</b>（见 ST5 偏差登记 #1）：
 * 检索只需要年份与报告类型就能定位；报告期（1/1~12/31）的真正用途是
 * 判断某条证据是不是「本期」，那是引用阶段的事，放进 WHERE 只会多个恒为 null 的条件。
 *
 * <p><b>为什么要有这个类</b>：Dense 与 Sparse 两路必须用<b>完全相同</b>的过滤条件，
 * 否则两路的候选集不同源，RRF 融合会引入偏差。把条件集中在一处，
 * 两路都从它生成 SQL 片段（见 DenseRetriever.appendFilters）。
 *
 * <p>注意：权限（tenant / user ACL）不在本阶段（阶段 8）。本类只做业务过滤。
 */
public record RetrievalFilters(
        /** 限定文档集合；空表示不限（全库） */
        List<Long> documentIds,
        Long companyId,
        Integer docYear,
        /** 报告类型：ANNUAL / SEMI_ANNUAL / Q1 / Q3，见 V6 迁移的 CHECK 约束 */
        String docType,
        /** 章节路径前缀，如「第八节」—— 把检索限制在某一章 */
        String sectionPathPrefix,
        /** 只看某个 chunk 版本；为空则用 document.active_chunk_version */
        Integer chunkVersion
) {
    public RetrievalFilters {
        documentIds = documentIds == null ? List.of()
                : documentIds.stream().filter(java.util.Objects::nonNull).distinct().toList();
    }

    public static RetrievalFilters none() {
        return new RetrievalFilters(null, null, null, null, null, null);
    }

    public static RetrievalFilters ofDocument(long documentId) {
        return new RetrievalFilters(List.of(documentId), null, null, null, null, null);
    }

    public static RetrievalFilters ofDocuments(List<Long> documentIds) {
        return new RetrievalFilters(documentIds, null, null, null, null, null);
    }

    /** 只替换文档集合，其余条件保持不变（作用域覆盖过滤条件时用）。 */
    public RetrievalFilters withDocumentIds(List<Long> ids) {
        return new RetrievalFilters(ids, companyId, docYear, docType, sectionPathPrefix, chunkVersion);
    }
}
