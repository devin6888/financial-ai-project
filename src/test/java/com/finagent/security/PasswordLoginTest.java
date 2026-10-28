package com.finagent.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 账号密码登录的真链路测试：真 Tomcat、真过滤器、真数据库、真 pgcrypto。
 *
 * <p>为什么不用 MockMvc：这里要验的是「过滤器 + 会话表 + 密码校验」三者的协作，
 * MockMvc 会跳过 servlet 过滤器链，验不到「登出后同一 token 立刻失效」。
 *
 * <p>节流上限调成 3，让 429 用例不必打十次。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "finagent.tenant.auth-enabled=true",
        "finagent.tenant.acl-enabled=true",
        "finagent.auth.max-login-failures=3"
})
@ActiveProfiles("test")
class PasswordLoginTest {

    private static final String DEMO_USER = "demo";
    private static final String DEMO_PASSWORD = "demo123456";

    @Value("${local.server.port}")
    int port;

    @Autowired
    JdbcTemplate jdbc;

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final ObjectMapper json = new ObjectMapper();

    @AfterEach
    void cleanSessions() {
        jdbc.update("DELETE FROM api_session WHERE user_id IN (SELECT id FROM app_user WHERE username = ?)", DEMO_USER);
        TenantContext.clear();
    }

    @Test
    void demoLoginReturnsTokenAndIdentity() throws Exception {
        Http login = post("/api/auth/login", "{\"username\":\"demo\",\"password\":\"demo123456\"}");
        assertEquals(200, login.status());

        JsonNode body = json.readTree(login.body());
        String token = body.path("token").asText();
        assertEquals(43, token.length(), "32 字节 base64url 无填充 = 43 字符");
        assertEquals("demo", body.path("user").path("username").asText());
        assertEquals("演示用户", body.path("user").path("displayName").asText());
        assertTrue(body.path("user").path("roles").toString().contains("ADMIN"));
        assertTrue(body.path("user").path("roles").toString().contains("ANALYST"));
        assertTrue(body.path("expiresAt").asText().compareTo("2026-01-01") > 0, "过期时间必须在未来");

        Http me = get("/api/me", token);
        assertEquals(200, me.status());
        assertEquals("demo", json.readTree(me.body()).path("username").asText());
        assertEquals(25L, json.readTree(me.body()).path("userId").asLong());
    }

    @Test
    void wrongPasswordAndUnknownUserAreIndistinguishable() throws Exception {
        Http wrong = post("/api/auth/login", "{\"username\":\"demo\",\"password\":\"nope\"}");
        Http unknown = post("/api/auth/login", "{\"username\":\"nobody-at-all\",\"password\":\"nope\"}");

        assertEquals(401, wrong.status());
        assertEquals(401, unknown.status());
        assertEquals(wrong.body(), unknown.body(), "报文必须逐字相同，否则这个接口成了用户枚举器");
        assertTrue(wrong.body().contains("AUTH_INVALID"));
    }

    @Test
    void logoutRevokesTheTokenImmediately() throws Exception {
        String token = loginToken();

        assertEquals(204, delete("/api/auth/session", token).status());

        assertEquals(401, get("/api/me", token).status(), "撤销后同一个 token 必须立刻失效");
        assertEquals(1, jdbc.queryForObject(
                "SELECT count(*) FROM api_session WHERE revoked_at IS NOT NULL AND user_id = 25", Integer.class),
                "撤销是软删除：行还在，只写 revoked_at");
    }

    @Test
    void seedTokensStillWork() throws Exception {
        // 三个 dev-* 预置 token 是 500+ 既有用例与 e2e 的地基，不能被新登录方式挤掉
        Http me = get("/api/me", "dev-analyst-token");
        assertEquals(200, me.status());
        assertEquals("analyst", json.readTree(me.body()).path("username").asText());
    }

    @Test
    void blankCredentialsAre400() throws Exception {
        assertEquals(400, post("/api/auth/login", "{\"username\":\"\",\"password\":\"\"}").status());
        assertEquals(400, post("/api/auth/login", "{}").status());
    }

    @Test
    void repeatedFailuresAreThrottled() throws Exception {
        String username = "ghost-" + UUID.randomUUID();
        String body = "{\"username\":\"" + username + "\",\"password\":\"nope\"}";

        for (int i = 0; i < 3; i++) {
            assertEquals(401, post("/api/auth/login", body).status(), "第 " + (i + 1) + " 次失败");
        }
        Http blocked = post("/api/auth/login", body);
        assertEquals(429, blocked.status());
        assertTrue(blocked.body().contains("AUTH_TOO_MANY"));
        assertFalse(blocked.body().contains(username), "响应不得回显用户名");
    }

    private String loginToken() throws Exception {
        Http login = post("/api/auth/login", "{\"username\":\"demo\",\"password\":\"demo123456\"}");
        assertEquals(200, login.status());
        String token = json.readTree(login.body()).path("token").asText();
        assertNotNull(token);
        return token;
    }

    private record Http(int status, String body) {
    }

    private Http post(String path, String body) throws Exception {
        return send("POST", path, null, body);
    }

    private Http get(String path, String token) throws Exception {
        return send("GET", path, token, null);
    }

    private Http delete(String path, String token) throws Exception {
        return send("DELETE", path, token, null);
    }

    private Http send(String method, String path, String token, String body) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .timeout(Duration.ofSeconds(30));
        if (token != null) {
            builder.header("Authorization", "Bearer " + token);
        }
        if (body != null) {
            builder.header("Content-Type", "application/json");
            builder.method(method, HttpRequest.BodyPublishers.ofString(body));
        } else {
            builder.method(method, HttpRequest.BodyPublishers.noBody());
        }
        HttpResponse<String> response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        return new Http(response.statusCode(), response.body());
    }
}
