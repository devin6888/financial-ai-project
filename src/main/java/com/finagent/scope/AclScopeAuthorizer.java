package com.finagent.scope;

import com.finagent.security.CurrentUser;
import com.finagent.security.TenantContext;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * 真实的 ACL：判断「当前调用方能不能读这几份文档」。
 *
 * <p>为什么要有这一层（而 RLS 不够）：RLS 只按 <b>租户</b> 隔离，同一个租户内所有文档对所有同事都可见；
 * 而产品需要「文档默认属于上传者，别人要读必须被显式授权」。两层各管一件事：
 * RLS 管跨租户，这里管租户内的归属与授权。
 *
 * <p>判定口径集中在 {@link DocumentAccessSql}（管理端也用它，避免两套实现漂移）：
 * <b>属主 OR 文档级授权（直接给用户 / 经角色）</b>，管理员在租户内显式全可见。
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

        // 非管理员：一条谓词解决属主 / USER 授权 / ROLE 授权三条路径（见 DocumentAccessSql）。
        //
        // 外面套 EXISTS(SELECT 1 FROM app_user u WHERE u.id = ?) 不是为了"验证用户存在"，
        // 而是为了让当前用户 id 只占 **一个** 占位符：谓词里要引用它三次，
        // 直接传 "?" 就得绑定三次同一个值，参数错位是这种拼接最常见的 bug。
        String sql = """
                SELECT d.id FROM document d
                 WHERE d.tenant_id = ?
                   AND d.id IN (%s)
                   AND EXISTS (SELECT 1 FROM app_user u WHERE u.id = ? AND %s)
                """.formatted(placeholders(ids.size()), DocumentAccessSql.readableBy("u.id"));
        List<Object> args = new ArrayList<>();
        args.add(user.tenantId());
        args.addAll(ids);
        args.add(user.userId());
        return jdbc.query(sql, (rs, i) -> rs.getLong(1), args.toArray());
    }

    private static String placeholders(int n) {
        return String.join(", ", Collections.nCopies(n, "?"));
    }
}
