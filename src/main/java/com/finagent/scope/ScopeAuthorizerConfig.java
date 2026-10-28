package com.finagent.scope;

import com.finagent.security.TenantProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 装配唯一的 {@link ScopeAuthorizer}。
 *
 * <p>为什么用「配置类 + 单个工厂方法」而不是给两个实现各打 {@code @Component} + {@code @Profile}：
 * <ul>
 *   <li>两个类同时是 Bean → 启动直接 {@code NoUniqueBeanDefinitionException}；</li>
 *   <li>成对写 {@code @ConditionalOnProperty} 也有坑：{@code havingValue} 不写时「存在且不是 false」都算命中，
 *       空串也算，于是两个条件同时成立；值写成 {@code TRUE} 又可能两个都不命中。
 *       单工厂方法从结构上<b>只可能</b>产出一个 Bean。</li>
 * </ul>
 *
 * <p>开关是 {@code finagent.tenant.acl-enabled}：默认 {@code true}（生产里「忘记开」比「忘记关」危险得多），
 * test profile 在 {@code application-test.yml} 里显式关成 {@code false}。
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(TenantProperties.class)
public class ScopeAuthorizerConfig {

    @Bean
    public ScopeAuthorizer scopeAuthorizer(TenantProperties properties, JdbcTemplate jdbc) {
        if (properties.aclEnabled()) {
            return new AclScopeAuthorizer(jdbc);
        }
        return new AllowAllAuthorizer();
    }
}
