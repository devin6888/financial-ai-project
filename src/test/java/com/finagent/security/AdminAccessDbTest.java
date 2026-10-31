package com.finagent.security;

import com.finagent.scope.AclScopeAuthorizer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 管理页的「谁能读哪些文档」与检索时真正用的判定必须一致。
 *
 * <p>这两处如果各写一套 SQL，最危险的 bug 是"管理页显示已授权、检索时读不到" ——
 * 管理员会以为自己已经授权成功。所以这里不比对硬编码的期望值，而是拿
 * {@link AclScopeAuthorizer}（检索链路真正调用的那个）的结论当基准，逐条对。
 *
 * <p>只读：测试库连接的角色对 {@code app_user} 没有写权限（V9 的安全设定），
 * 所以直接用 V11 种子里的 analyst / outsider / admin，不建临时用户。
 */
@SpringBootTest
@ActiveProfiles("test")
@Tag("db")
class AdminAccessDbTest {

    private static final long TENANT = 1L;

    @Autowired
    private AdminRepository admin;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    @DisplayName("管理员：本租户每份文档都可读，且 reason 全是 ADMIN")
    void adminSeesEveryTenantDocument() {
        long adminId = userId("admin");
        long tenantDocs = count("SELECT count(*) FROM document WHERE tenant_id = ?", TENANT);

        AdminRepository.Paged<AdminRepository.DocumentAccess> page =
                admin.documentsOfUser(TENANT, adminId, 1, 100);

        assertThat(tenantDocs).isPositive();
        assertThat(page.total()).isEqualTo(tenantDocs);
        assertThat(page.items()).extracting(AdminRepository.DocumentAccess::reason).containsOnly("ADMIN");
    }

    @Test
    @DisplayName("普通用户：管理页列出的文档 == AclScopeAuthorizer 判定可读的文档（两套实现必须同答案）")
    void userDocumentListMatchesAuthorizer() {
        long userId = userId("analyst");

        List<Long> allDocs = jdbc.queryForList("SELECT id FROM document WHERE tenant_id = ? ORDER BY id",
                Long.class, TENANT);
        TenantContext.set(new CurrentUser(userId, TENANT, "analyst", "分析师", Set.of("ANALYST")));
        List<Long> readableByAcl = new ArrayList<>(
                new LinkedHashSet<>(new AclScopeAuthorizer(jdbc).filterReadable(allDocs)));
        TenantContext.clear();

        AdminRepository.Paged<AdminRepository.DocumentAccess> page =
                admin.documentsOfUser(TENANT, userId, 1, 100);

        assertThat(readableByAcl).isNotEmpty();
        assertThat(page.total()).isEqualTo(readableByAcl.size());
        assertThat(page.items()).extracting(AdminRepository.DocumentAccess::documentId)
                .containsExactlyInAnyOrderElementsOf(readableByAcl);
        assertThat(page.items()).extracting(AdminRepository.DocumentAccess::reason).doesNotContain("ADMIN");
    }

    @Test
    @DisplayName("只读角色：没有任何授权时列表为空（不是报错，也不是悄悄给全量）")
    void roleWithoutGrantsSeesNothing() {
        long userId = userId("outsider");
        AdminRepository.Paged<AdminRepository.DocumentAccess> page = admin.documentsOfUser(TENANT, userId, 1, 100);
        assertThat(page.total()).isZero();
        assertThat(page.items()).isEmpty();
    }

    @Test
    @DisplayName("角色文档：ADMIN 角色天然全可见（resourceType=ALL），ANALYST 走公司级授权")
    void roleDocumentsExpandGrants() {
        long adminRole = roleId("ADMIN");
        long analystRole = roleId("ANALYST");
        long tenantDocs = count("SELECT count(*) FROM document WHERE tenant_id = ?", TENANT);

        AdminRepository.Paged<AdminRepository.RoleDoc> adminDocs = admin.documentsOfRole(TENANT, adminRole, 1, 100);
        assertThat(adminDocs.total()).isEqualTo(tenantDocs);
        assertThat(adminDocs.items()).extracting(AdminRepository.RoleDoc::resourceType).containsOnly("ALL");

        long analystDocs = count("""
                SELECT count(DISTINCT d.id) FROM document d
                 WHERE d.tenant_id = ? AND d.company_id = 7
                """, TENANT);
        AdminRepository.Paged<AdminRepository.RoleDoc> page = admin.documentsOfRole(TENANT, analystRole, 1, 100);
        assertThat(analystDocs).isPositive();
        assertThat(page.total()).isEqualTo(analystDocs);
        assertThat(page.items()).extracting(AdminRepository.RoleDoc::resourceType).containsOnly("COMPANY");
        assertThat(page.items()).allSatisfy(doc -> assertThat(doc.grantId()).isNotNull());
    }

