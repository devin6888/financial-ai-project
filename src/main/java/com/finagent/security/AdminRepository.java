package com.finagent.security;

import com.finagent.scope.DocumentAccessSql;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 用户 / 角色 / 资源授权的读写（阶段 18 ST18 需求 11）。
 *
 * <p>只被 {@link AdminController} 调用，所有 SQL 都带 {@code tenant_id} ——
 * 管理员再大也只在本租户内（设计 §15.4：租户是硬边界，不是权限）。
 *
 * <p>为什么密码在 SQL 里算：{@code crypt(pw, gen_salt('bf', 10))} 与 V11 迁移里种子用户的
 * 哈希口径完全一致，不需要在 Java 侧再引一个 bcrypt 依赖，也不会出现「两种 cost 混在库里」。
 *
 * <p>为什么「某个用户能读哪些文档」要在这里算：``AclScopeAuthorizer`` 只知道"这一批 id 里
 * 哪些可读"，管理端要反过来问"这个人到底能读哪些"。两边共用同一套判定条件
 * （{@link DocumentAccessSql}），否则管理页显示的和检索时真正能读的会不一致 ——
 * 那是权限系统最危险的一类 bug：管理员以为自己授过权了。
 */
@Repository
public class AdminRepository {

    // 「谁能读这份文档」的判定不在这里写第二份：统一用 {@link DocumentAccessSql}，
    // 与检索期的 AclScopeAuthorizer 共用同一段 SQL。阶段 21 之前这段谓词被抄了四份，
    // 每抄一次都可能悄悄漂移一次。


    private final JdbcTemplate jdbc;

    public AdminRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ---------- 用户 ----------

    /**
     * 分页 + 搜索 + 每人可读文档数。
     *
     * <p>文档数用相关子查询而不是再拉一遍全表：管理页一页只有 20 行，20 个子查询比
     * "查出全租户文档再到内存里对一遍"更省内存，也不会因为文档变多而把响应撑大。
     */
    public Paged<UserRow> searchUsers(long tenantId, String q, String status, int page, int size) {
        String like = likeOf(q);
        List<Object> args = new ArrayList<>();
        args.add(tenantId);
        args.add(q);
        args.add(like);
        args.add(like);
        args.add(status);
        args.add(status);
        String where = """
                 WHERE u.tenant_id = ?
                   AND (coalesce(?, '') = '' OR u.username ILIKE ? OR coalesce(u.display_name, '') ILIKE ?)
                   AND (coalesce(?, '') = '' OR u.status = ?)
                """;
        long total = count("SELECT count(*) FROM app_user u" + where, args);

        String visible = DocumentAccessSql.visibleTo("u.id");
        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(size);
        pageArgs.add(offset(page, size));
        List<UserRow> items = jdbc.query("""
                SELECT u.id, u.username, u.display_name, u.status, u.created_at,
                       coalesce(array_agg(r.code ORDER BY r.code) FILTER (WHERE r.code IS NOT NULL), '{}') AS roles,
                       (SELECT count(*) FROM document d WHERE d.tenant_id = u.tenant_id AND %s) AS doc_count
                  FROM app_user u
                  LEFT JOIN user_role ur ON ur.user_id = u.id
                  LEFT JOIN role r ON r.id = ur.role_id
                %s
                 GROUP BY u.id, u.username, u.display_name, u.status, u.created_at
                 ORDER BY u.username
                 LIMIT ? OFFSET ?
                """.formatted(visible, where), (rs, i) -> new UserRow(
                rs.getLong("id"),
                rs.getString("username"),
                rs.getString("display_name"),
                rs.getString("status"),
                List.of((String[]) rs.getArray("roles").getArray()),
                rs.getObject("created_at", OffsetDateTime.class),
                rs.getLong("doc_count")),
                pageArgs.toArray());
        return new Paged<>(items, total, page, size);
    }

    /** 用户名重复由唯一约束兜住 → 调用方捕获 DuplicateKeyException 转 409。 */
    public long createUser(long tenantId, String username, String displayName, String password) {
        return jdbc.queryForObject("""
                INSERT INTO app_user (tenant_id, username, display_name, status, api_token_hash, password_hash)
                VALUES (?, ?, ?, 'ACTIVE', md5(random()::text) || md5(gen_random_uuid()::text),
                        crypt(?, gen_salt('bf', 10)))
                RETURNING id
                """, Long.class, tenantId, username, displayName, password);
    }

