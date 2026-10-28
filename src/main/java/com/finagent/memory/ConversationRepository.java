package com.finagent.memory;

import com.finagent.scope.ChatScope;
import com.finagent.scope.ScopeKind;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.ObjectMapper;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

/**
 * conversation / conversation_message 的读写。
 *
 * <p>所有查询都显式带 {@code tenant_id}：RLS 已经挡了一层，但显式条件让计划走索引
 * （(tenant_id, owner_user_id, updated_at) 复合索引），也让「为什么加了 tenant 还能查到」
 * 这种问题在代码里就能回答，不用去翻策略。
 *
 * <p>归属校验（owner_user_id）只能在这一层做：RLS 只管租户，同租户的两个用户互相看得见。
 */
@Repository
public class ConversationRepository {

    private static final String COLUMNS = "id, tenant_id, owner_user_id, title, scope_kind, scope_json, "
            + "status, created_at, updated_at";

    private static final String MESSAGE_COLUMNS = "id, conversation_id, role, content, citations_json, "
            + "scope_snapshot, trace_id, token_count, created_at";

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final RowMapper<Conversation> rowMapper = this::mapConversation;
    private final RowMapper<ConversationMessage> messageMapper = this::mapMessage;

    public ConversationRepository(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    public long create(long tenantId, long ownerUserId, String title, ChatScope scope) {
        ChatScope s = scope == null ? ChatScope.corpus() : scope;
        Long id = jdbc.queryForObject(
                "INSERT INTO conversation (tenant_id, owner_user_id, title, scope_kind, scope_json) "
                        + "VALUES (?, ?, ?, ?, ?::jsonb) RETURNING id",
                Long.class, tenantId, ownerUserId, title, s.kind().name(),
                ScopeSnapshots.scopeJson(mapper, s));
        return id == null ? -1L : id;
    }

    public Optional<Conversation> findOwned(long id, long tenantId, long ownerUserId) {
        List<Conversation> rows = jdbc.query(
                "SELECT " + COLUMNS + " FROM conversation "
                        + "WHERE id = ? AND tenant_id = ? AND owner_user_id = ? AND status = ?",
                rowMapper, id, tenantId, ownerUserId, Conversation.STATUS_ACTIVE);
        return rows.stream().findFirst();
    }

    public List<Conversation> listOwned(long tenantId, long ownerUserId, int limit) {
        return jdbc.query(
                "SELECT " + COLUMNS + " FROM conversation "
                        + "WHERE tenant_id = ? AND owner_user_id = ? AND status = ? "
                        + "ORDER BY updated_at DESC, id DESC LIMIT ?",
                rowMapper, tenantId, ownerUserId, Conversation.STATUS_ACTIVE, limit);
    }

    /** 倒序（最新在前），翻页用 beforeId。服务层要给模型用时自己反转。 */
    public List<ConversationMessage> messages(long conversationId, Long beforeId, int limit) {
        if (beforeId == null) {
            return jdbc.query(
                    "SELECT " + MESSAGE_COLUMNS + " FROM conversation_message "
                            + "WHERE conversation_id = ? ORDER BY id DESC LIMIT ?",
                    messageMapper, conversationId, limit);
        }
        return jdbc.query(
                "SELECT " + MESSAGE_COLUMNS + " FROM conversation_message "
                        + "WHERE conversation_id = ? AND id < ? ORDER BY id DESC LIMIT ?",
                messageMapper, conversationId, beforeId, limit);
    }

    /**
     * 摘要指针之后最旧的 N 条（正序）。
     *
     * <p>摘要要「从小到大吃」，所以这里是 ASC；短期窗口要最新，见 {@link #newestAfter}。
     */
    public List<ConversationMessage> oldestAfter(long conversationId, long afterId, int limit) {
        return jdbc.query(
                "SELECT " + MESSAGE_COLUMNS + " FROM conversation_message "
                        + "WHERE conversation_id = ? AND id > ? ORDER BY id ASC LIMIT ?",
                messageMapper, conversationId, afterId, limit);
    }

    /** 摘要指针之后最新的 N 条（倒序，与 {@link #messages} 同一口径，调用方自己反转）。 */
    public List<ConversationMessage> newestAfter(long conversationId, long afterId, int limit) {
        return jdbc.query(
                "SELECT " + MESSAGE_COLUMNS + " FROM conversation_message "
                        + "WHERE conversation_id = ? AND id > ? ORDER BY id DESC LIMIT ?",
                messageMapper, conversationId, afterId, limit);
    }

    /** 摘要指针之后还有多少条没被汇总（阈值判断用）。 */
    public int countAfter(long conversationId, long afterId) {
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM conversation_message WHERE conversation_id = ? AND id > ?",
                Integer.class, conversationId, afterId);
        return count == null ? 0 : count;
    }

    public long appendMessage(long tenantId, long conversationId, String role, String content,
                              String citationsJson, String scopeSnapshot, String traceId, int tokenCount) {
        Long id = jdbc.queryForObject(
                "INSERT INTO conversation_message (tenant_id, conversation_id, role, content, "
                        + "citations_json, scope_snapshot, trace_id, token_count) "
                        + "VALUES (?, ?, ?, ?, ?::jsonb, ?::jsonb, ?, ?) RETURNING id",
                Long.class, tenantId, conversationId, role, content, citationsJson,
                scopeSnapshot, traceId, tokenCount);
        return id == null ? -1L : id;
    }

    public void touch(long conversationId) {
        jdbc.update("UPDATE conversation SET updated_at = now() WHERE id = ?", conversationId);
    }

    public void renameTitle(long conversationId, String title) {
        jdbc.update("UPDATE conversation SET title = ?, updated_at = now() WHERE id = ?", title, conversationId);
    }

    public void markDeleted(long conversationId) {
        jdbc.update("UPDATE conversation SET status = ?, updated_at = now() WHERE id = ?",
                Conversation.STATUS_DELETED, conversationId);
    }

    private Conversation mapConversation(ResultSet rs, int rowNum) throws SQLException {
        ScopeKind kind = ScopeSnapshots.parseKind(rs.getString("scope_kind"), ScopeKind.CORPUS);
        return new Conversation(
                rs.getLong("id"), rs.getLong("tenant_id"), rs.getLong("owner_user_id"),
                rs.getString("title"), kind, readScope(rs.getString("scope_json"), kind),
                rs.getString("status"),
                rs.getObject("created_at", OffsetDateTime.class),
                rs.getObject("updated_at", OffsetDateTime.class));
    }

    private ChatScope readScope(String json, ScopeKind fallback) {
        if (json == null || json.isBlank()) {
            return new ChatScope(fallback, null, null);
        }
        return ScopeSnapshots.toScope(mapper.readValue(json, ScopeSnapshots.ScopePayload.class), fallback);
    }

    private ConversationMessage mapMessage(ResultSet rs, int rowNum) throws SQLException {
        return new ConversationMessage(
                rs.getLong("id"), rs.getLong("conversation_id"), rs.getString("role"),
                rs.getString("content"), rs.getString("citations_json"), rs.getString("scope_snapshot"),
                rs.getString("trace_id"), rs.getInt("token_count"),
                rs.getObject("created_at", OffsetDateTime.class));
    }
}
