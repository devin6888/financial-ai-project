package com.finagent.chunk;

import com.finagent.chunk.model.Chunk;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;

/** document_chunk 的 SQL。只负责拼语句，不负责事务与校验。 */
@Repository
public class ChunkRepository {

    private final JdbcTemplate jdbc;

    public ChunkRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 当前 active 版本；从未索引过时为 0。 */
    public int activeVersion(long documentId) {
        Integer v = jdbc.queryForObject(
                "SELECT coalesce(active_chunk_version, 0) FROM document WHERE id = ?",
                Integer.class, documentId);
        return v == null ? 0 : v;
    }

    public long insertParent(long documentId, int chunkVersion, Chunk c, String parserVersion) {
        Long id = jdbc.queryForObject("""
                INSERT INTO document_chunk (document_id, parent_id, chunk_level, chunk_type,
                    section_path, title, content, page_start, page_end, source_block_ids,
                    bbox, token_count, chunk_index, status, parser_version,
                    chunk_strategy_version, chunk_version)
                VALUES (?, NULL, 'PARENT', ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, 'ACTIVE', ?, ?, ?)
                RETURNING id
                """, Long.class,
                documentId, c.type().name(), c.sectionPathText(), c.title(), c.content(),
                c.pageStart(), c.pageEnd(), c.sourceBlockIds(), c.bboxJson(),
                c.tokenCount(), c.chunkIndex(), parserVersion, ChunkStrategy.VERSION, chunkVersion);
        return id == null ? -1L : id;
    }

    public long insertChild(long documentId, long parentId, int chunkVersion, Chunk c,
                            float[] embedding, String embeddingTextHash,
                            String embeddingModel, String embeddingVersion, String parserVersion) {
        Long id = jdbc.queryForObject("""
                INSERT INTO document_chunk (document_id, parent_id, chunk_level, chunk_type,
                    section_path, title, content, page_start, page_end, source_block_ids,
                    bbox, token_count, chunk_index, status, parser_version,
                    chunk_strategy_version, chunk_version, caption, unit, currency,
                    headers_json, related_footnote_ids, embedding, embedding_model,
                    embedding_version, embedding_text_hash)
                VALUES (?, ?, 'CHILD', ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, 'ACTIVE', ?, ?, ?,
                        ?, ?, ?, ?::jsonb, ?, ?::vector, ?, ?, ?)
                RETURNING id
                """, Long.class,
                documentId, parentId, c.type().name(), c.sectionPathText(), c.title(), c.content(),
                c.pageStart(), c.pageEnd(), c.sourceBlockIds(), c.bboxJson(),
                c.tokenCount(), c.chunkIndex(), parserVersion, ChunkStrategy.VERSION, chunkVersion,
                c.caption(), c.unit(), c.currency(), c.headersJson(), c.relatedFootnoteIds(),
                toVectorLiteral(embedding), embeddingModel, embeddingVersion, embeddingTextHash);
        return id == null ? -1L : id;
    }

    public int countByVersion(long documentId, int chunkVersion, String level) {
        Integer n = jdbc.queryForObject("""
                SELECT count(*) FROM document_chunk
                 WHERE document_id = ? AND chunk_version = ? AND chunk_level = ?
                """, Integer.class, documentId, chunkVersion, level);
        return n == null ? 0 : n;
    }

    public int countChildrenWithoutEmbedding(long documentId, int chunkVersion) {
        Integer n = jdbc.queryForObject("""
                SELECT count(*) FROM document_chunk
                 WHERE document_id = ? AND chunk_version = ? AND chunk_level = 'CHILD'
                   AND embedding IS NULL
                """, Integer.class, documentId, chunkVersion);
        return n == null ? 0 : n;
    }

    public int countActiveInVersion(long documentId, int chunkVersion) {
        Integer n = jdbc.queryForObject("""
                SELECT count(*) FROM document_chunk
                 WHERE document_id = ? AND chunk_version = ? AND status = 'ACTIVE'
                """, Integer.class, documentId, chunkVersion);
        return n == null ? 0 : n;
    }

    public int countAll(long documentId, int chunkVersion) {
        Integer n = jdbc.queryForObject("""
                SELECT count(*) FROM document_chunk WHERE document_id = ? AND chunk_version = ?
                """, Integer.class, documentId, chunkVersion);
        return n == null ? 0 : n;
    }

