package com.finagent.chat;

import com.finagent.agent.AgentStreamService;
import com.finagent.document.DocumentRepository;
import com.finagent.scope.ScopeAuthorizer;
import com.finagent.scope.ScopeEmptyException;
import com.finagent.scope.SelectableDocument;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 问答入口（2026-10-29 阶段 14 P4 起：内部全部走 LangGraph 图，只有一条执行路径）。
 *
 * <p>为什么保留这个控制器而不是让前端改调 `/api/agent/*`：URL 与事件协议是前端、e2e、README
 * 的既有契约；换的是实现，不是协议。`/api/agent/*` 保留为调试入口（resume / 查状态）。
 */
@RestController
public class ChatController {

    private static final Logger log = LoggerFactory.getLogger(ChatController.class);

    private final AgentStreamService agentStreams;
    private final DocumentRepository documents;
    private final ScopeAuthorizer authorizer;
    private final SseStreamTemplate sse;

    public ChatController(AgentStreamService agentStreams, DocumentRepository documents,
                          ScopeAuthorizer authorizer, SseStreamTemplate sse) {
        this.agentStreams = agentStreams;
        this.documents = documents;
        this.authorizer = authorizer;
        this.sse = sse;
    }

    /**
     * 对话入口（非流式问答）
     */
    @PostMapping("/api/chat")
    public ResponseEntity<Object> chat(@RequestBody(required = false) ChatRequest request) {
        if (request == null || request.question().isBlank()) {
            return ResponseEntity.badRequest().body(error("BAD_REQUEST", "question 不能为空"));
        }
        return ResponseEntity.ok(agentStreams.ask(request));
    }

    /**
     * 流式问答（契约 C15）。
     *
     * <p>线程、心跳、错误事件的处理都在 {@link SseStreamTemplate}（2026-10-30 从两个 controller 抽出来的），
     * 这里只剩「校验参数 → 交给 service」。
     */
    @PostMapping(value = "/api/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@RequestBody(required = false) ChatRequest request) {
        if (request == null || request.question() == null || request.question().isBlank()) {
            // 开流之前的错误：还能用状态码，走统一异常处理返回 400
            throw new IllegalArgumentException("question 不能为空");
        }
        return sse.start(emitter -> agentStreams.startStream(request, event -> SseStreamTemplate.send(emitter, event)));
    }

    /**
     * 「下拉框里能选的文档」。
     *
     * <p>这个端点必须过 ACL：不过的话，攻击者不用猜 id，直接把全库清单拉走就够了
     * （设计 §15.4 第 2 类越权 = 枚举不该看到的 documentId）。RLS 只挡住跨租户，
     * 租户内谁都能看见谁，所以要再叠一次 {@link ScopeAuthorizer#filterReadable}。
     */
    @GetMapping("/api/scope/documents")
    public List<SelectableDocument> documents() {
        List<SelectableDocument> all = documents.findSelectableDocuments();
        if (all.isEmpty()) {
            return all;
        }
        Set<Long> readable = new LinkedHashSet<>(
                authorizer.filterReadable(all.stream().map(SelectableDocument::id).toList()));
        return all.stream().filter(d -> readable.contains(d.id())).toList();
    }

    /**
     * 作用域解析出 0 份文档 → 409。
     *
     * <p>用 409 而不是 200 + 空答案：这是「你选的范围不可用」，不是「范围内没答案」，
     * 混在一起前端就只能提示「没找到」，用户会以为该换问法。
     */
    @ExceptionHandler(ScopeEmptyException.class)
    public ResponseEntity<Object> scopeEmpty(ScopeEmptyException e) {
        Map<String, Object> body = error("SCOPE_EMPTY", e.getMessage());
        body.put("warnings", e.warnings());
        return ResponseEntity.status(HttpStatus.CONFLICT).body(body);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Object> badRequest(IllegalArgumentException e) {
        log.debug("请求参数不合法：{}", e.getMessage());
        return ResponseEntity.badRequest().body(error("BAD_REQUEST", e.getMessage()));
    }

    private static Map<String, Object> error(String code, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", code);
        body.put("message", message);
        return body;
    }
}