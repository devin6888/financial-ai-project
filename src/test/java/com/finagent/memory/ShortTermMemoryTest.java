package com.finagent.memory;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 短期窗口：成对保留、整条丢弃、倒序进正序出。 */
class ShortTermMemoryTest {

    private ConversationRepository repository;
    private ShortTermMemory memory;

    @BeforeEach
    void setUp() {
        repository = mock(ConversationRepository.class);
    }

    private void props(int window, int rounds, double ratio) {
        memory = new ShortTermMemory(repository, new MemoryProperties(true, window, rounds, ratio,
                0.10, 0.05, 0.05, 0.40, 600, 3, 12, 0.6, 8, true));
    }

    /** 按时间正序造 n 轮，每轮 USER + ASSISTANT；仓储返回倒序（最新在前）。 */
    private void stubTurns(int n, int tokensPerMessage) {
        List<ConversationMessage> chronological = new ArrayList<>();
        long id = 1;
        for (int i = 1; i <= n; i++) {
            chronological.add(message(id++, ConversationMessage.ROLE_USER, "q" + i, tokensPerMessage));
            chronological.add(message(id++, ConversationMessage.ROLE_ASSISTANT, "a" + i, tokensPerMessage));
        }
        stubChronological(chronological);
    }

    private void stubChronological(List<ConversationMessage> chronological) {
        List<ConversationMessage> newestFirst = new ArrayList<>(chronological);
        Collections.reverse(newestFirst);
        when(repository.messages(anyLong(), isNull(), anyInt())).thenReturn(newestFirst);
    }

    @Test
    @DisplayName("预算内：全部保留，按时间正序")
    void keepsAllWithinBudget() {
        props(1000, 3, 0.15);      // 预算 150
        stubTurns(3, 20);          // 6 条 × 20 = 120

        ShortTermMemory.Window window = memory.load(1L);

        assertThat(window.messages()).hasSize(6);
        assertThat(window.tokenCount()).isEqualTo(120);
        assertThat(window.dropped()).isZero();
        assertThat(window.messages().get(0).content()).isEqualTo("q1");
        assertThat(window.messages().get(5).content()).isEqualTo("a3");
    }

    @Test
    @DisplayName("超预算：从最旧整轮丢弃，不截断单条")
    void dropsOldestPairsWhenOverBudget() {
        props(1000, 4, 0.15);      // 预算 150
        stubTurns(4, 20);          // 160 > 150

        ShortTermMemory.Window window = memory.load(1L);

        assertThat(window.messages()).hasSize(6);
        assertThat(window.tokenCount()).isEqualTo(120);
        assertThat(window.dropped()).isEqualTo(2);
        assertThat(window.messages().get(0).content()).isEqualTo("q2");
    }

    @Test
    @DisplayName("最旧一条是助手：配对断了，先丢掉它")
    void dropsLeadingAssistant() {
        props(1000, 3, 0.15);
        List<ConversationMessage> chronological = new ArrayList<>(Arrays.asList(
                message(1, ConversationMessage.ROLE_USER, "q1", 10),
                message(2, ConversationMessage.ROLE_ASSISTANT, "a1", 10),
                message(3, ConversationMessage.ROLE_USER, "q2", 10),
                message(4, ConversationMessage.ROLE_ASSISTANT, "a2", 10),
                message(5, ConversationMessage.ROLE_USER, "q3", 10),
                message(6, ConversationMessage.ROLE_ASSISTANT, "a3", 10)));
        chronological.remove(0);   // 模拟条数上限把最旧的问题截掉了
        stubChronological(chronological);

        ShortTermMemory.Window window = memory.load(1L);

        assertThat(window.dropped()).isEqualTo(1);
        assertThat(window.messages()).hasSize(4);
        assertThat(window.messages().get(0).role()).isEqualTo(ConversationMessage.ROLE_USER);
        assertThat(window.messages().get(0).content()).isEqualTo("q2");
    }

    @Test
    @DisplayName("单条就超预算：整条丢掉，留下空窗口")
    void singleMessageOverBudgetIsDropped() {
        props(100, 2, 0.15);       // 预算 15
        stubTurns(1, 50);          // 100 > 15

        ShortTermMemory.Window window = memory.load(1L);

        assertThat(window.messages()).isEmpty();
        assertThat(window.tokenCount()).isZero();
        assertThat(window.dropped()).isEqualTo(2);
    }

    @Test
    @DisplayName("没有历史 → 空窗口")
    void emptyConversation() {
        props(8000, 6, 0.15);
        stubChronological(List.of());

        ShortTermMemory.Window window = memory.load(1L);

        assertThat(window.messages()).isEmpty();
        assertThat(window.tokenCount()).isZero();
        assertThat(window.dropped()).isZero();
    }

    @Test
    @DisplayName("turns()：一问一答配成一对，孤儿消息跳过")
    void turnsPairUserWithAssistant() {
        props(8000, 6, 0.15);
        stubChronological(List.of(
                message(1, ConversationMessage.ROLE_ASSISTANT, "孤儿回答", 5),
                message(2, ConversationMessage.ROLE_USER, "q1", 5),
                message(3, ConversationMessage.ROLE_ASSISTANT, "a1", 5),
                message(4, ConversationMessage.ROLE_USER, "q2", 5),
                message(5, ConversationMessage.ROLE_ASSISTANT, "a2", 5)));

        List<com.finagent.understanding.ContextTurn> turns = memory.load(1L).turns();

        assertThat(turns).hasSize(2);
        assertThat(turns.get(0).user()).isEqualTo("q1");
        assertThat(turns.get(0).assistant()).isEqualTo("a1");
        assertThat(turns.get(1).user()).isEqualTo("q2");
    }

    @Test
    @DisplayName("turns()：只有问题没有回答时，助手侧为 null")
    void turnWithoutAnswerHasNullAssistant() {
        props(8000, 6, 0.15);
        stubChronological(List.of(message(1, ConversationMessage.ROLE_USER, "q1", 5)));

        List<com.finagent.understanding.ContextTurn> turns = memory.load(1L).turns();

        assertThat(turns).hasSize(1);
        assertThat(turns.get(0).assistant()).isNull();
    }

    private static ConversationMessage message(long id, String role, String content, int tokens) {
        return new ConversationMessage(id, 1L, role, content, null, "{}", "trace", tokens,
                OffsetDateTime.parse("2026-10-27T12:00:00+08:00"));
    }
}