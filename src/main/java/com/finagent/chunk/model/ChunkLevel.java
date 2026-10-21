package com.finagent.chunk.model;

/**
 * 切块层级。
 *
 * <p>Parent 与 Child 都存在 {@code document_chunk} 同一张表里，靠这个字段区分，
 * 并用 {@code parent_id} 自引用关联（§8.8）。
 */
public enum ChunkLevel {
    /** 上下文单元：完整语义小节 / 表格组。V1 **不建向量**（§8.6）。 */
    PARENT,
    /** 检索单元：Parent 内可独立召回的语义单元。V1 **只给它建向量**。 */
    CHILD
}
