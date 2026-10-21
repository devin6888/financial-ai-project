package com.finagent.chunk;

import com.finagent.chunk.model.ParentGroup;
import com.finagent.document.DocumentModel;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ParentChunkerTest {

    private static DocumentModel docWith(List<DocumentModel.Block> blocks,
                                         List<DocumentModel.Table> tables) {
        return new DocumentModel(null, blocks, tables);
    }

    private static DocumentModel.Block block(String id, String text, int page, String... path) {
        return new DocumentModel.Block(id, "text", text,
                new DocumentModel.Provenance(page, null), List.of(path));
    }

    private static DocumentModel.Table table(String id, int page, String... path) {
        return new DocumentModel.Table(id, page, page, 1, 1, List.of(), null,
                List.of(path), null);
    }

    @Test
    void truncatesSectionPathToParentDepth() {
        // 三级路径应归到前两级（PARENT_DEPTH = 2）——
        // 这是评审指出的核心修正：按最深层级归组会让 Parent 退化成几十字的小节。
        var doc = docWith(List.of(
                block("b0", "a", 1, "第八节 财务报告", "五、会计政策", "12、长期股权投资"),
                block("b1", "b", 1, "第八节 财务报告", "五、会计政策", "13、其他")), List.of());

        List<ParentGroup> groups = ParentChunker.split(doc);

        assertThat(groups).hasSize(1);
        assertThat(groups.get(0).parent().sectionPath())
                .containsExactly("第八节 财务报告", "五、会计政策");
        assertThat(groups.get(0).blocks()).hasSize(2);
    }

    @Test
    void ordersGroupsByFirstPageNotByFirstSeen() {
        // 评审 C4：初版把「只有表格」的章节一律追加到文档末尾，chunk_index 顺序错乱。
        // 现在统一按最小页码排序。
        var doc = docWith(
                List.of(block("b0", "正文在最后", 180, "第九节 末章")),
                List.of(table("t0", 3, "第一节 开头"),
                        table("t1", 6, "第二节 中间")));

        List<ParentGroup> groups = ParentChunker.split(doc);

        assertThat(groups).extracting(g -> g.parent().title())
                .containsExactly("第一节 开头", "第二节 中间", "第九节 末章");
    }

    @Test
    void blocksWithoutSectionPathGoIntoOneLeadingParent() {
        // 封面/前置页没有章节路径，不能丢
        var doc = docWith(List.of(
                block("b0", "封面文字", 1),
                block("b1", "正文", 2, "第一节")), List.of());

        List<ParentGroup> groups = ParentChunker.split(doc);

        assertThat(groups).hasSize(2);
        assertThat(groups.get(0).parent().title()).isEqualTo("(无章节)");
        assertThat(groups.get(0).parent().content()).contains("封面文字");
    }

    @Test
    void parentPageRangeCoversAllMembers() {
        var doc = docWith(List.of(
                block("b0", "a", 3, "S"), block("b1", "b", 7, "S")), List.of());
        var parent = ParentChunker.split(doc).get(0).parent();
        assertThat(parent.pageStart()).isEqualTo(3);
        assertThat(parent.pageEnd()).isEqualTo(7);
    }

    @Test
    void parentTypeIsTextWhenBothTextAndTablesExist() {
        // 评审 C11：同时有正文与表格时，Parent 的内容主体是正文，不能标成 TABLE
        var doc = docWith(
                List.of(block("b0", "正文", 1, "第一节")),
                List.of(table("t0", 1, "第一节")));

        var parent = ParentChunker.split(doc).get(0).parent();

        assertThat(parent.type()).isEqualTo(com.finagent.chunk.model.ChunkType.TEXT);
        assertThat(parent.sourceBlockIds()).contains("b0").contains("t0");
    }
}
