package com.finagent.memory;

import com.finagent.chunk.ChunkRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

/**
 * long_term_memory 的读写。
 *
 * <p>所有查询显式带 tenant_id + subject_id：RLS 只挡租户，同一租户里两个用户的记忆
 * 互相看得见，所以「谁的记忆」必须在 SQL 里说清楚（与 ConversationRepository 同口径）。
 */
@Repository
public class LongTermMemoryRepository {

    private static final String COLUMNS = "id, tenant_id, subject_type, subject_id, memory_type, "
            + "memory_key, content, value_json::text AS value_json, source_type, source_ref, confidence, "
            + "importance, status, version, expires_at, last_accessed_at, retrieval_count, "
            + "created_at, updated_at";

    private final JdbcTemplate jdbc;
    private final RowMapper<LongTermMemory> rowMapper = this::map;

    public LongTermMemoryRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 同一 (租户, 主体, 类型, key) 的 ACTIVE 行；写了 FOR UPDATE 就锁到事务结束（冲突版本化要用）。 */
    public Optional<LongTermMemory> findActiveByKey(long tenantId, long subjectId, String memoryType,
                                                    String memoryKey, boolean forUpdate) {
        String sql = "SELECT " + COLUMNS + " FROM long_term_memory "
                + "WHERE tenant_id = ? AND subject_id = ? AND memory_type = ? AND memory_key = ? "
                + "AND status = ?" + (forUpdate ? " FOR UPDATE" : "");
        return jdbc.query(sql, rowMapper, tenantId, subjectId, memoryType, memoryKey,
                LongTermMemory.STATUS_ACTIVE).stream().findFirst();
    }

    public Optional<LongTermMemory> findById(long tenantId, long subjectId, long id) {
        return jdbc.query("SELECT " + COLUMNS + " FROM long_term_memory "
                        + "WHERE id = ? AND tenant_id = ? AND subject_id = ?",
                rowMapper, id, tenantId, subjectId).stream().findFirst();
    }

    /** 列 ACTIVE 行；memoryType 为空则不过滤类型。 */
    public List<LongTermMemory> listActive(long tenantId, long subjectId, String memoryType) {
        if (memoryType == null || memoryType.isBlank()) {
            return jdbc.query("SELECT " + COLUMNS + " FROM long_term_memory "
                            + "WHERE tenant_id = ? AND subject_id = ? AND status = ? "
                            + "ORDER BY memory_type, memory_key, id",
                    rowMapper, tenantId, subjectId, LongTermMemory.STATUS_ACTIVE);
        }
        return jdbc.query("SELECT " + COLUMNS + " FROM long_term_memory "
                        + "WHERE tenant_id = ? AND subject_id = ? AND status = ? AND memory_type = ? "
                        + "ORDER BY memory_key, id",
                rowMapper, tenantId, subjectId, LongTermMemory.STATUS_ACTIVE, memoryType);
    }

    /**
     * 候选行（不含向量排序）：召回的「先查库」这一步，用来判断「有没有必要调 embedding」。
     */
    public List<LongTermMemory> listCandidates(long tenantId, long subjectId, int limit) {
        return jdbc.query("SELECT " + COLUMNS + " FROM long_term_memory "
                        + "WHERE tenant_id = ? AND subject_id = ? AND status = ? "
                        + "AND (expires_at IS NULL OR expires_at > now()) "
                        + "ORDER BY importance DESC, id DESC LIMIT ?",
                rowMapper, tenantId, subjectId, LongTermMemory.STATUS_ACTIVE, limit);
    }

