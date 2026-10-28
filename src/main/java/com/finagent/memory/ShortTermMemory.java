package com.finagent.memory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 短期记忆：最近 N 轮的问答原文。
 *
 * <p>三条硬规则：
 * <ol>
 *   <li><b>成对保留</b>：一轮 = USER + ASSISTANT。宁可少一轮，也不留「一条助手回答没有对应问题」
 *       —— 那种残片会让模型把上一轮的答案当成事实继续推理。</li>
 *   <li><b>整条丢弃</b>：超预算时从最旧的一轮开始整轮丢，绝不截断单条字符串
 *       （截断会产生半句话，模型会自己补全，这是幻觉的常见来源）。</li>
 *   <li><b>只读已落库的消息</b>：本轮的问题还没写库（写完才落库），所以不会自己把自己
 *       当成历史 —— 否则 Prompt 里会出现两遍当前问题。</li>
 * </ol>
 */
@Component
public class ShortTermMemory {

    private static final Logger log = LoggerFactory.getLogger(ShortTermMemory.class);

    private final ConversationRepository repository;
    private final MemoryProperties props;

    public ShortTermMemory(ConversationRepository repository, MemoryProperties props) {
        this.repository = repository;
        this.props = props;
    }

    /** @param dropped 因为预算或配对被丢掉的消息条数，写进 trace 便于解释「为什么没记住」 */
    public record Window(List<ConversationMessage> messages, int tokenCount, int dropped) {

        public static final Window EMPTY = new Window(List.of(), 0, 0);

        /**
         * 把消息序列压成「一问一答」的配对形态，供指代消解使用。
         *
         * <p>孤儿消息跳过（没有配对的 USER 或 ASSISTANT）：消解需要「问了什么 + 答了什么」，
         * 只有半边的记录会让模型误判上文。每侧文本由 {@link com.finagent.understanding.ContextTurn#of}
         * 截到 200 字 —— Prompt 空间要留给证据。
         */
        public List<com.finagent.understanding.ContextTurn> turns() {
            List<com.finagent.understanding.ContextTurn> out = new ArrayList<>();
            for (int i = 0; i < messages.size(); i++) {
                ConversationMessage current = messages.get(i);
                if (!ConversationMessage.ROLE_USER.equals(current.role())) {
                    continue;
                }
                String assistant = null;
                if (i + 1 < messages.size()
                        && ConversationMessage.ROLE_ASSISTANT.equals(messages.get(i + 1).role())) {
                    assistant = messages.get(i + 1).content();
                }
                out.add(com.finagent.understanding.ContextTurn.of(current.content(), assistant));
            }
            return List.copyOf(out);
        }
    }

    public Window load(long conversationId) {
        return load(conversationId, 0L);
    }

    /**
     * 只取 {@code afterMessageId} 之后的消息（默认 0 = 全部）。
     *
     * <p>为什么要这个参数：已经被摘要覆盖的消息不该再作为原文进 Prompt，
     * 否则同一段内容会在「摘要」与「最近对话」里各出现一次，白占预算还让模型看到重复信息。
     */
    public Window load(long conversationId, long afterMessageId) {
        int maxMessages = Math.max(2, props.shortTermRounds() * 2);
        List<ConversationMessage> newestFirst = afterMessageId > 0
                ? repository.newestAfter(conversationId, afterMessageId, maxMessages)
                : repository.messages(conversationId, null, maxMessages);
        if (newestFirst.isEmpty()) {
            return Window.EMPTY;
        }

        List<ConversationMessage> ordered = new ArrayList<>(newestFirst);
        Collections.reverse(ordered);        // 库里是倒序，Prompt 要正序（从旧到新）

        int dropped = 0;
        // 最旧一条是 ASSISTANT：说明这一对的前半截超出了条数上限，丢掉它保持成对
        if (ConversationMessage.ROLE_ASSISTANT.equals(ordered.get(0).role())) {
            ordered.remove(0);
            dropped++;
        }

        int budget = props.shortTermBudgetTokens();
        int total = ordered.stream().mapToInt(ConversationMessage::tokenCount).sum();
        while (total > budget && ordered.size() >= 2) {
            total -= ordered.get(0).tokenCount();
            total -= ordered.get(1).tokenCount();
            ordered.remove(0);
            ordered.remove(0);
            dropped += 2;
        }
        if (total > budget && !ordered.isEmpty()) {
            // 只剩一条还超预算：整条丢掉，不为省 token 截断
            dropped += ordered.size();
            total = 0;
            ordered.clear();
        }
        if (dropped > 0) {
            log.debug("短期记忆裁剪：会话 {} 丢弃 {} 条，保留 {} 条 / {} token（预算 {}）",
                    conversationId, dropped, ordered.size(), total, budget);
        }
        return new Window(List.copyOf(ordered), total, dropped);
    }
}
