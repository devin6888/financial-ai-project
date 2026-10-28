package com.finagent.memory;

import com.finagent.security.UnauthorizedException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;

/**
 * 长期记忆的显式 API（本阶段只做显式写入，不做模型自动提炼，ST9 D2）。
 *
 * <p>身份只来自认证过滤器：请求体里没有 userId / tenantId 字段，传了也不认（契约 C11）。
 */
@RestController
@RequestMapping("/api/memories")
public class MemoryController {

    private final LongTermMemoryService memories;

    public MemoryController(LongTermMemoryService memories) {
        this.memories = memories;
    }

    public record CreateMemoryRequest(String type, String key, String content, Integer importance,
                                      String expiresAt) {
    }

    public record MemoryView(long id, String type, String key, String content, int importance,
                             int version, String status, String sourceType, String expiresAt,
                             OffsetDateTime updatedAt) {
    }

    public record CreateMemoryResponse(long id, String type, String key, int version, String status) {
    }

    @PostMapping
    public ResponseEntity<CreateMemoryResponse> create(@RequestBody CreateMemoryRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("请求体不能为空");
        }
        LongTermMemory saved = memories.save(
                new LongTermMemoryService.SaveRequest(request.type(), request.key(), request.content(),
                        request.importance(), parseExpiry(request.expiresAt())),
                LongTermMemory.SOURCE_API, null, null);
        return ResponseEntity.status(HttpStatus.CREATED).body(new CreateMemoryResponse(
                saved.id(), saved.memoryType(), saved.memoryKey(), saved.version(), saved.status()));
    }

    @GetMapping
    public List<MemoryView> list(@RequestParam(required = false) String type) {
        return memories.list(type).stream()
                .map(m -> new MemoryView(m.id(), m.memoryType(), m.memoryKey(), m.content(), m.importance(),
                        m.version(), m.status(), m.sourceType(),
                        m.expiresAt() == null ? null : m.expiresAt().toString(), m.updatedAt()))
                .toList();
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable long id) {
        memories.delete(id);
        return ResponseEntity.noContent().build();
    }

    @ExceptionHandler(MemoryNotFoundException.class)
    public ResponseEntity<Map<String, String>> notFound(MemoryNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(Map.of("error", "MEMORY_NOT_FOUND", "message", e.getMessage()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> badRequest(IllegalArgumentException e) {
        return ResponseEntity.badRequest()
                .body(Map.of("error", "BAD_REQUEST", "message", e.getMessage()));
    }

    /** 没带 token 时由过滤器直接 401；这里兜住「过滤器关了但业务要身份」的情况。 */
    @ExceptionHandler(UnauthorizedException.class)
    public ResponseEntity<Map<String, String>> unauthorized(UnauthorizedException e) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(Map.of("error", e.code() == null ? "AUTH_REQUIRED" : e.code()));
    }

    private static OffsetDateTime parseExpiry(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return OffsetDateTime.parse(raw.strip());
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("expiresAt 需要 ISO-8601 时间（含时区），例如 2027-01-01T00:00:00Z");
        }
    }
}
