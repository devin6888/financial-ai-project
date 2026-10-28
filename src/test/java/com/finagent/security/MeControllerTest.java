package com.finagent.security;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code GET /api/me}：身份只来自 TenantContext。
 *
 * <p>这里刻意用 standaloneSetup（不启动容器）：本用例要证明的是「控制器读的是
 * ThreadLocal，不是请求里的任何字段」，与过滤器/数据库无关；真链路的 401 与身份
 * 注入由 RestAuthContractTest（打真 Tomcat）覆盖。
 */
class MeControllerTest {

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new MeController()).build();
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    @DisplayName("有身份 → 200，字段来自上下文，roles 按字母序")
    void returnsIdentity() throws Exception {
        TenantContext.set(new CurrentUser(1L, 1L, "analyst", "分析师",
                new LinkedHashSet<>(List.of("VIEWER", "ANALYST"))));

        mvc.perform(get("/api/me"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(1))
                .andExpect(jsonPath("$.tenantId").value(1))
                .andExpect(jsonPath("$.username").value("analyst"))
                .andExpect(jsonPath("$.displayName").value("分析师"))
                // 排序是为了响应稳定：前端可当缓存键，测试可断言固定字符串
                .andExpect(jsonPath("$.roles[0]").value("ANALYST"))
                .andExpect(jsonPath("$.roles[1]").value("VIEWER"));
    }

    @Test
    @DisplayName("查询串里自称的 tenantId/userId 一律无效")
    void ignoresClientSuppliedIdentity() throws Exception {
        TenantContext.set(new CurrentUser(1L, 1L, "analyst", "分析师", Set.of("ANALYST")));

        mvc.perform(get("/api/me").param("tenantId", "2").param("userId", "3"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tenantId").value(1))
                .andExpect(jsonPath("$.userId").value(1));
    }

    @Test
    @DisplayName("没有身份 → 401 AUTH_REQUIRED（不是 200 + 空角色）")
    void missingIdentityIs401() throws Exception {
        mvc.perform(get("/api/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("AUTH_REQUIRED"));
    }

    @Test
    @DisplayName("roles 为 null → 输出空数组，不输出 null")
    void nullRolesBecomeEmptyArray() throws Exception {
        TenantContext.set(new CurrentUser(9L, 1L, "viewer", "只读", null));

        mvc.perform(get("/api/me"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.roles").isArray())
                .andExpect(jsonPath("$.roles.length()").value(0));
    }
}
