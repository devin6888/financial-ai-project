package com.finagent.tenant;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 越权验收（设计 §15.4 的四类越权 + 本项目的两条补充）。
 *
 * <p>这一类比单元测试贵（起真 Tomcat、真过滤器、真 RLS、其中一条会调真模型），但它是
 * 「有权限」与「没权限」唯一无法自欺的证据：mock 出来的 401/409 只能证明 mock 会返回什么。
 *
 * <p>六类负样本：
 * <ol>
 *   <li>语义高度相关但无权限：同一租户、无任何授权的 outsider 问核心财务问题；</li>
 *   <li>直接传他人 documentId：outsider 用 DOCUMENTS 模式点 [7, 38]；</li>
 *   <li>枚举：外租户管理员拉「可选文档列表」；</li>
 *   <li>对象直链：外租户拿 documentId 直查详情，不能是 403（403 会泄漏「这个 id 存在」）；</li>
 *   <li>请求体伪造身份：塞 tenantId / userId 不改变任何结果；</li>
 *   <li>对照组：分析师在同一套链路上能正常拿到带引用的答案（否则上面的空结果可能只是接口坏了）。</li>
 * </ol>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"finagent.tenant.auth-enabled=true", "finagent.tenant.acl-enabled=true"})
@ActiveProfiles("test")
@Tag("e2e")
class TenantIsolationE2ETest {

    private static final String TOKEN_ANALYST = "dev-analyst-token";
    private static final String TOKEN_OUTSIDER = "dev-outsider-token";
    private static final String QUESTION = "东方财富2025年营业收入是多少";

    @LocalServerPort private int port;
    @Autowired private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        TenantFixture.ensureIntruder(jdbc);
    }

    @Test
    @DisplayName("① 同租户无授权：语义高度相关也拿不到数据（409 + 正文无数字）")
    void sameTenantWithoutGrantIsRefused() {
        TenantFixture.Http r = TenantFixture.request(port, "POST", "/api/chat", TOKEN_OUTSIDER,
                "{\"question\":\"" + QUESTION + "\"}");

        assertThat(r.status()).isEqualTo(409);
        assertThat(r.body()).contains("SCOPE_EMPTY");
        assertThat(r.body()).doesNotContainPattern("[0-9]");
    }

    @Test
    @DisplayName("② 传他人 documentId：DOCUMENTS 模式直接点 7/38 → 409，不返回任何引用")
    void foreignDocumentIdsResolveToEmptyScope() {
        TenantFixture.Http r = TenantFixture.request(port, "POST", "/api/chat", TOKEN_OUTSIDER,
                "{\"question\":\"" + QUESTION + "\",\"scope\":{\"kind\":\"DOCUMENTS\",\"documentIds\":[7,38]}}");

        assertThat(r.status()).isEqualTo(409);
        assertThat(r.body()).contains("SCOPE_EMPTY");
        // 错误响应里不能再把「你点过的 id」原样回显，否则等于确认这些文档存在
        assertThat(r.body()).doesNotContain("\"citations\"");
        assertThat(r.body()).doesNotContainPattern("[0-9]");
    }

    @Test
    @DisplayName("③ 跨租户枚举：外租户管理员的可选文档列表为空（RLS 硬边界，与角色无关）")
    void otherTenantCannotEnumerateDocuments() {
        TenantFixture.Http r = TenantFixture.request(port, "GET", "/api/scope/documents",
                TenantFixture.INTRUDER_TOKEN, null);

        assertThat(r.status()).isEqualTo(200);
        assertThat(r.body().trim()).isEqualTo("[]");
    }

    @Test
    @DisplayName("④ 对象直链：外租户查他人 documentId → 404（不是 403，不能确认这个 id 存在）")
    void crossTenantDocumentDetailIsNotFound() {
        TenantFixture.Http r = TenantFixture.request(port, "GET", "/api/documents/7",
                TenantFixture.INTRUDER_TOKEN, null);

        assertThat(r.status()).isEqualTo(404);
        assertThat(r.body()).doesNotContain("parsed/");
        assertThat(r.body()).doesNotContain("东方财富");
    }

    @Test
    @DisplayName("⑤ 伪造身份：请求体塞 tenantId=1、userId=1 不会让 outsider 看到任何东西")
    void forgedIdentityInBodyIsIgnored() {
        TenantFixture.Http r = TenantFixture.request(port, "POST", "/api/chat", TOKEN_OUTSIDER,
                "{\"question\":\"" + QUESTION + "\",\"tenantId\":1,\"userId\":1}");

        assertThat(r.status()).isEqualTo(409);
        assertThat(r.body()).contains("SCOPE_EMPTY");
    }

    @Test
    @DisplayName("⑥ 对照组：分析师能正常问答，且引用全部落在自己可读的文档里")
    void analystStillGetsGroundedAnswer() {
        TenantFixture.Http r = TenantFixture.request(port, "POST", "/api/chat", TOKEN_ANALYST,
                "{\"question\":\"" + QUESTION + "\"}");

        assertThat(r.status()).isEqualTo(200);
        assertThat(r.body()).contains("\"sufficient\":true");
        assertThat(r.body()).contains("\"citations\":[{");

        // 引用必须只来自 300059（分析师被授权的公司）。响应里出现 600519 的 documentId 就是越权。
        String body = r.body();
        assertThat(body).doesNotContain("\"parsed/").doesNotContain("cos.");
        System.out.println("TENANT-E2E|analyst|status=" + r.status() + "|len=" + body.length());
    }
}
