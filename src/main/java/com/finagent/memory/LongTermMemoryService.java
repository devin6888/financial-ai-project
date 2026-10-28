package com.finagent.memory;

import com.finagent.embedding.EmbeddingService;
import com.finagent.security.CurrentUser;
import com.finagent.security.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * 长期记忆的写入流水线：校验 → 冲突检查 → 版本化 upsert → 审计。
 *
 * <p>三条不能改的实现细节（ST9 §12.3）：
 * <ol>
 *   <li>主体只来自 {@link TenantContext}（请求体里自称的 userId 一律忽略，契约 C11）；</li>
 *   <li>同 key 内容相同 → 幂等返回旧行、不写事件；内容不同 → 旧行 SUPERSEDED + 新行 version+1；</li>
 *   <li>向量化失败不拦写入 —— 精确 key 读取仍然有效，只是这条暂时进不了向量召回。</li>
 * </ol>
 */
@Service
public class LongTermMemoryService {

    private static final Logger log = LoggerFactory.getLogger(LongTermMemoryService.class);

    public static final int MAX_CONTENT_CHARS = 300;
    private static final Pattern KEY_PATTERN = Pattern.compile("[a-z][a-z0-9._-]{0,127}");

    private final LongTermMemoryRepository repository;
    private final MemoryEventRepository events;
    private final EmbeddingService embedding;
    private final MemoryProperties props;
    private final MemoryCommandParser parser = new MemoryCommandParser();

    public LongTermMemoryService(LongTermMemoryRepository repository, MemoryEventRepository events,
                                 EmbeddingService embedding, MemoryProperties props) {
        this.repository = repository;
        this.events = events;
        this.embedding = embedding;
        this.props = props;
    }

    /** 写入请求（API 与会话内命令共用一个入口）。 */
    public record SaveRequest(String type, String key, String content, Integer importance,
                              OffsetDateTime expiresAt) {
    }

    /**
     * 写一条记忆。
     *
     * <p>注意：{@link #maybeRecordCommand} 内部调它会走<b>同一个事务</b>（自调用不经过代理，
     * 但要靠外层方法上的 @Transactional 生效），所以两个方法都标了注解。
     */
    @Transactional
    public LongTermMemory save(SaveRequest request, String sourceType, String sourceRef, String traceId) {
        CurrentUser user = TenantContext.require();
        String type = normalizeType(request == null ? null : request.type());
        String key = validateKey(request == null ? null : request.key());
        String content = validateContent(request == null ? null : request.content());
        int importance = request == null || request.importance() == null
                ? 3 : Math.min(5, Math.max(1, request.importance()));
        OffsetDateTime expiresAt = request == null ? null : request.expiresAt();

        LongTermMemory existing = repository
                .findActiveByKey(user.tenantId(), user.userId(), type, key, true)
                .orElse(null);
        if (existing != null && existing.content().equals(content)) {
            log.debug("记忆未变化，幂等返回：key={}", key);
            return existing;
        }

        int version = existing == null ? 1 : existing.version() + 1;
        if (existing != null && !repository.supersede(user.tenantId(), existing.id())) {
            throw new IllegalStateException("记忆并发改写冲突：key=" + key);
        }

        float[] vector = embedSafe(content);
        long id = repository.insert(user.tenantId(), LongTermMemory.SUBJECT_USER, user.userId(), type, key,
                content, null, vector,
                vector == null ? null : embedding.modelName(),
                vector == null ? null : embedding.modelVersion(),
                sourceType, sourceRef, 1.0, importance, version, expiresAt);

        events.insert(user.tenantId(), id, MemoryEventRepository.TARGET_MEMORY, id,
                existing == null ? MemoryEventRepository.ACTION_CREATE : MemoryEventRepository.ACTION_SUPERSEDE,
                existing == null ? null : existing.content(), content,
                existing == null ? "created" : "superseded_version_" + version, traceId);

        return repository.findById(user.tenantId(), user.userId(), id)
                .orElseThrow(() -> new IllegalStateException("记忆写入后读不到：id=" + id));
    }

