package com.finagent.chat;

import com.finagent.document.DocumentRepository;
import com.finagent.scope.ScopeAuthorizer;
import com.finagent.scope.ScopeEmptyException;
import com.finagent.scope.SelectableDocument;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 阶段 5 的两个端点：问答与「可选文档」列表。 */
@RestController
public class ChatController {

    private static final Logger log = LoggerFactory.getLogger(ChatController.class);

    private final ChatService chatService;
    private final DocumentRepository documents;
    private final ScopeAuthorizer authorizer;

    public ChatController(ChatService chatService, DocumentRepository documents,
                          ScopeAuthorizer authorizer) {
        this.chatService = chatService;
        this.documents = documents;
        this.authorizer = authorizer;
    }

    /**
     * 对话入口
     */
    @PostMapping("/api/chat")
    public ResponseEntity<Object> chat(@RequestBody(required = false) ChatRequest request) {
        if (request == null || request.question().isBlank()) {
            return ResponseEntity.badRequest().body(error("BAD_REQUEST", "question 不能为空"));
        }
        return ResponseEntity.ok(chatService.ask(request));
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