package com.finagent.chat;

import com.finagent.memory.Conversation;
import com.finagent.memory.ConversationContext;
import com.finagent.memory.ConversationContextService;
import com.finagent.memory.ConversationMessage;
import com.finagent.memory.ConversationNotFoundException;
import com.finagent.memory.ConversationService;
import com.finagent.memory.ContextPatch;
import com.finagent.scope.ChatScope;
import com.finagent.security.UnauthorizedException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 会话端点（阶段 9）。
 *
 * <p>为什么单独一个控制器而不是塞进 {@link ChatController}：ChatController 是「问答」，
 * 生命周期取决于一次请求；这里是「会话的 CRUD」，生命周期跨多轮。混在一起会让人
 * 以为会话是问答的附属物。另一个实际原因：会话端点必须登录，问答端点也要，但两者的
 * 404/409 语义不同（会话是 404，作用域是 409）。
 */
@RestController
public class ConversationController {

    private final ConversationService conversations;
    private final ConversationContextService contexts;
    private final ObjectMapper mapper;

    public ConversationController(ConversationService conversations,
                                  ConversationContextService contexts, ObjectMapper mapper) {
        this.conversations = conversations;
        this.contexts = contexts;
        this.mapper = mapper;
    }

    /** 新建会话。scope 为 null 表示全库（CORPUS）。 */
    @PostMapping("/api/conversations")
    public ResponseEntity<ConversationView> create(@RequestBody(required = false) CreateConversation body) {
        String title = body == null ? null : body.title();
        ChatScope scope = body == null ? null : body.scope();
        return ResponseEntity.status(HttpStatus.CREATED).body(view(conversations.create(title, scope)));
    }

    @GetMapping("/api/conversations")
    public List<ConversationView> list(@RequestParam(value = "limit", defaultValue = "20") int limit) {
        return conversations.list(limit).stream().map(this::view).toList();
    }

    @GetMapping("/api/conversations/{id}")
    public ConversationView get(@PathVariable("id") long id) {
        return view(conversations.requireOwned(id));
    }

    /**
     * 会话里的消息，<b>最新在前</b>。
     *
     * <p>翻页只给 beforeId（上一页最后一条的 messageId），不给 offset：
     * 会话在翻页期间还会继续追加新消息，offset 会导致重复或漏读。
     */
    @GetMapping("/api/conversations/{id}/messages")
    public MessagesPage messages(@PathVariable("id") long id,
                                 @RequestParam(value = "limit", defaultValue = "50") int limit,
                                 @RequestParam(value = "beforeId", required = false) Long beforeId) {
        List<ConversationMessage> rows = conversations.messages(id, beforeId, limit);
        List<MessageView> views = rows.stream().map(this::messageView).toList();
        Long nextBeforeId = views.isEmpty() ? null : views.get(views.size() - 1).messageId();
        return new MessagesPage(id, views, nextBeforeId);
    }

    /** 当前会话的结构化上下文（公司 / 年份 / 文档 / 指标）。没有则返回空值视图。 */
    @GetMapping("/api/conversations/{id}/context")
    public ContextView context(@PathVariable("id") long id) {
        conversations.requireOwned(id);
        return contexts.find(id).map(this::contextView)
                .orElseGet(() -> new ContextView(id, null, null, null, null, null, List.of(), null, null));
    }

    /**
     * 用户显式纠错（设计 §11.10）。每次变更写一条 memory_event，便于回放「谁改的」。
     *
     * <p>{@code clear=true} 是「清空后按本体的非空字段重建」，不是逐字段置空。
     */
    @PatchMapping("/api/conversations/{id}/context")
    public ContextView patchContext(@PathVariable("id") long id,
                                    @RequestBody(required = false) ContextPatch body) {
        Conversation conversation = conversations.requireOwned(id);
        return contextView(contexts.patch(conversation, body));
    }

    /**
     * 改会话标题。
     *
     * <p>注意与 {@code PATCH /api/conversations/{id}/context} 的区别：那条改的是
     * 结构化上下文（公司/年份），这条改的是标题。两条都叫 PATCH，路径不同，别合并 ——
     * 合并后「只想改名」的请求会顺带清掉上下文。
     */
    @PatchMapping("/api/conversations/{id}")
    public ConversationView rename(@PathVariable("id") long id,
                                   @RequestBody(required = false) RenameConversation body) {
        return view(conversations.rename(id, body == null ? null : body.title()));
    }

    /** 逻辑删除：status='DELETED'，消息与摘要保留（审计与回溯要用）。 */
    @DeleteMapping("/api/conversations/{id}")
    public ResponseEntity<Void> delete(@PathVariable("id") long id) {
        conversations.delete(id);
        return ResponseEntity.noContent().build();
    }

    @ExceptionHandler(ConversationNotFoundException.class)
    public ResponseEntity<Object> notFound(ConversationNotFoundException e) {
        // 不确认存在性：不是自己的会话与不存在的会话同一个响应
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", "CONVERSATION_NOT_FOUND");
        body.put("message", "会话不存在或无权访问");
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(body);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Object> badRequest(IllegalArgumentException e) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", "BAD_REQUEST");
        body.put("message", e.getMessage());
        return ResponseEntity.badRequest().body(body);
    }

    @ExceptionHandler(UnauthorizedException.class)
    public ResponseEntity<Object> unauthorized(UnauthorizedException e) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", e.code());
        body.put("message", "需要登录");
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(body);
    }

    private ConversationView view(Conversation c) {
        return new ConversationView(c.id(), c.title(), c.status(), c.scope(),
                c.createdAt(), c.updatedAt());
    }

    private ContextView contextView(ConversationContext c) {
        return new ContextView(c.conversationId(), c.companyId(), c.companyCode(), c.companyName(),
                c.yearFrom(), c.yearTo(), c.documentIds(), c.currentMetric(), c.updatedAt());
    }

    private MessageView messageView(ConversationMessage m) {
        return new MessageView(m.id(), m.role(), m.content(), citations(m.citationsJson()), m.createdAt());
    }

    private JsonNode citations(String json) {
        if (json == null || json.isBlank()) {
            return mapper.createArrayNode();
        }
        return mapper.readTree(json);
    }

    public record CreateConversation(String title, ChatScope scope) { }

    /** 只接受标题；带别的字段会被忽略（Jackson 未开 FAIL_ON_UNKNOWN_PROPERTIES）。 */
    public record RenameConversation(String title) { }

    public record ConversationView(long conversationId, String title, String status, ChatScope scope,
                                   OffsetDateTime createdAt, OffsetDateTime updatedAt) { }

    public record MessageView(long messageId, String role, String content, JsonNode citations,
                              OffsetDateTime createdAt) { }

    public record MessagesPage(long conversationId, List<MessageView> messages, Long nextBeforeId) { }

    public record ContextView(long conversationId, Long companyId, String companyCode, String companyName,
                              Integer yearFrom, Integer yearTo, List<Long> documentIds,
                              String currentMetric, OffsetDateTime updatedAt) { }
}
