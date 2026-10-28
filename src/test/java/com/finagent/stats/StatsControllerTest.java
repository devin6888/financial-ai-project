package com.finagent.stats;

import com.finagent.document.DocumentRepository;
import com.finagent.document.DocumentRepository.DocumentRow;
import com.finagent.scope.ScopeAuthorizer;
import com.finagent.security.CurrentUser;
import com.finagent.security.TenantContext;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code GET /api/stats}：概览页的数字口径。
 *
 * <p>这里用 standaloneSetup + mock 依赖，断言的是**统计口径**而不是数据库：
 * <ul>
 *   <li>文档数只数 ACL 放行后的（不是 listDocuments 返回的全部）；</li>
 *   <li>四个解析状态键永远齐（前端不用判空），没人命中的状态显示 0；</li>
 *   <li>一条文档都不可读时，不去查块数（空集拼 IN () 是语法错误）。</li>
 * </ul>
 */
class StatsControllerTest {

    private DocumentRepository documents;
    private ScopeAuthorizer authorizer;
    private JdbcTemplate jdbc;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        documents = mock(DocumentRepository.class);
        authorizer = mock(ScopeAuthorizer.class);
        jdbc = mock(JdbcTemplate.class);
        mvc = MockMvcBuilders.standaloneSetup(new StatsController(documents, authorizer, jdbc)).build();
        TenantContext.set(new CurrentUser(1L, 1L, "analyst", "分析师", Set.of("ANALYST")));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private static DocumentRow row(long id, String code, String status, long chunks) {
        return new DocumentRow(id, "文档 " + id, code, "公司 " + code, 2025, "ANNUAL", status,
                181, null, 3, chunks, null);
    }

    @Test
    @DisplayName("文档数只数 ACL 放行的；四个状态键永远齐")
    void countsOnlyReadableDocuments() throws Exception {
        when(documents.listDocuments(200)).thenReturn(List.of(
                row(7L, "300059", "DONE", 654),
                row(38L, "300059", "DONE", 630),
                row(9L, "600519", "FAILED", 0)));
        when(authorizer.filterReadable(any())).thenReturn(List.of(7L, 38L));
        when(jdbc.queryForMap(anyString(), any(Object[].class)))
                .thenReturn(Map.of("parents", 208L, "children", 1284L));
        when(jdbc.queryForObject(anyString(), eq(Long.class), any(Object[].class))).thenReturn(3L);

        mvc.perform(get("/api/stats"))
                .andExpect(status().isOk())
                // 第三份 600519 被 ACL 挡掉：概览不能比文档页多显示一份
                .andExpect(jsonPath("$.documents").value(2))
                .andExpect(jsonPath("$.companies").value(1))
                .andExpect(jsonPath("$.activeParents").value(208))
                .andExpect(jsonPath("$.activeChildren").value(1284))
                .andExpect(jsonPath("$.documentsByStatus.DONE").value(2))
                .andExpect(jsonPath("$.documentsByStatus.PARSING").value(0))
                .andExpect(jsonPath("$.documentsByStatus.PENDING").value(0))
                .andExpect(jsonPath("$.documentsByStatus.FAILED").value(0))
                .andExpect(jsonPath("$.recent.length()").value(2))
                .andExpect(jsonPath("$.conversations").value(3))
                .andExpect(jsonPath("$.memories").value(3));
    }

    @Test
    @DisplayName("一条都不可读时不查块数，块数为 0")
    void emptyScopeSkipsChunkQuery() throws Exception {
        when(documents.listDocuments(200)).thenReturn(List.of(row(7L, "300059", "DONE", 654)));
        when(authorizer.filterReadable(any())).thenReturn(List.of());
        when(jdbc.queryForObject(anyString(), eq(Long.class), any(Object[].class))).thenReturn(0L);

        mvc.perform(get("/api/stats"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.documents").value(0))
                .andExpect(jsonPath("$.activeChildren").value(0))
                .andExpect(jsonPath("$.recent.length()").value(0));

        verify(jdbc, never()).queryForMap(anyString(), any(Object[].class));
    }

    @Test
    @DisplayName("没有身份 → 401 AUTH_REQUIRED")
    void anonymousIs401() throws Exception {
        TenantContext.clear();

        mvc.perform(get("/api/stats"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("AUTH_REQUIRED"));
    }
}