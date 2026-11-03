package com.finagent.scope;

import com.finagent.security.CurrentUser;
import com.finagent.security.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ACL 的数据库级测试：真的查授权表，不 mock。
 *
 * <p>为什么用 {@code properties = acl-enabled=true} 而不是另造 profile：test profile 默认装配
 * {@link AllowAllAuthorizer}（否则 300 多个既有测试都要先造 token），在那里测 ACL 等于测空气。
 * 用 properties 只把这一个测试的上下文打开 ACL，既不改全局配置，也不引入没人维护的 {@code acl} profile。
 *
 * <p>为什么<b>故意让 RLS 不生效</b>：本测试自己造的公司/文档都写 tenant_id=1，且表 owner 连接绕过
 * RLS（V8 没有 FORCE），所以断言失败只可能是 ACL 写错，而不是被 RLS 顺手挡住了。
 * 跨租户那条线由 {@code RlsEnforcementTest}（rls profile）单独覆盖，两者互补。
 *
 * <p>写库（造公司/文档/授权行）必须在<b>没有</b> TenantContext 时做：V9 已经 REVOKE 掉
 * {@code finagent_app} 对 {@code resource_grant} 的写权限，带上下文借连接会 SET ROLE 成运行时角色，
 * 插授权行会被数据库拒绝。所以 {@code @AfterEach} 的第一行必须是 {@code TenantContext.clear()}。
 */
@Tag("db")
@SpringBootTest(properties = "finagent.tenant.acl-enabled=true")
@ActiveProfiles("test")
class AclScopeAuthorizerTest {

    @Autowired private ScopeAuthorizer authorizer;
    @Autowired private JdbcTemplate jdbc;

    private final List<Long> createdDocuments = new ArrayList<>();

    @AfterEach
    void cleanup() {
        TenantContext.clear();   // 必须最先：先清上下文，借到的连接才是 owner 角色，才有写权限
        List<Long> resourceIds = new ArrayList<>(createdDocuments);
        if (!resourceIds.isEmpty()) {
            jdbc.update("DELETE FROM resource_grant WHERE resource_id IN (" + placeholders(resourceIds.size()) + ")",
                    resourceIds.toArray());
        }
        deleteByIds("document", createdDocuments);
        createdDocuments.clear();
    }

    @Test
    @DisplayName("开关为 true 时装配的是真实 ACL 实现，不是 AllowAll")
    void aclSwitchSelectsRealImplementation() {
        assertThat(authorizer).isInstanceOf(AclScopeAuthorizer.class);
    }

    @Test
    @DisplayName("管理员在本租户内全可见（不需要任何授权行）")
    void adminSeesAllDocumentsInTenant() {
        asUser("admin");
        assertThat(readable(7L, 38L)).containsExactlyInAnyOrder(7L, 38L);
    }

    @Test
    @DisplayName("分析师经 ROLE 级文档授权看到 300059 的两份年报")
    void analystSeesDocumentsGrantedByRole() {
        // V19 迁移把原来的「ANALYST → COMPANY 7」展开成了「ANALYST → document 7 / 38」两行；
        // 这一段就是在验证展开的结果真的能读，而不是只写进了表。
        asUser("analyst");
        assertThat(readable(7L, 38L)).containsExactlyInAnyOrder(7L, 38L);
    }

    @Test
    @DisplayName("没有任何授权的用户看不到别人的文档")
    void userWithoutGrantSeesNothing() {
        asUser("outsider");
        assertThat(readable(7L, 38L)).isEmpty();
    }

    @Test
    @DisplayName("属主看得到自己上传的文档（没有授权行也行）")
    void ownerSeesOwnDocument() {
        long doc = insertDocument(userId("analyst"), companyId("300059"));
        asUser("analyst");
        assertThat(readable(doc)).containsExactly(doc);
    }

    @Test
    @DisplayName("别人的文档，没有授权就看不见")
    void nonOwnerWithoutGrantSeesNothing() {
        long doc = insertDocument(userId("analyst"), companyId("300059"));
        asUser("outsider");
        assertThat(readable(doc)).isEmpty();
    }

    @Test
    @DisplayName("给本人的文档级授权生效")
    void userGrantOnDocumentIsHonored() {
        long doc = insertDocument(userId("analyst"), companyId("300059"));
        insertDocumentGrant("USER", userId("outsider"), doc, "now() + interval '1 day'");
        asUser("outsider");
        assertThat(readable(doc)).containsExactly(doc);
    }

    @Test
    @DisplayName("给角色的文档级授权生效（同事被授予同一份）")
    void roleGrantOnDocumentIsHonored() {
        long doc = insertDocument(userId("analyst"), companyId("300059"));
        insertDocumentGrant("ROLE", roleId("VIEWER"), doc, "now() + interval '1 day'");
        asUser("outsider");   // outsider 只有 VIEWER 角色
        assertThat(readable(doc)).containsExactly(doc);
    }

