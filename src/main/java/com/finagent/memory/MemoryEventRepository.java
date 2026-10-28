package com.finagent.memory;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * memory_event：记忆的审计流水（追加型，运行时只 INSERT）。
 *
 * <p>为什么单独一张表而不是靠 updated_at：结构化上下文与摘要是「当前状态」（覆盖写），
 * 事后没法知道「谁在第几轮把它改了」。不可重算的正是这些<b>变更动作</b>（D9）。
 */
@Repository
public class MemoryEventRepository {

    public static final String TARGET_CONTEXT = "CONVERSATION_CONTEXT";
    public static final String TARGET_SUMMARY = "CONVERSATION_SUMMARY";
    public static final String TARGET_MEMORY = "MEMORY";

    public static final String ACTION_CREATE = "CREATE";
    public static final String ACTION_UPDATE = "UPDATE";
    public static final String ACTION_SUPERSEDE = "SUPERSEDE";
    public static final String ACTION_DELETE = "DELETE";
    public static final String ACTION_CONTEXT_UPDATE = "CONTEXT_UPDATE";
    public static final String ACTION_SUMMARY_REWRITE = "SUMMARY_REWRITE";

    private final JdbcTemplate jdbc;

    public MemoryEventRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(long tenantId, Long memoryId, String targetType, long targetId, String action,
                       String oldValue, String newValue, String reason, String traceId) {
        jdbc.update("INSERT INTO memory_event (tenant_id, memory_id, target_type, target_id, action, "
                        + "old_value, new_value, reason, trace_id) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                tenantId, memoryId, targetType, targetId, action, oldValue, newValue, reason, traceId);
    }
}
