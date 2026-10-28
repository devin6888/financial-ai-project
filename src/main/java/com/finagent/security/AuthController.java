package com.finagent.security;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;

/**
 * 登录与会话管理。
 *
 * <p>两个端点：
 * <ul>
 *   <li>{@code POST /api/auth/login}：用户名 + 密码 → 会话 token（这是唯一不需要 token 的 API，见
 *       {@link AuthenticationFilter#shouldNotFilter}）；</li>
 *   <li>{@code DELETE /api/auth/session}：撤销当前 token（登出）。</li>
 * </ul>
 *
 * <p>错误码只有三档：400 参数缺失、401 AUTH_INVALID（用户不存在 / 密码错 / 已停用三种情况
 * 报文逐字相同，防止用户枚举）、429 AUTH_TOO_MANY（节流）。
 */
@RestController
@RequestMapping("/api/auth")
@EnableConfigurationProperties(AuthProperties.class)
public class AuthController {

    private static final String BEARER = "Bearer ";

    private final PasswordAuthenticator authenticator;
    private final ApiSessionRepository sessions;
    private final LoginThrottle throttle;
    private final AuthProperties props;

    public AuthController(PasswordAuthenticator authenticator, ApiSessionRepository sessions,
                          LoginThrottle throttle, AuthProperties props) {
        this.authenticator = authenticator;
        this.sessions = sessions;
        this.throttle = throttle;
        this.props = props;
    }

    record LoginRequest(String username, String password) {
    }

    record LoginUser(long userId, long tenantId, String username, String displayName, List<String> roles) {
    }

    record LoginResponse(String token, OffsetDateTime expiresAt, LoginUser user) {
    }

    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody(required = false) LoginRequest body, HttpServletRequest http) {
        if (body == null || body.username() == null || body.username().isBlank()
                || body.password() == null || body.password().isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "BAD_REQUEST",
                    "message", "username 与 password 都不能为空"));
        }
        String key = LoginThrottle.key(body.username(), http.getRemoteAddr());
        if (throttle.isBlocked(key)) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body(Map.of(
                    "error", "AUTH_TOO_MANY",
                    "message", "失败次数过多，请稍后再试"));
        }
        Optional<CurrentUser> user = authenticator.authenticate(body.username(), body.password());
        if (user.isEmpty()) {
            throttle.recordFailure(key);
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of(
                    "error", "AUTH_INVALID",
                    "message", "用户名或密码错误"));
        }
        throttle.reset(key);
        CurrentUser current = user.get();
        ApiSessionRepository.IssuedSession issued = sessions.issue(
                current.userId(), current.tenantId(), "web-login", props.sessionTtlHours());
        return ResponseEntity.ok(new LoginResponse(
                issued.token(),
                issued.expiresAt(),
                new LoginUser(current.userId(), current.tenantId(), current.username(), current.displayName(),
                        List.copyOf(new TreeSet<>(current.roles())))));
    }

    @DeleteMapping("/session")
    public ResponseEntity<Void> logout(@RequestHeader(name = HttpHeaders.AUTHORIZATION, required = false) String header) {
        if (header != null && header.startsWith(BEARER)) {
            sessions.revoke(ApiTokenRepository.sha256Hex(header.substring(BEARER.length()).trim()));
        }
        return ResponseEntity.noContent().build();
    }
}
