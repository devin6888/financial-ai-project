package com.finagent.memory;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.ObjectMapper;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * conversation_context 的读写（每会话一行，覆盖写）。
 *
 * <p>document_ids 是 {@code bigint[]}：这里的写法是把列表拼成 PG 数组字面量
 * {@code {7,38}} 再 {@code ?::bigint[]}。为什么不用 {@code setArray}：JdbcTemplate 的变参
 * 会把它当普通对象 setObject，PG 驱动不认（阶段 4 在 DenseRetriever 里踩过同样的坑）。
 */
@Repository
public class ConversationContextRepository {

    private static final String SELECT = "SELECT cc.conversation_id, cc.tenant_id, cc.company_id, "
            + "cc.year_from, cc.year_to, cc.document_ids, cc.current_metric, cc.context_json, cc.updated_at, "
            + "c.code AS company_code, c.name AS company_name "
            + "FROM conversation_context cc LEFT JOIN company c ON c.id = cc.company_id "
            + "WHERE cc.conversation_id = ?";

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final RowMapper<ConversationContext> rowMapper = this::map;

    public ConversationContextRepository(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    public Optional<ConversationContext> find(long conversationId) {
        List<ConversationContext> rows = jdbc.query(SELECT, rowMapper, conversationId);
        return rows.stream().findFirst();
    }

    public void upsert(long tenantId, ConversationContext context) {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("companyId", context.companyId());
        json.put("yearFrom", context.yearFrom());
        json.put("yearTo", context.yearTo());
        json.put("documentIds", context.documentIds());
        json.put("currentMetric", context.currentMetric());
        String contextJson = mapper.writeValueAsString(json);

        jdbc.update("INSERT INTO conversation_context (conversation_id, tenant_id, company_id, "
                        + "year_from, year_to, document_ids, current_metric, context_json, updated_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?::bigint[], ?, ?::jsonb, now()) "
                        + "ON CONFLICT (conversation_id) DO UPDATE SET "
                        + "company_id = EXCLUDED.company_id, year_from = EXCLUDED.year_from, "
                        + "year_to = EXCLUDED.year_to, document_ids = EXCLUDED.document_ids, "
                        + "current_metric = EXCLUDED.current_metric, context_json = EXCLUDED.context_json, "
                        + "updated_at = now()",
                context.conversationId(), tenantId, context.companyId(), context.yearFrom(),
                context.yearTo(), toArrayLiteral(context.documentIds()), context.currentMetric(),
                contextJson);
    }

    static String toArrayLiteral(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return "{}";
        }
        StringBuilder sb = new StringBuilder("{");
        for (int i = 0; i < ids.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(ids.get(i));
        }
        return sb.append('}').toString();
    }

    private ConversationContext map(ResultSet rs, int rowNum) throws SQLException {
        return new ConversationContext(rs.getLong("conversation_id"), rs.getLong("tenant_id"),
                (Long) rs.getObject("company_id"), rs.getString("company_code"), rs.getString("company_name"),
                (Integer) rs.getObject("year_from"), (Integer) rs.getObject("year_to"),
                readIds(rs.getArray("document_ids")), rs.getString("current_metric"),
                rs.getString("context_json"), rs.getObject("updated_at", OffsetDateTime.class));
    }

    private static List<Long> readIds(Array array) throws SQLException {
        if (array == null) {
            return List.of();
        }
        Object raw = array.getArray();
        List<Long> ids = new ArrayList<>();
        for (Object o : (Object[]) raw) {
            if (o != null) {
                ids.add(((Number) o).longValue());
            }
        }
        return List.copyOf(ids);
    }
}
