package com.finagent.context;

import com.finagent.chunk.ChunkRepository;
import com.finagent.chunk.ChunkRepository.ChunkView;
import com.finagent.retrieval.RetrievedChunk;
import com.finagent.understanding.QueryType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 上下文装配的规则测试。
 *
 * <p>仓储 mock 掉、渲染用真 ObjectMapper：验的是「选了哪几块、按什么顺序、怎么渲染」，
 * 不是 SQL；真实库的装配效果见 ContextBuilderE2ETest。
 */
class ContextBuilderTest {

    private ChunkRepository chunks;
    private ContextProperties props;
    private ContextBuilder builder;

    @BeforeEach
    void setUp() {
        chunks = mock(ChunkRepository.class);
        props = new ContextProperties();
        builder = new ContextBuilder(chunks, props, JsonMapper.builder().build());
        when(chunks.findViewsByIds(anyList())).thenReturn(List.of());
        when(chunks.findChildViewsByParents(anyList())).thenReturn(List.of());
    }

    @Test
    @DisplayName("同一 Parent 的两个命中只展开一次，不重复加载孩子")
    void sameParentExpandedOnce() {
        long parent = 100L;
        ChunkView c1 = view(1, parent, 10, 100, "第一段");
        ChunkView c2 = view(2, parent, 11, 100, "第二段");
        stubViews(List.of(parentView(parent, 800, "parent"), c1, c2), List.of(c1, c2));

        ContextPackage pkg = builder.build("q", QueryType.EXACT_FACT, List.of(hit(c1, 0.9), hit(c2, 0.8)), null);

        verify(chunks, times(1)).findChildViewsByParents(any());
        assertThat(pkg.blocks()).extracting(ContextBlock::chunkId).containsExactly(1L, 2L);
        assertThat(pkg.parentsExpanded()).isEqualTo(1);
    }

    @Test
    @DisplayName("组内二次选择：0.9 / 0.85 / 0.3 → 0.3 低于 0.6×0.9=0.54，被丢弃")
    void secondSelectionDropsLowScore() {
        long parent = 100L;
        ChunkView a = view(1, parent, 10, 100, "A");
        ChunkView b = view(2, parent, 20, 100, "B");
        // 中间插一个未命中的 filler：邻居是按「children 里的位置」算的，
        // 没有 filler 时 c 恰好是 b 的相邻块，会被当 SIBLING 正确补进来。
        ChunkView filler = view(4, parent, 21, 100, "F");
        ChunkView c = view(3, parent, 30, 100, "C");
        stubViews(List.of(parentView(parent, 900, "parent"), a, b, filler, c), List.of(a, b, filler, c));

        ContextPackage pkg = builder.build("q", QueryType.EXACT_FACT,
                List.of(hit(a, 0.9), hit(b, 0.85), hit(c, 0.3)), null);

        // 1、2 是命中且过阈值；4 是 2 的相邻块（SIBLING 补齐，属预期）；
        // 3 分 0.3 < 0.54，且不挨着任何命中块 → 必须被丢掉。
        assertThat(pkg.blocks()).extracting(ContextBlock::chunkId).containsExactly(1L, 2L, 4L);
        assertThat(pkg.blocks()).extracting(ContextBlock::role)
                .containsExactly(ContextBlock.ROLE_HIT, ContextBlock.ROLE_HIT, ContextBlock.ROLE_SIBLING);
        assertThat(pkg.blocks()).extracting(ContextBlock::chunkId).doesNotContain(3L);
    }

    @Test
    @DisplayName("邻居补齐：命中中间块 → 前后各一个 SIBLING")
    void neighborsAdded() {
        long parent = 100L;
        List<ChunkView> children = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            children.add(view(i + 1, parent, i, 100, "第" + i + "段"));
        }
        ChunkView hitView = children.get(2);
        stubViews(List.of(parentView(parent, 600, "parent")), children);

        ContextPackage pkg = builder.build("q", QueryType.EXACT_FACT, List.of(hit(hitView, 0.9)), null);

