package com.finagent.chunk;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 旧 chunk 版本清理的真库用例（阶段 20 技术债 #3）。
 *
 * <p>全部在<b>自己造的文档</b>上做：造 5 个版本（每版 2 行），让 v2 的第 1 行被历史消息引用、
 * v1 的第 1 行被事实表引用，再验证「待删版本里的被引用行留下、其余行删掉、active 与最近 3 版不动」。
 * 不动真实语料；整个用例在事务里，跑完回滚。
 */
@Tag("db")
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class ChunkRetentionDbTest {

    @Autowired private ChunkRetentionService retention;
    @Autowired private JdbcTemplate jdbc;

    @Test
    void planKeepsActiveAndRecentVersions() {
        long docId = newDocument();

        RetentionPlan plan = retention.plan(docId);

        // 5 个版本、保留最近 3 个（v5/v4/v3）+ active（v5）→ 待删 v1、v2
        assertThat(plan.activeVersion()).isEqualTo(5);
        assertThat(plan.keepVersions()).containsExactly(5, 4, 3);
        assertThat(plan.deleteVersions()).containsExactly(1, 2);
        assertThat(plan.rowsToDelete()).isEqualTo(4);
        // v1 第 1 行（事实表）与 v2 第 1 行（历史消息）被引用，不会被删
        assertThat(plan.rowsSpared()).isEqualTo(2);
        assertThat(plan.rowsToFree()).isEqualTo(2);
    }

    @Test
    void applyDeletesOnlyUnreferencedRowsOfDroppedVersions() {
        long docId = newDocument();
        RetentionPlan plan = retention.plan(docId);

        int deleted = retention.apply(plan);

        assertThat(deleted).isEqualTo(2);
        // v1、v2 各剩 1 行（被引用的那行还在），v3~v5 原样
        assertThat(countRows(docId, 1)).isEqualTo(1);
        assertThat(countRows(docId, 2)).isEqualTo(1);
        assertThat(countRows(docId, 3)).isEqualTo(2);
        assertThat(countRows(docId, 5)).isEqualTo(2);
    }

    @Test
    void citedRowsSurviveSoOldAnswersStillOpen() {
        long docId = newDocument();
        long cited = jdbc.queryForObject(
                "SELECT chunk_id FROM financial_fact WHERE document_id = ?", Long.class, docId);

        retention.apply(retention.plan(docId));

        // 引用指向的 chunk 必须还在，否则历史答案点引用会 404
        Long stillThere = jdbc.queryForObject(
                "SELECT count(*) FROM document_chunk WHERE id = ?", Long.class, cited);
        assertThat(stillThere).isEqualTo(1);
    }

    @Test
    void planOnDocumentWithNothingToDeleteIsEmpty() {
        long docId = newDocumentWithVersions(2);

        RetentionPlan plan = retention.plan(docId);

        assertThat(plan.deleteVersions()).isEmpty();
        assertThat(plan.rowsToFree()).isZero();
        assertThat(retention.apply(plan)).isZero();
    }

    @Test
    void keepSetNeverDropsActiveEvenWhenItIsOld() {
        // 版本号降序是 [9,7,5]，但 active 指向 5（比如回滚过）—— active 必须留
        Set<Integer> keep = ChunkRetentionService.keepSet(List.of(9, 7, 5), 5, 2);

        assertThat(keep).containsExactlyInAnyOrder(5, 9, 7);
    }

    // ------------------------------------------------------------ 造数据

    /** 造 5 个版本的文档：每版 2 行 CHILD，v5 是 ACTIVE；v2 第 1 行被消息引用、v1 第 1 行被事实表引用。 */
    private long newDocument() {
        long docId = newDocumentWithVersions(5);
        Long v1chunk = chunkIdOf(docId, 1);
        Long v2chunk = chunkIdOf(docId, 2);

        Long owner = jdbc.queryForObject("SELECT id FROM app_user ORDER BY id LIMIT 1", Long.class);
        Long conversationId = jdbc.queryForObject("""
                INSERT INTO conversation (tenant_id, owner_user_id, title)
                VALUES (1, ?, '清理测试会话') RETURNING id
                """, Long.class, owner);
        jdbc.update("""
                INSERT INTO conversation_message (tenant_id, conversation_id, role, content, citations_json)
                VALUES (1, ?, 'ASSISTANT', '带引用的答案', ?::jsonb)
                """, conversationId,
                "[{\"childChunkId\":" + v2chunk + ",\"pageStart\":7}]");

        jdbc.update("""
                INSERT INTO financial_fact (tenant_id, document_id, chunk_id, metric, period, value, unit)
                VALUES (1, ?, ?, '营业总收入', '2025', 1.0, '元')
                """, docId, v1chunk);
        return docId;
    }

    /** 造 versions 个版本、每版 2 行；最后一版 ACTIVE。 */
    private long newDocumentWithVersions(int versions) {
        String hash = "retention-hash-" + System.nanoTime();
        Long docId = jdbc.queryForObject("""
                INSERT INTO document (tenant_id, doc_type, title, object_key, content_hash,
                                      parse_status, active_chunk_version)
                VALUES (1, 'ANNUAL', '清理测试文档', ?, ?, 'DONE', ?)
                RETURNING id
                """, Long.class, "test/" + hash + ".pdf", hash, versions);

        for (int version = 1; version <= versions; version++) {
            for (int i = 0; i < 2; i++) {
                jdbc.update("""
                        INSERT INTO document_chunk (tenant_id, document_id, chunk_level, chunk_type,
                                                    content, chunk_index, status, chunk_version)
                        VALUES (1, ?, 'CHILD', 'TEXT', ?, ?, ?, ?)
                        """, docId, "版本" + version + "第" + i + "段", version * 10 + i,
                        version == versions ? "ACTIVE" : "SUPERSEDED", version);
            }
        }
        return docId;
    }

    private Long chunkIdOf(long documentId, int version) {
        return jdbc.queryForObject(
                "SELECT id FROM document_chunk WHERE document_id = ? AND chunk_version = ? ORDER BY id LIMIT 1",
                Long.class, documentId, version);
    }

    private Long countRows(long documentId, int version) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM document_chunk WHERE document_id = ? AND chunk_version = ?",
                Long.class, documentId, version);
    }
}
