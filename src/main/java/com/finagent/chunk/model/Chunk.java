package com.finagent.chunk.model;

import java.util.List;

/**
 * 切块的**中间产物**（尚未入库，此时还没有 id 与 embedding）。
 *
 * <p>刻意与数据库实体分开：切块是纯函数，不该知道自增主键、向量、事务这些东西。
 * 入库由 {@code ChunkPersister} 负责把父子关系与版本号补上。
 *
 * <p>字段顺序与设计文档 §8.8 对齐，便于逐条对照。
 *
 * @param level            PARENT / CHILD
 * @param type             TEXT / TABLE / LIST（内容主体形态）
 * @param sectionPath      自根到本文小节的完整路径（来自产物的 section_path，**不截断**）
 * @param title            本小节标题（sectionPath 的最后一段）
 * @param content          **原始**标准化内容 —— 事实源，不含为 embedding 拼接的上下文
 * @param pageStart        起始页（1 起始）
 * @param pageEnd          结束页
 * @param sourceBlockIds   回溯产物的 block_id / table_id，逗号分隔
 * @param bboxJson         页面矩形（JSON），供前端高亮
 * @param tokenCount       token 估算值
 * @param chunkIndex       同级内的顺序号（PARENT 与 CHILD 各自从 0 开始）
 * @param caption          表格题注
 * @param unit             计量单位，如「元」
 * @param currency         币种，如「CNY」
 * @param headersJson      表头（JSON 数组），表格分片时每片相同
 * @param relatedFootnoteIds 关联脚注的 block_id（本阶段只建列不写入）
 * @param sourceOffset     字符级偏移（本阶段只建列不写入）
 */
public record Chunk(
        ChunkLevel level,
        ChunkType type,
        List<String> sectionPath,
        String title,
        String content,
        int pageStart,
        int pageEnd,
        String sourceBlockIds,
        String bboxJson,
        int tokenCount,
        int chunkIndex,
        String caption,
        String unit,
        String currency,
        String headersJson,
        String relatedFootnoteIds,
        String sourceOffset
) {

    /**
     * section_path 的存储形式。
     *
     * <p>用 {@code §} 分隔而不是 {@code >}：章节标题本身可能含 {@code >}（如「A&nbsp;>&nbsp;B 类业务」），
     * 而 {@code §} 在中文财报里几乎不会出现在正文中。前缀 LIKE 查询也因此更安全。
     */
    /**
     * 换一个 chunk_index 返回新实例（record 不可变）。
     *
     * <p>为什么需要：切块时 {@code chunkIndex} 是**每个 Parent 内部**从 0 编号的
     *（对 sibling 扩展最自然），但入库时要求它在版本内**全局唯一** ——
     * 否则不同 Parent 的 0 号 Child 会撞 {@code uq_chunk_version_index}。
     * 入库前统一重编号，见 {@code WritePlan.assemble}。
     */
    public Chunk withChunkIndex(int newIndex) {
        return new Chunk(level, type, sectionPath, title, content, pageStart, pageEnd,
                sourceBlockIds, bboxJson, tokenCount, newIndex, caption, unit, currency,
                headersJson, relatedFootnoteIds, sourceOffset);
    }

    public String sectionPathText() {
        return sectionPath == null || sectionPath.isEmpty()
                ? null
                : String.join(" § ", sectionPath);
    }
}
