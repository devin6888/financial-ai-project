package com.finagent.memory;

import com.finagent.chunk.TokenEstimator;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 装配这一轮注入 Prompt 的背景信息。
 *
 * <p>阶段 9 分任务逐步填满四层（Task 3 短期 → Task 4 结构化 → Task 6 摘要 → Task 8 长期），
 * 但<b>入口只有这一个</b>：ChatService 只调 {@link #assembleMemory(Conversation)}，以后加层不用
 * 再改编排代码。
 *
 * <p>两层之间不重叠是硬要求：短期窗口只取摘要指针<b>之后</b>的消息，
 * 否则同一段对话会既在摘要里又在原文里出现一遍。
 */
@Component
public class MemoryAssembler {

    private final ShortTermMemory shortTerm;
    private final ConversationContextRepository contextRepository;
    private final ConversationSummaryRepository summaryRepository;
    private final MemoryPromptRenderer renderer;
    private final MemoryProperties props;

    public MemoryAssembler(ShortTermMemory shortTerm, ConversationContextRepository contextRepository,
                           ConversationSummaryRepository summaryRepository,
                           MemoryPromptRenderer renderer, MemoryProperties props) {
        this.shortTerm = shortTerm;
        this.contextRepository = contextRepository;
        this.summaryRepository = summaryRepository;
        this.renderer = renderer;
        this.props = props;
    }

    public MemoryContext assembleMemory(Conversation conversation) {
        if (conversation == null || !props.enabled()) {
            return MemoryContext.EMPTY;
        }
        // 会话摘要
        ConversationSummary summary = summaryRepository.find(conversation.id()).orElse(null);
        // 压缩会话摘要
        String summaryText = summaryText(summary);

        //短期会话，最近几轮的会话信息
        ShortTermMemory.Window window = shortTerm.load(conversation.id(), ConversationSummarizer.untilOf(summary));

        //会话的结构化信息
        String structured = structured(conversation.id());

        MemorySections sections = new MemorySections(structured, summaryText, window.messages(), List.of());
        if (sections.isBlank()) {
            return MemoryContext.EMPTY;
        }

        // 组装四层会话记忆的提示词
        String memoryPrompt = renderer.render(sections);
        return new MemoryContext(memoryPrompt, new MemoryContext.Stats(
                window.messages().size(), window.dropped(),
                summaryText == null ? 0 : summaryText.length(), 0, false), window.turns(), sections);
    }

    /** {@link #withLongTerm} 的结果：新的背景信息 + INSTRUCTION 是否超预算（要留警告）。 */
    public record WithLongTerm(MemoryContext context, boolean instructionOverflow) {
    }

    /**
     * 把长期记忆并进已经装配好的背景信息（QU 之后才能召回，所以是「后加」而不是「一起装」）。
     *
     * <p>两套预算不能合并（D10）：
     * <ul>
     *   <li>INSTRUCTION（用户明确要求）走独立 {@code instructionTokens}；<b>超了也全部注入</b>，
     *       只返回 overflow=true 让编排层加一条警告 —— 静默丢弃用户指令是更严重的错误；</li>
     *   <li>CONTEXTUAL / WATCHLIST 走 {@code longTermBudgetTokens}（窗口的 5%），装不下就丢。</li>
     * </ul>
     */
    public WithLongTerm withLongTerm(MemoryContext base, List<LongTermMemory> items) {
        if (items == null || items.isEmpty()) {
            return new WithLongTerm(base, false);
        }
        MemorySections sections = base.sections() == null ? MemorySections.EMPTY : base.sections();
        List<String> instructions = new ArrayList<>();
        List<String> others = new ArrayList<>();
        for (LongTermMemory memory : items) {
            String line = LongTermMemoryRecaller.renderLine(memory);
            if (LongTermMemory.TYPE_INSTRUCTION.equals(memory.memoryType())) {
                instructions.add(line);
            } else {
                others.add(line);
            }
        }
        boolean overflow = tokens(instructions) > props.instructionTokens();

        // 预算 <= 0 表示「这一层关掉」，与 structured / summaryText 的语义保持一致：
        // 早期实现写的是 budget > 0 才比大小，结果 ratio=0 反而变成「不限量」，方向正好相反。
        int budget = props.longTermBudgetTokens();
        List<String> keptOthers = new ArrayList<>();
        int used = 0;
        if (budget > 0) {
            for (String line : others) {
                int t = TokenEstimator.estimate(line);
                if (used + t > budget) {
                    continue;
                }
                keptOthers.add(line);
                used += t;
            }
        }

        List<String> merged = new ArrayList<>(instructions);
        merged.addAll(keptOthers);
        MemorySections next = new MemorySections(sections.structured(), sections.summary(),
                sections.shortTerm(), merged);
        MemoryContext.Stats old = base.stats();
        MemoryContext.Stats stats = new MemoryContext.Stats(old.shortTermMessages(),
                old.shortTermDropped(), old.summaryChars(), merged.size(), true);
        return new WithLongTerm(new MemoryContext(renderer.render(next), stats, base.recentTurns(), next),
                overflow);
    }

    private static int tokens(List<String> lines) {
        int sum = 0;
        for (String line : lines) {
            sum += TokenEstimator.estimate(line);
        }
        return sum;
    }

    /**
     * 结构化上下文那一行（公司 / 年份 / 文档 / 指标）。
     *
     * <p>超预算就<b>整段丢掉</b>：这一行本来就只占 0.3%~0.5%，一旦超了说明数据异常
     * （比如 document ids 几百个），裁一半反而让模型误读范围。
     */
    private String structured(long conversationId) {
        int budget = props.structuredBudgetTokens();
        if (budget <= 0) {
            return null;
        }
        return contextRepository.find(conversationId)
                .map(ConversationContext::render)
                .filter(s -> !s.isBlank())
                .filter(s -> TokenEstimator.estimate(s) <= budget)
                .orElse(null);
    }

    /** 摘要段：超预算整段丢（摘要本就是压缩物，截断它等于让模型看到半句话）。 */
    private String summaryText(ConversationSummary summary) {
        int budget = props.summaryBudgetTokens();
        if (summary == null || budget <= 0) {
            return null;
        }
        String text = summary.summary();
        if (text == null || text.isBlank()) {
            return null;
        }
        return TokenEstimator.estimate(text) <= budget ? text : null;
    }
}
