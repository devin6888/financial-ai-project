package com.finagent.citation;

import java.util.List;

/**
 * 一条可反解析的引用（§9.12）。
 *
 * <p>所有字段都由后端从 {@code ContextBlock} 取，<b>不采信模型输出</b>：
 * 模型只能给编号 [E3]，编号 → chunk 的映射在后端完成。这样「引用指向真实存在的块」
 * 是结构性保证，而不是靠校验去猜模型有没有编造。
 *
 * @param parentChunkId PARENT_WHOLE 块与历史数据可能没有父块 → 允许 null
 * @param bbox          单元素数组（库里是单框）；pdf 无坐标时为 null
 * @param tableRef      本阶段恒 null
 * @param evidenceType  TABLE / TEXT / NARRATIVE
 * @param scope         合并 / 母公司；判断不了为 null
 */
public record Citation(
        long documentId,
        Long parentChunkId,
        long childChunkId,
        Integer pageStart,
        Integer pageEnd,
        List<BBox> bbox,
        TableRef tableRef,
        String evidenceType,
        String unit,
        String scope,
        String docTitle,
        String sectionPath
) {
    public static final String TYPE_TABLE = "TABLE";
    public static final String TYPE_TEXT = "TEXT";
    public static final String TYPE_NARRATIVE = "NARRATIVE";

    public static final String SCOPE_CONSOLIDATED = "合并";
    public static final String SCOPE_PARENT = "母公司";
}
