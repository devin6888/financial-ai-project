package com.finagent.chunk;

import com.finagent.chunk.model.Chunk;
import com.finagent.chunk.model.ChunkLevel;
import com.finagent.chunk.model.ChunkType;
import com.finagent.chunk.model.ParentGroup;
import com.finagent.document.DocumentModel;

import java.util.ArrayList;
import java.util.List;

/**
 * Parent -> Child 切分。TEXT/LIST 与 TABLE 走**不同策略**（§8.5 明确要求）。
 *
 * <p><b>算法在「片段」层面统一</b>：先把每个 block 拆成 ≤ CHILD_MAX 的片段
 * （超长块按句子边界二次切分），再把片段累积成 Child。这样「正常累积」与「超长块切分」
 * 共用同一套收口逻辑，不会出现两条路径行为不一致。
 */
public final class ChildChunker {

    /**
     * 一个可累积的最小单元。
     *
     * <p>为什么不直接用 Block：一个 block 可能需要被切成多个片段
     *（设计文档 §8.5「过长时优先按 paragraph/sentence 边界拆分」），
     * 切开后它们仍属于同一个 block_id。
     */
    record Piece(String blockId, String text, int page, String label, DocumentModel.BBox bbox) {
    }

    /**
     * TEXT / LIST 切分。
     *
     * @throws IllegalArgumentException Parent 没有正文块（表格走 splitTable）
     */
    public static List<Chunk> splitText(ParentGroup group) {
        List<DocumentModel.Block> blocks = group.blocks();
        if (blocks == null || blocks.isEmpty()) {
            return List.of();
        }

        // 1) 展开成片段：超长块按句子切
        List<Piece> pieces = new ArrayList<>();
        for (DocumentModel.Block b : blocks) {
            int page = b.provenance() == null ? 0 : b.provenance().page_no();
            DocumentModel.BBox bbox = b.provenance() == null ? null : b.provenance().bbox();
            for (String seg : splitToMax(b.text(), ChunkStrategy.CHILD_MAX_TOKENS)) {
                pieces.add(new Piece(b.block_id(), seg, page, b.label(), bbox));
            }
        }

        // 2) 累积成 Child
        List<Chunk> children = new ArrayList<>();
        int index = 0;
        int start = 0;
        while (start < pieces.size()) {
            int end = start;
            int tokens = 0;
            while (end < pieces.size()) {
                int t = TokenEstimator.estimate(pieces.get(end).text());
                if (end > start && tokens + t > ChunkStrategy.CHILD_MAX_TOKENS) {
                    break;
                }
                tokens += t;
                end++;
                if (tokens >= ChunkStrategy.CHILD_MIN_TOKENS) {
                    break;
                }
            }

            children.add(toChild(group, pieces.subList(start, end), index++));

            int lastIdx = end - 1;
            int lastTokens = TokenEstimator.estimate(pieces.get(lastIdx).text());
            // 【评审 B1】必须同时满足 lastIdx > start，否则当一片只有 1 个片段时
            // start 会被设回原值 → 死循环（真实数据 [29, 709] 命中）。
            if (end < pieces.size() && lastIdx > start
                    && lastTokens <= ChunkStrategy.TEXT_OVERLAP_TOKENS * 2) {
                start = lastIdx;      // 块级 overlap：下一片从这块重新开始
            } else {
                start = end;
            }
        }
        return children;
    }

    /**
     * 把一段文本切成每段 ≤ maxTokens 的片段。
     *
     * <p>设计依据 §8.5：「Child 默认 300~700 tokens，**过长时优先按 paragraph/sentence 边界拆分**」。
     * 真实数据里存在 709 token 的单块 —— 不切就会产生超限 Child，验收 #3 的
     * 「100% ≤ 700」也就做不到。
     *
     * <p>策略：
     * <ol>
     *   <li>不超限 → 原样返回（绝大多数情况）</li>
     *   <li>超限 → 先按句末标点切句，再贪心合并成 ≤ max 的片段</li>
     *   <li>单句仍超限 → 退回按字符硬切（避免死循环）</li>
     * </ol>
     */
    static List<String> splitToMax(String text, int maxTokens) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        String trimmed = text.strip();
        if (TokenEstimator.estimate(trimmed) <= maxTokens) {
            return List.of(trimmed);
        }

