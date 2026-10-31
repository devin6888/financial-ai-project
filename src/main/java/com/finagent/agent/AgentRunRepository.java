package com.finagent.agent;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * {@code agent_run} / {@code agent_run_step} 的读写。
 *
 * <p>三条约定：
 * <ol>
 *   <li>所有查询都显式带 {@code tenant_id}（RLS 是第二道防线，不是唯一一道）；</li>
 *   <li>状态变更走具名方法（{@code markDone} / {@code markFailed} …），不允许「随便 UPDATE 一个字段」，
 *       否则状态机就没人守了；</li>
 *   <li>步序由 {@link #nextSeq(UUID)} 取，调用方在同一个事务里用它 —— 表上有 {@code UNIQUE(run_id, seq)} 兜底。</li>
 * </ol>
 */
@Repository
public class AgentRunRepository {

    private static final String COLUMNS = """
            id, tenant_id, user_id, conversation_id, question, status, current_node,
            plan_json::text AS plan_json, decision, decision_note, error, created_at, updated_at""";

    private static final RowMapper<AgentRun> RUN_MAPPER = (rs, i) -> new AgentRun(
            rs.getObject("id", UUID.class),
            rs.getLong("tenant_id"),
            rs.getLong("user_id"),
            rs.getObject("conversation_id", Long.class),
            rs.getString("question"),
            rs.getString("status"),
            rs.getString("current_node"),
            rs.getString("plan_json"),
            rs.getString("decision"),
            rs.getString("decision_note"),
            rs.getString("error"),
            rs.getObject("created_at", OffsetDateTime.class),
            rs.getObject("updated_at", OffsetDateTime.class));

    private static final RowMapper<AgentRunStep> STEP_MAPPER = (rs, i) -> new AgentRunStep(
            rs.getLong("id"),
            rs.getLong("tenant_id"),
            rs.getObject("run_id", UUID.class),
            rs.getInt("seq"),
            rs.getString("node"),
            rs.getString("status"),
            rs.getString("summary"),
            rs.getObject("duration_ms", Integer.class),
            rs.getObject("created_at", OffsetDateTime.class));

    private final JdbcTemplate jdbc;

    public AgentRunRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 新建一次运行，状态固定为 RUNNING（别的初始状态没有业务含义）。 */
    public void create(UUID id, long tenantId, long userId, Long conversationId, String question) {
        jdbc.update("""
                INSERT INTO agent_run (id, tenant_id, user_id, conversation_id, question, status)
                VALUES (?, ?, ?, ?, ?, 'RUNNING')""",
                id, tenantId, userId, conversationId, question);
    }

    public Optional<AgentRun> find(UUID id, long tenantId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM agent_run WHERE id = ? AND tenant_id = ?",
                RUN_MAPPER, id, tenantId).stream().findFirst();
    }

    public List<AgentRun> listByConversation(long tenantId, long conversationId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM agent_run WHERE tenant_id = ? AND conversation_id = ?"
                        + " ORDER BY created_at DESC", RUN_MAPPER, tenantId, conversationId);
    }

    /** 运行开始/中间推进：只更新「当前节点」。 */
    public int updateCurrentNode(UUID id, long tenantId, String node) {
        return jdbc.update("UPDATE agent_run SET current_node = ?, updated_at = now()"
                + " WHERE id = ? AND tenant_id = ?", node, id, tenantId);
    }

    /** 停在中继点等人审批：同时记下停在哪个节点与规划结果。 */
    public int markWaitingApproval(UUID id, long tenantId, String node, String planJson) {
        return jdbc.update("""
                UPDATE agent_run
                   SET status = 'WAITING_APPROVAL', current_node = ?,
                       plan_json = ?::jsonb, updated_at = now()
                 WHERE id = ? AND tenant_id = ?""",
                node, planJson, id, tenantId);
    }

    /** 人工决定落库；重复点同一决定是幂等的（后写覆盖前写）。 */
    public int recordDecision(UUID id, long tenantId, String decision, String note) {
        return jdbc.update("""
                UPDATE agent_run
                   SET decision = ?, decision_note = ?, updated_at = now()
                 WHERE id = ? AND tenant_id = ?""",
                decision, note, id, tenantId);
    }

    public int markDone(UUID id, long tenantId, String node) {
        return jdbc.update("""
                UPDATE agent_run
                   SET status = 'DONE', current_node = ?, error = NULL, updated_at = now()
                 WHERE id = ? AND tenant_id = ?""",
                node, id, tenantId);
    }

    public int markFailed(UUID id, long tenantId, String error) {
        return jdbc.update("""
                UPDATE agent_run
                   SET status = 'FAILED', error = ?, updated_at = now()
                 WHERE id = ? AND tenant_id = ?""",
                error, id, tenantId);
    }

    public int markRejected(UUID id, long tenantId, String note) {
        return jdbc.update("""
                UPDATE agent_run
                   SET status = 'REJECTED', decision = 'REJECT', decision_note = ?, updated_at = now()
                 WHERE id = ? AND tenant_id = ?""",
                note, id, tenantId);
    }

    /** 运行内下一个步序（1 开始）。读 + 写不在一个事务里也没关系：表上唯一约束会挡住重复。 */
    public int nextSeq(UUID runId) {
        Integer max = jdbc.queryForObject(
                "SELECT coalesce(max(seq), 0) FROM agent_run_step WHERE run_id = ?", Integer.class, runId);
        return (max == null ? 0 : max) + 1;
    }

    public void appendStep(UUID runId, long tenantId, int seq, String node, String status,
                           String summary, Integer durationMs) {
        jdbc.update("""
                INSERT INTO agent_run_step (tenant_id, run_id, seq, node, status, summary, duration_ms)
                VALUES (?, ?, ?, ?, ?, ?, ?)""",
                tenantId, runId, seq, node, status, summary, durationMs);
    }

    public List<AgentRunStep> steps(UUID runId, long tenantId) {
        return jdbc.query("SELECT id, tenant_id, run_id, seq, node, status, summary, duration_ms, created_at"
                + " FROM agent_run_step WHERE run_id = ? AND tenant_id = ? ORDER BY seq",
                STEP_MAPPER, runId, tenantId);
    }
}