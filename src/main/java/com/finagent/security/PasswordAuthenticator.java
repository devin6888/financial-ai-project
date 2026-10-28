package com.finagent.security;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 用户名 + 密码校验。
 *
 * <p>关键取舍：<b>哈希与校验都用 PostgreSQL 的 pgcrypto</b>（bcrypt，cost 10），
 * 而不是在 Java 里引一个 bcrypt 库。理由：
 * <ul>
 *   <li>只多一条 SQL，不新增依赖；</li>
 *   <li>明文密码只作为绑定参数经过一次网络，进不了日志与堆快照的字符串常量池；</li>
 *   <li>「密码错」与「用户不存在」在 SQL 层就是同一个空结果集，天然不可枚举。</li>
 * </ul>
 *
 * <p>唯一的代价是：密码校验必须走数据库，数据库不可用时登录失败（这是 fail closed，方向正确）。
 */
@Component
public class PasswordAuthenticator {

    /** 密码长度上限：bcrypt 本身只取前 72 字节，超长输入只是白送 CPU。 */
    private static final int MAX_PASSWORD_CHARS = 200;

    private static final String SQL = """
            SELECT u.id AS user_id, u.tenant_id, u.username, u.display_name, u.status, r.code
              FROM app_user u
              LEFT JOIN user_role ur ON ur.user_id = u.id
              LEFT JOIN role r ON r.id = ur.role_id
             WHERE u.username = ?
               AND u.status = 'ACTIVE'
               AND u.password_hash IS NOT NULL
               AND u.password_hash = crypt(?, u.password_hash)
            """;

    private final JdbcTemplate jdbc;

    public PasswordAuthenticator(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<CurrentUser> authenticate(String username, String password) {
        if (username == null || username.isBlank() || password == null || password.isEmpty()) {
            return Optional.empty();
        }
        if (password.length() > MAX_PASSWORD_CHARS) {
            return Optional.empty();
        }
        List<Row> rows = jdbc.query(SQL, (rs, i) -> new Row(
                rs.getLong("user_id"),
                rs.getLong("tenant_id"),
                rs.getString("username"),
                rs.getString("display_name"),
                rs.getString("status"),
                rs.getString("code")), username.trim(), password);
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        // 用户名只在租户内唯一；若同一用户名在多个租户都存在，宁可失败也不猜
        long distinctUsers = rows.stream().map(Row::userId).distinct().count();
        if (distinctUsers != 1) {
            return Optional.empty();
        }
        Row first = rows.get(0);
        Set<String> roles = new LinkedHashSet<>();
        for (Row row : rows) {
            if (row.roleCode() != null) {
                roles.add(row.roleCode());
            }
        }
        return Optional.of(new CurrentUser(first.userId(), first.tenantId(), first.username(), first.displayName(), roles));
    }

    private record Row(long userId, long tenantId, String username, String displayName,
                       String status, String roleCode) {
    }
}
