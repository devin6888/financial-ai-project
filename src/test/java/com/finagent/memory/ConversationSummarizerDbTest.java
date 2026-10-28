package com.finagent.memory;

import com.finagent.scope.ChatScope;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * 真库：15 轮对话后摘要指针前移，但消息一条都没少。
 *
 * <p>用 {@code @MockitoBean} 替掉 {@link SummaryGenerator}：这里要验的是「什么时候汇总、
 * 汇总哪一批、指针走到哪」，不是模型写得好不好（Prompt 质量由人工抽查）。
 */
@SpringBootTest(properties = "finagent.memory.enabled=true")
@ActiveProfiles("test")
class ConversationSummarizerDbTest {

    private static final long TENANT = 1L;

    @Autowired
    private ConversationSummarizer summarizer;

    @Autowired
    private ConversationRepository conversations;

    @Autowired
    private ConversationSummaryRepository summaries;

    @Autowired
    private JdbcTemplate jdbc;

    @MockitoBean
    private SummaryGenerator generator;

    private final List<Long> created = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        created.forEach(id -> {
            jdbc.update("DELETE FROM memory_event WHERE target_id = ? AND target_type = 'CONVERSATION_SUMMARY'", id);
            jdbc.update("DELETE FROM conversation WHERE id = ?", id);
        });
    }

    @Test
    @DisplayName("15 轮（30 条）> 阈值 12 → 指针前移到第 14 条，消息总数不变")
    void fifteenRoundsMovePointerWithoutDeleting() {
        when(generator.generate(anyString())).thenReturn("前七轮的结论：2024 年营业收入 1200 亿");
        Conversation conversation = newConversation();
        appendRounds(conversation, 15);

        ConversationSummary summary = summarizer.maybeSummarize(conversation);

        assertThat(summary).isNotNull();
        assertThat(summary.version()).isEqualTo(1);
        // 汇总「最早的一半」= 30/2 = 15 → 取偶数 14 条，指针落在第 14 条消息上
        assertThat(summary.summaryUntilMessageId()).isEqualTo(nthMessageId(conversation, 14));
        assertThat(countMessages(conversation)).isEqualTo(30);
        assertThat(countEvents(conversation)).isEqualTo(1);
    }

    @Test
    @DisplayName("再攒 20 条 → version=2，指针继续前移，消息仍是 50 条")
    void secondBatchIsIncremental() {
        when(generator.generate(anyString())).thenReturn("第一批结论");
        Conversation conversation = newConversation();
        appendRounds(conversation, 15);
        summarizer.maybeSummarize(conversation);

        appendRounds(conversation, 10);
        ConversationSummary second = summarizer.maybeSummarize(conversation);

        assertThat(second.version()).isEqualTo(2);
        // 第一次汇总了前 14 条；之后又攒了 20 条 → 待汇总 16 + 20 = 36 条
        // → 取最早的一半 18 条，指针从第 14 条走到第 32 条
        assertThat(second.summaryUntilMessageId()).isEqualTo(nthMessageId(conversation, 32));
        assertThat(countMessages(conversation)).isEqualTo(50);
        assertThat(countEvents(conversation)).isEqualTo(2);
    }

    @Test
    @DisplayName("不够阈值 → 不生成摘要（一行都不写）")
    void belowThresholdWritesNothing() {
        Conversation conversation = newConversation();
        appendRounds(conversation, 3);

        assertThat(summarizer.maybeSummarize(conversation)).isNull();
        assertThat(summaries.find(conversation.id())).isEmpty();
    }

    private Conversation newConversation() {
        long id = conversations.create(TENANT, 1L, "test-summary-db", ChatScope.corpus());
        created.add(id);
        return new Conversation(id, TENANT, 1L, "test-summary-db", com.finagent.scope.ScopeKind.CORPUS,
                ChatScope.corpus(), Conversation.STATUS_ACTIVE, null, null);
    }

    private void appendRounds(Conversation conversation, int rounds) {
        int already = countMessages(conversation);
        for (int i = 1; i <= rounds; i++) {
            conversations.appendMessage(TENANT, conversation.id(), ConversationMessage.ROLE_USER,
                    "第" + (already + i) + "个问题", null, "{}", "t", 10);
            conversations.appendMessage(TENANT, conversation.id(), ConversationMessage.ROLE_ASSISTANT,
                    "第" + (already + i) + "个回答", null, "{}", "t", 10);
        }
    }

    /** 第 n 条消息的真实 id（消息 id 是全局序列，不是 1..n，所以不能硬编码）。 */
    private Long nthMessageId(Conversation conversation, int n) {
        return jdbc.queryForObject(
                "SELECT id FROM conversation_message WHERE conversation_id = ? ORDER BY id OFFSET ? LIMIT 1",
                Long.class, conversation.id(), n - 1);
    }

    private int countMessages(Conversation conversation) {
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM conversation_message WHERE conversation_id = ?",
                Integer.class, conversation.id());
        return count == null ? 0 : count;
    }

    private int countEvents(Conversation conversation) {
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM memory_event WHERE target_type = 'CONVERSATION_SUMMARY' AND target_id = ?",
                Integer.class, conversation.id());
        return count == null ? 0 : count;
    }
}
