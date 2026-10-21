package com.finagent.chunk;

import com.finagent.chunk.model.Chunk;
import com.finagent.chunk.model.ChunkLevel;
import com.finagent.chunk.model.ChunkType;
import com.finagent.chunk.model.ParentGroup;
import com.finagent.document.DocumentModel;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 把 DocumentModel 归组成 Parent。
 *
 * <p><b>为什么按 section_path 归组</b>：阶段 2 已为每个 block/table 算好章节路径
 *（含坐标重排与编号规则重建层级），直接拿来当边界，复用那部分工作。
 *
 * <p><b>为什么截断到 {@link ChunkStrategy#PARENT_DEPTH} 层</b>：见 ChunkStrategy 的注释 ——
 * 按最深层级归组会让 Parent 退化成几十字的小节，实测中位仅 48 token，
 * 与 §8.2 的「完整语义小节（1500~3000）」严重不符。
 */
public final class ParentChunker {

    private static final String NO_SECTION_TITLE = "(无章节)";

    public static List<ParentGroup> split(DocumentModel doc) {
        Map<List<String>, List<DocumentModel.Block>> blocksByKey = new LinkedHashMap<>();
        Map<List<String>, List<DocumentModel.Table>> tablesByKey = new LinkedHashMap<>();
        Map<List<String>, Integer> firstPageByKey = new LinkedHashMap<>();

        if (doc.blocks() != null) {
            for (DocumentModel.Block b : doc.blocks()) {
                if (b.text() == null || b.text().isBlank()) {
                    continue;
                }
                List<String> key = parentKey(b.section_path());
                blocksByKey.computeIfAbsent(key, k -> new ArrayList<>()).add(b);
                int page = b.provenance() == null ? 0 : b.provenance().page_no();
                firstPageByKey.merge(key, page, Math::min);
            }
        }
        if (doc.tables() != null) {
            for (DocumentModel.Table t : doc.tables()) {
                List<String> key = parentKey(t.section_path());
                tablesByKey.computeIfAbsent(key, k -> new ArrayList<>()).add(t);
                firstPageByKey.merge(key, t.page_start(), Math::min);
            }
        }

        // 评审 C4 修正：统一按最小页码排序，而不是「正文先、仅表格的章节追加到末尾」。
        // 后者会让 chunk_index 顺序与原文完全脱节，阶段 4 的邻居扩展会取错人。
        List<List<String>> keys = new ArrayList<>(firstPageByKey.keySet());
        keys.sort(Comparator.comparingInt(firstPageByKey::get));

        List<ParentGroup> groups = new ArrayList<>();
        int index = 0;
        for (List<String> key : keys) {
            List<DocumentModel.Block> blocks = blocksByKey.getOrDefault(key, List.of());
            List<DocumentModel.Table> tables = tablesByKey.getOrDefault(key, List.of());
            if (blocks.isEmpty() && tables.isEmpty()) {
                continue;
            }

            StringBuilder sb = new StringBuilder();
            List<String> memberIds = new ArrayList<>();
            int minPage = Integer.MAX_VALUE;
            int maxPage = Integer.MIN_VALUE;

            for (DocumentModel.Block b : blocks) {
                sb.append(b.text().strip()).append('\n');
                memberIds.add(b.block_id());
                int page = b.provenance() == null ? 0 : b.provenance().page_no();
                minPage = Math.min(minPage, page);
                maxPage = Math.max(maxPage, page);
            }
            for (DocumentModel.Table t : tables) {
                // 表格完整数据在 TABLE Child 里；Parent 只记成员，避免重复存一份大内容
                memberIds.add(t.table_id());
                minPage = Math.min(minPage, t.page_start());
                maxPage = Math.max(maxPage, t.page_end());
            }

            String content = sb.toString().strip();
            if (content.isEmpty()) {
                content = "(本章节仅含表格：" + tables.size() + " 张)";
            }
            if (minPage == Integer.MAX_VALUE) {
                minPage = 0;
                maxPage = 0;
            }

            // Parent 的身份**就是**这个截断键（它本身就是一个二级小节），
            // 不能取首个成员的完整路径 —— 那会让 Parent 的 section_path 说成它只覆盖某个三级小节。
            List<String> path = key;
            Chunk parent = new Chunk(
                    ChunkLevel.PARENT,
                    // 评审 C11：同时有正文与表格时不能标成 TABLE —— 内容主体是正文。
                    blocks.isEmpty() ? ChunkType.TABLE : ChunkType.TEXT,
                    path,
                    path.isEmpty() ? NO_SECTION_TITLE : path.get(path.size() - 1),
                    content, minPage, maxPage,
                    String.join(",", memberIds), null,
                    TokenEstimator.estimate(content), index++,
                    null, null, null, null, null, null);
            groups.add(new ParentGroup(parent, blocks, tables));
        }
        return groups;
    }

    /** section_path 截断到 PARENT_DEPTH 层，作为归组键。 */
    static List<String> parentKey(List<String> sectionPath) {
        List<String> path = normalize(sectionPath);
        if (path.size() <= ChunkStrategy.PARENT_DEPTH) {
            return path;
        }
        return List.copyOf(path.subList(0, ChunkStrategy.PARENT_DEPTH));
    }

    private static List<String> normalize(List<String> path) {
        return path == null ? List.of() : path;
    }

    private ParentChunker() {
    }
}
