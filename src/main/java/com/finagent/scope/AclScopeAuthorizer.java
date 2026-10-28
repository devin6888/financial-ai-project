package com.finagent.scope;

import com.finagent.security.CurrentUser;
import com.finagent.security.TenantContext;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * 真实的 ACL：判断「当前调用方能不能读这几份文档」。
 *
 * <p>为什么要有这一层（而 RLS 不够）：RLS 只按 <b>租户</b> 隔离，同一个租户内所有文档对所有同事都可见；
 * 而产品需要「公司级授权」（分析师只负责 300059）和「私有文档」（只有 owner 能看）。
 * 两层各管一件事：RLS 管跨租户，这里管租户内的可见性。
 *
 * <p>为什么查询里还要显式写 {@code d.tenant_id = ?}：CLI 与测试用表 owner 连接时 RLS 被绕过，
 * 只有显式条件才能让「owner 连接」和「运行时角色连接」得到同一个答案。
 *
 * <p>什么时候装配它：{@code finagent.tenant.acl-enabled=true}（默认）；关掉时装配
 * {@link AllowAllAuthorizer}。装配在 {@link ScopeAuthorizerConfig} 里用单个工厂方法完成，
 * 不用 {@code @Component} / {@code @Profile}——两个类同时是 Bean 时启动会直接
 * {@code NoUniqueBeanDefinitionException}。
 */
public class AclScopeAuthorizer implements ScopeAuthorizer {

    private final JdbcTemplate jdbc;

    public AclScopeAuthorizer(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<Long> filterReadable(List<Long> documentIds) {
        if (documentIds == null || documentIds.isEmpty()) {
            return List.of();
        }
        CurrentUser user = TenantContext.require();

        // 去重但保序：调用方拿到的顺序与传入一致，后面「配额/引用排序」还会用到它
        List<Long> ids = new ArrayList<>(new LinkedHashSet<>(documentIds));

        if (user.isAdmin()) {
            // 管理员「全可见」也只在本租户内：租户边界是硬边界，不因角色放宽
            String sql = "SELECT d.id FROM document d WHERE d.tenant_id = ? AND d.id IN ("
                    + placeholders(ids.size()) + ")";
            List<Object> args = new ArrayList<>();
            args.add(user.tenantId());
            args.addAll(ids);
            return jdbc.query(sql, (rs, i) -> rs.getLong(1), args.toArray());
        }

        // 非管理员两条通路，缺一条就会出安全漏洞：
        //   1) PRIVATE 文档只看 owner —— 公司级授权**不能**穿透到私有文档
        //   2) 其余文档看授权：TENANT 文档查 COMPANY 授权，PRIVATE 之外的其它可见性查 DOCUMENT 授权
        StringBuilder sql = new StringBuilder("""
                SELECT d.id FROM document d
                 WHERE d.tenant_id = ?
                   AND d.id IN (%s)
                   AND (
                        (d.visibility = 'PRIVATE' AND d.owner_user_id = ?)
                     OR EXISTS (
                          SELECT 1 FROM resource_grant g
                           WHERE g.tenant_id = d.tenant_id
                             AND g.resource_type = CASE d.visibility
                                                     WHEN 'TENANT' THEN 'COMPANY'
                                                     ELSE 'DOCUMENT'
                                                   END
                             AND g.resource_id = CASE d.visibility
                                                   WHEN 'TENANT' THEN d.company_id
                                                   ELSE d.id
                                                 END
                             AND g.permission IN ('READ', 'READ_WRITE')
                             AND (g.expires_at IS NULL OR g.expires_at > now())
                             AND (
                                  (g.subject_type = 'USER' AND g.subject_id = ?)
                """.formatted(placeholders(ids.size())));
        List<Object> args = new ArrayList<>();
        args.add(user.tenantId());
        args.addAll(ids);
        args.add(user.userId());   // PRIVATE owner
        args.add(user.userId());   // 直接授给这个用户

        // 角色授权：CurrentUser 里是角色 code（ADMIN/ANALYST/VIEWER），而 resource_grant 存的是 role.id，
        // 所以要用 user_role 把 code 换成 id —— 顺手也验证了「这个用户真的有这个角色」。
        List<String> roleCodes = user.roles() == null ? List.of() : List.copyOf(user.roles());
        if (!roleCodes.isEmpty()) {
            sql.append("""
                                  OR (g.subject_type = 'ROLE' AND EXISTS (
                                       SELECT 1 FROM user_role ur JOIN role r ON r.id = ur.role_id
                                        WHERE ur.user_id = ? AND r.tenant_id = d.tenant_id
                                          AND r.id = g.subject_id AND r.code IN (%s)))
                    """.formatted(placeholders(roleCodes.size())));
            args.add(user.userId());
            args.addAll(roleCodes);
        }
        sql.append("""
                             )
                       )
                   )
                """);
        return jdbc.query(sql.toString(), (rs, i) -> rs.getLong(1), args.toArray());
    }

    private static String placeholders(int n) {
        return String.join(", ", java.util.Collections.nCopies(n, "?"));
    }
}
