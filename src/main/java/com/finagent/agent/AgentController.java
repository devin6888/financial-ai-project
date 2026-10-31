package com.finagent.agent;

import com.finagent.chat.ChatStreamEvent;
import com.finagent.chat.SseStreamTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Agent 端点（阶段 11 Task 7，契约 C17 / C18）。
 *
 * <p><b>这里只剩「看一轮」「续一轮」两件事</b>（2026-10-30 清理）：
 * <ul>
 *   <li>{@code POST /api/agent/runs/{id}/resume} —— 审批后续跑，全系统只有这一处能续跑；</li>
 *   <li>{@code GET  /api/agent/runs/{id}} —— 运行视图（计划 / 证据 / 步骤），刷新页面靠它恢复。</li>
 * </ul>
 *
 * <p>原来还有一个 {@code POST /api/agent/runs/stream}（开新运行），阶段 14 把两条链路合并成一张图后
 * 它和 {@code POST /api/chat/stream} 走的是同一个 {@code AgentStreamService.startStream}，
 * 前端也一直只用 chat 那个，于是删掉，避免两个入口各改一遍。
 *
 * <p>线程、心跳、错误事件的处理在 {@link SseStreamTemplate}。
 */
@RestController
public class AgentController {

    private final AgentStreamService streams;
    private final AgentRunService runs;
    private final SseStreamTemplate sse;

    public AgentController(AgentStreamService streams, AgentRunService runs, SseStreamTemplate sse) {
        this.streams = streams;
        this.runs = runs;
        this.sse = sse;
    }

    /** 审批后继续同一条流。 */
    @PostMapping(value = "/api/agent/runs/{id}/resume", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter resume(@PathVariable UUID id, @RequestBody(required = false) ResumeRequest request) {
        String decision = request == null ? null : request.decision();
        String note = request == null ? null : request.note();
        return sse.start(emitter -> {
            try {
                streams.resumeStream(id, decision, note, event -> SseStreamTemplate.send(emitter, event));
            } catch (AgentRunNotFoundException e) {
                // 开流之前已经确认过认证，这里只剩「run 不存在 / 不是你的」：按 C11 一律 404 语义
                SseStreamTemplate.send(emitter, ChatStreamEvent.error("AGENT_RUN_NOT_FOUND", "运行不存在"));
            }
        });
    }

    /** 状态 + 计划 + 证据 + 步骤（刷新页面、重启之后靠它看进度）。 */
    @GetMapping("/api/agent/runs/{id}")
    public AgentRunService.RunView view(@PathVariable UUID id) {
        return runs.view(id);
    }

    @ExceptionHandler(AgentRunNotFoundException.class)
    public ResponseEntity<Object> notFound(AgentRunNotFoundException e) {
        // 与 C11 一致：别人的 run 一律 404，不确认存在性
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(error("AGENT_RUN_NOT_FOUND", "运行不存在"));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Object> badRequest(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(error("BAD_REQUEST", e.getMessage()));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Object> illegalState(IllegalStateException e) {
        // agent.enabled=false 落在这里：这是「功能没开」，不是参数错
        return ResponseEntity.badRequest().body(error("AGENT_DISABLED", e.getMessage()));
    }

    private static Map<String, Object> error(String code, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", code);
        body.put("message", message == null ? "" : message);
        return body;
    }

    /** 审批请求体。 */
    public record ResumeRequest(String decision, String note) {
    }
}
