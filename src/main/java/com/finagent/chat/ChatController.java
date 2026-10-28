package com.finagent.chat;

import com.finagent.document.DocumentRepository;
import com.finagent.scope.ScopeAuthorizer;
import com.finagent.scope.ScopeEmptyException;
import com.finagent.scope.SelectableDocument;
import com.finagent.security.CurrentUser;
import com.finagent.security.TenantContext;
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

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/** 阶段 5 的两个端点：问答与「可选文档」列表。 */
@RestController
public class ChatController {

    private static final Logger log = LoggerFactory.getLogger(ChatController.class);

    private final ChatService chatService;
    private final DocumentRepository documents;
    private final ScopeAuthorizer authorizer;
    private final ExecutorService streamExecutor;
    private final ScheduledExecutorService streamPinger;
    private final StreamProperties streamProperties;

    public ChatController(ChatService chatService, DocumentRepository documents,
                          ScopeAuthorizer authorizer, ExecutorService chatStreamExecutor,
                          ScheduledExecutorService chatStreamPinger, StreamProperties streamProperties) {
        this.chatService = chatService;
        this.documents = documents;
        this.authorizer = authorizer;
        this.streamExecutor = chatStreamExecutor;
        this.streamPinger = chatStreamPinger;
        this.streamProperties = streamProperties;
    }

    /**
     * 对话入口（非流式问答）
     */
    @PostMapping("/api/chat")
    public ResponseEntity<Object> chat(@RequestBody(required = false) ChatRequest request) {
        if (request == null || request.question().isBlank()) {
            return ResponseEntity.badRequest().body(error("BAD_REQUEST", "question 不能为空"));
        }
        return ResponseEntity.ok(chatService.ask(request));
    }

    /**
     * 流式问答（契约 C15）。
     *
     * <p>三个必须注意的点：
     * <ol>
     *   <li><b>租户上下文要带过去</b>：{@code TenantContext} 是 ThreadLocal，生成跑在另一个线程，
     *       不重建的话 RLS 会 fail closed 返回 0 行，表现为「流式回答永远说没有证据」；</li>
     *   <li><b>开流之后的错误只能发 error 事件</b>：HTTP 头已经发出去了，改不了状态码；</li>
     *   <li><b>心跳不能省</b>：真实链路里检索 + 生成可能十几秒没有新事件，中间层会当成空闲连接。</li>
     * </ol>
     */
    @PostMapping(value = "/api/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@RequestBody(required = false) ChatRequest request) {
        if (request == null || request.question() == null || request.question().isBlank()) {
            // 开流之前的错误：还能用状态码，走统一异常处理返回 400
            throw new IllegalArgumentException("question 不能为空");
        }
        CurrentUser user = TenantContext.require();
        SseEmitter emitter = new SseEmitter(streamProperties.timeoutMs());
        ScheduledFuture<?> ping = startPing(emitter);

        emitter.onCompletion(() -> ping.cancel(false));
        emitter.onTimeout(() -> {
            ping.cancel(false);
            emitter.complete();
        });
        emitter.onError(e -> ping.cancel(false));

        streamExecutor.execute(() -> {
            TenantContext.set(user);
            try {
                chatService.askStreaming(request, event -> send(emitter, event));
                emitter.complete();
            } catch (IllegalArgumentException e) {
                send(emitter, ChatStreamEvent.error("BAD_REQUEST", e.getMessage()));
                emitter.complete();
            } catch (ScopeEmptyException e) {
                send(emitter, ChatStreamEvent.error("SCOPE_EMPTY", e.getMessage()));
                emitter.complete();
            } catch (RuntimeException e) {
                log.error("流式问答失败", e);
                send(emitter, ChatStreamEvent.error("INTERNAL", "服务器处理失败，请重试"));
                emitter.complete();
            } finally {
                ping.cancel(false);
                TenantContext.clear();
            }
        });
        return emitter;
    }

    private ScheduledFuture<?> startPing(SseEmitter emitter) {
        long interval = Math.max(1000L, streamProperties.pingMs());
        return streamPinger.scheduleAtFixedRate(() -> {
            try {
                emitter.send(SseEmitter.event().comment("ping"));
            } catch (IOException | IllegalStateException e) {
                // 客户端已经走了：不刷日志，等 onCompletion / onError 收尾
            }
        }, interval, interval, TimeUnit.MILLISECONDS);
    }

    /**
     * 流式发送响应数据
     * @param emitter 发送器
     * @param event 发送内容
     */
    private static void send(SseEmitter emitter, ChatStreamEvent event) {
        try {
            emitter.send(
                    SseEmitter.event()
                            .name(event.event())
                            .data(event.data(), MediaType.APPLICATION_JSON)
            );
        } catch (IOException | IllegalStateException e) {
            // 断流（用户关页面 / 网络断）不是错误：答案已经落库，前端重进会话即可看到
            log.debug("流式事件发送失败（连接已断）：{}", event.event());
        }
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