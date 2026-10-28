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
    private final ApiSessionRepository sessions;

    public AuthenticationFilter(ApiTokenRepository tokens, ApiSessionRepository sessions) {
        this.tokens = tokens;
        this.sessions = sessions;
    }

    /**
     * 只保护 {@code /api/**}，其余一律放行。
     *
     * <p>两个理由：
     * <ul>
     *   <li><b>静态资源不拦</b>：阶段 10 把前端产物放进 {@code static/}，单端口部署。
     *       若这里拦 {@code /}，浏览器拿到 401 就不会执行 JS，用户连登录框都看不到 ——
     *       实测过，不是推测。静态文件本身不含业务数据，鉴权点在 API。</li>
     *   <li><b>/error 不拦</b>：容器内部转发，再拦一次会把 401 变成「401 的 401」。</li>
     *   <li><b>/api/auth/login 不拦</b>：登录本身没有 token 可带，拦了就没法登录。
     *       只放这一个路径，登出（DELETE /api/auth/session）仍然要求已登录。</li>
     * </ul>
     */
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String uri = request.getRequestURI();
        if (!uri.startsWith("/api/")) {
            return true;
        }
        return uri.equals("/api/auth/login");
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
        String hash = ApiTokenRepository.sha256Hex(token);
        // 两条路：预置 token（dev-analyst-token 这类，测试与老链接用）→ 登录签发的会话 token
        Optional<CurrentUser> user = tokens.findByTokenHash(hash);
        if (user.isEmpty()) {
            user = sessions.findByTokenHash(hash);
            user.ifPresent(u -> sessions.touch(hash));
        }
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
