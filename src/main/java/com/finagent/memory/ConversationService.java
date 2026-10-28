package com.finagent.memory;

import com.finagent.chat.ChatResponse;
import com.finagent.chunk.TokenEstimator;
import com.finagent.scope.ChatScope;
import com.finagent.scope.ResolvedScope;
import com.finagent.security.CurrentUser;
import com.finagent.security.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

/**
 * 会话的业务入口：谁建的、谁能看、这一轮该记什么。
 *
 * <p>三条职责边界：
 * <ol>
 *   <li><b>归属校验在这一层</b>：{@link ConversationRepository} 只按 tenant + owner 查，
 *       查不到就是「不存在」（404），不区分「没这个会话」与「不是你的会话」。</li>
 *   <li><b>作用域只用会话配置兜底</b>：单轮请求带了 scope 就用单轮的，不带才用会话的；
 *       这一轮不会反过来改会话配置（用户临时看一眼别的文档，不该污染长期设置）。</li>
 *   <li><b>落库在答完之后</b>：先出答案再写消息，写失败也不能把答案丢掉（加警告，不抛）。</li>
 * </ol>
 */
@Service
public class ConversationService {

    private static final Logger log = LoggerFactory.getLogger(ConversationService.class);

    /** 新建会话时的占位标题；第一轮提问后会被问题替换。 */
    public static final String DEFAULT_TITLE = "新会话";
    private static final int TITLE_MAX = 60;
    private static final int QUESTION_TITLE_MAX = 40;

    private final ConversationRepository repository;
    private final ObjectMapper mapper;

    public ConversationService(ConversationRepository repository, ObjectMapper mapper) {
        this.repository = repository;
        this.mapper = mapper;
    }

    public Conversation create(String title, ChatScope scope) {
        CurrentUser user = TenantContext.require();
        String normalized = title == null || title.isBlank() ? DEFAULT_TITLE : title.strip();
        if (normalized.length() > TITLE_MAX) {
            normalized = normalized.substring(0, TITLE_MAX);
        }
        long id = repository.create(user.tenantId(), user.userId(), normalized, scope);
        return repository.findOwned(id, user.tenantId(), user.userId())
                .orElseThrow(() -> new IllegalStateException("会话创建后读不回来：" + id));
    }

    public Conversation requireOwned(Long conversationId) {
        if (conversationId == null) {
            throw new ConversationNotFoundException(0L);
        }
        CurrentUser user = TenantContext.require();
        return repository.findOwned(conversationId, user.tenantId(), user.userId())
                .orElseThrow(() -> new ConversationNotFoundException(conversationId));
    }

    public List<Conversation> list(int limit) {
        CurrentUser user = TenantContext.require();
        int safe = limit <= 0 ? 20 : Math.min(limit, 100);
        return repository.listOwned(user.tenantId(), user.userId(), safe);
    }

    public List<ConversationMessage> messages(long conversationId, Long beforeId, int limit) {
        requireOwned(conversationId);
        int safe = limit <= 0 ? 50 : Math.min(limit, 200);
        return repository.messages(conversationId, beforeId, safe);
    }

    /** 逻辑删除：先校验归属，再改状态。消息与摘要不删（审计与回溯要用）。 */
    @Transactional
    public void delete(long conversationId) {
        requireOwned(conversationId);
        repository.markDeleted(conversationId);
    }

    /** 这一轮实际生效的作用域：请求给了就用请求的，否则用会话配置。 */
    public ChatScope effectiveScope(ChatScope requested, Conversation conversation) {
        if (requested != null) {
            return requested;
        }
        return conversation == null ? null : conversation.scope();
    }

    /**
     * 记录一轮问答：USER + ASSISTANT 两条消息 + 会话 updated_at。
     *
     * @return ASSISTANT 那条消息的 id（响应里回给前端，便于「跳到这一条」）
     */
    @Transactional
    public long recordTurn(Conversation conversation, String question, ChatResponse response,
                           ResolvedScope scope) {
        CurrentUser user = TenantContext.require();
        String snapshot = ScopeSnapshots.turnJson(mapper, conversation.scope(), scope);
        String citations = mapper.writeValueAsString(
                response == null || response.citations() == null ? List.of() : response.citations());
        String traceId = response == null || response.trace() == null ? null : response.trace().traceId();
        String answer = response == null || response.answer() == null ? "" : response.answer();

        repository.appendMessage(user.tenantId(), conversation.id(), ConversationMessage.ROLE_USER,
                question, null, snapshot, traceId, TokenEstimator.estimate(question));
        long assistantId = repository.appendMessage(user.tenantId(), conversation.id(),
                ConversationMessage.ROLE_ASSISTANT, answer, citations, snapshot, traceId,
                TokenEstimator.estimate(answer));
        repository.touch(conversation.id());

        if (DEFAULT_TITLE.equals(conversation.title())) {
            repository.renameTitle(conversation.id(), titleFrom(question));
        }
        log.info("会话 {} 记录一轮：问答 {} 字 / 答案 {} 字，trace={}",
                conversation.id(), question.length(), answer.length(), traceId);
        return assistantId;
    }

    static String titleFrom(String question) {
        String q = question == null ? "" : question.strip().replaceAll("\\s+", " ");
        if (q.isEmpty()) {
            return DEFAULT_TITLE;
        }
        return q.length() <= QUESTION_TITLE_MAX ? q : q.substring(0, QUESTION_TITLE_MAX) + "…";
    }
}
