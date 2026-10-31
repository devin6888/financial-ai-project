package com.finagent.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * REST 层的认证契约（走真过滤器、真 Tomcat、真 RLS，不 mock 任何一环）。
 *
 * <p>为什么不能用 standalone MockMvc 测：{@code AuthenticationFilter} 是靠 Spring Boot 自动注册进
 * 过滤器链的，standaloneSetup 根本不加载它 —— 那样测出的「401」只会是「路由不存在」。
 *
 * <p>为什么打开 {@code auth-enabled}：{@code application-test.yml} 里它是 false，为的是让 300 多个
 * 既有测试不必带 token。契约测试必须显式把它打开，才是在验生产链路。
 *
 * <p>本类<b>不调真模型</b>：401 / 409 都发生在检索与生成之前，所以整类跑得很快；
 * 「有权限的正常问答」与「真模型下的拒答措辞」由阶段 8 的越权 e2e 覆盖。
 */
@Tag("db")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"finagent.tenant.auth-enabled=true", "finagent.tenant.acl-enabled=true"})
@ActiveProfiles("test")
class RestAuthContractTest {

    private static final String TOKEN_ADMIN = "dev-admin-token";
    private static final String TOKEN_ANALYST = "dev-analyst-token";
    private static final String TOKEN_OUTSIDER = "dev-outsider-token";

    @LocalServerPort private int port;

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5)).build();

    @Test
    @DisplayName("不带 token → 401 + AUTH_REQUIRED")
    void missingTokenIsUnauthorized() {
        HttpResult r = post("/api/chat", null, "{\"question\":\"东方财富2025年营业收入是多少\"}");

        assertThat(r.status()).isEqualTo(401);
        assertThat(r.body()).contains("\"error\":\"AUTH_REQUIRED\"");
    }

    @Test
    @DisplayName("token 错误 → 401 + AUTH_INVALID（与「用户不存在」同一个响应，不做用户枚举）")
    void invalidTokenIsUnauthorized() {
        HttpResult r = post("/api/chat", "dev-no-such-token", "{\"question\":\"q\"}");

        assertThat(r.status()).isEqualTo(401);
        assertThat(r.body()).contains("\"error\":\"AUTH_INVALID\"");
    }

    @Test
    @DisplayName("每一个对外端点在无 token 时都 401 —— 没有「忘了加认证」的漏口")
    void everyEndpointRequiresToken() {
        assertThat(get("/api/scope/documents", null).status()).isEqualTo(401);
        assertThat(get("/api/documents/7", null).status()).isEqualTo(401);
        assertThat(post("/api/chat", null, "{\"question\":\"q\"}").status()).isEqualTo(401);
        assertThat(post("/api/agent/runs/" + java.util.UUID.randomUUID() + "/resume", null,
                "{\"decision\":\"APPROVE\"}").status()).isEqualTo(401);
        assertThat(get("/api/agent/runs/" + java.util.UUID.randomUUID(), null).status()).isEqualTo(401);
    }

    @Test
    @DisplayName("同一租户但无任何授权的用户：409 SCOPE_EMPTY，且响应正文里没有一个财务数字")
    void outsiderGetsScopeEmptyNotLeakedData() {
        HttpResult r = post("/api/chat", TOKEN_OUTSIDER, "{\"question\":\"东方财富2025年营业收入是多少\"}");

        assertThat(r.status()).isEqualTo(409);
        assertThat(r.body()).contains("\"error\":\"SCOPE_EMPTY\"");
        // 拒答时正文不能夹带任何数字（金额、页码、chunkId 都属于泄漏面）
        assertThat(r.body()).doesNotContainPattern("[0-9]");
    }

    @Test
    @DisplayName("请求体/查询串里塞 tenantId、userId 一律无效：身份只来自 token")
    void clientSuppliedTenantIsIgnored() {
        // ① 查询串注入：分析师带上 tenantId=2 也只会拿到自己租户（tenant 1）里被授权的文档
        HttpResult listed = get("/api/scope/documents?tenantId=2&userId=3", TOKEN_ANALYST);
        assertThat(listed.status()).isEqualTo(200);
        assertThat(listed.body()).contains("\"id\":7");
        assertThat(listed.body()).doesNotContain("\"id\":999");

        // ② 请求体注入：多出来的字段既不会报错，也不会改变行为（照样按空问题判 400）
        HttpResult chat = post("/api/chat", TOKEN_ANALYST,
                "{\"question\":\"   \",\"tenantId\":2,\"userId\":3}");
        assertThat(chat.status()).isEqualTo(400);
        assertThat(chat.body()).contains("\"error\":\"BAD_REQUEST\"");
        assertThat(chat.body()).contains("question");
    }

    @Test
    @DisplayName("管理员能枚举到本租户的文档（对照组：证明上面的空列表是 ACL 造成的，不是接口坏了）")
    void adminCanEnumerateTenantDocuments() {
        HttpResult r = get("/api/scope/documents", TOKEN_ADMIN);

        assertThat(r.status()).isEqualTo(200);
        assertThat(r.body()).contains("\"id\":7").contains("\"id\":38");
    }

    // ------------------------------------------------------------ HTTP 助手

    private record HttpResult(int status, String body) { }

    private HttpResult get(String path, String token) {
        return send(HttpRequest.newBuilder(uri(path)).GET(), token);
    }

    private HttpResult post(String path, String token, String json) {
        HttpRequest.Builder b = HttpRequest.newBuilder(uri(path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8));
        return send(b, token);
    }

    private HttpResult send(HttpRequest.Builder builder, String token) {
        if (token != null) {
            builder.header("Authorization", "Bearer " + token);
        }
        try {
            HttpResponse<byte[]> resp = http.send(builder.timeout(Duration.ofSeconds(30)).build(),
                    HttpResponse.BodyHandlers.ofByteArray());
            return new HttpResult(resp.statusCode(), new String(resp.body(), StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("请求失败：" + e.getMessage(), e);
        }
    }

    private URI uri(String path) {
        return URI.create("http://localhost:" + port + path);
    }
}
