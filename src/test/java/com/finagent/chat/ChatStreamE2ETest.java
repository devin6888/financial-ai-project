package com.finagent.chat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 流式全链路（真模型、真数据库、真过滤器）。
 *
 * <p>验四件事：
 * <ol>
 *   <li>Content-Type 是 text/event-stream，且第一个事件是 stage（阶段 13 起，首屏反馈靠它）；</li>
 *   <li>阶段顺序 = understanding → retrieving（→ reranking）→ meta → generating；</li>
 *   <li>能收到 delta 且首字延迟远小于总耗时；
 *       且证据（meta）一定早于答案（delta）—— 这就是「证据先到、答案后到」；</li>
 *   <li>final 里的引用非空 —— 这一条同时验证了「租户上下文跨线程没丢」：
 *       丢了的话 RLS 会返回 0 行证据，final 里 citations 必为空。</li>
 * </ol>
 *
 * <p>关于 delta 与 final 的正文：两者<strong>不是逐字相等</strong>。模型在 delta 里写的是
 * 「提示词编号」（如 [E2][E7][E11][E13]），final 里被 {@code AnswerService.renumberCitations}
 * 重排成 [E1..En]。所以这里比对「去掉编号后的正文」，编号本身另有一条断言（必须连续）。
 *
 * <p>为什么先跑一轮再断言：第一次请求要初始化 Tomcat 的异步响应、SseEmitter 的消息转换器、
 * Hikari 连接池，首包耗时是冷启动值。断言用的是第二轮（热路径），冷启动只设一个宽松上限。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"finagent.tenant.auth-enabled=true", "finagent.tenant.acl-enabled=true",
                "finagent.memory.enabled=true"})
@ActiveProfiles("test")
@Tag("e2e")
@Timeout(240)
class ChatStreamE2ETest {

    private static final String TOKEN = "dev-analyst-token";
    private static final Pattern CITE = Pattern.compile("\\[E(\\d+)]");

    @LocalServerPort
    private int port;

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    @Test
    @DisplayName("流式问答：stage → meta → stage → delta → final，最终响应带引用")
    void streamsAnswerWithCitations() throws Exception {
        // 第一轮是冷启动：Tomcat 异步响应、SseEmitter 消息转换器、Hikari 连接池都在这一轮初始化，
        // 它的首包耗时不代表线上表现，所以只设一个宽松上限。
        Probe cold = probe("东方财富2025年营业总收入是多少");
        System.out.println("STREAM-COLD|firstStageMs=" + cold.firstStageMs()
                + "|metaMs=" + cold.metaMs() + "|firstDeltaMs=" + cold.firstTokenMs());

        Probe p = probe("东方财富2025年营业总收入是多少");

        // ① 首屏反馈：热路径上第一个 stage 必须立刻到（本地回环，实测几十毫秒）
        assertThat(p.firstStageMs()).as("首屏反馈过慢").isLessThanOrEqualTo(300);
        assertThat(cold.firstStageMs()).as("冷启动也不该超过 3 秒").isLessThanOrEqualTo(3000);
        // ② 顺序：理解 → 检索 →（精排）→ 生成；且证据（meta）早于答案（delta）
        assertThat(p.phases()).startsWith(ChatStreamEvent.PHASE_UNDERSTANDING,
                ChatStreamEvent.PHASE_RETRIEVING);
        assertThat(p.phases()).endsWith(ChatStreamEvent.PHASE_GENERATING);
        assertThat(p.metaMs()).as("证据必须早于首字：先给证据，再给答案")
                .isLessThan(p.firstTokenMs());

        assertThat(p.citations()).as("引用非空，说明租户上下文跨线程没丢").isGreaterThan(0);
        assertThat(p.answer()).isNotBlank();

        // ③ 正文一致（编号除外），且 final 里的编号必须是连续的 1..n
        assertThat(orderOutMarkers(p.deltas()))
                .as("delta 拼接与 final 正文（去编号）应一致")
                .isEqualTo(orderOutMarkers(p.answer()));
        assertThat(markers(p.answer()))
                .containsExactlyElementsOf(sequentialMarkers(p.citations()));

        System.out.println("STREAM-E2E|firstStageMs=" + p.firstStageMs() + "|metaMs=" + p.metaMs()
                + "|firstDeltaMs=" + p.firstTokenMs() + "|totalMs=" + p.totalMs()
                + "|stages=" + p.phases().size() + "|phases=" + p.phases()
                + "|deltas=" + p.deltas().length() + "|citations=" + p.citations());

        // 落盘给报告引用（不参与断言的观察值：LLM 抖动大）
        Files.writeString(Path.of("target", "stream-timing.json"),
                "{\"firstStageMs\":" + p.firstStageMs() + ",\"metaMs\":" + p.metaMs()
                        + ",\"firstTokenMs\":" + p.firstTokenMs() + ",\"totalMs\":" + p.totalMs()
                        + ",\"coldFirstStageMs\":" + cold.firstStageMs()
                        + ",\"stages\":" + p.phases().size()
                        + ",\"phases\":\"" + String.join(",", p.phases()) + "\"}",
                StandardCharsets.UTF_8);
    }