    /**
     * 向量召回。
     *
     * <p>{@code embedding <=> ?} 返回的是<b>余弦距离</b>（0 = 完全相同），不是相似度：
     * 阈值必须写「距离 &lt;= 1 - minScore」，写反了会变成「越不相关越优先」。
     */
    public List<VectorHit> searchByVector(long tenantId, long subjectId, float[] queryVector,
                                          double maxDistance, int limit) {
        return jdbc.query("SELECT " + COLUMNS + ", (embedding <=> ?::vector) AS distance "
                        + "FROM long_term_memory "
                        + "WHERE tenant_id = ? AND subject_id = ? AND status = ? "
                        + "AND (expires_at IS NULL OR expires_at > now()) AND embedding IS NOT NULL "
                        + "AND (embedding <=> ?::vector) <= ? "
                        + "ORDER BY distance LIMIT ?",
                (rs, rowNum) -> new VectorHit(map(rs, rowNum), rs.getDouble("distance")),
                ChunkRepository.toVectorLiteral(queryVector),
                tenantId, subjectId, LongTermMemory.STATUS_ACTIVE,
                ChunkRepository.toVectorLiteral(queryVector), maxDistance, limit);
    }

    /** 一条向量召回结果：命中行 + 它到查询向量的余弦距离。 */
    public record VectorHit(LongTermMemory memory, double distance) {
    }

    /** 插入一行 ACTIVE，返回新行 id。embedding 可为 null（尽力而为，见 ST9 §12.3）。 */
    public long insert(long tenantId, String subjectType, long subjectId, String memoryType, String memoryKey,
                       String content, String valueJson, float[] embedding, String embeddingModel,
                       String embeddingVersion, String sourceType, String sourceRef, double confidence,
                       int importance, int version, OffsetDateTime expiresAt) {
        Long id = jdbc.queryForObject(
                "INSERT INTO long_term_memory (tenant_id, subject_type, subject_id, memory_type, memory_key, "
                        + "content, value_json, embedding, embedding_model, embedding_version, source_type, "
                        + "source_ref, confidence, importance, status, version, expires_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, ?::vector, ?, ?, ?, ?, ?, ?, ?, ?, ?) "
                        + "RETURNING id",
                Long.class, tenantId, subjectType, subjectId, memoryType, memoryKey, content, valueJson,
                ChunkRepository.toVectorLiteral(embedding), embeddingModel, embeddingVersion, sourceType,
                sourceRef, confidence, importance, LongTermMemory.STATUS_ACTIVE, version, expiresAt);
        return id == null ? -1L : id;
    }

    /** 旧行降级。返回 false 表示它已经不是 ACTIVE（并发下别人先降级了）。 */
    public boolean supersede(long tenantId, long id) {
        return jdbc.update("UPDATE long_term_memory SET status = ?, updated_at = now() "
                        + "WHERE id = ? AND tenant_id = ? AND status = ?",
                LongTermMemory.STATUS_SUPERSEDED, id, tenantId, LongTermMemory.STATUS_ACTIVE) > 0;
    }

    public boolean markDeleted(long tenantId, long id) {
        return jdbc.update("UPDATE long_term_memory SET status = ?, updated_at = now() "
                        + "WHERE id = ? AND tenant_id = ? AND status = ?",
                LongTermMemory.STATUS_DELETED, id, tenantId, LongTermMemory.STATUS_ACTIVE) > 0;
    }

    /** 召回时顺手记一次访问；失败不影响回答，调用方要 catch。 */
    public void touchAccessed(long tenantId, long id) {
        jdbc.update("UPDATE long_term_memory SET last_accessed_at = now(), "
                + "retrieval_count = retrieval_count + 1 WHERE id = ? AND tenant_id = ?", id, tenantId);
    }

    private LongTermMemory map(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new LongTermMemory(
                rs.getLong("id"),
                rs.getLong("tenant_id"),
                rs.getString("subject_type"),
                rs.getLong("subject_id"),
                rs.getString("memory_type"),
                rs.getString("memory_key"),
                rs.getString("content"),
                rs.getString("value_json"),
                rs.getString("source_type"),
                rs.getString("source_ref"),
                rs.getBigDecimal("confidence") == null ? 1.0 : rs.getBigDecimal("confidence").doubleValue(),
                rs.getInt("importance"),
                rs.getString("status"),
                rs.getInt("version"),
                rs.getObject("expires_at", OffsetDateTime.class),
                rs.getObject("last_accessed_at", OffsetDateTime.class),
                rs.getInt("retrieval_count"),
                rs.getObject("created_at", OffsetDateTime.class),
                rs.getObject("updated_at", OffsetDateTime.class));
    }
}
