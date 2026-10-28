package com.finagent.tenant;

import com.finagent.security.ApiTokenRepository;
import org.springframework.jdbc.core.JdbcTemplate;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * 越权测试的夹具：造第二个租户 + 一个「外租户管理员」，并提供走真实 HTTP 的调用助手。
 *
 * <p>为什么要有第二个租户：本项目所有业务数据都在租户 1。要证明「租户是硬边界」，
 * 必须有一个<b>什么业务数据都没有</b>的租户 —— 它看到的一切空结果，都只能是 RLS 造成的。
 *
 * <p>为什么外租户用户给 ADMIN 角色：这样「看不到」就与角色无关，只与租户边界有关。
 * 如果给他 VIEWER，测试失败时你会先怀疑角色，浪费一轮排查。
 *
 * <p>所有写库操作都在<b>没有</b> {@code TenantContext} 的主线程里做（走表 owner，RLS 不生效）；
 * 带上下文借出的连接会被 SET ROLE 成 {@code finagent_app}，而 V9 已经收走了它对认证表的写权限。
 */
final class TenantFixture {

    static final String OTHER_TENANT_NAME = "other-test";
    static final String INTRUDER_USERNAME = "intruder";
    static final String INTRUDER_TOKEN = "dev-intruder-token";

    private TenantFixture() { }

    /** 幂等：租户已存在就返回它的 id。 */
    static long ensureOtherTenant(JdbcTemplate jdbc) {
        jdbc.update("""
                INSERT INTO tenant (name, status)
                SELECT ?, 'ACTIVE'
                 WHERE NOT EXISTS (SELECT 1 FROM tenant WHERE name = ?)
                """, OTHER_TENANT_NAME, OTHER_TENANT_NAME);
        return jdbc.queryForObject("SELECT id FROM tenant WHERE name = ?", Long.class, OTHER_TENANT_NAME);
    }

    /** 幂等：租户内的 ADMIN 角色。 */
    static long ensureAdminRole(JdbcTemplate jdbc, long tenantId) {
        jdbc.update("""
                INSERT INTO role (tenant_id, code, name, is_system)
                SELECT ?, 'ADMIN', '系统管理员', true
                 WHERE NOT EXISTS (SELECT 1 FROM role WHERE tenant_id = ? AND code = 'ADMIN')
                """, tenantId, tenantId);
        return jdbc.queryForObject("SELECT id FROM role WHERE tenant_id = ? AND code = 'ADMIN'",
                Long.class, tenantId);
    }

    /**
     * 幂等：外租户的 intruder 用户。
     *
     * <p>用 {@code ON CONFLICT (tenant_id, username) DO UPDATE SET api_token_hash = EXCLUDED...}，
     * 这样重复跑不会因为「用户名已存在」失败，也不会因为 token 被改过而登录不上。
     */
    static long ensureIntruder(JdbcTemplate jdbc) {
        long tenantId = ensureOtherTenant(jdbc);
        long roleId = ensureAdminRole(jdbc, tenantId);
        Long userId = jdbc.queryForObject("""
                INSERT INTO app_user (tenant_id, username, display_name, api_token_hash)
                VALUES (?, ?, '外租户入侵者', ?)
                ON CONFLICT (tenant_id, username)
                DO UPDATE SET api_token_hash = EXCLUDED.api_token_hash, status = 'ACTIVE'
                RETURNING id
                """, Long.class, tenantId, INTRUDER_USERNAME, ApiTokenRepository.sha256Hex(INTRUDER_TOKEN));
        jdbc.update("INSERT INTO user_role (user_id, role_id) VALUES (?, ?) ON CONFLICT DO NOTHING",
                userId, roleId);
        return userId;
    }

    // ------------------------------------------------------------ HTTP

    record Http(int status, String body) { }

    static Http request(int port, String method, String path, String token, String json) {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .timeout(Duration.ofSeconds(120));
        if (json != null) {
            b.header("Content-Type", "application/json")
                    .method(method, HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8));
        } else {
            b.method(method, HttpRequest.BodyPublishers.noBody());
        }
        if (token != null) {
            b.header("Authorization", "Bearer " + token);
        }
        try {
            HttpResponse<byte[]> resp = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(5)).build()
                    .send(b.build(), HttpResponse.BodyHandlers.ofByteArray());
            return new Http(resp.statusCode(), new String(resp.body(), StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("请求 " + method + " " + path + " 失败：" + e.getMessage(), e);
        }
    }
}
