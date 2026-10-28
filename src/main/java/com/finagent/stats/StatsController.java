package com.finagent.stats;

import com.finagent.document.DocumentRepository;
import com.finagent.document.DocumentRepository.DocumentRow;
import com.finagent.scope.ScopeAuthorizer;
import com.finagent.security.CurrentUser;
import com.finagent.security.TenantContext;
import com.finagent.security.UnauthorizedException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 概览页的数据源（阶段 10 视觉升级新增，计划 ST10 §10.3 第 6 项）。
 *
 * <p>三条口径，与列表端点保持一致，避免「概览说 4 份、文档页只显示 1 份」这种自相矛盾：
 * <ul>
 *   <li>文档数只数**当前账号可读**的（{@link ScopeAuthorizer#filterReadable}），不是租户全量；</li>
 *   <li>块数只数**当前 ACTIVE 版本**的 PARENT / CHILD（与 DocumentRepository.listDocuments 的 chunkCount 同口径）；</li>
 *   <li>会话与记忆只数**自己的**（owner / subject），别人有多少属于存在性泄漏。</li>
 * </ul>
 */
@RestController
public class StatsController {

    private final DocumentRepository documents;
    private final ScopeAuthorizer authorizer;
    private final JdbcTemplate jdbc;

    public StatsController(DocumentRepository documents, ScopeAuthorizer authorizer, JdbcTemplate jdbc) {
        this.documents = documents;
        this.authorizer = authorizer;
        this.jdbc = jdbc;
    }

    @GetMapping("/api/stats")
    public Stats stats() {
        CurrentUser user = TenantContext.require();

        List<DocumentRow> all = documents.listDocuments(200);
        List<Long> ids = all.stream().map(DocumentRow::id).toList();
        Set<Long> readable = new LinkedHashSet<>(authorizer.filterReadable(ids));
        List<DocumentRow> visible = all.stream().filter(row -> readable.contains(row.id())).toList();

        Map<String, Long> byStatus = new LinkedHashMap<>();
        for (String status : List.of("DONE", "PARSING", "PENDING", "FAILED")) {
            byStatus.put(status, 0L);
        }
        for (DocumentRow row : visible) {
            byStatus.merge(row.parseStatus() == null ? "PENDING" : row.parseStatus(), 1L, Long::sum);
        }

        Set<String> companies = new LinkedHashSet<>();
        for (DocumentRow row : visible) {
            if (row.companyCode() != null) {
                companies.add(row.companyCode());
            }
        }

        long parents = 0;
        long children = 0;
        if (!readable.isEmpty()) {
            String placeholders = String.join(",", java.util.Collections.nCopies(readable.size(), "?"));
            String sql = """
                    SELECT count(*) FILTER (WHERE dc.chunk_level = 'PARENT') AS parents,
                           count(*) FILTER (WHERE dc.chunk_level = 'CHILD')  AS children
                      FROM document_chunk dc
                      JOIN document d ON d.id = dc.document_id
                     WHERE d.id IN (%s)
                       AND dc.status = 'ACTIVE'
                       AND dc.chunk_version = d.active_chunk_version
                    """.formatted(placeholders);
            Map<String, Object> row = jdbc.queryForMap(sql, readable.toArray());
            parents = ((Number) row.get("parents")).longValue();
            children = ((Number) row.get("children")).longValue();
        }

        long conversations = jdbc.queryForObject(
                "SELECT count(*) FROM conversation WHERE tenant_id = ? AND owner_user_id = ? AND status = 'ACTIVE'",
                Long.class, user.tenantId(), user.userId());
        long memories = jdbc.queryForObject(
                "SELECT count(*) FROM long_term_memory WHERE tenant_id = ? AND subject_id = ? AND status = 'ACTIVE'",
                Long.class, user.tenantId(), user.userId());

        List<RecentDocument> recent = new ArrayList<>();
        for (DocumentRow row : visible.subList(0, Math.min(6, visible.size()))) {
            recent.add(new RecentDocument(row.id(), row.title(), row.companyCode(), row.companyName(),
                    row.docYear(), row.docType(), row.parseStatus(), row.chunkCount()));
        }

        return new Stats(visible.size(), byStatus, companies.size(), parents, children,
                conversations, memories, recent);
    }

    @ExceptionHandler(UnauthorizedException.class)
    public org.springframework.http.ResponseEntity<Map<String, Object>> unauthorized(UnauthorizedException e) {
        return org.springframework.http.ResponseEntity.status(401)
                .body(Map.of("error", e.code(), "message", e.getMessage()));
    }

    public record Stats(long documents, Map<String, Long> documentsByStatus, long companies,
                        long activeParents, long activeChildren, long conversations, long memories,
                        List<RecentDocument> recent) {
    }

    public record RecentDocument(long id, String title, String companyCode, String companyName,
                                 Integer docYear, String docType, String parseStatus, long chunkCount) {
    }
}