        assertThat(pkg.blocks()).extracting(ContextBlock::chunkIndex).containsExactly(1, 2, 3);
        assertThat(pkg.blocks()).extracting(ContextBlock::role)
                .containsExactly(ContextBlock.ROLE_SIBLING, ContextBlock.ROLE_HIT, ContextBlock.ROLE_SIBLING);
        assertThat(pkg.siblingsAdded()).isEqualTo(2);
    }

    @Test
    @DisplayName("命中的是首块 → 只补后一个，不越界")
    void noNeighborBeforeFirst() {
        long parent = 100L;
        List<ChunkView> children = List.of(view(1, parent, 0, 100, "首段"), view(2, parent, 1, 100, "次段"));
        stubViews(List.of(parentView(parent, 300, "parent")), children);

        ContextPackage pkg = builder.build("q", QueryType.EXACT_FACT, List.of(hit(children.get(0), 0.9)), null);

        assertThat(pkg.blocks()).extracting(ContextBlock::chunkId).containsExactly(1L, 2L);
        assertThat(pkg.blocks().get(0).role()).isEqualTo(ContextBlock.ROLE_HIT);
        assertThat(pkg.blocks().get(1).role()).isEqualTo(ContextBlock.ROLE_SIBLING);
    }

    @Test
    @DisplayName("预算 100、块各 60 → 只装一块，并记 context_truncated")
    void budgetKeepsWholeBlocks() {
        props.setMaxTokens(100);
        long parent = 100L;
        List<ChunkView> children = List.of(view(1, parent, 0, 60, "A"), view(2, parent, 1, 60, "B"));
        stubViews(List.of(parentView(parent, 300, "parent")), children);

        ContextPackage pkg = builder.build("q", QueryType.EXACT_FACT,
                List.of(hit(children.get(0), 0.9), hit(children.get(1), 0.8)), null);

        assertThat(pkg.blocks()).hasSize(1);
        assertThat(pkg.tokenCount()).isEqualTo(60);
        assertThat(pkg.warnings()).contains("context_truncated");
        // 没有被截断的字符串：留下的那块内容完整
        assertThat(pkg.blocks().get(0).content()).isEqualTo("A");
    }

    @Test
    @DisplayName("表格块渲染出表名 / 单位 / 表头 / 内容四行")
    void tableRendering() {
        long parent = 100L;
        ChunkView table = new ChunkView(1, 7, parent, "CHILD", "TABLE",
                "第二节 公司简介和主要财务指标 § 五、主要会计数据和财务指标",
                "五、主要会计数据和财务指标", "| 营业收入 | 16,067,741,039.51 |", 47, 47, 400, 13,
                "主要会计数据和财务指标", "元", "[\"项目\",\"2025年\"]", null, "东方财富2025年年度报告");
        stubViews(List.of(parentView(parent, 900, "parent")), List.of(table));

        ContextPackage pkg = builder.build("q", QueryType.EXACT_FACT, List.of(hit(table, 0.9)), null);

        assertThat(pkg.text())
                .contains("[E1] 东方财富2025年年度报告 · 第 47 页")
                .contains("第二节 公司简介和主要财务指标 > 五、主要会计数据和财务指标")
                .contains("表：主要会计数据和财务指标（单位：元）")
                .contains("表头：项目 | 2025年")
                .contains("内容：")
                .contains("| 营业收入 | 16,067,741,039.51 |");
    }

    @Test
    @DisplayName("caption / unit / headers 全空 → 这三行都不出现，也不留空行")
    void noEmptyTableLines() {
        long parent = 100L;
        ChunkView table = new ChunkView(1, 7, parent, "CHILD", "TABLE", "第八节", "标题", "| a | b |",
                10, 10, 50, 1, null, null, null, null, "东方财富2025年年度报告");
        stubViews(List.of(parentView(parent, 300, "parent")), List.of(table));

        ContextPackage pkg = builder.build("q", QueryType.EXACT_FACT, List.of(hit(table, 0.9)), null);

        assertThat(pkg.text()).doesNotContain("表：").doesNotContain("表头：");
        assertThat(pkg.text()).contains("内容：");
    }

    @Test
    @DisplayName("COMPLEX_ANALYSIS + 整节 ≤ 1500 token → 整段展开为子块，不用父块 content")
    void complexAnalysisTakesWholeSection() {
        long parent = 100L;
        ChunkView p = parentView(parent, 1200, "整段 Parent 正文");
        ChunkView child = view(1, parent, 5, 300, "片段");
        stubViews(List.of(p, child), List.of(child));

        ContextPackage pkg = builder.build("q", QueryType.COMPLEX_ANALYSIS, List.of(hit(child, 0.9)), null);

        // 整段给的是子块（命中 → HIT），不是父块
        assertThat(pkg.blocks()).hasSize(1);
        assertThat(pkg.blocks().get(0).role()).isEqualTo(ContextBlock.ROLE_HIT);
        assertThat(pkg.blocks().get(0).chunkId()).isEqualTo(child.id());
        assertThat(pkg.text()).contains("片段");
        // 父块 content 永不进提示词
        assertThat(pkg.text()).doesNotContain("整段 Parent 正文");
    }

    @Test
    @DisplayName("EXACT_FACT 即使 Parent 只有 1200 token 也不整段（只要命中的那几块）")
    void exactFactDoesNotTakeWholeParent() {
        long parent = 100L;
        ChunkView p = parentView(parent, 1200, "整段 Parent 正文");
        ChunkView child = view(1, parent, 5, 300, "片段");
        stubViews(List.of(p, child), List.of(child));

        ContextPackage pkg = builder.build("q", QueryType.EXACT_FACT, List.of(hit(child, 0.9)), null);

        assertThat(pkg.blocks()).extracting(ContextBlock::chunkId).containsExactly(1L);
        assertThat(pkg.blocks().get(0).role()).isEqualTo(ContextBlock.ROLE_HIT);
    }

    @Test
    @DisplayName("组内按 chunkIndex 升序：命中顺序颠倒也要按阅读顺序输出")
    void blocksOrderedByChunkIndex() {
        long parent = 100L;
        ChunkView first = view(1, parent, 10, 100, "先");
        ChunkView second = view(2, parent, 20, 100, "后");
        stubViews(List.of(parentView(parent, 600, "parent")), List.of(first, second));

        ContextPackage pkg = builder.build("q", QueryType.EXACT_FACT,
                List.of(hit(second, 0.95), hit(first, 0.9)), null);

        assertThat(pkg.blocks()).extracting(ContextBlock::chunkIndex).containsExactly(10, 20);
    }

    @Test
    @DisplayName("空命中 → 空包，不抛异常")
    void emptyHits() {
        ContextPackage pkg = builder.build("q", QueryType.EXACT_FACT, List.of(), null);

        assertThat(pkg.isEmpty()).isTrue();
        assertThat(pkg.text()).isEmpty();
        assertThat(pkg.tokenCount()).isZero();
        assertThat(pkg.blocks()).isEmpty();
    }

    @Test
    @DisplayName("evidenceNo 从 1 连续编号，与 blocks 下标一致")
    void evidenceNumbersAreContiguous() {
        long parentA = 100L;
        long parentB = 200L;
        ChunkView a = view(1, parentA, 0, 50, "A");
        ChunkView b = view(2, parentB, 0, 50, "B");
        ChunkView c = view(3, parentB, 1, 50, "C");
        when(chunks.findViewsByIds(anyList())).thenReturn(List.of(parentView(parentA, 200, "pa"),
                parentView(parentB, 200, "pb"), a, b, c));
        when(chunks.findChildViewsByParents(anyList()))
                .thenReturn(List.of(a, b, c));

        ContextPackage pkg = builder.build("q", QueryType.EXACT_FACT,
                List.of(hit(a, 0.9), hit(b, 0.7)), null);

        for (int i = 0; i < pkg.blocks().size(); i++) {
            assertThat(pkg.blocks().get(i).evidenceNo()).isEqualTo(i + 1);
            assertThat(pkg.text()).contains("[E" + (i + 1) + "]");
        }
    }

    @Test
    @DisplayName("没有 Parent 的命中（父为空）也能装配，只是不补邻居")
    void hitWithoutParent() {
        ChunkView orphan = new ChunkView(1, 7, null, "CHILD", "TEXT", "第八节", "标题",
                "孤立块", null, null, 50, 1, null, null, null, null, "东方财富2025年年度报告");
        when(chunks.findViewsByIds(anyList())).thenReturn(List.of(orphan));

        ContextPackage pkg = builder.build("q", QueryType.EXACT_FACT, List.of(hit(orphan, 0.9)), null);

        assertThat(pkg.blocks()).hasSize(1);
        assertThat(pkg.warnings()).contains("context_parent_missing");
        assertThat(pkg.text()).contains("页码未知").contains("孤立块");
    }

    @Test
    @DisplayName("COMPLEX_ANALYSIS + 命中表格块 → 整段展开后表格内容仍在（修整段丢表）")
    void complexAnalysisKeepsTableInWholeSection() {
        long parent = 100L;
        ChunkView text = view(1, parent, 0, 300, "正文段");
        ChunkView table = new ChunkView(2, 7, parent, "CHILD", "TABLE",
                "第二节 公司简介和主要财务指标 § 六、分季度主要财务指标",
                "六、分季度主要财务指标", "| 营业收入 | 3,485,587,013.94 |", 7, 7, 300, 1,
                "分季度主要财务指标", "元", "[\"项目\",\"金额\"]", null, "东方财富2025年年度报告");
        stubViews(List.of(parentView(parent, 600, "parent"), text, table), List.of(text, table));

        ContextPackage pkg = builder.build("q", QueryType.COMPLEX_ANALYSIS, List.of(hit(table, 0.9)), null);

        assertThat(pkg.blocks()).extracting(ContextBlock::chunkId).containsExactly(1L, 2L);
        assertThat(pkg.blocks().get(1).role()).isEqualTo(ContextBlock.ROLE_HIT);
        assertThat(pkg.text()).contains("表：分季度主要财务指标").contains("3,485,587,013.94");
    }

    @Test
    @DisplayName("COMPLEX_ANALYSIS + 整节子块和 > 1500 → 退回精确路径，不整段")
    void bigSectionFallsBackToPrecisePath() {
        long parent = 100L;
        List<ChunkView> children = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            children.add(view(i + 1, parent, i, 400, "第" + i + "段"));
        }
        ChunkView hitView = children.get(1);
        stubViews(List.of(parentView(parent, 2000, "parent")), children);

        ContextPackage pkg = builder.build("q", QueryType.COMPLEX_ANALYSIS, List.of(hit(hitView, 0.9)), null);

        // 4 × 400 = 1600 > 1500 → 整段不触发，退回精确路径：命中块 + ±1 邻居 = 3 块，第 4 块被丢
        assertThat(pkg.blocks()).extracting(ContextBlock::chunkId).containsExactly(1L, 2L, 3L);
        assertThat(pkg.blocks()).extracting(ContextBlock::role)
                .containsExactly(ContextBlock.ROLE_SIBLING, ContextBlock.ROLE_HIT, ContextBlock.ROLE_SIBLING);
        assertThat(pkg.parentsExpanded()).isEqualTo(1);
    }

    // ------------------------------------------------------------ helpers

    private void stubViews(List<ChunkView> views, List<ChunkView> children) {
        when(chunks.findViewsByIds(anyList())).thenReturn(views);
        when(chunks.findChildViewsByParents(anyList())).thenReturn(children);
    }

    private static ChunkView parentView(long id, int tokens, String content) {
        return new ChunkView(id, 7, null, "PARENT", "TEXT", "第八节", "第八节", content,
                1, 2, tokens, 0, null, null, null, null, "东方财富2025年年度报告");
    }

    private static ChunkView view(long id, long parentId, int chunkIndex, int tokens, String content) {
        return new ChunkView(id, 7, parentId, "CHILD", "TEXT", "第八节 > 一、概述", "概述", content,
                10, 10, tokens, chunkIndex, null, null, null, null, "东方财富2025年年度报告");
    }

    private static RetrievedChunk hit(ChunkView v, double score) {
        return new RetrievedChunk(v.id(), v.parentId() == null ? 0L : v.parentId(), v.documentId(),
                v.chunkType(), v.sectionPath(), v.title(), v.content(),
                v.pageStart() == null ? 0 : v.pageStart(), v.pageEnd() == null ? 0 : v.pageEnd(),
                null, 1, null, 0.0, score);
    }
}
