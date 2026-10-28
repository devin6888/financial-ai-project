package com.finagent.document;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link DocumentRepository#listDocuments(int)} 打真库验两件事：
 * ① 未解析完成的文档**必须**出现在列表里（否则文档页看不到「在等什么」）；
 * ② chunkCount 与「ACTIVE 版本的 CHILD 行数」逐字相等（口径不能自己发明）。
 */
@SpringBootTest
@ActiveProfiles("test")
class DocumentListDbTest {

    private static final String PENDING_TITLE = "test-doc-list-pending";

    @Autowired
    private DocumentRepository documents;

    @Autowired
    private JdbcTemplate jdbc;

    @AfterEach
    void tearDown() {
        jdbc.update("DELETE FROM document WHERE title = ?", PENDING_TITLE);
    }

    @Test
    @DisplayName("列表同时包含 DONE 与 PENDING（未解析的不隐藏）")
    void listIncludesNotDoneDocuments() {
        long id = jdbc.queryForObject("""
                INSERT INTO document (tenant_id, doc_type, title, object_key, content_hash, parse_status)
                VALUES (1, 'ANNUAL', ?, 'test/doc-list.pdf', ?, 'PENDING')
                RETURNING id
                """, Long.class, PENDING_TITLE, "test-hash-" + System.nanoTime());

        List<DocumentRepository.DocumentRow> rows = documents.listDocuments(200);

        Optional<DocumentRepository.DocumentRow> pending = rows.stream()
                .filter(r -> r.id() == id).findFirst();
        assertThat(pending).isPresent();
        assertThat(pending.get().parseStatus()).isEqualTo("PENDING");
        assertThat(pending.get().chunkCount()).isZero();
        assertThat(pending.get().activeChunkVersion()).isNull();
        assertThat(rows).anyMatch(r -> "DONE".equals(r.parseStatus()));
    }

    @Test
    @DisplayName("chunkCount 恰等于 ACTIVE 版本的 CHILD 行数")
    void chunkCountMatchesActiveChildRows() {
        DocumentRepository.DocumentRow doc = documents.listDocuments(200).stream()
                .filter(r -> r.id() == 7L)
                .findFirst()
                .orElseThrow(() -> new AssertionError("库里应有 document 7（东方财富2025年报）"));

        Long expected = jdbc.queryForObject("""
                SELECT count(*) FROM document d
                  JOIN document_chunk dc ON dc.document_id = d.id
                 WHERE d.id = 7
                   AND d.active_chunk_version IS NOT NULL
                   AND dc.chunk_version = d.active_chunk_version
                   AND dc.chunk_level = 'CHILD'
                   AND dc.status = 'ACTIVE'
                """, Long.class);

        assertThat(doc.chunkCount()).isEqualTo(expected);
        assertThat(doc.companyCode()).isEqualTo("300059");
        assertThat(doc.pagesParsed()).isNotNull();
    }

    @Test
    @DisplayName("limit 生效：1 条就只回 1 条")
    void limitIsApplied() {
        assertThat(documents.listDocuments(1)).hasSize(1);
    }
}