    public int updateProfile(long tenantId, long userId, String displayName, String status) {
        return jdbc.update("""
                UPDATE app_user SET display_name = coalesce(?, display_name), status = coalesce(?, status)
                 WHERE id = ? AND tenant_id = ?
                """, displayName, status, userId, tenantId);
    }

    public int replaceRoles(long tenantId, long userId, List<String> roleCodes) {
        jdbc.update("DELETE FROM user_role WHERE user_id = ?", userId);
        if (roleCodes == null || roleCodes.isEmpty()) {
            return 0;
        }
        return jdbc.update("""
                INSERT INTO user_role (user_id, role_id)
                SELECT ?, r.id FROM role r WHERE r.tenant_id = ? AND r.code = ANY (?)
                """, userId, tenantId, roleCodes.toArray(new String[0]));
    }

    /**
     * 除 this user 之外，这个租户里还有几个「活着的管理员」。
     *
     * <p>只数 ACTIVE 用户：把唯一的管理员停用掉，效果和删掉它的 ADMIN 角色一样 —— 谁都进不了管理页。
     */
    public int countOtherAdmins(long tenantId, long userId) {
        Integer n = jdbc.queryForObject("""
                SELECT count(DISTINCT u.id)
                  FROM app_user u
                  JOIN user_role ur ON ur.user_id = u.id
                  JOIN role r ON r.id = ur.role_id
                 WHERE u.tenant_id = ? AND u.status = 'ACTIVE'
                   AND r.tenant_id = ? AND r.code = 'ADMIN'
                   AND u.id <> ?
                """, Integer.class, tenantId, tenantId, userId);
        return n == null ? 0 : n;
    }

    /** 一次性查出这些 code 里哪些在库里不存在 —— 用来把「拼错的角色」变成 400 而不是静默少授权。 */
    public List<String> unknownRoles(long tenantId, List<String> roleCodes) {
        if (roleCodes == null || roleCodes.isEmpty()) {
            return List.of();
        }
        List<String> known = jdbc.queryForList(
                "SELECT code FROM role WHERE tenant_id = ? AND code = ANY (?)",
                String.class, tenantId, roleCodes.toArray(new String[0]));
        List<String> missing = new ArrayList<>(roleCodes);
        missing.removeAll(known);
        return missing;
    }

    public boolean userExists(long tenantId, long userId) {
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM app_user WHERE id = ? AND tenant_id = ?", Integer.class, userId, tenantId);
        return count != null && count > 0;
    }

    /**
     * 某个用户能读的文档，带"为什么能读"。
     *
     * <p>reason 四档：{@code ADMIN} 管理员全可见、{@code OWNER} 本人上传的、
     * {@code USER} 直接授给本人、{@code ROLE} 经角色授给他。
     */
    public Paged<DocumentAccess> documentsOfUser(long tenantId, long userId, int page, int size) {
        boolean admin = isAdmin(tenantId, userId);
        // 用户 id 直接用字面量拼进 SQL：它是 long（来自会话/路径变量，不是可写字符串），没有注入面；
        // 换来的是同一条判定里要引用三四次时不会出现参数错位 —— 这里历史上就错过一次。
        String uid = Long.toString(userId);
        String visible = DocumentAccessSql.visibleTo(uid);
        long total = count("""
                SELECT count(*) FROM document d
                 WHERE d.tenant_id = ?
                   AND %s
                """.formatted(visible), List.of(tenantId));

        List<DocumentAccess> items = jdbc.query("""
                SELECT d.id, d.title, d.doc_year,
                       CASE WHEN ? THEN 'ADMIN'
                            WHEN d.owner_user_id = %s THEN 'OWNER'
                            WHEN EXISTS (
                                 SELECT 1 FROM resource_grant g
                                  WHERE g.tenant_id = d.tenant_id
                                    AND g.subject_type = 'USER' AND g.subject_id = %s
                                    AND g.resource_type = 'DOCUMENT' AND g.resource_id = d.id
                                    AND g.permission IN ('READ', 'READ_WRITE')
                                    AND (g.expires_at IS NULL OR g.expires_at > now())
                            ) THEN 'USER'
                            ELSE 'ROLE' END AS reason
                  FROM document d
                 WHERE d.tenant_id = ?
                   AND %s
                 ORDER BY d.doc_year DESC NULLS LAST, d.id DESC
                 LIMIT ? OFFSET ?
                """.formatted(uid, uid, visible), (rs, i) -> new DocumentAccess(
                rs.getLong("id"),
                rs.getString("title"),
                (Integer) rs.getObject("doc_year"),
                rs.getString("reason")),
                admin, tenantId, size, offset(page, size));
        return new Paged<>(items, total, page, size);
    }

