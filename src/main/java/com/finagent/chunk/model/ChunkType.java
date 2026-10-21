package com.finagent.chunk.model;

/**
 * 切块内容形态。
 *
 * <p>注意：它描述的是**内容的主体形态**，不是"这个 Parent 里有没有表格"。
 * 一个章节同时有正文和表格时，Parent 的类型是 TEXT —— 表格另由 TABLE Child 承载
 *（评审 C11：初版把这种情况标成 TABLE，但 content 里只有正文）。
 */
public enum ChunkType {
    TEXT,
    TABLE,
    LIST
}
