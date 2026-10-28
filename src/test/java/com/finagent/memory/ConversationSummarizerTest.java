package com.finagent.memory;

import com.finagent.scope.ChatScope;
import com.finagent.scope.ScopeKind;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 摘要的阈值、增量、失败三条线。 */
class ConversationSummarizerTest {

    private ConversationRepository messages;
    private ConversationSummaryRepository summaries;
    private MemoryEventRepository events;
    private ConversationSummarizer summarizer;
    private String lastPrompt;

    @BeforeEach
    void setUp() {
        messages = mock(ConversationRepository.class);
        summaries = mock(ConversationSummaryRepository.class);
        events = mock(MemoryEventRepository.class);
        when(summaries.find(anyLong())).thenReturn(java.util.Optional.empty());
        when(summaries.upsert(anyLong(), anyLong(), anyString(), anyLong()))
                .thenAnswer(inv -> new ConversationSummary(inv.getArgument(1), inv.getArgument(2),
                        inv.getArgument(3), 1, OffsetDateTime.now()));
        summarizer = new ConversationSummarizer(messages, summaries, events, props(12), prompt -> {
            lastPrompt = prompt;
            return "  用户问过营业收入；助手答 1200 亿。 ";
        });
    }

    @Test
    @DisplayName("未汇总消息没超过阈值 → 不生成、不写库")
    void belowThresholdDoesNothing() {
        when(messages.countAfter(1L, 0L)).thenReturn(12);

        ConversationSummary result = summarizer.maybeSummarize(conversation());

        assertThat(result).isNull();
        verify(summaries, never()).upsert(anyLong(), anyLong(), anyString(), anyLong());
        assertThat(lastPrompt).isNull();
    }

    @Test
    @DisplayName("超过阈值 → 汇总最早的一半（偶数条），指针前移到那一半的最后一条")
    void summarizesOldestHalfAndMovesPointer() {
        when(messages.countAfter(1L, 0L)).thenReturn(30);
        when(messages.oldestAfter(1L, 0L, 14)).thenReturn(batch(1, 14));

        ConversationSummary saved = summarizer.maybeSummarize(conversation());

        assertThat(saved.summary()).isEqualTo("用户问过营业收入；助手答 1200 亿。");
        assertThat(saved.summaryUntilMessageId()).isEqualTo(14L);
        verify(summaries).upsert(1L, 1L, "用户问过营业收入；助手答 1200 亿。", 14L);
        verify(events).insert(eq(1L), eq(null), eq(MemoryEventRepository.TARGET_SUMMARY), eq(1L),
                eq(MemoryEventRepository.ACTION_SUMMARY_REWRITE), eq(null), anyString(), eq("threshold"), eq(null));
        // Prompt 里是最旧的那一批，不是最新的
        assertThat(lastPrompt).contains("用户：消息1").doesNotContain("消息15");
    }

    @Test
    @DisplayName("第二次触发：旧摘要作为输入，指针继续前移")
    void secondRunIsIncremental() {
        when(summaries.find(1L)).thenReturn(java.util.Optional.of(
                new ConversationSummary(1L, "旧摘要：问过 2024 年营收", 20L, 1, null)));
        when(messages.countAfter(1L, 20L)).thenReturn(20);
        when(messages.oldestAfter(1L, 20L, 10)).thenReturn(batch(21, 10));

        summarizer.maybeSummarize(conversation());

        assertThat(lastPrompt).contains("【旧摘要】").contains("问过 2024 年营收");
        verify(summaries).upsert(eq(1L), eq(1L), anyString(), eq(30L));
    }

    @Test
    @DisplayName("生成失败 → 异常上抛，不写摘要、不写事件（指针不动，下轮重试）")
    void generatorFailureWritesNothing() {
        ConversationSummarizer broken = new ConversationSummarizer(messages, summaries, events,
                props(12), prompt -> {
                    throw new IllegalStateException("模型超时");
                });
        when(messages.countAfter(1L, 0L)).thenReturn(30);
        when(messages.oldestAfter(1L, 0L, 14)).thenReturn(batch(1, 14));

        assertThatThrownBy(() -> broken.maybeSummarize(conversation()))
                .isInstanceOf(IllegalStateException.class);

        verify(summaries, never()).upsert(anyLong(), anyLong(), anyString(), anyLong());
        verify(events, never()).insert(anyLong(), any(), anyString(), anyLong(), anyString(),
                any(), any(), any(), any());
    }

    @Test
    @DisplayName("生成空摘要 → 视为失败")
    void blankSummaryIsFailure() {
        ConversationSummarizer blank = new ConversationSummarizer(messages, summaries, events,
                props(12), prompt -> "   ");
        when(messages.countAfter(1L, 0L)).thenReturn(30);
        when(messages.oldestAfter(1L, 0L, 14)).thenReturn(batch(1, 14));

        assertThatThrownBy(() -> blank.maybeSummarize(conversation()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("摘要生成结果为空");
    }

    @Test
    @DisplayName("超长摘要压成一行并截到 200 字")
    void longSummaryIsTrimmed() {
        ConversationSummarizer longOne = new ConversationSummarizer(messages, summaries, events,
                props(12), prompt -> "字".repeat(500));
        when(messages.countAfter(1L, 0L)).thenReturn(30);
        when(messages.oldestAfter(1L, 0L, 14)).thenReturn(batch(1, 14));

        assertThat(longOne.maybeSummarize(conversation()).summary())
                .hasSize(ConversationSummarizer.MAX_CHARS + 1).endsWith("…");
    }

    private static List<ConversationMessage> batch(long from, int count) {
        return java.util.stream.LongStream.range(from, from + count)
                .mapToObj(i -> new ConversationMessage(i, 1L,
                        i % 2 == 0 ? ConversationMessage.ROLE_ASSISTANT : ConversationMessage.ROLE_USER,
                        "消息" + i, null, "{}", "t", 10, OffsetDateTime.now()))
                .toList();
    }

    private static MemoryProperties props(int trigger) {
        return new MemoryProperties(true, 8000, 6, 0.15, 0.10, 0.05, 0.05, 0.40, 600, 3, trigger,
                0.6, 8, true);
    }

    private static Conversation conversation() {
        return new Conversation(1L, 1L, 1L, "会话", ScopeKind.CORPUS, ChatScope.corpus(),
                Conversation.STATUS_ACTIVE, null, null);
    }
}
