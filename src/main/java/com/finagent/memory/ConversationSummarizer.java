package com.finagent.memory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 会话摘要：消息攒够一批就把最早的一半压进摘要，指针前移。
 *
 * <p>三条硬规则（ST9 §4 Task 6 / §11.2）：
 * <ol>
 *   <li><b>失败不影响回答</b>：这里只管抛，{@code ChatService} 捕获后加 warning，下一轮重试
 *       —— 指针没动，所以下一轮面对的还是同一批消息；</li>
 *   <li><b>只前移指针，不删消息</b>：完整历史留在 {@code conversation_message}，审计与回放要用；</li>
 *   <li><b>摘要不产生引用</b>：它只会进「背景信息 · 不可引用」段落。</li>
 * </ol>
 *
 * <p>为什么一次只汇总「最早的一半」：一次吃完会让摘要与短期窗口之间出现空白区
 * （既不在摘要里、也不在窗口里）；半量推进保证两段始终接得上。
 */
@Component
public class ConversationSummarizer {

    private static final Logger log = LoggerFactory.getLogger(ConversationSummarizer.class);

    /** 摘要正文上限（字符）；超出截断并加省略号。 */
    static final int MAX_CHARS = 200;

    /** 单条消息进 Prompt 前先截断，避免一条长回答挤掉整批对话。 */
    static final int MESSAGE_MAX_CHARS = 300;

    private final ConversationRepository messages;
    private final ConversationSummaryRepository summaries;
    private final MemoryEventRepository events;
    private final MemoryProperties props;
    private final SummaryGenerator generator;

    public ConversationSummarizer(ConversationRepository messages,
                                  ConversationSummaryRepository summaries,
                                  MemoryEventRepository events,
                                  MemoryProperties props,
                                  SummaryGenerator generator) {
        this.messages = messages;
        this.summaries = summaries;
        this.events = events;
        this.props = props;
        this.generator = generator;
    }

    /**
     * 够阈值就汇总一批并返回新摘要；不够阈值返回现状（可能为 null）。
     *
     * @throws RuntimeException 生成失败时原样抛出，由调用方决定「只加 warning」
     */
    @Transactional
    public ConversationSummary maybeSummarize(Conversation conversation) {
        if (conversation == null || !props.enabled()) {
            return null;
        }
        ConversationSummary current = summaries.find(conversation.id()).orElse(null);
        long until = untilOf(current);

        int pending = messages.countAfter(conversation.id(), until);
        if (pending <= props.summaryTriggerMessages()) {
            return current;
        }

        // 取最早的一半，且取偶数条（一轮 = 用户 + 助手，成对汇总才不会让摘要出现「半个问题」）
        int half = Math.max(2, pending / 2);
        int take = Math.max(2, half % 2 == 0 ? half : half - 1);
        List<ConversationMessage> batch = messages.oldestAfter(conversation.id(), until, take);
        if (batch.isEmpty()) {
            return current;
        }

        String summary = trim(generator.generate(prompt(current, batch)));
        if (summary == null) {
            throw new IllegalStateException("摘要生成结果为空");
        }
        long lastId = batch.get(batch.size() - 1).id();

        events.insert(conversation.tenantId(), null, MemoryEventRepository.TARGET_SUMMARY,
                conversation.id(), MemoryEventRepository.ACTION_SUMMARY_REWRITE,
                current == null ? null : current.summary(), summary, "threshold", null);
        ConversationSummary saved = summaries.upsert(conversation.tenantId(), conversation.id(),
                summary, lastId);
        log.info("会话 {} 摘要更新：汇总 {} 条（指针 {} → {}），version={}",
                conversation.id(), batch.size(), until, lastId, saved.version());
        return saved;
    }

    static long untilOf(ConversationSummary summary) {
        return summary == null || summary.summaryUntilMessageId() == null
                ? 0L : summary.summaryUntilMessageId();
    }

    /** 旧摘要 + 待汇总对话；旧摘要为空时整段省略。 */
    static String prompt(ConversationSummary current, List<ConversationMessage> batch) {
        StringBuilder sb = new StringBuilder();
        if (current != null && current.summary() != null && !current.summary().isBlank()) {
            sb.append("【旧摘要】\n").append(current.summary()).append("\n\n");
        }
        sb.append("【待汇总对话】\n");
        for (ConversationMessage m : batch) {
            if (m.content() == null || m.content().isBlank()) {
                continue;
            }
            String role = ConversationMessage.ROLE_USER.equals(m.role()) ? "用户" : "助手";
            sb.append(role).append("：").append(shrink(m.content())).append('\n');
        }
        return sb.toString();
    }

    static String shrink(String text) {
        String stripped = text.strip().replaceAll("\\s+", " ");
        return stripped.length() <= MESSAGE_MAX_CHARS
                ? stripped : stripped.substring(0, MESSAGE_MAX_CHARS) + "…";
    }

    /** 压成一行、截到 200 字；空白返回 null（调用方据此判定失败）。 */
    static String trim(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String flat = text.strip().replaceAll("\\s+", " ");
        return flat.length() <= MAX_CHARS ? flat : flat.substring(0, MAX_CHARS) + "…";
    }
}