    /** 当前用户的 ACTIVE 记忆（过期的不展示）。 */
    public List<LongTermMemory> list(String type) {
        CurrentUser user = TenantContext.require();
        String normalized = type == null || type.isBlank() ? null : normalizeType(type);
        OffsetDateTime now = OffsetDateTime.now();
        return repository.listActive(user.tenantId(), user.userId(), normalized).stream()
                .filter(m -> !m.isExpired(now))
                .toList();
    }

    /** 逻辑删除：行保留（「用户什么时候撤回的偏好」不可重算），审计记一条 DELETE。 */
    @Transactional
    public void delete(long id) {
        CurrentUser user = TenantContext.require();
        LongTermMemory memory = repository.findById(user.tenantId(), user.userId(), id)
                .orElseThrow(() -> new MemoryNotFoundException(id));
        if (!repository.markDeleted(user.tenantId(), id)) {
            throw new MemoryNotFoundException(id);
        }
        events.insert(user.tenantId(), id, MemoryEventRepository.TARGET_MEMORY, id,
                MemoryEventRepository.ACTION_DELETE, memory.content(), null, "user_request", null);
    }

    /**
     * 会话里的显式命令（「以后…」「记住：…」）→ 写一条 INSTRUCTION。
     *
     * <p>三道前置判断都返回空而不是抛：记忆关着、没有身份、句子不像命令 —— 这三种情况
     * 都不该让一轮问答失败，甚至不该让调用方知道「这里本来可以写一条记忆」。
     */
    @Transactional
    public Optional<LongTermMemory> maybeRecordCommand(String question, String traceId) {
        if (!props.enabled() || TenantContext.optional().isEmpty()) {
            return Optional.empty();
        }
        Optional<MemoryCommand> command = parser.parse(question);
        if (command.isEmpty()) {
            return Optional.empty();
        }
        MemoryCommand c = command.get();
        LongTermMemory saved = save(new SaveRequest(c.memoryType(), c.memoryKey(), c.content(), null, null),
                LongTermMemory.SOURCE_COMMAND, c.sourceRef(), traceId);
        log.info("已记住显式命令：key={} version={}", saved.memoryKey(), saved.version());
        return Optional.of(saved);
    }

    /** 向量化失败只记日志：没有向量的记忆仍能按 key 精确读取。 */
    private float[] embedSafe(String content) {
        try {
            List<float[]> vectors = embedding.embedAll(List.of(content));
            return vectors.isEmpty() ? null : vectors.get(0);
        } catch (RuntimeException e) {
            log.warn("记忆向量化失败，只存文本：{}", e.toString());
            return null;
        }
    }

    /** PREFERENCE / CONSTRAINT 是计划里的旧名字，统一并入 INSTRUCTION（ST9 §12.1）。 */
    static String normalizeType(String raw) {
        if (raw == null || raw.isBlank()) {
            return LongTermMemory.TYPE_INSTRUCTION;
        }
        String type = raw.strip().toUpperCase(Locale.ROOT);
        return switch (type) {
            case "PREFERENCE", "CONSTRAINT" -> LongTermMemory.TYPE_INSTRUCTION;
            case LongTermMemory.TYPE_INSTRUCTION, LongTermMemory.TYPE_CONTEXTUAL,
                 LongTermMemory.TYPE_WATCHLIST -> type;
            default -> throw new IllegalArgumentException("未知的记忆类型：" + raw);
        };
    }

    private static String validateKey(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("memoryKey 不能为空");
        }
        String key = raw.strip();
        if (!KEY_PATTERN.matcher(key).matches()) {
            throw new IllegalArgumentException(
                    "memoryKey 只能用小写字母开头的 [a-z0-9._-]，最多 128 字符：" + key);
        }
        return key;
    }

    private static String validateContent(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("content 不能为空");
        }
        String content = raw.strip().replaceAll("\\s+", " ");
        if (content.length() > MAX_CONTENT_CHARS) {
            throw new IllegalArgumentException("content 最长 " + MAX_CONTENT_CHARS + " 字符");
        }
        return content;
    }
}
