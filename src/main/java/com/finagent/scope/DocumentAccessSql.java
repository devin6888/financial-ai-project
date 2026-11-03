package com.finagent.scope;

/**
 * 「谁能读这份文档」的唯一一份 SQL 谓词（阶段 21）。
 *
 * <p>为什么要单独抽出来：同一条判定原先被抄了四份 —— 检索期的 {@link AclScopeAuthorizer}，
 * 以及管理端的用户文档列表、「为什么能读」、角色文档数。四份里任何一份漂移，都会出现
 * 「列表看不到、但引用能读到」这类自相矛盾的结果，而这是权限系统最难查的一类 bug。
 *
 * <p>口径只有一句人话：
 * <pre>
 * 可读 = 我是这份文档的属主
 *      OR 有一条文档级授权给到我（直接给用户，或给我的某个角色）
 * </pre>
 * 「整家上市公司」不是判定口径，只是管理端的便捷写法 —— 落库前已展开成文档级授权行
 * （见 {@code V19__document_ownership_and_grants.sql}）。这样任何一条「能看」都能对应到一行可删的数据。
 *
 * <p>占位符约定：{@code %1$s} 是「用户 id 的 SQL 表达式」，一条谓词里会用到 <b>三次</b>
 * （属主、直接授权、角色授权）。传 {@code "?"} 就必须绑定三次同一个值；为避免这种易错点，
 * {@link AclScopeAuthorizer} 用 {@code EXISTS (SELECT 1 FROM app_user u WHERE u.id = ? AND ...)}
 * 把三次引用收敛成一个占位符，管理端则直接传入字面量 id（{@code long}，无注入面）。
 *
 * <p>别名要求：外层必须把文档表叫 {@code d}（{@code FROM document d}）。
 */
public final class DocumentAccessSql {

    private DocumentAccessSql() {
    }

    private static final String READABLE = """
            (d.owner_user_id = %1$s
             OR EXISTS (SELECT 1 FROM resource_grant g
                         WHERE g.tenant_id = d.tenant_id
                           AND g.resource_type = 'DOCUMENT'
                           AND g.resource_id = d.id
                           AND g.permission IN ('READ', 'READ_WRITE')
                           AND (g.expires_at IS NULL OR g.expires_at > now())
                           AND ((g.subject_type = 'USER' AND g.subject_id = %1$s)
                             OR (g.subject_type = 'ROLE' AND EXISTS (
                                  SELECT 1 FROM user_role ur
                                   WHERE ur.user_id = %1$s AND ur.role_id = g.subject_id)))))
            """;

    private static final String IS_ADMIN = """
            EXISTS (SELECT 1 FROM user_role ur0 JOIN role r0 ON r0.id = ur0.role_id
                     WHERE ur0.user_id = %1$s AND r0.tenant_id = d.tenant_id AND r0.code = 'ADMIN')
            """;

    private static final String READABLE_BY_ROLE = """
            EXISTS (SELECT 1 FROM resource_grant g
                     WHERE g.tenant_id = d.tenant_id
                       AND g.resource_type = 'DOCUMENT'
                       AND g.resource_id = d.id
                       AND g.permission IN ('READ', 'READ_WRITE')
                       AND (g.expires_at IS NULL OR g.expires_at > now())
                       AND g.subject_type = 'ROLE' AND g.subject_id = %1$s)
            """;

    /**
     * 非管理员可读：属主，或有文档级授权（用户 / 角色）。
     *
     * @param userExpr 用户 id 的 SQL 表达式，例如 {@code "?"}、{@code "u.id"}、{@code "42"}
     */
    public static String readableBy(String userExpr) {
        return READABLE.formatted(userExpr);
    }

    /** 管理端口径：管理员全可见，其余同 {@link #readableBy(String)}。 */
    public static String visibleTo(String userExpr) {
        return "(" + IS_ADMIN.formatted(userExpr) + " OR " + readableBy(userExpr) + ")";
    }

    /** 角色维度的可读（「这个角色能读哪些文档」），ADMIN 角色由调用方单独处理。 */
    public static String readableByRole(String roleExpr) {
        return READABLE_BY_ROLE.formatted(roleExpr);
    }
}