    @Test
    @DisplayName("公司里新上传的文档不会因为「以前授过这家公司」就自动可读（快照语义）")
    void newDocumentOfCompanyIsNotInherited() {
        // 分析师已经有 300059 的 7/38 两份授权；这里再造一份同公司的新文档，
        // 属主是 analyst 本人 —— 所以要看 outsider：他没有任何针对这份新文档的授权，
        // 若实现里还残留「COMPANY 授权展开成公司全部文档」的旧规则，这条就会红。
        long fresh = insertDocument(userId("analyst"), companyId("300059"));
        asUser("outsider");
        assertThat(readable(fresh)).isEmpty();
    }

    @Test
    @DisplayName("过期的授权等于没有授权")
    void expiredGrantIsIgnored() {
        long doc = insertDocument(userId("analyst"), companyId("300059"));
        insertDocumentGrant("USER", userId("outsider"), doc, "now() - interval '1 day'");
        asUser("outsider");
        assertThat(readable(doc)).isEmpty();
    }

    @Test
    @DisplayName("未过期的授权可用（对照组：证明上一条的失败确实来自过期）")
    void validGrantIsHonored() {
        long doc = insertDocument(userId("analyst"), companyId("300059"));
        insertDocumentGrant("USER", userId("outsider"), doc, "now() + interval '1 day'");
        asUser("outsider");
        assertThat(readable(doc)).containsExactly(doc);
    }

    @Test
    @DisplayName("传入空列表 → 空结果，不查库也不报错")
    void emptyInputReturnsEmpty() {
        asUser("admin");
        assertThat(authorizer.filterReadable(List.of())).isEmpty();
        assertThat(authorizer.filterReadable(null)).isEmpty();
    }

    // ---------- 助手 ----------

    private List<Long> readable(long... ids) {
        List<Long> input = new ArrayList<>();
        for (long id : ids) {
            input.add(id);
        }
        return authorizer.filterReadable(input);
    }

    private void asUser(String username) {
        Long userId = userId(username);
        Long tenantId = jdbc.queryForObject("SELECT tenant_id FROM app_user WHERE id = ?", Long.class, userId);
        List<String> roles = jdbc.queryForList("""
                SELECT r.code FROM user_role ur JOIN role r ON r.id = ur.role_id WHERE ur.user_id = ?
                """, String.class, userId);
        TenantContext.set(new CurrentUser(userId, tenantId, username, username,
                new LinkedHashSet<>(roles)));
    }

    private Long userId(String username) {
        return jdbc.queryForObject("SELECT id FROM app_user WHERE username = ?", Long.class, username);
    }

    private Long companyId(String code) {
        return jdbc.queryForObject("SELECT id FROM company WHERE tenant_id = 1 AND code = ?", Long.class, code);
    }

    /**
     * 造一份测试文档。
     *
     * <p>{@code doc_year} 故意用 1999：V9 的 {@code uq_document_identity}
     * 是 {@code (tenant_id, company_id, doc_year, doc_type, version)} 的唯一索引，
     * 用 2025 + ANNUAL + 300059 会撞上库里真实存在的 document 7。
     *
     * <p>阶段 21 起 {@code document.owner_user_id} 是 NOT NULL：没有「无主文档」这种状态，
     * 每份文档都能答出「谁传的」。
     */
    private long insertDocument(Long ownerUserId, Long companyId) {
        String hash = "acl-test-" + UUID.randomUUID();
        Long id = jdbc.queryForObject("""
                INSERT INTO document (tenant_id, company_id, doc_type, doc_year, title, object_key,
                                      content_hash, parse_status, version, owner_user_id)
                VALUES (1, ?, 'ANNUAL', 1999, 'ACL 测试文档', 'test/acl.pdf', ?, 'DONE', 1, ?)
                RETURNING id
                """, Long.class, companyId, hash, ownerUserId);
        createdDocuments.add(id);
        return id;
    }

    private void insertDocumentGrant(String subjectType, long subjectId, long documentId, String expiresExpr) {
        jdbc.update("""
                INSERT INTO resource_grant
                    (tenant_id, subject_type, subject_id, resource_type, resource_id, permission, expires_at)
                VALUES (1, ?, ?, 'DOCUMENT', ?, 'READ', %s)
                """.formatted(expiresExpr), subjectType, subjectId, documentId);
    }

    private Long roleId(String code) {
        return jdbc.queryForObject("SELECT id FROM role WHERE tenant_id = 1 AND code = ?", Long.class, code);
    }

    private void deleteByIds(String table, List<Long> ids) {
        if (ids.isEmpty()) {
            return;
        }
        jdbc.update("DELETE FROM " + table + " WHERE id IN (" + placeholders(ids.size()) + ")", ids.toArray());
    }

    private static String placeholders(int n) {
        return String.join(", ", Collections.nCopies(n, "?"));
    }
}
