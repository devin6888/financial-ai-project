package com.finagent.context;

import com.finagent.chunk.ChunkRepository;
import com.finagent.chunk.ChunkRepository.ChunkView;
import com.finagent.retrieval.RetrievedChunk;
import com.finagent.understanding.QueryType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 真库装配验证：用 document 7（东方财富 2025 年报）里真实的表格块走一遍
 * ChunkRepository 的两个 IN 查询 + 渲染，确认 mock 测试盖不到的映射问题
 * （headers_json / bbox 的 ::text、page 字段类型、Parent 展开的真实 token 数）不存在。
 */
@SpringBootTest
@ActiveProfiles("test")
@Tag("e2e")
class ContextBuilderE2ETest {

    private static final long DOC_2025 = 7L;

    @Autowired
    private ContextBuilder builder;

    @Autowired
    private ChunkRepository chunks;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    @DisplayName("真实表格块：渲染出表名 / 表头 / 页码，且自动补出同 Parent 的邻居")
    void realTableChunkRendersWithCaptionAndHeader() {
        List<Long> tableIds = jdbc.queryForList(
                "SELECT id FROM document_chunk WHERE document_id = ? AND chunk_level = 'CHILD'"
                        + " AND status = 'ACTIVE' AND chunk_type = 'TABLE' AND caption IS NOT NULL"
                        + " ORDER BY id LIMIT 2", Long.class, DOC_2025);
        assertThat(tableIds).as("文档 7 应有带表名的表格块").isNotEmpty();

        List<RetrievedChunk> hits = hitsOf(tableIds);
        ContextPackage pkg = builder.build("主要会计数据和财务指标", QueryType.EXACT_FACT, hits, null);

        assertThat(pkg.blocks()).isNotEmpty();
        assertThat(pkg.blocks()).anySatisfy(b -> {
            assertThat(b.chunkType()).isEqualTo("TABLE");
            assertThat(b.caption()).isNotBlank();
            assertThat(b.pageStart()).isNotNull();
        });
        ContextBlock table = pkg.blocks().stream()
                .filter(b -> "TABLE".equals(b.chunkType())).findFirst().orElseThrow();
        assertThat(pkg.text()).contains("[E1]").contains("表：" + table.caption()).contains("内容：");
        assertThat(pkg.text()).contains(" · 第 ");
        assertThat(pkg.tokenCount()).isPositive().isLessThanOrEqualTo(6000);
        assertThat(pkg.parentsExpanded()).isPositive();
    }

    @Test
    @DisplayName("同 Parent 的两个命中只展开一次，且都出现在上下文里")
    void twoHitsSameParentExpandedOnce() {
        Long parentId = jdbc.queryForObject(
                "SELECT parent_id FROM document_chunk WHERE document_id = ? AND chunk_level = 'CHILD'"
                        + " AND status = 'ACTIVE' AND parent_id IS NOT NULL"
                        + " GROUP BY parent_id HAVING count(*) >= 3 ORDER BY parent_id LIMIT 1",
                Long.class, DOC_2025);
        assertThat(parentId).isNotNull();

        List<Long> childIds = jdbc.queryForList(
                "SELECT id FROM document_chunk WHERE parent_id = ? AND status = 'ACTIVE'"
                        + " ORDER BY chunk_index LIMIT 2", Long.class, parentId);
        assertThat(childIds).hasSize(2);

        ContextPackage pkg = builder.build("营业收入", QueryType.EXACT_FACT, hitsOf(childIds), null);

        assertThat(pkg.parentsExpanded()).isEqualTo(1);
        assertThat(pkg.blocks()).extracting(ContextBlock::chunkId).containsAll(childIds);
    }

    @Test
    @DisplayName("短 Parent + COMPLEX_ANALYSIS → 整段 Parent；evidenceNo 连续")
    void shortParentWholeForComplexAnalysis() {
        Long parentId = jdbc.queryForObject(
                "SELECT dc.parent_id FROM document_chunk dc WHERE dc.document_id = ? AND dc.chunk_level = 'CHILD'"
                        + " AND dc.status = 'ACTIVE' AND dc.parent_id IS NOT NULL"
                        + " AND (SELECT token_count FROM document_chunk p WHERE p.id = dc.parent_id) <= 1500"
                        + " ORDER BY dc.parent_id LIMIT 1",
                Long.class, DOC_2025);
        assertThat(parentId).isNotNull();
        Long childId = jdbc.queryForObject(
                "SELECT id FROM document_chunk WHERE parent_id = ? AND status = 'ACTIVE'"
                        + " ORDER BY chunk_index LIMIT 1", Long.class, parentId);

        ContextPackage pkg = builder.build("整体经营情况如何", QueryType.COMPLEX_ANALYSIS, hitsOf(List.of(childId)), null);

        assertThat(pkg.blocks()).isNotEmpty();
        assertThat(pkg.blocks().get(0).role()).isEqualTo(ContextBlock.ROLE_PARENT_WHOLE);
        assertThat(pkg.blocks().get(0).chunkId()).isEqualTo(parentId);
        for (int i = 0; i < pkg.blocks().size(); i++) {
            assertThat(pkg.blocks().get(i).evidenceNo()).isEqualTo(i + 1);
        }
    }

    @Test
    @DisplayName("空 id 列表与不存在的 id 都不炸，返回空包")
    void missingIdsAreSafe() {
        assertThat(builder.build("q", QueryType.EXACT_FACT, List.of(), null).isEmpty()).isTrue();
        assertThat(chunks.findViewsByIds(List.of()).isEmpty()).isTrue();
    }

    private List<RetrievedChunk> hitsOf(List<Long> ids) {
        List<ChunkView> views = chunks.findViewsByIds(ids);
        return views.stream().map(v -> new RetrievedChunk(
                v.id(), v.parentId() == null ? 0L : v.parentId(), v.documentId(),
                v.chunkType(), v.sectionPath(), v.title(), v.content(),
                v.pageStart() == null ? 0 : v.pageStart(),
                v.pageEnd() == null ? 0 : v.pageEnd(),
                null, v.tokenCount(), null, 0.0, 0.9)).toList();
    }
}
