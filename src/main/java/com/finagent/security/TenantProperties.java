package com.finagent.security;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 租户/认证/ACL 配置。
 *
 * @param authEnabled       是否启用 Bearer token 认证（默认 true；test profile 关掉）
 * @param aclEnabled        是否启用真实 ACL（默认 true；test profile 关掉，改用 {@code AllowAllAuthorizer}）
 * @param fallbackTenantId  没有 ThreadLocal 上下文时使用的租户 id；0 表示不使用兜底
 *                          （生产应为 0，让「无上下文」直接查不到任何行）
 */
@ConfigurationProperties(prefix = "finagent.tenant")
public record TenantProperties(boolean authEnabled,
                               @DefaultValue("true") boolean aclEnabled,
                               long fallbackTenantId) {
}
