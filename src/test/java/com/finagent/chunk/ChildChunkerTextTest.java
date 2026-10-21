package com.finagent.chunk;

import com.finagent.chunk.model.Chunk;
import com.finagent.chunk.model.ChunkLevel;
import com.finagent.chunk.model.ChunkType;
import com.finagent.chunk.model.ParentGroup;
import com.finagent.document.DocumentModel;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ChildChunkerTextTest {

    /** 造一个约 n 个 token 的段落（中文一字约一 token，且不含句末标点）。 */
    private static String para(int tokens) {
        return "财".repeat(tokens);
    }

    /** 造一句带句号的话。 */
    private static String sentence(int tokens) {
        return "财".repeat(Math.max(1, tokens - 1)) + "。";
    }

    private static DocumentModel.Block block(String id, String text, int page, String label) {
        return new DocumentModel.Block(id, label, text,
                new DocumentModel.Provenance(page, null),
                List.of("第一节", "一、小节"));
    }

    private static DocumentModel.Block block(String id, String text, int page) {
        return block(id, text, page, "text");
    }

    /** 评审 B6：初版传 parent=null 导致 toChild NPE，5 个用例全挂。Parent 必须是真对象。 */
    private static ParentGroup groupOf(DocumentModel.Block... blocks) {
        Chunk parent = new Chunk(ChunkLevel.PARENT, ChunkType.TEXT,
                List.of("第一节", "一、小节"), "一、小节", "(parent)",
                1, 1, "", null, 1, 0,
                null, null, null, null, null, null);
        return new ParentGroup(parent, List.of(blocks), List.of());
    }

    @Test
    void splitsWhenAccumulatedTokensWouldExceedMax() {
        ParentGroup g = groupOf(
                block("b0", para(400), 1),
                block("b1", para(400), 1),
                block("b2", para(400), 2));

        List<Chunk> children = ChildChunker.splitText(g);

        assertThat(children).hasSize(3);
        assertThat(children.get(0).sourceBlockIds()).isEqualTo("b0");
        assertThat(children.get(1).sourceBlockIds()).isEqualTo("b1");
    }

    @Test
    void packsSmallBlocksTogether() {
        ParentGroup g = groupOf(
                block("b0", para(100), 1),
                block("b1", para(100), 1),
                block("b2", para(100), 1));

        List<Chunk> children = ChildChunker.splitText(g);

        assertThat(children).hasSize(1);
        assertThat(children.get(0).sourceBlockIds()).isEqualTo("b0,b1,b2");
        assertThat(children.get(0).tokenCount())
                .isBetween(ChunkStrategy.CHILD_MIN_TOKENS, ChunkStrategy.CHILD_MAX_TOKENS);
    }

    /** 评审 B1：这个形状是真实数据里存在的（单块 ≤120 token），初版会死循环。 */
    @Test
    void doesNotLoopForeverOnTinySingleBlockSlice() {
        ParentGroup g = groupOf(block("b0", para(29), 1),
                block("b1", para(709), 2));

        List<Chunk> children = ChildChunker.splitText(g);

        assertThat(children).isNotEmpty();
        assertThat(String.join(",", children.stream()
                .map(Chunk::sourceBlockIds).toList()))
                .contains("b0").contains("b1");
    }

    /**
     * 超长单块（设计文档 §8.5：过长时按 paragraph/sentence 边界拆分）。
     *
     * <p><b>注意这里与计划初版相反</b>：初版断言「允许超过 700」，那会让验收 #3 的
     * 「100% ≤ 700」永远做不到。真实数据里存在 709 token 的块，必须切。
     */
    @Test
    void oversizedBlockIsSplitSoNoChildExceedsMax() {
        ParentGroup g = groupOf(block("b0", para(2000), 1));

        List<Chunk> children = ChildChunker.splitText(g);

        assertThat(children).hasSizeGreaterThan(1);
        assertThat(children).allSatisfy(c ->
                assertThat(c.tokenCount()).isLessThanOrEqualTo(ChunkStrategy.CHILD_MAX_TOKENS));
        // 同一 block 被切成多段时，source_block_ids 只记一次
        assertThat(children).allSatisfy(c ->
                assertThat(c.sourceBlockIds()).isEqualTo("b0"));
    }

    /** 超长块优先按句子切，而不是按字符硬切。 */
    @Test
    void oversizedBlockSplitsAtSentenceBoundaryWhenPossible() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 10; i++) {
            sb.append(sentence(120));     // 10 句 × 120 token ≈ 1200
        }
        ParentGroup g = groupOf(block("b0", sb.toString(), 1));

        List<Chunk> children = ChildChunker.splitText(g);

        assertThat(children).hasSizeGreaterThan(1);
        // 每片都应以句号结尾 —— 说明是在句子边界上切的，不是按字符硬切
        assertThat(children).allSatisfy(c ->
                assertThat(c.content()).endsWith("。"));
    }

    /**
     * 评审 C5：初版用 [400,50,400,400] 测 overlap，实际切成 [b0],[b1,b2],[b3]，
     * **根本没测到 overlap**。真正触发 overlap 的形状是 [250,60,400]。
     */
    @Test
    void overlapsSmallTrailingPieceIntoNextChild() {
        ParentGroup g = groupOf(
                block("b0", para(250), 1),
                block("b1", para(60), 1),
                block("b2", para(400), 2));

        List<Chunk> children = ChildChunker.splitText(g);

        assertThat(children).hasSize(2);
        assertThat(children.get(0).sourceBlockIds()).isEqualTo("b0,b1");
        // 第二片以 b1 开头 —— overlap 生效
        assertThat(children.get(1).sourceBlockIds()).startsWith("b1");
    }

    @Test
    void childKeepsSectionPathAndType() {
        ParentGroup g = groupOf(block("b0", para(400), 5));
        Chunk c = ChildChunker.splitText(g).get(0);
        assertThat(c.type()).isEqualTo(ChunkType.TEXT);
        assertThat(c.pageStart()).isEqualTo(5);
        assertThat(c.pageEnd()).isEqualTo(5);
        assertThat(c.sectionPath()).containsExactly("第一节", "一、小节");
    }

    @Test
    void listItemBlocksBecomeListChunks() {
        // 评审 C10：真实数据有 137 个 list_item
        ParentGroup g = groupOf(block("b0", para(400), 1, "list_item"));
        assertThat(ChildChunker.splitText(g).get(0).type()).isEqualTo(ChunkType.LIST);
    }

    @Test
    void emptyParentYieldsNoChildren() {
        ParentGroup g = new ParentGroup(null, List.of(), List.of());
        assertThat(ChildChunker.splitText(g)).isEmpty();
    }
}
