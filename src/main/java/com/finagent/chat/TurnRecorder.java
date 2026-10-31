package com.finagent.chat;

import com.finagent.memory.Conversation;
import com.finagent.memory.ConversationContextService;
import com.finagent.memory.ConversationService;
import com.finagent.memory.ConversationSummarizer;
import com.finagent.memory.LongTermMemoryService;
import com.finagent.scope.ResolvedScope;
import com.finagent.understanding.QueryUnderstanding;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 一轮问答的「收尾」：上下文落库、会话消息落库、记忆命令、摘要。
 *
 * <p>为什么单独抽出来（ST14 P1）：阶段 9 把这套逻辑长在  ChatService 里，阶段 11 新增的
 * 图（LangGraph4j）跑完不写会话，于是「深度分析」那一轮成了幽灵对话 —— 答案给了、历史里没有。
 * 收尾逻辑与「怎么检索、怎么生成」无关，只依赖「这一轮问了什么、答了什么、用了什么范围」，
 * 所以两条执行路径（直链 / 图）共用这一份实现，它们的行为就不可能分叉。
 *
 * <p>顺序不能反：先落消息，再更新摘要（摘要要包含这一轮）；每步失败都只加警告，
 * 绝不因为收尾失败让用户丢掉已经生成好的答案。
 */
@Service
public class TurnRecorder {

    private static final Logger log = LoggerFactory.getLogger(TurnRecorder.class);

    private final ConversationService conversations;
    private final ConversationContextService contexts;
    private final LongTermMemoryService memories;
    private final ConversationSummarizer summarizer;

    public TurnRecorder(ConversationService conversations, ConversationContextService contexts,
                        LongTermMemoryService memories, ConversationSummarizer summarizer) {
        this.conversations = conversations;
        this.contexts = contexts;
        this.memories = memories;
        this.summarizer = summarizer;
    }

    /** 会话的结构化槽位（公司/年份/文档/指标）；没有就返回 null。 */
    public com.finagent.memory.ConversationContext contextOf(Conversation conversation) {
        return conversation == null ? null : contexts.find(conversation.id()).orElse(null);
    }

    /**
     * 结构化上下文：回答成功后才落库（本轮检索用的是「上一轮的状态」，落库失败不能影响这一轮的答案）。
     *
     * @return 失败时的稳定警告码（{@code context_persist_failed}），成功返回空列表
     */
    public List<String> updateContext(Conversation conversation, QueryUnderstanding qu, ResolvedScope scope) {
        if (conversation == null) {
            return List.of();
        }
        try {
            contexts.applyTurn(conversation, qu, scope);
            return List.of();
        } catch (RuntimeException e) {
            log.error("会话 {} 结构化上下文落库失败", conversation.id(), e);
            return List.of("context_persist_failed");
        }
    }

    /**
     * 收尾一轮：落库 → 显式记忆命令 → 摘要。
     *
     * @param conversation 无会话（单轮问答）时传 {@code null}，此时只处理记忆命令
     */
    public ChatResponse record(Conversation conversation, String question,
                               ChatResponse response, ResolvedScope scope) {
        ChatResponse persisted = persist(conversation, question, response, scope);
        // 显式命令（「以后…」「记住：…」）在收尾时落库：不改这一轮的回答，失败也只加警告。
        // 有没有会话都要记 —— 偏好不属于某一次会话。
        ChatResponse withMemory = recordMemoryCommand(question, persisted);
        if (conversation == null) {
            return withMemory;
        }
        try {
            summarizer.maybeSummarize(conversation);
            return withMemory;
        } catch (RuntimeException e) {
            log.error("会话 {} 摘要更新失败，本轮忽略", conversation.id(), e);
            List<String> warnings = new ArrayList<>(withMemory.warnings());
            warnings.add("summary_failed");
            return withMemory.withWarnings(List.copyOf(warnings));
        }
    }

    /**
     * 识别并写入显式记忆命令。
     *
     * <p>服务层自己会判「记忆开关 / 有没有身份 / 像不像命令」，三种情况都返回空 ——
     * 所以这里不需要额外条件，也不该因为「本来可以写一条记忆」去污染响应。
     */
    private ChatResponse recordMemoryCommand(String question, ChatResponse response) {
        try {
            memories.maybeRecordCommand(question, response.trace() == null ? null : response.trace().traceId());
            return response;
        } catch (RuntimeException e) {
            log.error("显式记忆写入失败，本轮忽略", e);
            List<String> warnings = new ArrayList<>(response.warnings());
            warnings.add("memory_write_failed");
            return response.withWarnings(List.copyOf(warnings));
        }
    }

    /**
     * 落库这一轮问答（会话问答才有）。
     *
     * <p>为什么失败只加警告不抛：答案已经生成、trace 已经落盘，为了一条会话记录让用户
     * 看到 500 是更差的选择。前端看到 {@code conversation_persist_failed} 可以提示
     * 「这轮不会出现在历史里」。
     */
    private ChatResponse persist(Conversation conversation, String question, ChatResponse response,
                                 ResolvedScope scope) {
        if (conversation == null) {
            return response;
        }
        try {
            long messageId = conversations.recordTurn(conversation, question, response, scope);
            return response.withConversation(conversation.id(), messageId);
        } catch (RuntimeException e) {
            log.error("会话 {} 落库失败，本轮答案不落库", conversation.id(), e);
            List<String> warnings = new ArrayList<>(response.warnings());
            warnings.add("conversation_persist_failed");
            return response.withWarnings(List.copyOf(warnings)).withConversation(conversation.id(), null);
        }
    }
}
