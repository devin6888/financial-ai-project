package com.finagent.memory;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

/** conversation_summary 的读写：一会话一行，覆盖写，version 递增。 */
@Repository
public class ConversationSummaryRepository {

    private static final String COLUMNS =
            "conversation_id, summary, summary_until_message_id, version, updated_at";

    private final JdbcTemplate jdbc;

    public ConversationSummaryRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<ConversationSummary> MAPPER = (rs, rowNum) -> new ConversationSummary(
            rs.getLong("conversation_id"),
            rs.getString("summary"),
            rs.getObject("summary_until_message_id", Long.class),
            rs.getInt("version"),
            rs.getObject("updated_at", OffsetDateTime.class));

    public Optional<ConversationSummary> find(long conversationId) {
        List<ConversationSummary> rows = jdbc.query(
                "SELECT " + COLUMNS + " FROM conversation_summary WHERE conversation_id = ?",
                MAPPER, conversationId);
        return rows.stream().findFirst();
    }

    /**
     * 写入（覆盖）。
     *
     * <p>两点刻意为之：
     * <ul>
     *   <li>指针用 {@code GREATEST}：两轮并发都想推进指针时，小的那个不该把大的盖回去；
     *       摘要正文以最后一次写入为准（它是「当前状态」，允许被更完整的版本覆盖）。</li>
     *   <li>{@code version = version + 1}：审计要看得出「摘要变过几次」，而不是只有一个时间戳。</li>
     * </ul>
     */
    public ConversationSummary upsert(long tenantId, long conversationId, String summary,
                                      long summaryUntilMessageId) {
        jdbc.update("INSERT INTO conversation_summary "
                        + "(tenant_id, conversation_id, summary, summary_until_message_id, version) "
                        + "VALUES (?, ?, ?, ?, 1) "
                        + "ON CONFLICT (conversation_id) DO UPDATE SET "
                        + "summary = EXCLUDED.summary, "
                        + "summary_until_message_id = GREATEST("
                        + "  conversation_summary.summary_until_message_id, "
                        + "  EXCLUDED.summary_until_message_id), "
                        + "version = conversation_summary.version + 1, "
                        + "updated_at = now()",
                tenantId, conversationId, summary, summaryUntilMessageId);
        return find(conversationId).orElseThrow();
    }
}