    /** 原子切换：只更新指针，提交后检索立刻看到新版本。 */
    public void switchActiveVersion(long documentId, int chunkVersion) {
        jdbc.update("""
                UPDATE document SET active_chunk_version = ?, chunk_strategy_version = ?
                 WHERE id = ?
                """, chunkVersion, ChunkStrategy.VERSION, documentId);
    }

    public int supersedeOtherVersions(long documentId, int keepVersion) {
        return jdbc.update("""
                UPDATE document_chunk SET status = 'SUPERSEDED'
                 WHERE document_id = ? AND chunk_version <> ? AND status = 'ACTIVE'
                """, documentId, keepVersion);
    }

    /**
     * 某个版本下 CHILD 的 token 分布。
     *
     * <p>为什么要有它：验收 #3 的口径是「100% ≤ 700 且 ≥80% ≥150」，
     * 而评审明确指出「不要遮一个假的达标率，要输出完整分布」。
     */
    public ChunkStats statsByVersion(long documentId, int chunkVersion) {
        return jdbc.queryForObject("""
                SELECT count(*)                                                       AS n,
                       coalesce(min(token_count), 0)                                   AS mn,
                       coalesce(percentile_disc(0.5) WITHIN GROUP (ORDER BY token_count), 0) AS p50,
                       coalesce(percentile_disc(0.9) WITHIN GROUP (ORDER BY token_count), 0) AS p90,
                       coalesce(max(token_count), 0)                                   AS mx,
                       count(*) FILTER (WHERE token_count <= 700)                      AS le700,
                       count(*) FILTER (WHERE token_count >= 150)                      AS ge150
                  FROM document_chunk
                 WHERE document_id = ? AND chunk_version = ? AND chunk_level = 'CHILD'
                """, (rs, n) -> new ChunkStats(
                        rs.getInt("n"), rs.getInt("mn"), rs.getInt("p50"), rs.getInt("p90"),
                        rs.getInt("mx"), rs.getInt("le700"), rs.getInt("ge150")),
                documentId, chunkVersion);
    }

    public int countOverToken(long documentId, int chunkVersion, String level, int threshold) {
        Integer n = jdbc.queryForObject("""
                SELECT count(*) FROM document_chunk
                 WHERE document_id = ? AND chunk_version = ? AND chunk_level = ?
                   AND token_count > ?
                """, Integer.class, documentId, chunkVersion, level, threshold);
        return n == null ? 0 : n;
    }

    public int countOverTokenByType(long documentId, int chunkVersion, String level,
                                    String type, int threshold) {
        Integer n = jdbc.queryForObject("""
                SELECT count(*) FROM document_chunk
                 WHERE document_id = ? AND chunk_version = ? AND chunk_level = ?
                   AND chunk_type = ? AND token_count > ?
                """, Integer.class, documentId, chunkVersion, level, type, threshold);
        return n == null ? 0 : n;
    }

    public int countByType(long documentId, int chunkVersion, String level, String type) {
        Integer n = jdbc.queryForObject("""
                SELECT count(*) FROM document_chunk
                 WHERE document_id = ? AND chunk_version = ? AND chunk_level = ? AND chunk_type = ?
                """, Integer.class, documentId, chunkVersion, level, type);
        return n == null ? 0 : n;
    }

    /** 上下文完整性：section_path / bbox / embedding_text_hash 三者齐全的 CHILD 数。 */
    public int countWithFullContext(long documentId, int chunkVersion) {
        Integer n = jdbc.queryForObject("""
                SELECT count(*) FROM document_chunk
                 WHERE document_id = ? AND chunk_version = ? AND chunk_level = 'CHILD'
                   AND section_path IS NOT NULL AND bbox IS NOT NULL
                   AND embedding_text_hash IS NOT NULL
                """, Integer.class, documentId, chunkVersion);
        return n == null ? 0 : n;
    }

    /** CHILD 的 token 分布与达标计数。 */
    public record ChunkStats(int count, int min, int p50, int p90, int max,
                             int atMost700Count, int atLeast150Count) {

        public double pctAtMost(int threshold, int countOf) {
            return count == 0 ? 0.0 : 100.0 * countOf / count;
        }

        public double pctAtMost700() {
            return pctAtMost(700, atMost700Count);
        }

        public double pctAtLeast150() {
            return pctAtMost(150, atLeast150Count);
        }
    }

