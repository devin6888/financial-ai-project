package com.finagent.chunk;

import com.finagent.document.DocumentModel;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

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
        int numCols = Math.max(table.num_cols(), 1);
        List<DocumentModel.Cell> cells =
                table.cells() == null ? List.of() : table.cells();
        Normalized normalized = normalize(cells, numCols);
        int cols = normalized.cols();

        List<List<String>> grid = new ArrayList<>(rows);
        for (int r = 0; r < rows; r++) {
            List<String> row = new ArrayList<>(cols);
            for (int c = 0; c < cols; c++) {
                row.add("");
            }
            grid.add(row);
        }
        // 【阶段 6 员工表丢数值的根因】单列单元格（col_span=1）先落位，
        // 跨列 span 后落位 —— 否则重叠的跨列标签会先占住数值格，数值被「只填空白格」跳过。
        List<DocumentModel.Cell> ordered = new ArrayList<>(normalized.cells());
        ordered.sort(Comparator
                .comparingInt((DocumentModel.Cell c) -> Math.max(c.col_span(), 1))
                .thenComparingInt(DocumentModel.Cell::row)
                .thenComparingInt(DocumentModel.Cell::col));
        for (DocumentModel.Cell cell : ordered) {
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

    private record Normalized(List<DocumentModel.Cell> cells, int cols) {
    }

    /**
     * 折叠 Docling TableFormer 偶发的「整列重复 span」（把 2 列表误判成 3 列）。
     *
     * <p>判定（保守）：只有当<b>每一行</b>都存在「跨列格 + 同文本重叠格」时，才认为整张表
     * 被凭空多判了一列，此时去重并把列号紧凑重排；否则原样返回 —— 单列优先落位已经能保住
     * 数值，不去冒险动合法的合并单元格（部分合并可能只出现在表头一两行，误判会错列）。
     */
    static Normalized normalize(List<DocumentModel.Cell> cells, int numCols) {
        if (cells.isEmpty() || numCols <= 1) {
            return new Normalized(cells, numCols);
        }
        Map<Integer, List<DocumentModel.Cell>> byRow = new TreeMap<>();
        for (DocumentModel.Cell c : cells) {
            byRow.computeIfAbsent(c.row(), k -> new ArrayList<>()).add(c);
        }

        List<List<DocumentModel.Cell>> dedupedRows = new ArrayList<>();
        int nonEmptyRows = 0;
        int rowsWithDup = 0;
        int maxCols = 1;
        for (Map.Entry<Integer, List<DocumentModel.Cell>> e : byRow.entrySet()) {
            List<DocumentModel.Cell> rowCells = e.getValue();
            rowCells.sort(Comparator.comparingInt(DocumentModel.Cell::col));
            nonEmptyRows++;
            List<DocumentModel.Cell> deduped = new ArrayList<>();
            boolean rowDuped = false;
            for (DocumentModel.Cell c : rowCells) {
                if (!deduped.isEmpty()) {
                    DocumentModel.Cell prev = deduped.get(deduped.size() - 1);
                    int prevSpan = Math.max(prev.col_span(), 1);
                    // 与前一个格重叠（落在其 span 内）且文本相同 → span 被重复物化，丢弃
                    if (c.col() < prev.col() + prevSpan && c.text().equals(prev.text())) {
                        rowDuped = true;
                        continue;
                    }
                }
                deduped.add(c);
            }
            if (rowDuped) {
                rowsWithDup++;
            }
            dedupedRows.add(deduped);
            maxCols = Math.max(maxCols, deduped.size());
        }
        // 只有每一行都去重、且去重后确实少了一列，才紧凑重排；否则不动。
        if (rowsWithDup == 0 || rowsWithDup != nonEmptyRows || maxCols >= numCols) {
            return new Normalized(cells, numCols);
        }
        List<DocumentModel.Cell> out = new ArrayList<>();
        for (List<DocumentModel.Cell> rowCells : dedupedRows) {
            for (int i = 0; i < rowCells.size(); i++) {
                DocumentModel.Cell c = rowCells.get(i);
                int span = (i + 1 < rowCells.size()) ? 1 : Math.max(1, maxCols - i);
                out.add(new DocumentModel.Cell(c.text(), c.row(), i, c.row_span(), span));
            }
        }
        return new Normalized(out, maxCols);
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
