package com.finagent.document;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 进度单调性的真库验证（ST18 需求 2）。
 *
 * <p>为什么必须在真库测：GREATEST 与 CASE 的先后顺序、以及「阶段文案只在前进时改写」
 * 全靠一条 SQL 表达，纯 mock 只能验「调了没」，验不了「写进去了什么」。
 */
@Tag("db")
@SpringBootTest
@ActiveProfiles("test")
class DocumentProgressDbTest {

    private static final String TITLE = "test-doc-progress";

    @Autowired
    private DocumentRepository documents;

    @Autowired
    private DocumentProgress progress;

    @Autowired
    private JdbcTemplate jdbc;

    @AfterEach
    void tearDown() {
        jdbc.update("DELETE FROM document WHERE title = ?", TITLE);
    }

    private long insertPending() {
        return jdbc.queryForObject("""
                INSERT INTO document (tenant_id, doc_type, title, object_key, content_hash, parse_status)
                VALUES (1, 'ANNUAL', ?, 'test/progress.pdf', ?, 'PENDING')
                RETURNING id
                """, Long.class, TITLE, "test-progress-" + System.nanoTime());
    }

    @Test
    @DisplayName("进度只增不减：先 50 再 30，库里仍是 50 且阶段文案不被回退覆盖")
    void progressNeverGoesBackwards() {
        long id = insertPending();

        progress.step(id, DocumentProgress.CHUNKING, DocumentProgress.STAGE_CHUNKING);
        progress.step(id, DocumentProgress.PARSING, DocumentProgress.STAGE_PARSING);

        DocumentRepository.DocumentRow row = documents.listDocuments(200).stream()
                .filter(r -> r.id() == id).findFirst().orElseThrow();
        assertThat(row.parseProgress()).isEqualTo(DocumentProgress.CHUNKING);
        assertThat(row.progressStage()).isEqualTo(DocumentProgress.STAGE_CHUNKING);
    }

    @Test
    @DisplayName("越界百分比被夹到 0~100（CHECK 约束不会被触发）")
    void clampsOutOfRange() {
        long id = insertPending();

        documents.updateProgress(id, 500, "已完成");

        Integer stored = jdbc.queryForObject(
                "SELECT parse_progress FROM document WHERE id = ?", Integer.class, id);
        assertThat(stored).isEqualTo(100);
    }

    @Test
    @DisplayName("列表与详情都带出进度字段（前端靠它们渲染进度条）")
    void listAndDetailExposeProgress() {
        long id = insertPending();
        progress.step(id, DocumentProgress.PARSED, DocumentProgress.STAGE_PARSED);

        DocumentRepository.DocumentRow row = documents.listDocuments(200).stream()
                .filter(r -> r.id() == id).findFirst().orElseThrow();
        assertThat(row.parseProgress()).isEqualTo(DocumentProgress.PARSED);
        assertThat(row.progressStage()).isEqualTo(DocumentProgress.STAGE_PARSED);

        DocumentRepository.DocumentDetail detail = documents.findDetail(id).orElseThrow();
        assertThat(detail.parseProgress()).isEqualTo(DocumentProgress.PARSED);
        assertThat(detail.progressStage()).isEqualTo(DocumentProgress.STAGE_PARSED);
    }

    @Test
    @DisplayName("markFailed 把阶段文案改成「失败」，进度保留最后值")
    void markFailedKeepsLastPercent() {
        long id = insertPending();
        progress.step(id, DocumentProgress.PARSING, DocumentProgress.STAGE_PARSING);

        documents.markFailed(id, "解析服务超时");

        DocumentRepository.DocumentRow row = documents.listDocuments(200).stream()
                .filter(r -> r.id() == id).findFirst().orElseThrow();
        assertThat(row.parseStatus()).isEqualTo("FAILED");
        assertThat(row.parseProgress()).isEqualTo(DocumentProgress.PARSING);
        assertThat(row.progressStage()).isEqualTo("失败");
    }
}
