package com.finagent.security;

import org.springframework.jdbc.datasource.DelegatingDataSource;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * 按「当前请求有没有身份」切换数据库角色与租户变量。
 *
 * <p><b>有身份（HTTP 请求线程）</b>：{@code SET ROLE finagent_app} + {@code set_config('app.tenant_id', …)}
 * —— 于是 RLS 生效，读不到别的租户，写不进别的租户。
 *
 * <p><b>没有身份（Kafka worker / CLI / 启动恢复）</b>：{@code RESET ROLE}，回到 owner 视角。
 * 这不是为了绕过安全，而是因为这些路径**必须先读到父行才能知道租户**
 * （document_chunk、task_message 的 tenant 都从 document 行推导）；如果它们也被 RLS 挡住，
 * 就变成「要读 document 才能设租户，要设租户才能读 document」的死循环，
 * 表现为 INSERT 0 行、UPDATE 0 行且不报错 —— 这是最难查的一类故障。
 *
 * <p><b>为什么不用 SET LOCAL</b>：SET LOCAL 只在事务内有效，而本项目大量 JdbcTemplate 调用是
 * autocommit 的单语句 —— 变量在语句结束就消失，RLS 会永远看到 NULL（= 0 行，全部查不到）。
 * 所以用 session 级 {@code set_config(..., false)}，且每次借出都重新设置（含 RESET ROLE），
 * 不依赖 close 时的清理。
 *
 * <p>代价：每次借连接多两次极小的往返（本机实测 &lt; 0.5 ms）；一个 chat 请求会借几十次连接。
 */
public class TenantAwareDataSource extends DelegatingDataSource {

    private static final String RUNTIME_ROLE = "finagent_app";
    private static final String SET_TENANT = "SELECT set_config('app.tenant_id', ?, false)";

    private final long fallbackTenantId;

    public TenantAwareDataSource(DataSource target, long fallbackTenantId) {
        super(target);
        this.fallbackTenantId = fallbackTenantId;
    }

    @Override
    public Connection getConnection() throws SQLException {
        return withTenant(super.getConnection());
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        return withTenant(super.getConnection(username, password));
    }

    private Connection withTenant(Connection connection) throws SQLException {
        try {
            apply(connection);
            return connection;
        } catch (SQLException e) {
            // 设不上就别把连接借出去：否则后续查询会在「无租户」状态下静默返回 0 行
            try {
                connection.close();
            } catch (SQLException suppressed) {
                e.addSuppressed(suppressed);
            }
            throw e;
        }
    }

    private void apply(Connection connection) throws SQLException {
        CurrentUser user = TenantContext.current();
        long tenantId = user != null ? user.tenantId() : fallbackTenantId;
        try (Statement st = connection.createStatement()) {
            if (user != null) {
                st.execute("SET ROLE " + RUNTIME_ROLE);
            } else {
                st.execute("RESET ROLE");
            }
        }
        try (PreparedStatement ps = connection.prepareStatement(SET_TENANT)) {
            ps.setString(1, tenantId > 0 ? String.valueOf(tenantId) : "");
            ps.execute();
        }
    }
}
