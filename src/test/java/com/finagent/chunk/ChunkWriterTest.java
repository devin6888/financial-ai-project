package com.finagent.chunk;

import com.finagent.chunk.model.Chunk;
import com.finagent.chunk.model.ChunkLevel;
import com.finagent.chunk.model.ChunkType;
import com.finagent.chunk.model.ParentGroup;
import com.finagent.embedding.EmbeddingService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 入库与原子切换的集成测试（真实 PostgreSQL）。
 *
 * <p><b>命名不能是 *IT</b>（评审 M2）：surefire 默认 includes 不含 *IT，
 * 那样这些用例永远不会在 mvn test 里跑，核心验收是假绿灯。
 */
@SpringBootTest
@ActiveProfiles("test")
class ChunkWriterTest {

    /** 假模型：不打网络，回固定维度的零向量。CALLS 记录 embed 被调用次数（增量重嵌断言用）。 */
    static class FakeModel implements EmbeddingModel {
        static final java.util.concurrent.atomic.AtomicInteger CALLS =
                new java.util.concurrent.atomic.AtomicInteger();

        @Override
        public List<float[]> embed(List<String> texts) {
            CALLS.incrementAndGet();
            List<float[]> out = new ArrayList<>();
            for (int i = 0; i < texts.size(); i++) {
                out.add(new float[1024]);
            }
            return out;
        }

        @Override
        public EmbeddingResponse call(EmbeddingRequest request) {
            return null;
        }

        @Override
        public float[] embed(Document document) {
            return new float[1024];
        }
    }

    @TestConfiguration
    static class FakeEmbedding {
        @Bean
        @Primary
        EmbeddingService fakeEmbeddingService() {
            return new EmbeddingService(new FakeModel());
        }
    }

    @Autowired private ChunkWriter writer;
    @Autowired private ChunkRepository chunks;
    @Autowired private JdbcTemplate jdbc;

    private long documentId;

    @BeforeEach
    void createDocument() {
        String hash = "test-" + UUID.randomUUID();
        // tenant_id 是 NOT NULL（V9）：测试 profile 连 owner，不走 RLS，但约束照样生效
        jdbc.update("""
                INSERT INTO document (tenant_id, doc_type, title, object_key, content_hash, parse_status)
                VALUES (1, 'ANNUAL', '切块测试文档', 'test/x.pdf', ?, 'DONE')
                """, hash);
        documentId = jdbc.queryForObject(
                "SELECT id FROM document WHERE tenant_id = 1 AND content_hash = ?", Long.class, hash);
    }

    @AfterEach
    void cleanup() {
        jdbc.update("DELETE FROM document_chunk WHERE document_id = ?", documentId);
        jdbc.update("DELETE FROM document WHERE id = ?", documentId);
    }

    private static Chunk parent(int index) {
        return new Chunk(ChunkLevel.PARENT, ChunkType.TEXT,
                List.of("第一节"), "第一节", "父内容",
                1, 1, "b0", null, 3, index,
                null, null, null, null, null, null);
    }

    private static Chunk child(int index, String blockId) {
        return new Chunk(ChunkLevel.CHILD, ChunkType.TEXT,
                List.of("第一节"), "第一节", "子内容" + index,
                1, 1, blockId, null, 4, index,
                null, null, null, null, null, null);
    }

    /** 1 个 Parent + 3 个 Child 的最小批次。 */
    private static List<ParentGroup> groups(Chunk parent) {
        return List.of(new ParentGroup(parent, List.of(), List.of()));
    }


