package com.finagent.chunk;

import com.finagent.document.DocumentModel;

import java.util.ArrayList;
import java.util.List;

/**
 * 表格 -> markdown。
 *
 * <p><b>为什么不用 Docling chunker 的序列化</b>（阶段 2 实测）：它会把释义表输出成
 * 「中国证监会, 指 = 指. 中国证监会, 释义内容 = 中国证券监督管理委员会.」，表结构全丢。
 * 我们手里本来就有规整的 {@code cells} 网格，自己渲染更准。
 */
public final class TableRenderer {

    private static final int DEFAULT_HEADER_ROWS = 1;

    public static String toMarkdown(DocumentModel.Table table) {
        return toMarkdown(table, DEFAULT_HEADER_ROWS);
    }

    public static String toMarkdown(DocumentModel.Table table, int headerRows) {
        List<List<String>> grid = toGrid(table);
        StringBuilder sb = new StringBuilder();
        if (table.caption() != null && !table.caption().isBlank()) {
            sb.append(table.caption().strip()).append('\n');
        }
        if (grid.isEmpty()) {
            return sb.toString().strip();
        }
        int cols = colsOf(grid);
        for (int r = 0; r < grid.size(); r++) {
            sb.append(rowToMarkdown(grid.get(r), cols)).append('\n');
            if (r == headerRows - 1) {
                sb.append(separatorRow(cols)).append('\n');
            }
        }
        return sb.toString().strip();
    }

    /**
     * 把稀疏单元格铺成二维文本网格。
     *
     * <p><b>【评审 C7 修正】</b>初版会把 row_span/col_span 铺满整个跨度 —— 但真实产物的
     * cells 已按位置物化，铺展既冗余（实测 0 处差异），又造成 1134 次越界写被静默丢弃、
     * 285 个格子被重复覆盖（last-write-wins）。现在：越界忽略 + <b>只填空白格</b>。
     */
    public static List<List<String>> toGrid(DocumentModel.Table table) {
        int rows = Math.max(table.num_rows(), 1);
        int cols = Math.max(table.num_cols(), 1);
        List<List<String>> grid = new ArrayList<>(rows);
        for (int r = 0; r < rows; r++) {
            List<String> row = new ArrayList<>(cols);
            for (int c = 0; c < cols; c++) {
                row.add("");
            }
            grid.add(row);
        }
        if (table.cells() == null) {
            return grid;
        }
        for (DocumentModel.Cell cell : table.cells()) {
            String text = escape(cell.text());
            int rs = Math.max(cell.row_span(), 1);
            int cs = Math.max(cell.col_span(), 1);
            for (int dr = 0; dr < rs; dr++) {
                for (int dc = 0; dc < cs; dc++) {
                    int r = cell.row() + dr;
                    int c = cell.col() + dc;
                    if (r >= rows || c >= cols) {
                        continue;                    // 越界忽略，不写穿
                    }
                    if (grid.get(r).get(c).isEmpty()) {
                        grid.get(r).set(c, text);    // 只填空白格
                    }
                }
            }
        }
        return grid;
    }

    static int colsOf(List<List<String>> grid) {
        return grid.stream().mapToInt(List::size).max().orElse(0);
    }

    static String rowToMarkdown(List<String> row, int cols) {
        StringBuilder sb = new StringBuilder("|");
        for (int c = 0; c < cols; c++) {
            sb.append(' ').append(c < row.size() ? row.get(c) : "").append(" |");
        }
        return sb.toString();
    }

    static String separatorRow(int cols) {
        StringBuilder sb = new StringBuilder("|");
        for (int c = 0; c < cols; c++) {
            sb.append(" --- |");
        }
        return sb.toString();
    }

    private static String escape(String text) {
        if (text == null) {
            return "";
        }
        return text.replace("|", "\\|").replace("\n", " ").strip();
    }

    private TableRenderer() {
    }
}
