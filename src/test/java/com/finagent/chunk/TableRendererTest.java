package com.finagent.chunk;

import com.finagent.document.DocumentModel;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TableRendererTest {

    private static DocumentModel.Table table(int rows, int cols, List<DocumentModel.Cell> cells,
                                             String caption) {
        return new DocumentModel.Table("t0", 1, 1, rows, cols, cells, caption,
                List.of("S"), null);
    }

    @Test
    void rendersGridAsMarkdownTable() {
        var cells = List.of(
                new DocumentModel.Cell("项目", 0, 0, 1, 1),
                new DocumentModel.Cell("金额", 0, 1, 1, 1),
                new DocumentModel.Cell("营业收入", 1, 0, 1, 1),
                new DocumentModel.Cell("100", 1, 1, 1, 1));

        String md = TableRenderer.toMarkdown(table(2, 2, cells, null));

        assertThat(md).contains("| 项目 | 金额 |");
        assertThat(md).contains("| --- | --- |");
        assertThat(md).contains("| 营业收入 | 100 |");
    }

    @Test
    void includesCaptionWhenPresent() {
        var cells = List.of(new DocumentModel.Cell("A", 0, 0, 1, 1),
                new DocumentModel.Cell("B", 0, 1, 1, 1));
        String md = TableRenderer.toMarkdown(table(1, 2, cells, "单位：元"));
        assertThat(md).startsWith("单位：元");
    }

    @Test
    void mergedCellsRepeatTextIntoSpannedPositions() {
        // markdown 表达不了合并单元格，用重复文本近似 —— 比留空丢信息好
        var cells = List.of(new DocumentModel.Cell("合并头", 0, 0, 1, 2));
        String md = TableRenderer.toMarkdown(table(1, 2, cells, null));
        assertThat(md).contains("| 合并头 | 合并头 |");
    }

    @Test
    void blankCellsBecomeEmptyRatherThanNull() {
        var cells = List.of(
                new DocumentModel.Cell("A", 0, 0, 1, 1),
                new DocumentModel.Cell(null, 0, 1, 1, 1));
        String md = TableRenderer.toMarkdown(table(1, 2, cells, null));
        assertThat(md).doesNotContain("null");
        assertThat(md).contains("| A |  |");
    }

    @Test
    void escapesPipeInsideCellText() {
        var cells = List.of(new DocumentModel.Cell("a|b", 0, 0, 1, 1),
                new DocumentModel.Cell("c", 0, 1, 1, 1));
        String md = TableRenderer.toMarkdown(table(1, 2, cells, null));
        // 单元格里的 | 必须转义，否则 markdown 表格会错列
        assertThat(md).contains("a\\|b");
    }

    @Test
    void laterCellDoesNotOverwriteEarlierOne() {
        // 评审 C7：初版 last-write-wins，285 个格子被覆盖（本数据恰好顺序良性，但语义是错的）
        var cells = List.of(
                new DocumentModel.Cell("第一次", 0, 0, 1, 1),
                new DocumentModel.Cell("第二次", 0, 0, 1, 1));
        var grid = TableRenderer.toGrid(table(1, 1, cells, null));
        assertThat(grid.get(0).get(0)).isEqualTo("第一次");
    }

    @Test
    void outOfBoundsSpansAreIgnoredNotWrittenThrough() {
        // 评审 C7：真实数据里有 1134 次越界写被静默丢弃，必须显式忽略而不是写穿
        var cells = List.of(new DocumentModel.Cell("超宽", 0, 0, 1, 99));
        var grid = TableRenderer.toGrid(table(1, 2, cells, null));
        assertThat(grid.get(0)).hasSize(2);
        assertThat(grid.get(0).get(1)).isEqualTo("超宽");
    }
}