    @Test
    @DisplayName("用户列表：搜索命中用户名/显示名，分页 total 与 size 各自独立，可读文档数按真实授权算")
    void searchUsersFiltersAndPages() {
        AdminRepository.Paged<AdminRepository.UserRow> byName = admin.searchUsers(TENANT, "anal", null, 1, 5);
        assertThat(byName.items()).extracting(AdminRepository.UserRow::username).contains("analyst");
        assertThat(byName.items().size()).isLessThanOrEqualTo(5);

        AdminRepository.Paged<AdminRepository.UserRow> one = admin.searchUsers(TENANT, "analyst", "ACTIVE", 1, 20);
        assertThat(one.total()).isEqualTo(1);

        long analystDocs = count("""
                SELECT count(DISTINCT d.id) FROM document d
                 WHERE d.tenant_id = ? AND (
                       (d.visibility = 'PRIVATE' AND d.owner_user_id = ?)
                    OR EXISTS (SELECT 1 FROM resource_grant g
                                WHERE g.tenant_id = d.tenant_id
                                  AND g.subject_type = 'ROLE' AND g.subject_id = ?
                                  AND g.resource_type = CASE d.visibility WHEN 'TENANT' THEN 'COMPANY' ELSE 'DOCUMENT' END
                                  AND g.resource_id = CASE d.visibility WHEN 'TENANT' THEN d.company_id ELSE d.id END))
                """, TENANT, userId("analyst"), roleId("ANALYST"));
        assertThat(one.items().getFirst().docCount()).isEqualTo(analystDocs);
    }

    @Test
    @DisplayName("角色成员按角色过滤；不存在的角色由控制器挡在前面，这里只验 SQL 的口径")
    void membersOfRoleAreFiltered() {
        long analystRole = roleId("ANALYST");
        long expected = count("SELECT count(*) FROM user_role WHERE role_id = ?", analystRole);
        AdminRepository.Paged<AdminRepository.UserRow> members = admin.membersOfRole(TENANT, analystRole, 1, 50);
        assertThat(members.total()).isEqualTo(expected);
        assertThat(members.items()).allSatisfy(u -> assertThat(u.roles()).contains("ANALYST"));
    }

    @Test
    @DisplayName("文档选择器：不传搜索词也要能用（q 为 null 曾抛 NPE，因为 List.of 不接受 null）")
    void documentPickerWorksWithoutSearchTerm() {
        AdminRepository.Paged<AdminRepository.DocumentOption> page = admin.searchDocuments(TENANT, null, 1, 10);
        assertThat(page.total()).isEqualTo(count("SELECT count(*) FROM document WHERE tenant_id = ?", TENANT));
        assertThat(page.items()).isNotEmpty();
        assertThat(page.items()).allSatisfy(doc -> assertThat(doc.companyId()).isNotNull());

        AdminRepository.Paged<AdminRepository.DocumentOption> found = admin.searchDocuments(TENANT, "东方", 1, 10);
        assertThat(found.total()).isPositive();
    }

    @Test
    @DisplayName("自定义角色：建 → 改名 → 删，删除时连带清掉它的授权")
    void createRenameDeleteRole() {
        String code = "TMP_AUDITOR_" + System.nanoTime() % 100000;
        long id = admin.createRole(TENANT, code, "临时审计员");
        try {
            assertThat(admin.roleCodeExists(TENANT, code)).isTrue();
            assertThat(admin.listRoles(TENANT)).extracting(AdminRepository.RoleRow::code).contains(code);

            assertThat(admin.renameRole(TENANT, id, "内审")).isEqualTo(1);
            AdminRepository.RoleRow row = admin.roleById(TENANT, id);
            assertThat(row).isNotNull();
            assertThat(row.name()).isEqualTo("内审");
            assertThat(row.system()).isFalse();
            assertThat(row.userCount()).isZero();

            // 自定义角色可以授文档（这里借种子里的公司授权，验证删除会连带清掉）
            jdbc.update("""
                    INSERT INTO resource_grant (tenant_id, subject_type, subject_id, resource_type, resource_id,
                                                permission, granted_by)
                    VALUES (?, 'ROLE', ?, 'DOCUMENT', 38, 'READ', ?)
                    """, TENANT, id, userId("admin"));
            assertThat(count("SELECT count(*) FROM resource_grant WHERE subject_type = 'ROLE' AND subject_id = ?", id))
                    .isEqualTo(1);
        } finally {
            int removed = admin.deleteRoleWithGrants(TENANT, id);
            assertThat(removed).isEqualTo(1);
        }
        assertThat(admin.roleCodeExists(TENANT, code)).isFalse();
        assertThat(count("SELECT count(*) FROM resource_grant WHERE subject_type = 'ROLE' AND subject_id = ?", id))
                .isZero();
    }

    @Test
    @DisplayName("系统内置角色不能被 deleteRoleWithGrants 删掉")
    void systemRoleIsProtected() {
        long roleId = roleId("VIEWER");
        assertThat(admin.deleteRoleWithGrants(TENANT, roleId)).isZero();
        assertThat(admin.roleExists(TENANT, roleId)).isTrue();
    }

    // ---------- 助手 ----------

    private long userId(String username) {
        Long id = jdbc.queryForObject("SELECT id FROM app_user WHERE username = ? AND tenant_id = ?",
                Long.class, username, TENANT);
        return id;
    }

    private long roleId(String code) {
        Long id = jdbc.queryForObject("SELECT id FROM role WHERE code = ? AND tenant_id = ?", Long.class, code, TENANT);
        return id;
    }

    private long count(String sql, Object... args) {
        Long value = jdbc.queryForObject(sql, Long.class, args);
        return value == null ? 0 : value;
    }
}
