package com.finagent.security;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * 当前调用方身份（阶段 10 前端要用）。
 *
 * <p>为什么必须有这个端点：界面要显示「我是谁、什么角色、哪个租户」。没有它，用户
 * 拿到一个 401 或一份空列表时无法判断是 token 错了、还是这个人本来就看不到任何文档。
 *
 * <p><b>身份只来自 {@link TenantContext}</b>（由 {@link AuthenticationFilter} 从 Bearer
 * token 查库写入），绝不读请求体、查询串、Cookie 里自称的身份（契约 C11 §2.1）。
 * 这也是它不能顺便提供「切换用户」能力的原因 —— 那等于把身份来源从 token 改成参数。
 *
 * <p>roles 按字母序输出：同一个用户两次请求必须得到逐字节相同的响应，前端才能把
 * 它当作缓存键，测试也才能断言固定字符串。
 */
@RestController
public class MeController {

    @GetMapping("/api/me")
    public Me me() {
        CurrentUser user = TenantContext.require();
        List<String> roles = user.roles() == null ? List.of() : List.copyOf(new TreeSet<>(user.roles()));
        return new Me(user.userId(), user.tenantId(), user.username(), user.displayName(), roles);
    }

    /**
     * 没有身份时 401，而不是返回空角色集。
     *
     * <p>返回 200 + 空角色会让前端以为「登录成功但这个账号什么都没配」，
     * 真正的修复动作（换一个 token）就不会发生。
     */
    @ExceptionHandler(UnauthorizedException.class)
    public ResponseEntity<Object> unauthorized(UnauthorizedException e) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", e.code());
        body.put("message", "需要登录");
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(body);
    }

    /** @param roles 角色 code 列表（已排序），如 ["ANALYST"] */
    public record Me(long userId, long tenantId, String username, String displayName,
                     List<String> roles) {
    }
}