    @Test
    void writtenChunksInheritTenantFromDocument() {
        writer.writeVersion(documentId, "标题", groups(parent(0)),
                List.of(List.of(child(0, "b0"), child(1, "b1"))), "2.138.0");

        Long docTenant = jdbc.queryForObject(
                "SELECT tenant_id FROM document WHERE id = ?", Long.class, documentId);
        List<Long> chunkTenants = jdbc.queryForList(
                "SELECT DISTINCT tenant_id FROM document_chunk WHERE document_id = ?",
                Long.class, documentId);

        // 写路径不从调用方拿 tenant_id，而是从 document 行推导：子行永远不会挂到别的租户
        assertThat(chunkTenants).containsExactly(docTenant);

        Integer mismatched = jdbc.queryForObject("""
                SELECT count(*) FROM document_chunk c JOIN document d ON d.id = c.document_id
                 WHERE c.tenant_id <> d.tenant_id
                """, Integer.class);
        assertThat(mismatched).isZero();
    }

    @Test
    void firstWriteSwitchesToVersionOne() {
        int version = writer.writeVersion(documentId, "标题",
                groups(parent(0)),
                List.of(List.of(child(0, "b0"), child(1, "b1"), child(2, "b2"))),
                "2.138.0");

        assertThat(version).isEqualTo(1);
        assertThat(chunks.countByVersion(documentId, 1, "PARENT")).isEqualTo(1);
        assertThat(chunks.countByVersion(documentId, 1, "CHILD")).isEqualTo(3);
        assertThat(chunks.countChildrenWithoutEmbedding(documentId, 1)).isZero();
        assertThat(chunks.activeVersion(documentId)).isEqualTo(1);
    }

    @Test
    void secondWriteSupersedesOldVersion() {
        writer.writeVersion(documentId, "标题", groups(parent(0)),
                List.of(List.of(child(0, "b0"))), "2.138.0");
        int v2 = writer.writeVersion(documentId, "标题", groups(parent(0)),
                List.of(List.of(child(0, "b0"), child(1, "b1"))), "2.138.0");

        assertThat(v2).isEqualTo(2);
        assertThat(chunks.activeVersion(documentId)).isEqualTo(2);
        // 旧版本的行还在，但已不是 ACTIVE —— 保留供审计/回滚
        assertThat(chunks.countAll(documentId, 1)).isEqualTo(2);
        assertThat(chunks.countActiveInVersion(documentId, 1)).isZero();
        assertThat(chunks.countActiveInVersion(documentId, 2)).isEqualTo(3);
    }

    @Test
    void failureRollsBackAndKeepsOldVersionActive() {
        int v1 = writer.writeVersion(documentId, "标题", groups(parent(0)),
                List.of(List.of(child(0, "b0"))), "2.138.0");

        // 注入失败：第二个 Parent 的 chunk_index 与第一个重复 → 撞唯一索引
        var dup = groups(parent(0));   // 同 index 的 Parent 插两次
        assertThatThrownBy(() -> writer.writeVersion(documentId, "标题", dup,
                List.of(List.of(child(0, "b0")), List.of(child(0, "b0"))), "2.138.0"))
                .isInstanceOf(Exception.class);

        // 关键断言：旧版本仍然可用，指针没动，失败版本没有残留
        assertThat(chunks.activeVersion(documentId)).isEqualTo(v1);
        assertThat(chunks.countActiveInVersion(documentId, v1)).isEqualTo(2);
        assertThat(chunks.countAll(documentId, v1 + 1)).isZero();
    }

    @Test
    void unchangedChildrenReuseVectorsWithoutApiCall() {
        // 版本 1：2 个子块，需要真嵌一次
        writer.writeVersion(documentId, "标题", groups(parent(0)),
                List.of(List.of(child(0, "b0"), child(1, "b1"))), "2.138.0");
        int callsAfterV1 = FakeModel.CALLS.get();

        // 版本 2：同样 2 个子块（内容不变 → hash 相同）→ 全部复用，不应再调 API
        int v2 = writer.writeVersion(documentId, "标题", groups(parent(0)),
                List.of(List.of(child(0, "b0"), child(1, "b1"))), "2.138.0");

        assertThat(v2).isEqualTo(2);
        assertThat(FakeModel.CALLS.get()).isEqualTo(callsAfterV1);
        assertThat(chunks.countChildrenWithoutEmbedding(documentId, v2)).isZero();
    }
}
