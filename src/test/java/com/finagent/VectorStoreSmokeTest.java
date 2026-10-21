package com.finagent;

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
        jdbcTemplate.update(
                "INSERT INTO company(code, name) VALUES ('600519','贵州茅台') ON CONFLICT (code) DO NOTHING");
        Long companyId = jdbcTemplate.queryForObject(
                "SELECT id FROM company WHERE code='600519' ORDER BY id DESC LIMIT 1", Long.class);

        jdbcTemplate.update(
                "INSERT INTO document(company_id, doc_type, doc_year, title, object_key, content_hash) "
                        // 年份故意用 1999：V6 的 uq_document_identity 会拦住与已有文档
                        // （公司 600519 + 2025 + ANNUAL + v1）重复的身份，用真实年份会撞库
                        + "VALUES (?, 'ANNUAL', 1999, '冒烟测试', 'test/smoke.pdf', 'hash-smoke')",
                companyId);
        Long docId = jdbcTemplate.queryForObject(
                "SELECT id FROM document WHERE content_hash='hash-smoke' ORDER BY id DESC LIMIT 1", Long.class);

        jdbcTemplate.update(
                "INSERT INTO document_chunk(document_id, chunk_level, chunk_type, content, page_start, embedding) "
                        + "VALUES (?, 'CHILD', 'TEXT', '2025 年营业收入', 128, ?::vector)",
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
