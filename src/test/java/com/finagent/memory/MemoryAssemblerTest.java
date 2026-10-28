package com.finagent.memory;

import com.finagent.scope.ChatScope;
import com.finagent.scope.ScopeKind;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 装配器：会话为空 / 开关关闭时不读库；有历史时产出背景段与统计。 */
class MemoryAssemblerTest {

    private final MemoryPromptRenderer renderer = new MemoryPromptRenderer();
    private final ConversationContextRepository contextRepository = mock(ConversationContextRepository.class);
    private final ConversationSummaryRepository summaryRepository = mock(ConversationSummaryRepository.class);

    @BeforeEach
    void noStructuredContext() {
        when(contextRepository.find(anyLong())).thenReturn(java.util.Optional.empty());
        when(summaryRepository.find(anyLong())).thenReturn(java.util.Optional.empty());
    }

    @Test
    @DisplayName("没有会话（单轮问答）→ 空背景，且不去读消息")
    void noConversationMeansEmpty() {
        ShortTermMemory shortTerm = mock(ShortTermMemory.class);
        MemoryAssembler assembler = new MemoryAssembler(shortTerm, contextRepository, summaryRepository, renderer, props(true));

        assertThat(assembler.assembleMemory(null)).isEqualTo(MemoryContext.EMPTY);
        verify(shortTerm, never()).load(anyLong());
        verify(shortTerm, never()).load(anyLong(), anyLong());
    }

    @Test
    @DisplayName("开关关闭 → 空背景（回归：与阶段 8 一致）")
    void disabledMeansEmpty() {
        ShortTermMemory shortTerm = mock(ShortTermMemory.class);
        MemoryAssembler assembler = new MemoryAssembler(shortTerm, contextRepository, summaryRepository, renderer, props(false));

        assertThat(assembler.assembleMemory(conversation(1L))).isEqualTo(MemoryContext.EMPTY);
        verify(shortTerm, never()).load(anyLong(), anyLong());
    }

    @Test
    @DisplayName("有历史 → 背景段含上一轮内容，统计条数与裁剪数")
    void assemblesShortTermWindow() {
        ShortTermMemory shortTerm = mock(ShortTermMemory.class);
        when(shortTerm.load(7L, 0L)).thenReturn(new ShortTermMemory.Window(List.of(
                new ConversationMessage(1L, 7L, ConversationMessage.ROLE_USER, "第一个问题",
                        null, "{}", "t", 5, OffsetDateTime.now()),
                new ConversationMessage(2L, 7L, ConversationMessage.ROLE_ASSISTANT, "第一个回答",
                        null, "{}", "t", 6, OffsetDateTime.now())), 11, 2));
        MemoryAssembler assembler = new MemoryAssembler(shortTerm, contextRepository, summaryRepository, renderer, props(true));

        MemoryContext memory = assembler.assembleMemory(conversation(7L));

        assertThat(memory.text()).contains("用户：第一个问题").contains("助手：第一个回答");
        assertThat(memory.stats().shortTermMessages()).isEqualTo(2);
        assertThat(memory.stats().shortTermDropped()).isEqualTo(2);
        assertThat(memory.isEmpty()).isFalse();
    }

    @Test
    @DisplayName("历史为空 → 空背景（不产生只有标题的空段落）")
    void emptyWindowMeansEmpty() {
        ShortTermMemory shortTerm = mock(ShortTermMemory.class);
        when(shortTerm.load(7L, 0L)).thenReturn(ShortTermMemory.Window.EMPTY);
        MemoryAssembler assembler = new MemoryAssembler(shortTerm, contextRepository, summaryRepository, renderer, props(true));

        assertThat(assembler.assembleMemory(conversation(7L))).isEqualTo(MemoryContext.EMPTY);
    }

    @Test
    @DisplayName("有摘要 → 背景段含摘要，且短期窗口从摘要指针之后取")
    void summaryIsRenderedAndWindowStartsAfterPointer() {
        ShortTermMemory shortTerm = mock(ShortTermMemory.class);
        when(summaryRepository.find(7L)).thenReturn(java.util.Optional.of(
                new ConversationSummary(7L, "用户问过 2024 年营业收入，答 1200 亿。", 20L, 2, null)));
        when(shortTerm.load(7L, 20L)).thenReturn(ShortTermMemory.Window.EMPTY);
        MemoryAssembler assembler = new MemoryAssembler(shortTerm, contextRepository, summaryRepository, renderer, props(true));

        MemoryContext memory = assembler.assembleMemory(conversation(7L));

        assertThat(memory.text()).contains("此前对话摘要").contains("1200 亿");
        assertThat(memory.stats().summaryChars()).isEqualTo("用户问过 2024 年营业收入，答 1200 亿。".length());
        verify(shortTerm).load(7L, 20L);
    }

