package com.finagent.security;

import java.util.Set;

/**
 * 可信调用方。
 *
 * <p><b>只能由 {@link AuthenticationFilter} 构造</b>：它来自 Bearer token 查库的结果，
 * 绝不接受请求体、查询参数、Query Understanding 输出或 Cookie 里自称的身份（契约 C11 §2.1）。
 *
 * <p>roles 是角色 code 的集合（ADMIN / ANALYST / VIEWER），一个用户可能有多个。
 */
public record CurrentUser(long userId, long tenantId, String username,
                          String displayName, Set<String> roles) {

    public boolean hasRole(String code) {
        return roles != null && roles.contains(code);
    }

    /** 管理员在本租户内全可见 —— 注意「本租户」仍然是硬边界。 */
    public boolean isAdmin() {
        return hasRole("ADMIN");
    }
}
