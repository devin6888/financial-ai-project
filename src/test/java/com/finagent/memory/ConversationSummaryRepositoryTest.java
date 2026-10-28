package com.finagent.memory;

import com.finagent.scope.ChatScope;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** conversation_summary 的覆盖写、版本递增、指针只前进。 */
@SpringBootTest
@ActiveProfiles("test")
class ConversationSummaryRepositoryTest {

    private static final long TENANT = 1L;

    @Autowired
    private ConversationSummaryRepository summaries;

    @Autowired
    private ConversationRepository conversations;

    @Autowired
    private JdbcTemplate jdbc;

    private final List<Long> created = new ArrayList<>();

    @BeforeEach
    void createConversation() {
        created.add(conversations.create(TENANT, 1L, "test-summary-repo", ChatScope.corpus()));
    }

    @AfterEach
    void cleanUp() {
        created.forEach(id -> jdbc.update("DELETE FROM conversation WHERE id = ?", id));
    }

    @Test
    @DisplayName("首次写入 → version=1")
    void firstUpsert() {
        long id = created.get(0);

        ConversationSummary saved = summaries.upsert(TENANT, id, "摘要一", 10L);

        assertThat(saved.version()).isEqualTo(1);
        assertThat(saved.summaryUntilMessageId()).isEqualTo(10L);
        assertThat(summaries.find(id).orElseThrow().summary()).isEqualTo("摘要一");
    }

    @Test
    @DisplayName("覆盖写 → version 递增，指针前移，仍是一行")
    void secondUpsertOverwrites() {
        long id = created.get(0);
        summaries.upsert(TENANT, id, "摘要一", 10L);

        ConversationSummary saved = summaries.upsert(TENANT, id, "摘要二", 20L);

        assertThat(saved.version()).isEqualTo(2);
        assertThat(saved.summary()).isEqualTo("摘要二");
        assertThat(saved.summaryUntilMessageId()).isEqualTo(20L);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM conversation_summary WHERE conversation_id = ?", Integer.class, id))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("指针只前进：写入更小的指针不会把它退回去")
    void pointerNeverGoesBackwards() {
        long id = created.get(0);
        summaries.upsert(TENANT, id, "摘要二", 20L);

        ConversationSummary saved = summaries.upsert(TENANT, id, "并发的小版本", 10L);

        assertThat(saved.summaryUntilMessageId()).isEqualTo(20L);
    }
}
