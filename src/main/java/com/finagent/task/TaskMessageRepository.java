package com.finagent.task;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

/**
 * task_message 表的仓储（JdbcTemplate，与 DocumentRepository 同一套约定）。
 *
 * <p>两级语义：
 * <ul>
 *   <li>{@link #insert} —— 裸插入，不判重；同 event_id 第二次插入靠 DB 唯一约束抛
 *       {@link DuplicateKeyException}（这是消息级幂等的最后一道防线）；</li>
 *   <li>{@link #upsert} —— 幂等落 RECEIVED，同 event_id 复用已有行，不抛异常，
 *       用于 Kafka at-least-once 重投的常规路径。</li>
 * </ul>
 */
@Repository
public class TaskMessageRepository {

    private final JdbcTemplate jdbc;

    public TaskMessageRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 按事件 ID 查留痕（消息级去重判断用）。 */
    public Optional<TaskMessage> findByEventId(String eventId) {
        List<TaskMessage> rows = jdbc.query("""
                SELECT id, event_id, topic, message_key, document_id, status,
                       retry_count, last_error, created_at, updated_at
                  FROM task_message
                 WHERE event_id = ?
                """, MAPPER, eventId);
        return rows.stream().findFirst();
    }

    /**
     * 裸插入一条 RECEIVED，不判重。
     *
     * <p>同 event_id 重复插入会抛 {@link DuplicateKeyException}（Spring 把 PostgreSQL 的
     * 23505 唯一冲突翻译成它）。调用方若不做判重，就靠这个兜底。
     */
    public long insert(String eventId, String topic, String messageKey, long documentId) {
        // tenant 从父文档推导（子查询）。文档不存在时得到 NULL —— 这是**允许**的：
        // 坏消息推不出租户，但「坏消息也要留痕」是阶段 7 的硬要求，所以该列可空。
        Long id = jdbc.queryForObject("""
                INSERT INTO task_message (event_id, topic, message_key, document_id, tenant_id, status)
                VALUES (?, ?, ?, ?, (SELECT tenant_id FROM document WHERE id = ?), 'RECEIVED')
                RETURNING id
                """, Long.class, eventId, topic, messageKey, documentId, documentId);
        return id == null ? -1L : id;
    }

    /**
     * 幂等落一条 RECEIVED：event_id 已存在则复用该行，返回已有 id；否则插入新行。
     *
     * <p>同一条 Kafka 事件重投（网络抖动、消费者崩溃重放、手动重放 DLQ）不会产生第二行。
     */
    public long upsert(String eventId, String topic, String messageKey, long documentId) {
        try {
            return insert(eventId, topic, messageKey, documentId);
        } catch (DuplicateKeyException e) {
            return findByEventId(eventId).orElseThrow().id();
        }
    }

    /** 更新状态：RECEIVED → PROCESSING → SUCCESS | FAILED | DLQ。 */
    public void markStatus(long id, String status) {
        jdbc.update("""
                UPDATE task_message
                   SET status = ?, updated_at = now()
                 WHERE id = ?
                """, status, id);
    }

    /** 瞬时错误重试计数 +1，并记录最近一次错误信息。 */
    public void incrementRetry(long id, String error) {
        jdbc.update("""
                UPDATE task_message
                   SET retry_count = retry_count + 1,
                       last_error  = ?,
                       updated_at  = now()
                 WHERE id = ?
                """, error, id);
    }

    private static final RowMapper<TaskMessage> MAPPER = (rs, n) -> new TaskMessage(
            rs.getLong("id"),
            rs.getString("event_id"),
            rs.getString("topic"),
            rs.getString("message_key"),
            rs.getLong("document_id"),
            rs.getString("status"),
            rs.getInt("retry_count"),
            rs.getString("last_error"),
            rs.getObject("created_at", OffsetDateTime.class),
            rs.getObject("updated_at", OffsetDateTime.class));
}
