package com.finagent.agent;

import com.finagent.security.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Agent 端点的端到端测试：真 Tomcat、真过滤器、真 SSE，节点用假的（不调模型、不检索）。
 *
 * <p>验四件事：事件顺序、approval 的载荷、刷新后仍能查到状态、别人的 run 一律 404。
 */
@Tag("db")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"finagent.agent.enabled=true", "finagent.tenant.auth-enabled=true",
                "finagent.tenant.acl-enabled=true"})
@ActiveProfiles("test")
class AgentStreamE2ETest {

    private static final String ANALYST = "dev-analyst-token";
    private static final String OUTSIDER = "dev-outsider-token";

    @LocalServerPort
    private int port;

    @Autowired
    private JdbcTemplate jdbc;

    @MockitoBean
    private AnalysisNodes nodes;

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10)).build();
    private final List<UUID> created = new ArrayList<>();

    @BeforeEach
    void stubNodes() {
        FakeAnalysisNodes fake = FakeAnalysisNodes.happy();
        when(nodes.understand(any())).thenAnswer(i -> fake.understand(i.getArgument(0)));
        when(nodes.plan(any())).thenAnswer(i -> fake.plan(i.getArgument(0)));
        when(nodes.retrieve(any())).thenAnswer(i -> fake.retrieve(i.getArgument(0)));
        when(nodes.verify(any())).thenAnswer(i -> fake.verify(i.getArgument(0)));
        when(nodes.rewrite(any())).thenAnswer(i -> fake.rewrite(i.getArgument(0)));
        when(nodes.answer(any())).thenAnswer(i -> fake.answer(i.getArgument(0)));
        when(nodes.citeCheck(any())).thenAnswer(i -> fake.citeCheck(i.getArgument(0)));
        when(nodes.respondInsufficient(any())).thenAnswer(i -> fake.respondInsufficient(i.getArgument(0)));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
        for (UUID id : created) {
            jdbc.update("DELETE FROM agent_run WHERE id = ?", id);
            jdbc.update("DELETE FROM lg4jcheckpoint WHERE thread_id IN"
                    + " (SELECT thread_id FROM lg4jthread WHERE thread_name = ?)", id.toString());
            jdbc.update("DELETE FROM lg4jthread WHERE thread_name = ?", id.toString());
        }
        created.clear();
    }

    @Test
    @DisplayName("复杂问题：stage* → approval；刷新后查得到；APPROVE 后 stage* → final(DONE)")
    void hitlFlowOverHttp() {
        List<Frame> first = sse("POST", "/api/chat/stream", ANALYST,
                jsonBody("question", "复杂问题：营业总收入和净利润分别是多少"));
        UUID runId = UUID.fromString(approvalRunId(first));
        created.add(runId);

        assertThat(first).extracting(Frame::event)
                .containsSubsequence("stage", "stage", "stage", "stage", "approval");
        assertThat(phases(first)).containsExactly("understand", "plan", "retrieve", "verify");
        assertThat(first.get(first.size() - 1).data()).contains(quoted("plan")).contains(quoted("evidence"));

        HttpResponse<String> view = get("/api/agent/runs/" + runId, ANALYST);
        assertThat(view.statusCode()).isEqualTo(200);
        assertThat(view.body()).contains("WAITING_APPROVAL").contains("approve").contains("拆成 2 个子问题");

        List<Frame> resumed = sse("POST", "/api/agent/runs/" + runId + "/resume", ANALYST,
                "{\"decision\":\"APPROVE\"}");
        assertThat(phases(resumed)).containsExactly("compute", "answer", "cite_check");
        Frame last = resumed.get(resumed.size() - 1);
        assertThat(last.event()).isEqualTo("final");
        assertThat(last.data()).contains("DONE").contains("fake-answer");
    }

    @Test
    @DisplayName("问题本身不歧义（点了年份）→ 不弹审批，直接给 final（ST14 P3）")
    void unambiguousQuestionSkipsApproval() {
        List<Frame> frames = sse("POST", "/api/chat/stream", ANALYST,
                jsonBody("question", "复杂问题：2025年营业总收入和净利润分别是多少"));

        assertThat(frames).extracting(Frame::event).doesNotContain("approval");
        Frame last = frames.get(frames.size() - 1);
        assertThat(last.event()).isEqualTo("final");
        assertThat(last.data()).contains("DONE");
    }

    @Test
    @DisplayName("图跑完的一轮会写进会话：两条消息、正问原答（ST14 P1）")
    void graphTurnIsPersistedIntoConversation() {
        HttpResponse<String> created0 = post("/api/conversations", ANALYST, jsonBody("title", "agent 落库"));
        assertThat(created0.statusCode()).isEqualTo(201);
        long conversationId = Long.parseLong(jsonNumber(created0.body(), "conversationId"));
        try {
            List<Frame> first = sse("POST", "/api/chat/stream", ANALYST,
                    "{\"question\":\"复杂问题：营业总收入和净利润分别是多少\",\"conversationId\":" + conversationId + "}");
            UUID runId = UUID.fromString(approvalRunId(first));
            created.add(runId);
            sse("POST", "/api/agent/runs/" + runId + "/resume", ANALYST, "{\"decision\":\"APPROVE\"}");

            List<Map<String, Object>> messages = jdbc.queryForList(
                    "SELECT role, content FROM conversation_message WHERE conversation_id = ? ORDER BY id",
                    conversationId);
            assertThat(messages).hasSize(2);
            assertThat(messages.get(0).get("role")).isEqualTo("USER");
            assertThat(messages.get(0).get("content")).isEqualTo("复杂问题：营业总收入和净利润分别是多少");
            assertThat(messages.get(1).get("role")).isEqualTo("ASSISTANT");
            assertThat(String.valueOf(messages.get(1).get("content"))).contains("fake-answer");
        } finally {
            jdbc.update("DELETE FROM memory_event WHERE target_type IN"
                    + " ('CONVERSATION_CONTEXT','CONVERSATION_SUMMARY') AND target_id = ?", conversationId);
            jdbc.update("DELETE FROM conversation WHERE id = ?", conversationId);
        }
    }

    @Test
    @DisplayName("别人的 run：GET 一律 404，且正文不含问题与答案")
    void foreignRunIsNotFound() {
        List<Frame> first = sse("POST", "/api/chat/stream", ANALYST,
                "{\"question\":\"复杂问题：营业总收入和净利润分别是多少\"}");
        UUID runId = UUID.fromString(approvalRunId(first));
        created.add(runId);

        HttpResponse<String> response = get("/api/agent/runs/" + runId, OUTSIDER);

        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(response.body()).doesNotContain("复杂问题").doesNotContain("fake-answer");
    }

    @Test
    @DisplayName("没 token：401（过滤器统一挡）")
    void withoutTokenItIsUnauthorized() {
        HttpResponse<String> response = get("/api/agent/runs/" + UUID.randomUUID(), null);

        assertThat(response.statusCode()).isEqualTo(401);
    }

    // ------------------------------------------------------------------ helpers

    private static List<String> phases(List<Frame> frames) {
        return frames.stream().filter(f -> "stage".equals(f.event()))
                .map(f -> {
                    String needle = "\"phase\":\"";
                    int start = f.data().indexOf(needle) + needle.length();
                    int end = f.data().indexOf('"', start);
                    return f.data().substring(start, end);
                }).toList();
    }

    private static String approvalRunId(List<Frame> frames) {
        Frame approval = frames.stream().filter(f -> "approval".equals(f.event()))
                .findFirst().orElseThrow(() -> new AssertionError("没有 approval 事件：" + frames));
        return jsonString(approval.data(), "runId");
    }

    private static String jsonBody(String key, String value) {
        char q = 34;
        return "{" + q + key + q + ":" + q + value + q + "}";
    }

    private static String quoted(String key) {
        char q = 34;
        return q + key + q;
    }

    private static String jsonString(String json, String key) {
        String needle = "\"" + key + "\":\"";
        int start = json.indexOf(needle) + needle.length();
        return json.substring(start, json.indexOf('"', start));
    }

    private record Frame(String event, String data) {
    }

    private HttpResponse<String> post(String path, String token, String json) {
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(base() + path))
                    .timeout(Duration.ofSeconds(30))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json));
            if (token != null) {
                builder.header("Authorization", "Bearer " + token);
            }
            return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String jsonNumber(String json, String key) {
        String needle = "\"" + key + "\":";
        int start = json.indexOf(needle) + needle.length();
        int end = start;
        while (end < json.length() && (Character.isDigit(json.charAt(end)) || json.charAt(end) == '-')) {
            end++;
        }
        return json.substring(start, end);
    }

    private HttpResponse<String> get(String path, String token) {
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(base() + path))
                    .timeout(Duration.ofSeconds(30)).GET();
            if (token != null) {
                builder.header("Authorization", "Bearer " + token);
            }
            return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private List<Frame> sse(String method, String path, String token, String json) {
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(base() + path))
                    .timeout(Duration.ofSeconds(120))
                    .header("Content-Type", "application/json")
                    .header("Accept", "text/event-stream")
                    .method(method, HttpRequest.BodyPublishers.ofString(json));
            if (token != null) {
                builder.header("Authorization", "Bearer " + token);
            }
            HttpResponse<String> response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).as("SSE 状态码，body=" + response.body()).isEqualTo(200);
            return parse(response.body());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** 极小的 SSE 解析：event/data 两行 + 空行分隔；注释行（: ping）忽略。 */
    private static List<Frame> parse(String body) {
        List<Frame> frames = new ArrayList<>();
        Map<String, String> current = new LinkedHashMap<>();
        for (String line : body.split("\\r?\\n", -1)) {
            if (line.isEmpty()) {
                if (current.containsKey("event")) {
                    frames.add(new Frame(current.get("event"), current.getOrDefault("data", "")));
                }
                current.clear();
            } else if (line.startsWith("event:")) {
                current.put("event", line.substring(6).trim());
            } else if (line.startsWith("data:")) {
                current.merge("data", line.substring(5).trim(), (a, b) -> a + "\n" + b);
            }
        }
        return frames;
    }

    private String base() {
        return "http://127.0.0.1:" + port;
    }
}