    /**
     * 按 id 批量取 chunk（含 Parent 与文档标题）。
     *
     * <p>为什么带文档标题：上下文渲染要给模型看「这块来自哪份报告」——
     * 不写标题的话，两份年报的「营业收入」在文本里长得一模一样，模型无法区分。
     */
    public List<ChunkView> findViewsByIds(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        String sql = CHUNK_VIEW_SELECT + " WHERE dc.id IN ("
                + String.join(",", java.util.Collections.nCopies(ids.size(), "?")) + ")";
        return jdbc.query(sql, CHUNK_VIEW_MAPPER, ids.toArray());
    }

    /**
     * 取这些 Parent 下的全部 Child（保证 readingOrder：按 chunk_index 升序）。
     *
     * <p>只取 ACTIVE：版本切换后旧版本的块是 SUPERSEDED，混进来会得到两份互相矛盾的证据。
     */
    public List<ChunkView> findChildViewsByParents(List<Long> parentIds) {
        if (parentIds == null || parentIds.isEmpty()) {
            return List.of();
        }
        String sql = CHUNK_VIEW_SELECT + " WHERE dc.parent_id IN ("
                + String.join(",", java.util.Collections.nCopies(parentIds.size(), "?")) + ")"
                + " AND dc.status = 'ACTIVE' ORDER BY dc.chunk_index";
        return jdbc.query(sql, CHUNK_VIEW_MAPPER, parentIds.toArray());
    }

    private static final String CHUNK_VIEW_SELECT = """
            SELECT dc.id, dc.document_id, dc.parent_id, dc.chunk_level, dc.chunk_type,
                   dc.section_path, dc.title, dc.content, dc.page_start, dc.page_end,
                   dc.token_count, dc.chunk_index, dc.caption, dc.unit,
                   dc.headers_json::text AS headers_json, dc.bbox::text AS bbox_json,
                   d.title AS doc_title
              FROM document_chunk dc
              JOIN document d ON d.id = dc.document_id
            """;

    private static final org.springframework.jdbc.core.RowMapper<ChunkView> CHUNK_VIEW_MAPPER = (rs, n) ->
            new ChunkView(
                    rs.getLong("id"),
                    rs.getLong("document_id"),
                    (Long) rs.getObject("parent_id"),
                    rs.getString("chunk_level"),
                    rs.getString("chunk_type"),
                    rs.getString("section_path"),
                    rs.getString("title"),
                    rs.getString("content"),
                    (Integer) rs.getObject("page_start"),
                    (Integer) rs.getObject("page_end"),
                    rs.getInt("token_count"),
                    rs.getInt("chunk_index"),
                    rs.getString("caption"),
                    rs.getString("unit"),
                    rs.getString("headers_json"),
                    rs.getString("bbox_json"),
                    rs.getString("doc_title"));

    /**
     * 上下文装配需要的块视图。
     *
     * <p>页码与 bbox 用 {@link Integer} / {@link String} 可空：
     * 库里这两列本来就可空，用基本类型会在遇到 null 时抛 NPE —— 而这是数据现状，不是异常。
     */
    public record ChunkView(long id, long documentId, Long parentId, String chunkLevel,
                            String chunkType, String sectionPath, String title, String content,
                            Integer pageStart, Integer pageEnd, int tokenCount, int chunkIndex,
                            String caption, String unit, String headersJson, String bboxJson,
                            String docTitle) {
    }

    /** 清理某个版本（测试与失败恢复用）。 */
    public int deleteVersion(long documentId, int chunkVersion) {
        return jdbc.update(
                "DELETE FROM document_chunk WHERE document_id = ? AND chunk_version = ?",
                documentId, chunkVersion);
    }

    /** pgvector 的字面量形式：[1.0,2.0,...] */
    /**
     * pgvector 的字面量形式：[1.0,2.0,...]
     *
     * <p>为什么是 public：检索侧的 DenseRetriever 也要用它。
     * <b>只保留一个实现</b> —— 两处各写一份的话，格式一旦不一致，
     * 会出现「插入能成功但查询报错」这种极难排查的问题。
     */
    public static String toVectorLiteral(float[] v) {
        if (v == null) {
            return null;
        }
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < v.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(v[i]);
        }
        return sb.append(']').toString();
    }
}
