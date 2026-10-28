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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 零泄漏门限（设计 §18.3：泄漏计数必须为 0）。
 *
 * <p>和 {@link TenantIsolationE2ETest} 的分工：那边验「状态码对不对」（该 409 的 409），
 * 这边验「响应正文里有没有夹带」—— 一个接口完全可以状态码正确、正文里却带着对象键或别人的标题。
 *
 * <p>三个扫描面：
 * <ol>
 *   <li><b>对象存储痕迹</b>：{@code document.object_key} 原文、{@code parsed/}、{@code cos.}、
 *       {@code myqcloud.com} —— 一旦出现，攻击者拿 COS 直链就绕过了 RLS；</li>
 *   <li><b>文档标题</b>：调用方不可读的文档，连标题都不能出现（存在性泄漏）；</li>
 *   <li><b>正文片段</b>：从不可读文档的 chunk 里取 40 字片段，命中即泄漏。</li>
 * </ol>
 *
 * <p>门槛是<b>字符串命中数 = 0</b>，不是「大部分没有」。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"finagent.tenant.auth-enabled=true", "finagent.tenant.acl-enabled=true"})
@ActiveProfiles("test")
@Tag("e2e")
class ZeroLeakGateTest {

    private static final String TOKEN_ANALYST = "dev-analyst-token";
    private static final String TOKEN_OUTSIDER = "dev-outsider-token";
    private static final List<Long> READABLE_BY_ANALYST = List.of(7L, 38L);
    private static final String QUESTION = "东方财富2025年营业收入是多少";

    @LocalServerPort private int port;
    @Autowired private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        TenantFixture.ensureIntruder(jdbc);
    }

    @Test
    @DisplayName("五类响应的正文拼起来：对象键 / 他人标题 / 正文片段，命中数必须是 0")
    void noLeakAcrossRefusedAndAllowedResponses() {
        // 越权方的响应：对他们而言，租户 1 的每一份文档都不可读
        List<String> refusedBodies = new ArrayList<>();
        refusedBodies.add(ask(TOKEN_OUTSIDER, "{\"question\":\"" + QUESTION + "\"}"));                                     // 409
        refusedBodies.add(ask(TOKEN_OUTSIDER, "{\"question\":\"" + QUESTION
                + "\",\"scope\":{\"kind\":\"DOCUMENTS\",\"documentIds\":[7,38]}}"));                                        // 409
        refusedBodies.add(get("/api/scope/documents", TOKEN_OUTSIDER));                                                     // []
        refusedBodies.add(get("/api/scope/documents", TenantFixture.INTRUDER_TOKEN));                                       // []
        refusedBodies.add(get("/api/documents/7", TenantFixture.INTRUDER_TOKEN));                                           // 404

        // 有权限方的响应：对分析师而言，只有 7/38 可读，所以「不可读」= 其余文档
        String allowed = ask(TOKEN_ANALYST, "{\"question\":\"" + QUESTION + "\"}");

        int objectKeyHits = scanObjectKeys(refusedBodies, allowed);
        int titleHits = scanTitles(refusedBodies, allowed);
        int contentHits = scanContentSamples(refusedBodies, allowed);

        System.out.println("ZERO-LEAK|bodies=" + refusedBodies.size()
                + "|objectKeys=" + objectKeyHits + "|titles=" + titleHits
                + "|content=" + contentHits + "|threshold=0");

        assertThat(objectKeyHits).as("响应里出现了对象存储键（COS 直链可绕过 RLS）").isZero();
        assertThat(titleHits).as("响应里出现了调用方不可读的文档标题").isZero();
        assertThat(contentHits).as("响应里出现了不可读文档的正文片段").isZero();
    }

    // ------------------------------------------------------------ 三个扫描面

    private int scanObjectKeys(List<String> refused, String allowed) {
        int hits = 0;
        List<String> keys = jdbc.queryForList(
                "SELECT object_key FROM document WHERE object_key IS NOT NULL", String.class);
        for (String body : concat(refused, allowed)) {
            for (String key : keys) {
                if (key != null && !key.isBlank() && body.contains(key)) {
                    hits++;
                }
            }
            for (String marker : List.of("parsed/", "cos.", "myqcloud.com", "dev/")) {
                if (body.contains(marker)) {
                    hits++;
                }
            }
        }
        return hits;
    }

    private int scanTitles(List<String> refused, String allowed) {
        int hits = 0;
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id, title FROM document WHERE title IS NOT NULL");
        for (Map<String, Object> row : rows) {
            long id = ((Number) row.get("id")).longValue();
            String title = (String) row.get("title");
            if (title == null || title.isBlank()) {
                continue;
            }
            for (String body : refused) {
                if (body.contains(title)) {
                    hits++;   // 越权方连标题都不该看到
                }
            }
            if (!READABLE_BY_ANALYST.contains(id) && allowed.contains(title)) {
                hits++;       // 分析师不该在他读不到的文档标题上看到命中
            }
        }
        return hits;
    }

    private int scanContentSamples(List<String> refused, String allowed) {
        int hits = 0;
        // 越权方：租户 1 的全部文档都不可读 → 样本取自 7/38
        List<Map<String, Object>> restricted = jdbc.queryForList("""
                SELECT content FROM document_chunk
                 WHERE document_id IN (7, 38) AND chunk_level = 'CHILD' AND length(content) > 400
                 ORDER BY id LIMIT 12
                """);
        for (Map<String, Object> row : restricted) {
            String sample = slice((String) row.get("content"));
            if (sample == null) {
                continue;
            }
            for (String body : refused) {
                if (body.contains(sample)) {
                    hits++;
                }
            }
        }
        // 分析师：不可读的是 7/38 之外的文档
        List<Map<String, Object>> others = jdbc.queryForList("""
                SELECT content FROM document_chunk
                 WHERE document_id NOT IN (7, 38) AND chunk_level = 'CHILD' AND length(content) > 400
                 ORDER BY id LIMIT 12
                """);
        for (Map<String, Object> row : others) {
            String sample = slice((String) row.get("content"));
            if (sample != null && allowed.contains(sample)) {
                hits++;
            }
        }
        return hits;
    }

    /** 取正文中段 40 字：开头常是标题/表头，和「已知可见内容」更容易撞。 */
    private static String slice(String content) {
        if (content == null || content.length() < 480) {
            return null;
        }
        return content.substring(200, 240).trim();
    }

    private static List<String> concat(List<String> a, String b) {
        List<String> all = new ArrayList<>(a);
        all.add(b);
        return all;
    }

    // ------------------------------------------------------------ HTTP

    private String ask(String token, String json) {
        return TenantFixture.request(port, "POST", "/api/chat", token, json).body();
    }

    private String get(String path, String token) {
        return TenantFixture.request(port, "GET", path, token, null).body();
    }
}