    @Test
    @DisplayName("长期记忆：INSTRUCTION 与 WATCHLIST 都渲染进背景段，统计 merged 条数")
    void longTermIsRenderedIntoPrompt() {
        ShortTermMemory shortTerm = mock(ShortTermMemory.class);
        when(shortTerm.load(7L, 0L)).thenReturn(ShortTermMemory.Window.EMPTY);
        MemoryAssembler assembler = new MemoryAssembler(shortTerm, contextRepository, summaryRepository, renderer, props(true));
        MemoryContext base = assembler.assembleMemory(conversation(7L));

        MemoryAssembler.WithLongTerm merged = assembler.withLongTerm(base, List.of(
                memory(LongTermMemory.TYPE_INSTRUCTION, "answer.style", "回答先给结论"),
                memory(LongTermMemory.TYPE_WATCHLIST, "focus.company.300059", "长期关注东方财富")));

        assertThat(merged.instructionOverflow()).isFalse();
        assertThat(merged.context().text()).contains("回答先给结论").contains("长期关注东方财富")
                .contains("长期偏好与关注点");
        assertThat(merged.context().stats().longTermItems()).isEqualTo(2);
        assertThat(merged.context().stats().gateHit()).isTrue();
        assertThat(merged.context().sections().longTerm()).hasSize(2);
    }

    @Test
    @DisplayName("INSTRUCTION 超预算：overflow=true 但内容照样全部注入（不静默丢用户指令）")
    void instructionOverflowStillInjected() {
        ShortTermMemory shortTerm = mock(ShortTermMemory.class);
        when(shortTerm.load(7L, 0L)).thenReturn(ShortTermMemory.Window.EMPTY);
        MemoryAssembler assembler = new MemoryAssembler(shortTerm, contextRepository, summaryRepository, renderer, props(true));
        MemoryContext base = assembler.assembleMemory(conversation(7L));
        String longInstruction = "回答必须先给结论再给证据".repeat(60);

        MemoryAssembler.WithLongTerm merged = assembler.withLongTerm(base, List.of(
                memory(LongTermMemory.TYPE_INSTRUCTION, "instruction.general", longInstruction)));

        assertThat(merged.instructionOverflow()).isTrue();
        assertThat(merged.context().text()).contains(longInstruction);
    }

    @Test
    @DisplayName("CONTEXTUAL 超预算：装不下就丢；预算算出来 <= 0 等于这一层关掉")
    void contextualOverBudgetDropped() {
        ShortTermMemory shortTerm = mock(ShortTermMemory.class);
        when(shortTerm.load(7L, 0L)).thenReturn(ShortTermMemory.Window.EMPTY);
        MemoryAssembler assembler = new MemoryAssembler(shortTerm, contextRepository, summaryRepository,
                renderer, props(true, 0.0001));
        MemoryContext base = assembler.assembleMemory(conversation(7L));

        MemoryAssembler.WithLongTerm merged = assembler.withLongTerm(base, List.of(
                memory(LongTermMemory.TYPE_INSTRUCTION, "answer.style", "回答先给结论"),
                memory(LongTermMemory.TYPE_CONTEXTUAL, "contextual.quarter", "用户习惯先看单季度再看累计")));

        assertThat(merged.context().sections().longTerm()).containsExactly("回答先给结论（你的明确要求）");
    }

    @Test
    @DisplayName("没有召回到任何记忆 → 背景信息原样返回，不重渲染")
    void noLongTermLeavesBaseUntouched() {
        ShortTermMemory shortTerm = mock(ShortTermMemory.class);
        when(shortTerm.load(7L, 0L)).thenReturn(ShortTermMemory.Window.EMPTY);
        MemoryAssembler assembler = new MemoryAssembler(shortTerm, contextRepository, summaryRepository, renderer, props(true));
        MemoryContext base = assembler.assembleMemory(conversation(7L));

        MemoryAssembler.WithLongTerm merged = assembler.withLongTerm(base, List.of());

        assertThat(merged.context()).isSameAs(base);
        assertThat(merged.instructionOverflow()).isFalse();
    }

    private static LongTermMemory memory(String type, String key, String content) {
        return new LongTermMemory(1L, 1L, LongTermMemory.SUBJECT_USER, 1L, type, key, content, null,
                LongTermMemory.SOURCE_API, null, 1.0, 3, LongTermMemory.STATUS_ACTIVE, 1,
                null, null, 0, null, null);
    }

    private static MemoryProperties props(boolean enabled) {
        return props(enabled, 0.05);
    }

    private static MemoryProperties props(boolean enabled, double longTermRatio) {
        return new MemoryProperties(enabled, 8000, 6, 0.15, 0.10, 0.05, longTermRatio, 0.40, 600, 3, 12,
                0.6, 8, true);
    }

    private static Conversation conversation(long id) {
        return new Conversation(id, 1L, 1L, "会话", ScopeKind.CORPUS, ChatScope.corpus(),
                Conversation.STATUS_ACTIVE, null, null);
    }
}