    /** 只有管理页会用：这个人是不是管理员（决定「可读文档」要不要按全租户算）。 */
    public boolean isAdmin(long tenantId, long userId) {
        Integer count = jdbc.queryForObject("""
                SELECT count(*) FROM user_role ur JOIN role r ON r.id = ur.role_id
                 WHERE ur.user_id = ? AND r.tenant_id = ? AND r.code = 'ADMIN'
                """, Integer.class, userId, tenantId);
        return count != null && count > 0;
    }

    // ---------- 角色 ----------

    public List<RoleRow> listRoles(long tenantId) {
        return jdbc.query("""
                SELECT r.id, r.code, r.name, r.is_system,
                       (SELECT count(*) FROM user_role ur WHERE ur.role_id = r.id) AS user_count,
                       (SELECT count(DISTINCT d.id) FROM document d
                         WHERE d.tenant_id = r.tenant_id
                           AND (r.code = 'ADMIN' OR %s)) AS doc_count
                  FROM role r
                 WHERE r.tenant_id = ?
                 ORDER BY r.is_system DESC, r.code
                """.formatted(DocumentAccessSql.readableByRole("r.id")), (rs, i) -> new RoleRow(
                rs.getLong("id"),
                rs.getString("code"),
                rs.getString("name"),
                rs.getBoolean("is_system"),
                rs.getLong("user_count"),
                rs.getLong("doc_count")),
                tenantId);
    }

