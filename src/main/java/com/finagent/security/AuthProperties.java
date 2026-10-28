package com.finagent.security;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 登录相关参数。
 *
 * @param sessionTtlHours       会话 token 有效期（小时）。默认 168 = 7 天，够一次演示周期。
 * @param maxLoginFailures      同一「用户名 + IP」在窗口内允许的失败次数，超过返回 429
 * @param failureWindowMinutes  失败计数的滑动窗口（分钟）
 */
@ConfigurationProperties(prefix = "finagent.auth")
public record AuthProperties(
        @DefaultValue("168") int sessionTtlHours,
        @DefaultValue("10") int maxLoginFailures,
        @DefaultValue("15") int failureWindowMinutes) {
}