    /** 一次流式请求的全部观测值。 */
    private record Probe(long firstStageMs, long metaMs, long firstTokenMs, long totalMs,
                         List<String> phases, String deltas, String answer, int citations) { }

    private Probe probe(String question) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(
                        URI.create("http://127.0.0.1:" + port + "/api/chat/stream"))
                .header("Authorization", "Bearer " + TOKEN)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"question\":\"" + question + "\"}"))
                .build();
        long start = System.nanoTime();
        HttpResponse<InputStream> response = http.send(request,
                HttpResponse.BodyHandlers.ofInputStream());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Type").orElse(""))
                .contains("text/event-stream");

        Long firstStageMs = null;
        Long metaMs = null;
        Long firstDeltaMs = null;
        String finalJson = null;
        String lastEvent = null;
        List<String> phases = new ArrayList<>();
        StringBuilder deltas = new StringBuilder();
        try (InputStream in = response.body();
             BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("event:")) {
                    lastEvent = line.substring("event:".length()).trim();
                } else if (line.startsWith("data:") && lastEvent != null) {
                    String data = line.substring("data:".length()).trim();
                    switch (lastEvent) {
                        case ChatStreamEvent.STAGE -> {
                            if (firstStageMs == null) {
                                firstStageMs = elapsedMs(start);
                            }
                            phases.add(phaseOf(data));
                        }
                        case ChatStreamEvent.META -> {
                            assertThat(data).contains("\"evidence\"");
                            if (metaMs == null) {
                                metaMs = elapsedMs(start);
                            }
                        }
                        case ChatStreamEvent.DELTA -> {
                            if (firstDeltaMs == null) {
                                firstDeltaMs = elapsedMs(start);
                            }
                            deltas.append(readTextField(data));
                        }
                        case ChatStreamEvent.FINAL -> finalJson = data;
                        case ChatStreamEvent.ERROR -> throw new AssertionError("流式返回错误：" + data);
                        default -> { }
                    }
                }
            }
        }
        long totalMs = elapsedMs(start);

        assertThat(firstStageMs).as("应至少收到一个 stage").isNotNull();
        assertThat(metaMs).as("应收到 meta").isNotNull();
        assertThat(firstDeltaMs).as("应至少收到一个 delta").isNotNull();
        assertThat(finalJson).as("应收到 final 事件").isNotNull();

        JsonNode node = JsonMapper.builder().build().readTree(finalJson);
        return new Probe(firstStageMs, metaMs, firstDeltaMs, totalMs, phases, deltas.toString(),
                node.path("answer").asString(), node.path("citations").size());
    }

    private static long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }

    /** stage 事件的 data 是 {phase,label,detail}。 */
    private static String phaseOf(String data) {
        try {
            return JsonMapper.builder().build().readTree(data).path("phase").asString();
        } catch (RuntimeException e) {
            return "?";
        }
    }

    /** delta 事件的 data 是 {text:...}，取出 text；解析失败就返回空串（不让测试因格式抖动而红）。 */
    private static String readTextField(String data) {
        try {
            return JsonMapper.builder().build().readTree(data).path("text").asString();
        } catch (RuntimeException e) {
            return "";
        }
    }

    private static String orderOutMarkers(String text) {
        return text == null ? "" : CITE.matcher(text).replaceAll("").replaceAll("\\s+", "").trim();
    }

    private static List<String> markers(String text) {
        List<String> out = new ArrayList<>();
        Matcher m = CITE.matcher(text == null ? "" : text);
        while (m.find()) {
            out.add(m.group(1));
        }
        return out;
    }

    private static List<String> sequentialMarkers(int n) {
        List<String> out = new ArrayList<>();
        for (int i = 1; i <= n; i++) {
            out.add(String.valueOf(i));
        }
        return out;
    }
}