    /** 角色详情（改名前判断存在性、是否系统内置）。 */
    public RoleRow roleById(long tenantId, long roleId) {
        List<RoleRow> rows = jdbc.query("""
                SELECT r.id, r.code, r.name, r.is_system,
                       (SELECT count(*) FROM user_role ur WHERE ur.role_id = r.id) AS user_count,
                       0 AS doc_count
                  FROM role r
                 WHERE r.id = ? AND r.tenant_id = ?
                """, (rs, i) -> new RoleRow(
                rs.getLong("id"),
                rs.getString("code"),
                rs.getString("name"),
                rs.getBoolean("is_system"),
                rs.getLong("user_count"),
                rs.getLong("doc_count")),
                roleId, tenantId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public boolean roleCodeExists(long tenantId, String code) {
        Integer n = jdbc.queryForObject(
                "SELECT count(*) FROM role WHERE tenant_id = ? AND code = ?", Integer.class, tenantId, code);
        return n != null && n > 0;
    }

    /** 新建自定义角色：code 建好之后不可改（授权与代码里都按 code 认），只允许改显示名。 */
    public long createRole(long tenantId, String code, String name) {
        Long id = jdbc.queryForObject(
                "INSERT INTO role (tenant_id, code, name, is_system) VALUES (?, ?, ?, false) RETURNING id",
                Long.class, tenantId, code, name);
        return id == null ? 0L : id;
    }

    public int renameRole(long tenantId, long roleId, String name) {
        return jdbc.update("UPDATE role SET name = ? WHERE id = ? AND tenant_id = ?", name, roleId, tenantId);
    }

    /** 删角色：先把它的授权清掉再删角色本身（resource_grant 只存裸 id，没有外键兜底）。 */
    @Transactional
    public int deleteRoleWithGrants(long tenantId, long roleId) {
        jdbc.update("DELETE FROM resource_grant WHERE tenant_id = ? AND subject_type = 'ROLE' AND subject_id = ?",
                tenantId, roleId);
        return jdbc.update("DELETE FROM role WHERE id = ? AND tenant_id = ? AND is_system = false", roleId, tenantId);
    }

    public boolean roleExists(long tenantId, long roleId) {
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM role WHERE id = ? AND tenant_id = ?", Integer.class, roleId, tenantId);
        return count != null && count > 0;
    }

    /** 角色成员（管理页「角色详情」用）。 */
    public Paged<UserRow> membersOfRole(long tenantId, long roleId, int page, int size) {
        List<Object> args = List.of(tenantId, roleId);
        String where = """
                 FROM app_user u
                 JOIN user_role ur ON ur.user_id = u.id
                WHERE u.tenant_id = ? AND ur.role_id = ?
                """;
        long total = count("SELECT count(*) " + where, args);
        List<UserRow> items = jdbc.query("""
                SELECT u.id, u.username, u.display_name, u.status, u.created_at,
                       coalesce(array_agg(r.code ORDER BY r.code) FILTER (WHERE r.code IS NOT NULL), '{}') AS roles,
                       0 AS doc_count
                  FROM app_user u
                  JOIN user_role ur ON ur.user_id = u.id
                  LEFT JOIN role r ON r.id = ur.role_id
                 WHERE u.tenant_id = ? AND ur.role_id = ?
                 GROUP BY u.id, u.username, u.display_name, u.status, u.created_at
                 ORDER BY u.username
                 LIMIT ? OFFSET ?
                """, (rs, i) -> new UserRow(
                rs.getLong("id"),
                rs.getString("username"),
                rs.getString("display_name"),
                rs.getString("status"),
                List.of((String[]) rs.getArray("roles").getArray()),
                rs.getObject("created_at", OffsetDateTime.class),
                0L),
                tenantId, roleId, size, offset(page, size));
        return new Paged<>(items, total, page, size);
    }

    /**
     * 角色能读的文档。
     *
     * <p>ADMIN 角色没有授权记录也有全部文档 → 走另一条 SQL，并把 grant_id 置空、resource_type 记 ALL；
     * 其它角色按授权展开：一条文档级授权 → 那一份文档（阶段 21 起不再有公司级授权行，
     * 「整家公司」在授权时已展开成逐份文档）。
     * 展开而不是只列授权记录，是因为管理员真正关心的是"能读到哪些"。
     */
    public Paged<RoleDoc> documentsOfRole(long tenantId, long roleId, int page, int size) {
        String code = jdbc.queryForObject(
                "SELECT code FROM role WHERE id = ? AND tenant_id = ?", String.class, roleId, tenantId);
        boolean admin = "ADMIN".equals(code);
        long total;
        List<RoleDoc> items;
        if (admin) {
            total = count("SELECT count(*) FROM document d WHERE d.tenant_id = ?", List.of(tenantId));
            items = jdbc.query("""
                    SELECT NULL::bigint AS grant_id, d.id, d.title, d.doc_year,
                           'ALL' AS resource_type, 'READ' AS permission, NULL::timestamptz AS created_at
                      FROM document d
                     WHERE d.tenant_id = ?
                     ORDER BY d.doc_year DESC NULLS LAST, d.id DESC
                     LIMIT ? OFFSET ?
                    """, ROLE_DOC_MAPPER, tenantId, size, offset(page, size));
        } else {
            total = count("""
                    SELECT count(DISTINCT d.id)
                      FROM resource_grant g
                      JOIN document d ON d.tenant_id = g.tenant_id AND d.id = g.resource_id
                     WHERE g.tenant_id = ? AND g.resource_type = 'DOCUMENT'
                      AND g.subject_type = 'ROLE' AND g.subject_id = ?
                    """, List.of(tenantId, roleId));
            items = jdbc.query("""
                    SELECT g.id AS grant_id, d.id, d.title, d.doc_year,
                           g.resource_type, g.permission, g.created_at
                      FROM resource_grant g
                      JOIN document d ON d.tenant_id = g.tenant_id AND d.id = g.resource_id
                     WHERE g.tenant_id = ? AND g.resource_type = 'DOCUMENT'
                      AND g.subject_type = 'ROLE' AND g.subject_id = ?
                     ORDER BY d.doc_year DESC NULLS LAST, d.id DESC
                     LIMIT ? OFFSET ?
                    """, ROLE_DOC_MAPPER, tenantId, roleId, size, offset(page, size));
        }
        return new Paged<>(items, total, page, size);
    }

    // ---------- 资源授权 ----------

    /** 授权记录（审计视角），带搜索：按主体名或文档名找一条授权。 */
    public Paged<GrantRow> listGrants(long tenantId, String q, int page, int size) {
        String like = likeOf(q);
        List<Object> args = new ArrayList<>();
        args.add(tenantId);
        args.add(q);
        args.add(like);
        args.add(like);
        args.add(like);
        String where = """
                 FROM resource_grant g
                WHERE g.tenant_id = ?
                  AND (coalesce(?, '') = ''
                       OR EXISTS (SELECT 1 FROM role r WHERE r.id = g.subject_id AND r.code ILIKE ?)
                       OR EXISTS (SELECT 1 FROM app_user u WHERE u.id = g.subject_id AND u.username ILIKE ?)
                       OR EXISTS (SELECT 1 FROM document d WHERE d.id = g.resource_id AND d.title ILIKE ?))
                """;
        long total = count("SELECT count(*) " + where, args);
        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(size);
        pageArgs.add(offset(page, size));
        List<GrantRow> items = jdbc.query("""
                SELECT g.id, g.subject_type, g.subject_id, g.resource_type, g.resource_id, g.permission,
                       g.created_at,
                       CASE g.subject_type
                            WHEN 'ROLE' THEN (SELECT r.code FROM role r WHERE r.id = g.subject_id)
                            ELSE (SELECT u.username FROM app_user u WHERE u.id = g.subject_id)
                       END AS subject_name,
                       CASE g.resource_type
                            WHEN 'DOCUMENT' THEN (SELECT d.title FROM document d WHERE d.id = g.resource_id)
                            WHEN 'COMPANY' THEN (SELECT c.name FROM company c WHERE c.id = g.resource_id)
                            ELSE NULL
                       END AS resource_name
                %s
                 ORDER BY g.id DESC
                 LIMIT ? OFFSET ?
                """.formatted(where), (rs, i) -> new GrantRow(
                rs.getLong("id"),
                rs.getString("subject_type"),
                rs.getLong("subject_id"),
                rs.getString("subject_name"),
                rs.getString("resource_type"),
                rs.getLong("resource_id"),
                rs.getString("resource_name"),
                rs.getString("permission"),
                rs.getObject("created_at", OffsetDateTime.class)),
                pageArgs.toArray());
        return new Paged<>(items, total, page, size);
    }

    /**
     * 批量建「文档级」授权，返回真正新建的条数（已存在的不算）。
     *
     * <p>{@code ON CONFLICT DO NOTHING} 依赖 V19 的唯一索引 {@code uq_grant_subject_resource}：
     * 同一主体对同一份文档重复授权不会报错、也不会写第二行 —— 前端「保存」按钮可以放心按。
     *
     * <p>为什么循环 insert 而不是一条 {@code INSERT ... SELECT unnest(?)}：一份授权最多几百行，
     * 循环版 SQL 好读、出错时能指出是哪一份；真要上量再换批量版也不迟。
     */
    @Transactional
    public int createDocumentGrants(long tenantId, String subjectType, long subjectId,
                                    List<Long> documentIds, long grantedBy) {
        int created = 0;
        for (Long documentId : documentIds) {
            created += jdbc.update("""
                    INSERT INTO resource_grant (tenant_id, subject_type, subject_id, resource_type, resource_id,
                                                permission, granted_by)
                    VALUES (?, ?, ?, 'DOCUMENT', ?, 'READ', ?)
                    ON CONFLICT (tenant_id, subject_type, subject_id, resource_type, resource_id) DO NOTHING
                    """, tenantId, subjectType, subjectId, documentId, grantedBy);
        }
        return created;
    }

    /**
     * 某家上市公司在本租户下的全部文档 id —— 「整家公司授权」在落库前要展开成这些行。
     *
     * <p>快照语义：展开发生在授权那一刻，之后这家公司新上传的文档 <b>不会</b> 自动可读。
     * 这是有意的：授权要有明确的边界与一次可审计的动作，而不是一条看不见的规则。
     */
    public List<Long> documentIdsOfCompany(long tenantId, long companyId) {
        return jdbc.queryForList("""
                SELECT id FROM document
                 WHERE tenant_id = ? AND company_id = ?
                 ORDER BY id
                """, Long.class, tenantId, companyId);
    }

    public int deleteGrant(long tenantId, long grantId) {
        return jdbc.update("DELETE FROM resource_grant WHERE id = ? AND tenant_id = ?", grantId, tenantId);
    }

    /** 文档选择器：授权时要能按标题/公司名搜到文档。 */
    public Paged<DocumentOption> searchDocuments(long tenantId, String q, int page, int size) {
        // q 可能为 null（没传搜索词）；List.of 不接受 null，先用空串归一化
        String term = q == null ? "" : q.trim();
        String like = likeOf(term);
        List<Object> args = List.of(tenantId, term, like, like);
        String where = """
                 FROM document d
                 LEFT JOIN company c ON c.id = d.company_id
                WHERE d.tenant_id = ?
                  AND (coalesce(?, '') = '' OR d.title ILIKE ? OR coalesce(c.name, '') ILIKE ?)
                """;
        long total = count("SELECT count(*) " + where, args);
        List<DocumentOption> items = jdbc.query("""
                SELECT d.id, d.title, d.doc_year, d.company_id, c.name AS company_name
                %s
                 ORDER BY d.doc_year DESC NULLS LAST, d.id DESC
                 LIMIT ? OFFSET ?
                """.formatted(where), (rs, i) -> new DocumentOption(
                rs.getLong("id"),
                rs.getString("title"),
                (Integer) rs.getObject("doc_year"),
                (Long) rs.getObject("company_id"),
                rs.getString("company_name")),
                tenantId, term, like, like, size, offset(page, size));
        return new Paged<>(items, total, page, size);
    }

    public boolean documentExists(long tenantId, long documentId) {
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM document WHERE id = ? AND tenant_id = ?", Integer.class, documentId, tenantId);
        return count != null && count > 0;
    }

    public boolean companyExists(long tenantId, long companyId) {
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM company WHERE id = ? AND tenant_id = ?", Integer.class, companyId, tenantId);
        return count != null && count > 0;
    }

    /** 页头统计：账号 / 角色 / 文档 / 授权。 */
    public Overview overview(long tenantId) {
        return jdbc.queryForObject("""
                SELECT (SELECT count(*) FROM app_user WHERE tenant_id = ?) AS users,
                       (SELECT count(*) FROM role WHERE tenant_id = ?) AS roles,
                       (SELECT count(*) FROM document WHERE tenant_id = ?) AS documents,
                       (SELECT count(*) FROM resource_grant WHERE tenant_id = ?) AS grants
                """, (rs, i) -> new Overview(
                rs.getLong("users"), rs.getLong("roles"),
                rs.getLong("documents"), rs.getLong("grants")),
                tenantId, tenantId, tenantId, tenantId);
    }

    // ---------- 工具 ----------

    private long count(String sql, List<Object> args) {
        Long value = jdbc.queryForObject(sql, Long.class, args.toArray());
        return value == null ? 0 : value;
    }

    private static int offset(int page, int size) {
        return Math.max(0, page - 1) * size;
    }

    private static String likeOf(String q) {
        return "%" + (q == null ? "" : q.trim()) + "%";
    }

    private static final org.springframework.jdbc.core.RowMapper<RoleDoc> ROLE_DOC_MAPPER = (rs, i) ->
            new RoleDoc(
                    (Long) rs.getObject("grant_id"),
                    rs.getLong("id"),
                    rs.getString("title"),
                    (Integer) rs.getObject("doc_year"),
                    rs.getString("resource_type"),
                    rs.getString("permission"),
                    rs.getObject("created_at", OffsetDateTime.class));

    /** 一页数据 + 总数。前端分页控件要总数，不能只返回 items。 */
    public record Paged<T>(List<T> items, long total, int page, int size) {
    }

    public record UserRow(long id, String username, String displayName, String status,
                          List<String> roles, OffsetDateTime createdAt, long docCount) {
    }

    public record RoleRow(long id, String code, String name, boolean system, long userCount, long docCount) {
    }

    public record GrantRow(long id, String subjectType, long subjectId, String subjectName,
                           String resourceType, long resourceId, String resourceName,
                           String permission, OffsetDateTime createdAt) {
    }

    public record DocumentAccess(long documentId, String title, Integer docYear, String reason) {
    }

    public record RoleDoc(Long grantId, long documentId, String title, Integer docYear,
                          String resourceType, String permission, OffsetDateTime createdAt) {
    }

    public record DocumentOption(long id, String title, Integer docYear, Long companyId, String companyName) {
    }

    public record Overview(long users, long roles, long documents, long grants) {
    }
}
