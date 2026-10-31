package com.finagent.security;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Set;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 管理端点的路由 / 校验 / 分页口径 / 守卫。SQL 在真库测试里验。 */
class AdminControllerTest {

    private MockMvc mvc;
    private AdminRepository admin;

    @BeforeEach
    void setUp() {
        admin = mock(AdminRepository.class);
        mvc = MockMvcBuilders.standaloneSetup(new AdminController(admin)).build();
        TenantContext.set(new CurrentUser(1L, 1L, "demo", "演示用户", Set.of("ADMIN", "ANALYST")));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    @DisplayName("非管理员 → 403 ADMIN_REQUIRED，且一次仓储都不调（不能先查后拦）")
    void nonAdminIsRejectedBeforeTouchingData() throws Exception {
        TenantContext.set(new CurrentUser(2L, 1L, "viewer", "只读", Set.of("VIEWER")));

        mvc.perform(get("/api/admin/users"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("ADMIN_REQUIRED"));

        verify(admin, never()).searchUsers(anyLong(), any(), any(), anyInt(), anyInt());
    }

    @Test
    @DisplayName("没有身份 → 401，而不是 403（两者修复动作不同）")
    void anonymousIsUnauthorized() throws Exception {
        TenantContext.clear();

        mvc.perform(get("/api/admin/users"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("用户列表：q / status / page / size 全部透传，返回 items + total")
    void listUsersIsTenantScopedAndPaged() throws Exception {
        when(admin.searchUsers(7L, "ali", "ACTIVE", 2, 20)).thenReturn(new AdminRepository.Paged<>(
                List.of(new AdminRepository.UserRow(1L, "alice", "爱丽丝", "ACTIVE",
                        List.of("VIEWER"), null, 3L)), 21L, 2, 20));

        TenantContext.set(new CurrentUser(1L, 7L, "demo", "演示用户", Set.of("ADMIN")));
        mvc.perform(get("/api/admin/users").param("q", "ali").param("status", "ACTIVE")
                        .param("page", "2").param("size", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(21))
                .andExpect(jsonPath("$.page").value(2))
                .andExpect(jsonPath("$.items[0].username").value("alice"))
                .andExpect(jsonPath("$.items[0].docCount").value(3))
                .andExpect(jsonPath("$.items[0].roles[0]").value("VIEWER"));

        verify(admin).searchUsers(7L, "ali", "ACTIVE", 2, 20);
    }

    @Test
    @DisplayName("分页参数越界：page<1 夹到 1，size>100 夹到 100（防一次拉全表）")
    void pagingParametersAreClamped() throws Exception {
        when(admin.searchUsers(1L, null, null, 1, 100)).thenReturn(new AdminRepository.Paged<>(List.of(), 0L, 1, 100));

        mvc.perform(get("/api/admin/users").param("page", "0").param("size", "5000"))
                .andExpect(status().isOk());

        verify(admin).searchUsers(1L, null, null, 1, 100);
    }

    @Test
    @DisplayName("用户文档权限：用户不存在 → 404，且不查文档")
    void userDocumentsOfMissingUserIs404() throws Exception {
        when(admin.userExists(1L, 999L)).thenReturn(false);

        mvc.perform(get("/api/admin/users/999/documents"))
                .andExpect(status().isNotFound());

        verify(admin, never()).documentsOfUser(anyLong(), anyLong(), anyInt(), anyInt());
    }

    @Test
    @DisplayName("用户文档权限：返回每份文档的 reason（为什么能读）")
    void userDocumentsCarryReason() throws Exception {
        when(admin.userExists(1L, 42L)).thenReturn(true);
        when(admin.documentsOfUser(1L, 42L, 1, 10)).thenReturn(new AdminRepository.Paged<>(
                List.of(new AdminRepository.DocumentAccess(7L, "东方财富2025年年度报告", 2025, "TENANT", "ROLE")),
                1L, 1, 10));

        mvc.perform(get("/api/admin/users/42/documents"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].documentId").value(7))
                .andExpect(jsonPath("$.items[0].reason").value("ROLE"));
    }

    @Test
    @DisplayName("角色文档权限：角色不存在 → 404")
    void roleDocumentsOfMissingRoleIs404() throws Exception {
        when(admin.roleExists(1L, 88L)).thenReturn(false);

        mvc.perform(get("/api/admin/roles/88/documents"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("角色详情：成员与文档各走自己的分页")
    void roleDetailIsPaged() throws Exception {
        when(admin.roleExists(1L, 2L)).thenReturn(true);
        when(admin.membersOfRole(1L, 2L, 1, 10)).thenReturn(new AdminRepository.Paged<>(List.of(), 0L, 1, 10));
        when(admin.documentsOfRole(1L, 2L, 1, 10)).thenReturn(new AdminRepository.Paged<>(
                List.of(new AdminRepository.RoleDoc(5L, 7L, "东方财富2025年年度报告", 2025, "COMPANY", "READ", null)),
                1L, 1, 10));

        mvc.perform(get("/api/admin/roles/2/members")).andExpect(status().isOk());
        mvc.perform(get("/api/admin/roles/2/documents"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].resourceType").value("COMPANY"));
    }

    @Test
    @DisplayName("概览：四个计数一次拿回，页头不用发四个请求")
    void overviewHasFourCounts() throws Exception {
        when(admin.overview(1L)).thenReturn(new AdminRepository.Overview(5L, 4L, 2L, 3L));

        mvc.perform(get("/api/admin/overview"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.users").value(5))
                .andExpect(jsonPath("$.roles").value(4))
                .andExpect(jsonPath("$.documents").value(2))
                .andExpect(jsonPath("$.grants").value(3));
    }

    @Test
    @DisplayName("建用户：密码不足 8 位 → 400，且不落库")
    void shortPasswordIsRejected() throws Exception {
        mvc.perform(post("/api/admin/users").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"alice\",\"password\":\"123\",\"roles\":[\"VIEWER\"]}"))
                .andExpect(status().isBadRequest());

        verify(admin, never()).createUser(anyLong(), any(), any(), any());
    }

    @Test
    @DisplayName("建用户：用户名不合法 → 400")
    void invalidUsernameIsRejected() throws Exception {
        mvc.perform(post("/api/admin/users").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"a b\",\"password\":\"12345678\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").exists());
    }

    @Test
    @DisplayName("建用户：角色不存在 → 400，避免静默少授权")
    void unknownRoleIsRejected() throws Exception {
        when(admin.unknownRoles(1L, List.of("AUDITOR"))).thenReturn(List.of("AUDITOR"));

        mvc.perform(post("/api/admin/users").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"alice\",\"password\":\"12345678\",\"roles\":[\"AUDITOR\"]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("AUDITOR")));

        verify(admin, never()).createUser(anyLong(), any(), any(), any());
    }

    @Test
    @DisplayName("建用户成功 → 201，并把角色写进去")
    void createUserAssignsRoles() throws Exception {
        when(admin.unknownRoles(1L, List.of("VIEWER"))).thenReturn(List.of());
        when(admin.createUser(1L, "alice", "爱丽丝", "12345678")).thenReturn(42L);

        mvc.perform(post("/api/admin/users").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"alice\",\"displayName\":\"爱丽丝\",\"password\":\"12345678\",\"roles\":[\"VIEWER\"]}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(42));

        verify(admin).replaceRoles(1L, 42L, List.of("VIEWER"));
    }

    @Test
    @DisplayName("用户名重复 → 409 USERNAME_TAKEN（唯一约束兜住，控制器翻译）")
    void duplicateUsernameBecomes409() throws Exception {
        when(admin.unknownRoles(1L, List.of("VIEWER"))).thenReturn(List.of());
        when(admin.createUser(anyLong(), any(), any(), any())).thenThrow(new DuplicateKeyException("dup"));

        mvc.perform(post("/api/admin/users").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"demo\",\"password\":\"12345678\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("USERNAME_TAKEN"));
    }

    @Test
    @DisplayName("停用用户 → 200，status 透传；不传 roles 就不动授权")
    void disableUserKeepsRoles() throws Exception {
        when(admin.userExists(1L, 42L)).thenReturn(true);

        mvc.perform(put("/api/admin/users/42").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"DISABLED\"}"))
                .andExpect(status().isOk());

        verify(admin).updateProfile(1L, 42L, null, "DISABLED");
        verify(admin, never()).replaceRoles(anyLong(), anyLong(), any());
    }

    @Test
    @DisplayName("status 非枚举值 → 400")
    void invalidStatusIsRejected() throws Exception {
        when(admin.userExists(1L, 42L)).thenReturn(true);

        mvc.perform(put("/api/admin/users/42").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"DELETED\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("改不存在的用户 → 404")
    void updateMissingUserIs404() throws Exception {
        when(admin.userExists(1L, 999L)).thenReturn(false);

        mvc.perform(put("/api/admin/users/999").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"DISABLED\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("授权：resourceType 只接受 DOCUMENT / COMPANY → 别的值 400")
    void invalidResourceTypeIsRejected() throws Exception {
        mvc.perform(post("/api/admin/grants").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"subjectType\":\"ROLE\",\"subjectId\":2,\"resourceType\":\"SECTION\",\"resourceId\":3}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("授权：公司级（COMPANY）合法，且会校验公司存在")
    void companyGrantIsAccepted() throws Exception {
        when(admin.roleExists(1L, 2L)).thenReturn(true);
        when(admin.companyExists(1L, 7L)).thenReturn(true);
        when(admin.createGrant(1L, "ROLE", 2L, "COMPANY", 7L, 1L)).thenReturn(9L);

        mvc.perform(post("/api/admin/grants").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"subjectType\":\"ROLE\",\"subjectId\":2,\"resourceType\":\"COMPANY\",\"resourceId\":7}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(9));
    }

    @Test
    @DisplayName("授权：文档不存在 → 400（不给悬空授权）")
    void grantToMissingDocumentIs400() throws Exception {
        when(admin.roleExists(1L, 2L)).thenReturn(true);
        when(admin.documentExists(1L, 999L)).thenReturn(false);

        mvc.perform(post("/api/admin/grants").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"subjectType\":\"ROLE\",\"subjectId\":2,\"resourceType\":\"DOCUMENT\",\"resourceId\":999}"))
                .andExpect(status().isBadRequest());

        verify(admin, never()).createGrant(anyLong(), any(), anyLong(), any(), anyLong(), anyLong());
    }

    @Test
    @DisplayName("授权：对全租户可见（TENANT）文档授单份 → 400，因为它对检索不生效")
    void singleDocumentGrantOnTenantDocumentIs400() throws Exception {
        when(admin.roleExists(1L, 2L)).thenReturn(true);
        when(admin.documentExists(1L, 7L)).thenReturn(true);
        when(admin.documentVisibility(1L, 7L)).thenReturn("TENANT");

        mvc.perform(post("/api/admin/grants").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"subjectType\":\"ROLE\",\"subjectId\":2,\"resourceType\":\"DOCUMENT\",\"resourceId\":7}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("不会生效")));

        verify(admin, never()).createGrant(anyLong(), any(), anyLong(), any(), anyLong(), anyLong());
    }

    @Test
    @DisplayName("授权：私有（PRIVATE）文档可以按单份授权")
    void singleDocumentGrantOnPrivateDocumentIsAccepted() throws Exception {
        when(admin.roleExists(1L, 2L)).thenReturn(true);
        when(admin.documentExists(1L, 9L)).thenReturn(true);
        when(admin.documentVisibility(1L, 9L)).thenReturn("PRIVATE");
        when(admin.createGrant(1L, "ROLE", 2L, "DOCUMENT", 9L, 1L)).thenReturn(11L);

        mvc.perform(post("/api/admin/grants").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"subjectType\":\"ROLE\",\"subjectId\":2,\"resourceType\":\"DOCUMENT\",\"resourceId\":9}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(11));
    }

    @Test
    @DisplayName("新建角色：标识非法 / 重名 / 成功")
    void createRoleValidatesAndConflicts() throws Exception {
        mvc.perform(post("/api/admin/roles").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"auditor\",\"name\":\"审计员\"}"))
                .andExpect(status().isBadRequest());

        when(admin.roleCodeExists(1L, "AUDITOR")).thenReturn(true);
        mvc.perform(post("/api/admin/roles").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"AUDITOR\",\"name\":\"审计员\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("ROLE_CODE_TAKEN"));

        when(admin.roleCodeExists(1L, "AUDITOR")).thenReturn(false);
        when(admin.createRole(1L, "AUDITOR", "审计员")).thenReturn(5L);
        mvc.perform(post("/api/admin/roles").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"AUDITOR\",\"name\":\"审计员\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(5));
    }

    @Test
    @DisplayName("重命名角色：不存在 → 404；成功改显示名")
    void renameRole() throws Exception {
        when(admin.roleById(1L, 99L)).thenReturn(null);
        mvc.perform(put("/api/admin/roles/99").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"只读\"}"))
                .andExpect(status().isNotFound());

        when(admin.roleById(1L, 3L)).thenReturn(new AdminRepository.RoleRow(3L, "AUDITOR", "审计员", false, 2L, 0L));
        mvc.perform(put("/api/admin/roles/3").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"内审\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("内审"));
    }

    @Test
    @DisplayName("删角色：系统内置 400、还有成员 409、自定义空角色 200")
    void deleteRoleGuards() throws Exception {
        when(admin.roleById(1L, 1L)).thenReturn(new AdminRepository.RoleRow(1L, "ADMIN", "管理员", true, 1L, 2L));
        mvc.perform(delete("/api/admin/roles/1"))
                .andExpect(status().isBadRequest());

        when(admin.roleById(1L, 4L)).thenReturn(new AdminRepository.RoleRow(4L, "AUDITOR", "审计员", false, 2L, 0L));
        mvc.perform(delete("/api/admin/roles/4"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("ROLE_IN_USE"));

        when(admin.roleById(1L, 5L)).thenReturn(new AdminRepository.RoleRow(5L, "INTERN", "实习生", false, 0L, 0L));
        mvc.perform(delete("/api/admin/roles/5"))
                .andExpect(status().isOk());
        verify(admin).deleteRoleWithGrants(1L, 5L);
    }

    @Test
    @DisplayName("撤销不存在的授权 → 404")
    void deleteMissingGrantIs404() throws Exception {
        when(admin.deleteGrant(1L, 5L)).thenReturn(0);

        mvc.perform(delete("/api/admin/grants/5"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("授权记录与文档选择器都是分页接口")
    void grantsAndDocumentPickerArePaged() throws Exception {
        when(admin.listGrants(1L, "ali", 3, 20)).thenReturn(new AdminRepository.Paged<>(List.of(), 0L, 3, 20));
        when(admin.searchDocuments(1L, "东方", 1, 10)).thenReturn(new AdminRepository.Paged<>(
                List.of(new AdminRepository.DocumentOption(7L, "东方财富2025年年度报告", 2025, "TENANT", 7L, "东方财富")),
                1L, 1, 10));

        mvc.perform(get("/api/admin/grants").param("q", "ali").param("page", "3"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(3));
        mvc.perform(get("/api/admin/documents").param("q", "东方"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].companyName").value("东方财富"));
    }
}
