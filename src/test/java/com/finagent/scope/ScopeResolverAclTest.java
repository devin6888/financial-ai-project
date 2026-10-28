package com.finagent.scope;

import com.finagent.security.CurrentUser;
import com.finagent.security.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 作用域三条路径（CORPUS / FILTER / DOCUMENTS）都要过 ACL —— 真库真授权行。
 *
 * <p>为什么必须单独有这份测试：{@code ScopeResolverTest} 跑在 test profile 里装配的是
 * {@link AllowAllAuthorizer}，它<b>永远</b>返回原样，ACL 写错也测不出来。这里用
 * {@code properties = acl-enabled=true} 打开真实实现。
 *
 * <p>重点验的是「被 ACL 删掉候选」这个信号（{@code aclNarrowed}）：检索层靠它决定
 * CORPUS 要不要把 document id 下推到 SQL。丢掉这个信号，ACL 就只影响提示词、不影响能取到哪些块。
 */
@SpringBootTest(properties = "finagent.tenant.acl-enabled=true")
@ActiveProfiles("test")
class ScopeResolverAclTest {

    @Autowired private ScopeResolver resolver;
    @Autowired private JdbcTemplate jdbc;

    private final List<Long> createdDocuments = new ArrayList<>();
    private final List<Long> createdCompanies = new ArrayList<>();

    @AfterEach
    void cleanup() {
        TenantContext.clear();   // 先清上下文，借到的连接才是 owner 角色（V9 已收走运行时角色的写权限）
        List<Long> resourceIds = new ArrayList<>(createdDocuments);
        resourceIds.addAll(createdCompanies);
        if (!resourceIds.isEmpty()) {
            jdbc.update("DELETE FROM resource_grant WHERE resource_id IN (" + placeholders(resourceIds.size()) + ")",
                    resourceIds.toArray());
        }
        deleteByIds("document", createdDocuments);
        deleteByIds("company", createdCompanies);
        createdDocuments.clear();
        createdCompanies.clear();
    }

    @Test
    @DisplayName("CORPUS：analyst 只拿到被授权的文档，并打上 aclNarrowed")
    void corpusIsFilteredByAclAndMarkedNarrowed() {
        long hiddenCompany = insertCompany("ACLHID");
        long hiddenDoc = insertDocument(hiddenCompany, 2024);

        asUser("analyst");
        ResolvedScope scope = resolver.resolve(ChatScope.corpus());

        assertThat(scope.documentIds()).contains(7L, 38L).doesNotContain(hiddenDoc);
        assertThat(scope.aclNarrowed()).isTrue();
        assertThat(scope.warnings()).contains("scope_acl_hidden");
    }

    @Test
    @DisplayName("CORPUS：admin 能看到全部可检索文档，不算被收窄")
    void corpusForAdminIsNotNarrowed() {
        asUser("admin");
        ResolvedScope scope = resolver.resolve(ChatScope.corpus());

        assertThat(scope.documentIds()).contains(7L, 38L);
        assertThat(scope.aclNarrowed()).isFalse();
        assertThat(scope.warnings()).doesNotContain("scope_acl_hidden");
    }

    @Test
    @DisplayName("FILTER：筛选命中的文档全被 ACL 拦掉 → 报 ScopeEmptyException，不退化成全库")
    void filterPathThrowsWhenEverythingIsHidden() {
        long hiddenCompany = insertCompany("ACLF");
        insertDocument(hiddenCompany, 2024);

        asUser("analyst");
        ChatScope scope = ChatScope.filter(new ScopeQuery(List.of("ACLF"), null, null, null));

        assertThatThrownBy(() -> resolver.resolve(scope))
                .isInstanceOf(ScopeEmptyException.class)
                .hasMessageContaining("当前账号都不可读");
    }

    @Test
    @DisplayName("FILTER：admin 能拿到同一份文档，且 aclNarrowed=false")
    void filterPathKeepsReadableDocumentsForAdmin() {
        long company = insertCompany("ACLFOK");
        long doc = insertDocument(company, 2024);

        asUser("admin");
        ResolvedScope scope = resolver.resolve(
                ChatScope.filter(new ScopeQuery(List.of("ACLFOK"), null, null, null)));

        assertThat(scope.documentIds()).containsExactly(doc);
        assertThat(scope.aclNarrowed()).isFalse();
    }

    @Test
    @DisplayName("DOCUMENTS：请求里混入别人不可读的文档 → 只留可读的，并打上 aclNarrowed")
    void documentsPathDropsUnreadableIdsAndMarksNarrowed() {
        long hiddenCompany = insertCompany("ACLMIX");
        long hiddenDoc = insertDocument(hiddenCompany, 2024);

        asUser("analyst");
        ResolvedScope scope = resolver.resolve(ChatScope.documents(7L, hiddenDoc));

        assertThat(scope.documentIds()).containsExactly(7L);
        assertThat(scope.aclNarrowed()).isTrue();
    }

    // ---------- 助手 ----------

    private void asUser(String username) {
        Long userId = jdbc.queryForObject("SELECT id FROM app_user WHERE username = ?", Long.class, username);
        Long tenantId = jdbc.queryForObject("SELECT tenant_id FROM app_user WHERE id = ?", Long.class, userId);
        List<String> roles = jdbc.queryForList("""
                SELECT r.code FROM user_role ur JOIN role r ON r.id = ur.role_id WHERE ur.user_id = ?
                """, String.class, userId);
        TenantContext.set(new CurrentUser(userId, tenantId, username, username,
                new LinkedHashSet<>(roles)));
    }

    private long insertCompany(String code) {
        Long id = jdbc.queryForObject("""
                INSERT INTO company (tenant_id, code, name) VALUES (1, ?, ?) RETURNING id
                """, Long.class, code, "作用域 ACL 测试公司 " + code);
        createdCompanies.add(id);
        return id;
    }

    /** 造一份「可检索」文档：findRetrievable 要求 parse_status=DONE 且 active_chunk_version 非空。 */
    private long insertDocument(long companyId, int docYear) {
        String hash = "scope-acl-test-" + UUID.randomUUID();
        Long id = jdbc.queryForObject("""
                INSERT INTO document (tenant_id, company_id, doc_type, doc_year, title, object_key,
                                      content_hash, parse_status, version, active_chunk_version, visibility)
                VALUES (1, ?, 'ANNUAL', ?, '作用域 ACL 测试文档', 'test/scope-acl.pdf', ?, 'DONE', 1, 1, 'TENANT')
                RETURNING id
                """, Long.class, companyId, docYear, hash);
        createdDocuments.add(id);
        return id;
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
