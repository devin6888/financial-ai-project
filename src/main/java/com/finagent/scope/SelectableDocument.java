package com.finagent.scope;

/**
 * 前端「选择文档」用的列表项。
 *
 * @param chunkCount ACTIVE 版本的 CHILD 数（不是总行数，含 Parent 会虚高一倍）
 */
public record SelectableDocument(
        // 文档id
        long id,
        // 文档标题
        String title,
        // 所属公司编码
        String companyCode,
        String companyName,
        Integer docYear,
        String docType,
        Integer pageCount,
        long chunkCount
) {
}
