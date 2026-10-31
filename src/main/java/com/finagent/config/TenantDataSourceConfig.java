package com.finagent.config;

import com.finagent.security.TenantAwareDataSource;
import com.finagent.security.TenantProperties;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.jdbc.autoconfigure.DataSourceProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import javax.sql.DataSource;

/**
 * 数据源接线：裸连接池 + 租户感知包装。
 *
 * <p>为什么先绑 {@link DataSourceProperties} 再建池，而不是直接
 * {@code @ConfigurationProperties("spring.datasource")} 打在 HikariDataSource 上：
 * yml 里的键叫 {@code url}，而 Hikari 的属性叫 {@code jdbcUrl}，直接绑会「静默不生效」，
 * 启动时报 <b>dataSource or dataSourceClassName or jdbcUrl is required</b>。
 * DataSourceProperties 负责 url → jdbcUrl 这一步转换。
 *
 * <p>另外两点容易踩：
 * <ol>
 *   <li>{@code @Primary} 只打在包装后的那个上，否则 JdbcTemplate 注入时会看到两个候选；</li>
 *   <li>Boot 的 DataSourceAutoConfiguration 在容器已有 DataSource 时会退让，所以不会打架。</li>
 * </ol>
 *
 * <p>Flyway 用独立凭据（{@code spring.flyway.url/user/password}）：迁移要改表结构，
 * 运行时角色 finagent_app 没有 DDL 权限，也不需要 RLS 拦截迁移。
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(TenantProperties.class)
public class TenantDataSourceConfig {

    @Bean
    @ConfigurationProperties("spring.datasource")
    public DataSourceProperties dataSourceProperties() {
        return new DataSourceProperties();
    }

    /**
     * 原始连接池。
     *
     * <p>两段绑定缺一不可：{@link DataSourceProperties} 负责 {@code url → jdbcUrl}；
     * {@code spring.datasource.hikari.*}（池大小、超时）必须再单独绑一次 —— 手写 {@code new HikariDataSource}
     * 时会绕过 Boot 的 DataSourceAutoConfiguration，那些子键以前是<b>静默不生效</b>的
     * （2026-10-29 全量测试撞到 FATAL: sorry, too many clients already 才发现）。
     */
    @Bean
    @ConfigurationProperties("spring.datasource.hikari")
    public DataSource rawDataSource(@Qualifier("dataSourceProperties") DataSourceProperties props) {
        return props.initializeDataSourceBuilder().type(HikariDataSource.class).build();
    }

    @Bean
    @Primary
    public DataSource dataSource(@Qualifier("rawDataSource") DataSource raw, TenantProperties tenant) {
        return new TenantAwareDataSource(raw, tenant.fallbackTenantId());
    }
}