        List<String> sentences = splitSentences(trimmed);
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        int curTokens = 0;
        for (String s : sentences) {
            int t = TokenEstimator.estimate(s);
            if (t > maxTokens) {
                // 单句就超限：先冲刷当前片段，再把这句按字符硬切
                if (curTokens > 0) {
                    out.add(cur.toString().strip());
                    cur.setLength(0);
                    curTokens = 0;
                }
                out.addAll(hardSplit(s, maxTokens));
                continue;
            }
            if (curTokens > 0 && curTokens + t > maxTokens) {
                out.add(cur.toString().strip());
                cur.setLength(0);
                curTokens = 0;
            }
            cur.append(s);
            curTokens += t;
        }
        if (curTokens > 0) {
            out.add(cur.toString().strip());
        }
        return out.stream().filter(x -> !x.isBlank()).toList();
    }

    /** 按中文句末标点（及换行）切句，标点跟随前一句。 */
    static List<String> splitSentences(String text) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            cur.append(c);
            if (c == '。' || c == '；' || c == '！' || c == '？'
                    || c == '!' || c == '?' || c == ';' || c == '\n') {
                out.add(cur.toString());
                cur.setLength(0);
            }
        }
        if (!cur.isEmpty()) {
            out.add(cur.toString());
        }
        return out;
    }

    /** 兜底：单句超限时按字符切（宁可切断一句话，也不要产生无限大的 Child）。 */
    static List<String> hardSplit(String text, int maxTokens) {
        List<String> out = new ArrayList<>();
        // 中文一字约一 token，按 maxTokens 个字符切足够接近
        int step = Math.max(1, maxTokens);
        for (int i = 0; i < text.length(); i += step) {
            out.add(text.substring(i, Math.min(text.length(), i + step)));
        }
        return out;
    }

    static Chunk toChild(ParentGroup group, List<Piece> slice, int index) {
        StringBuilder sb = new StringBuilder();
        List<String> ids = new ArrayList<>();
        List<DocumentModel.BBox> boxes = new ArrayList<>();
        int minPage = Integer.MAX_VALUE;
        int maxPage = Integer.MIN_VALUE;
        boolean hasList = false;

        for (Piece p : slice) {
            sb.append(p.text().strip()).append('\n');
            if (!ids.contains(p.blockId())) {
                ids.add(p.blockId());     // 同一 block 被切成多段时只记一次
            }
            boxes.add(p.bbox());
            minPage = Math.min(minPage, p.page());
            maxPage = Math.max(maxPage, p.page());
            if ("list_item".equals(p.label())) {
                hasList = true;
            }
        }
        String content = sb.toString().strip();

        return new Chunk(
                ChunkLevel.CHILD,
                // 评审 C10：真实数据有 137 个 list_item，不能一律标 TEXT
                hasList ? ChunkType.LIST : ChunkType.TEXT,
                group.parent().sectionPath(), group.parent().title(), content,
                minPage == Integer.MAX_VALUE ? 0 : minPage,
                maxPage == Integer.MIN_VALUE ? 0 : maxPage,
                String.join(",", ids),
                // 评审 C6：bbox 必须有写入路径 —— 取成员片段 bbox 的并集
                ChunkGeometry.unionBbox(boxes),
                TokenEstimator.estimate(content), index,
                null, null, null, null, null, null);
    }

    /**
     * TABLE 切分：按**渲染后的 token 预算**切行组，每片重复表头与题注。
     *
     * <p><b>为什么不用行数阈值</b>（评审 C8）：初版定的「每 40 行一片」对真实数据永不触发
     * —— 345 张表里最大就是 40 行，而有 25 张渲染后超过 700 token（最大约 3886）。
     * 行数与 token 数不成正比，所以要按<b>渲染结果</b>切。
     *
     * <p>§8.2 硬要求：大表按行组切 Child，并<b>重复表头</b>。
     */
    public static List<Chunk> splitTable(ParentGroup group, DocumentModel.Table table) {
        List<List<String>> grid = TableRenderer.toGrid(table);
        String caption = table.caption() == null ? "" : table.caption().strip();
        int cols = TableRenderer.colsOf(grid);
        List<String> header = grid.isEmpty() ? List.of() : grid.get(0);

        List<Chunk> children = new ArrayList<>();
        if (grid.size() <= 1) {
            // 只有表头、或完全没有行：整体成一片，别返回空
            children.add(tableChild(group, table, TableRenderer.toMarkdown(table), 0));
            return children;
        }

        // 预算必须按**渲染后的字符串**算（含竖线与分隔行）——
        // 按单元格文本算会漏掉 markdown 的 | 与 ---，实测导致分片后仍出现 756 token 的超限片。
        int headerTokens = TokenEstimator.estimate(caption)
                + TokenEstimator.estimate(TableRenderer.rowToMarkdown(header, cols))
                + TokenEstimator.estimate(TableRenderer.separatorRow(cols));
        int index = 0;
        int from = 1;                      // 第 0 行是表头，不作为数据行
        while (from < grid.size()) {
            int to = from;
            int tokens = headerTokens;
            while (to < grid.size()) {
                int rowTokens = TokenEstimator.estimate(
                        TableRenderer.rowToMarkdown(grid.get(to), cols));
                if (to > from && tokens + rowTokens > ChunkStrategy.CHILD_MAX_TOKENS) {
                    break;
                }
                tokens += rowTokens;
                to++;
            }
            children.add(tableChild(group, table,
                    renderRows(caption, header, grid, cols, from, to), index++));
            from = to;
        }
        return children;
    }

    private static String renderRows(String caption, List<String> header,
                                     List<List<String>> grid, int cols, int from, int to) {
        StringBuilder sb = new StringBuilder();
        if (!caption.isEmpty()) {
            sb.append(caption).append('\n');
        }
        sb.append(TableRenderer.rowToMarkdown(header, cols)).append('\n');
        sb.append(TableRenderer.separatorRow(cols)).append('\n');   // 每片重复表头
        for (int r = from; r < to; r++) {
            sb.append(TableRenderer.rowToMarkdown(grid.get(r), cols)).append('\n');
        }
        return sb.toString().strip();
    }

    private static Chunk tableChild(ParentGroup group, DocumentModel.Table table,
                                    String content, int index) {
        TableMeta meta = TableMeta.fromCaption(table.caption());
        return new Chunk(
                ChunkLevel.CHILD, ChunkType.TABLE,
                group.parent().sectionPath(), group.parent().title(), content,
                table.page_start(), table.page_end(),
                table.table_id(), bboxJson(table.bbox()),
                TokenEstimator.estimate(content), index,
                table.caption(), meta.unit(), meta.currency(),
                headersJson(table), null, null);
    }

    private static String bboxJson(DocumentModel.BBox b) {
        return b == null ? null : "{\"left\":%s,\"top\":%s,\"right\":%s,\"bottom\":%s}"
                .formatted(b.left(), b.top(), b.right(), b.bottom());
    }

    /** 表头单元格的 JSON 数组，供阶段 4 的 Context Packing 与 Rerank 使用（§8.8）。 */
    static String headersJson(DocumentModel.Table table) {
        if (table.cells() == null) {
            return null;
        }
        StringBuilder sb = new StringBuilder("[");
        boolean first = true;
        for (DocumentModel.Cell c : table.cells()) {
            if (c.row() != 0) {
                continue;
            }
            if (!first) {
                sb.append(',');
            }
            first = false;
            sb.append('"').append(c.text() == null ? ""
                    : c.text().replace("\\", "\\\\").replace("\"", "\\\""))
              .append('"');
        }
        return sb.append(']').toString();
    }

    /**
     * 按 Parent 的内容形态分派 —— <b>正文与表格都要产出 Child</b>。
     *
     * <p>初版只走一个分支，会<b>丢掉 21.9% 的正文</b>（评审发现）。
     */
    public static List<Chunk> splitAll(ParentGroup group) {
        List<Chunk> children = new ArrayList<>();
        if (group.blocks() != null && !group.blocks().isEmpty()) {
            children.addAll(splitText(group));
        }
        if (group.tables() != null) {
            for (DocumentModel.Table t : group.tables()) {
                children.addAll(splitTable(group, t));
            }
        }
        return children;
    }

    private ChildChunker() {
    }
}
