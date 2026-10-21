package com.finagent.chunk;

import com.finagent.chunk.model.Chunk;
import com.finagent.chunk.model.ChunkLevel;
import com.finagent.chunk.model.ChunkType;
import com.finagent.chunk.model.ParentGroup;
import com.finagent.document.DocumentModel;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ChildChunkerTableTest {

    private static ParentGroup groupOf(List<DocumentModel.Block> blocks,
                                       List<DocumentModel.Table> tables) {
        Chunk parent = new Chunk(ChunkLevel.PARENT, ChunkType.TEXT,
                List.of("第十节 财务报告"), "第十节 财务报告", "(parent)",
                1, 1, "", null, 1, 0,
                null, null, null, null, null, null);
        return new ParentGroup(parent, blocks, tables);
    }

    private static DocumentModel.Table smallTable(String caption) {
        var cells = List.of(
                new DocumentModel.Cell("项目", 0, 0, 1, 1),
                new DocumentModel.Cell("金额", 0, 1, 1, 1),
                new DocumentModel.Cell("营业收入", 1, 0, 1, 1),
                new DocumentModel.Cell("100", 1, 1, 1, 1));
        return new DocumentModel.Table("t0", 10, 10, 2, 2, cells, caption,
                List.of("第十节 财务报告"), null);
    }

    /**
     * 合成大表：100 行 × 2 列、每格长文本。
     *
     * <p><b>为什么必须造</b>（评审 C8）：真实数据里最大表恰好 40 行，
     * 而分片是按 token 预算 —— 靠真实数据**测不到分片路径**。
     */
    private static DocumentModel.Table bigTable() {
        List<DocumentModel.Cell> cells = new ArrayList<>();
        cells.add(new DocumentModel.Cell("项目", 0, 0, 1, 1));
        cells.add(new DocumentModel.Cell("金额", 0, 1, 1, 1));
        String longText = "财".repeat(30);
        for (int r = 1; r <= 100; r++) {
            cells.add(new DocumentModel.Cell("指标" + r, r, 0, 1, 1));
            cells.add(new DocumentModel.Cell(longText + r, r, 1, 1, 1));
        }
        return new DocumentModel.Table("tBig", 20, 24, 101, 2, cells, null,
                List.of("第十节 财务报告"), null);
    }

    @Test
    void smallTableBecomesOneChild() {
        var children = ChildChunker.splitTable(groupOf(List.of(), List.of(smallTable(null))),
                smallTable(null));

        assertThat(children).hasSize(1);
        Chunk c = children.get(0);
        assertThat(c.type()).isEqualTo(ChunkType.TABLE);
        assertThat(c.tokenCount()).isLessThanOrEqualTo(ChunkStrategy.CHILD_MAX_TOKENS);
        assertThat(c.sourceBlockIds()).isEqualTo("t0");
        assertThat(c.headersJson()).isNotNull().contains("项目").contains("金额");
    }

    @Test
    void bigTableSplitsAndRepeatsHeaderInEveryPiece() {
        var table = bigTable();
        var children = ChildChunker.splitTable(groupOf(List.of(), List.of(table)), table);

        assertThat(children).hasSizeGreaterThan(1);
        // 每片都必须自带表头与分隔行（§8.2 硬要求）
        assertThat(children).allSatisfy(c -> {
            assertThat(c.content()).contains("| 项目 | 金额 |");
            assertThat(c.content()).contains("| --- | --- |");
            assertThat(c.tokenCount()).isLessThanOrEqualTo(ChunkStrategy.CHILD_MAX_TOKENS);
        });
        // 分片顺序号递增
        for (int i = 0; i < children.size(); i++) {
            assertThat(children.get(i).chunkIndex()).isEqualTo(i);
        }
    }

    @Test
    void unitAndCurrencyAreExtractedFromCaption() {
        var children = ChildChunker.splitTable(
                groupOf(List.of(), List.of(smallTable("单位：元"))), smallTable("单位：元"));
        Chunk c = children.get(0);
        assertThat(c.caption()).isEqualTo("单位：元");
        assertThat(c.unit()).isEqualTo("元");
        assertThat(c.currency()).isEqualTo("CNY");
    }

    @Test
    void missingCaptionYieldsNullUnitNotGuess() {
        var children = ChildChunker.splitTable(
                groupOf(List.of(), List.of(smallTable(null))), smallTable(null));
        assertThat(children.get(0).unit()).isNull();
        assertThat(children.get(0).currency()).isNull();
    }

    @Test
    void splitAllKeepsBothTextAndTables() {
        // 评审：初版只走一个分支，会丢掉 21.9% 的正文
        var block = new DocumentModel.Block("b0", "text", "财".repeat(400),
                new DocumentModel.Provenance(1, null), List.of("第十节 财务报告"));
        var group = groupOf(List.of(block), List.of(smallTable(null)));

        var children = ChildChunker.splitAll(group);

        assertThat(children).anySatisfy(c -> assertThat(c.type()).isEqualTo(ChunkType.TEXT));
        assertThat(children).anySatisfy(c -> assertThat(c.type()).isEqualTo(ChunkType.TABLE));
    }

    @Test
    void tableOnlyParentStillProducesAChild() {
        // 章节只有表格、没有正文块时，不能产出空列表
        var group = groupOf(List.of(), List.of(smallTable(null)));
        assertThat(ChildChunker.splitAll(group)).isNotEmpty();
    }
}
