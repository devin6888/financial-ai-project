package com.finagent;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("db")
@SpringBootTest
@ActiveProfiles("test")
// 每个测试方法在事务中执行并自动回滚，保证测试可重复运行且不污染开发库
@Transactional
class VectorStoreSmokeTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private EmbeddingModel embeddingModel;

    @Autowired
    private VectorStore vectorStore;

    @Test
    void springAiBeansAreWired() {
        assertThat(embeddingModel).isNotNull();
        assertThat(vectorStore).isNotNull();
    }

    @Test
    void pgvectorRoundTripWorksWith1024Dimensions() {
        // V9 之后唯一约束是「租户内唯一」：company 是 (tenant_id, code)、document 是 (tenant_id, content_hash)
        jdbcTemplate.update(
                "INSERT INTO company(tenant_id, code, name) VALUES (1,'600519','贵州茅台') "
                        + "ON CONFLICT (tenant_id, code) DO NOTHING");
        Long companyId = jdbcTemplate.queryForObject(
                "SELECT id FROM company WHERE code='600519' AND tenant_id=1 ORDER BY id DESC LIMIT 1", Long.class);

        // 冒烟测试可以重复跑：先清掉上一次的块，再按租户内唯一键复用同一份文档
        jdbcTemplate.update(
                "DELETE FROM document_chunk WHERE document_id IN "
                        + "(SELECT id FROM document WHERE tenant_id=1 AND content_hash='hash-smoke')");
        jdbcTemplate.update(
                "INSERT INTO document(tenant_id, company_id, doc_type, doc_year, title, object_key, content_hash) "
                        // 年份故意用 1999：V6 的 uq_document_identity 会拦住与已有文档
                        // （公司 600519 + 2025 + ANNUAL + v1）重复的身份，用真实年份会撞库
                        + "VALUES (1, ?, 'ANNUAL', 1999, '冒烟测试', 'test/smoke.pdf', 'hash-smoke') "
                        + "ON CONFLICT (tenant_id, content_hash) DO NOTHING",
                companyId);
        Long docId = jdbcTemplate.queryForObject(
                "SELECT id FROM document WHERE tenant_id=1 AND content_hash='hash-smoke' "
                        + "ORDER BY id DESC LIMIT 1", Long.class);

        jdbcTemplate.update(
                "INSERT INTO document_chunk(tenant_id, document_id, chunk_level, chunk_type, content, "
                        + "page_start, embedding) "
                        + "VALUES (1, ?, 'CHILD', 'TEXT', '2025 年营业收入', 128, ?::vector)",
                docId, vectorLiteral(1024, 0.01f));

        Double distance = jdbcTemplate.queryForObject(
                "SELECT embedding <=> ?::vector FROM document_chunk WHERE document_id = ? ORDER BY id DESC LIMIT 1",
                Double.class, vectorLiteral(1024, 0.02f), docId);

        assertThat(distance).isNotNull();
        assertThat(distance).isGreaterThanOrEqualTo(0.0);
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "SPRING_AI_OPENAI_API_KEY", matches = ".+")
    void dashscopeEmbeddingReturns1024Dimensions() {
        float[] vector = embeddingModel.embed("2025 年营业收入同比增长");
        assertThat(vector).hasSize(1024);
    }

    private static String vectorLiteral(int dimensions, float value) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < dimensions; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(value);
        }
        return sb.append(']').toString();
    }
}
