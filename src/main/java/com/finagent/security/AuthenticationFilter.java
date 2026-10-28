package com.finagent.security;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Optional;

/**
 * 认证：{@code Authorization: Bearer <token>} → 查库 → 建上下文 → 放行。
 *
 * <p>三条设计取舍：
 * <ul>
 *   <li><b>不用 Spring Security</b>：需求只有「解析 token / 建上下文 / 401」三件事，
 *       引入 Security 会顺带接管过滤器链、默认登录页与 CSRF，对一个无状态 JSON API 是负资产。</li>
 *   <li><b>401 而不是 403</b>：没有身份与身份无效都是 401；「有身份但没权限」由作用域层处理，
 *       返回 409 SCOPE_EMPTY，两者语义不同。</li>
 *   <li><b>「用户不存在」与「token 错误」返回同一句话</b>：否则这个接口就成了用户枚举器。</li>
 * </ul>
 *
 * <p>错误响应手写 JSON 而不用 ObjectMapper：载荷固定两个字段，少一个 Bean 依赖，
 * 单元测试可以直接 new 出 Filter（不必为了一个 401 报文启动 Spring）。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
@ConditionalOnProperty(prefix = "finagent.tenant", name = "auth-enabled", matchIfMissing = true)
public class AuthenticationFilter extends OncePerRequestFilter {

    private static final String BEARER = "Bearer ";

    private final ApiTokenRepository tokens;

    public AuthenticationFilter(ApiTokenRepository tokens) {
        this.tokens = tokens;
    }

    /** /error 是容器内部转发：再拦一次会把 401 变成「401 的 401」。 */
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return request.getRequestURI().startsWith("/error");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header == null || !header.startsWith(BEARER)) {
            reject(response, "AUTH_REQUIRED", "缺少 Authorization: Bearer <token>");
            return;
        }
        String token = header.substring(BEARER.length()).trim();
        Optional<CurrentUser> user = tokens.findByTokenHash(ApiTokenRepository.sha256Hex(token));
        if (user.isEmpty()) {
            reject(response, "AUTH_INVALID", "token 无效或已停用");
            return;
        }
        TenantContext.set(user.get());
        try {
            chain.doFilter(request, response);
        } finally {
            // 必须清：Tomcat 复用线程，残留上下文会让下一个请求带上别人的身份
            TenantContext.clear();
        }
    }

    private void reject(HttpServletResponse response, String code, String message) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"error\":\"" + code + "\",\"message\":\"" + message + "\"}");
    }
}
