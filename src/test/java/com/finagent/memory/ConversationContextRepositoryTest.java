package com.finagent.memory;

import com.finagent.scope.ChatScope;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import com.finagent.support.ConversationCleanup;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** conversation_context 的 jsonb + bigint[] 读写。 */
@Tag("db")
@SpringBootTest
@ActiveProfiles("test")
class ConversationContextRepositoryTest {

    private static final long TENANT = 1L;
    private static final long OWNER = 1L;
    private static final String TITLE_PREFIX = "test-ctx-";

    @Autowired
    private ConversationContextRepository repository;

    @Autowired
    private ConversationRepository conversations;

    @Autowired
    private JdbcTemplate jdbc;

    private final List<Long> created = new ArrayList<>();

    @BeforeEach
    void createConversation() {
        created.add(conversations.create(TENANT, OWNER, TITLE_PREFIX + "repo", ChatScope.corpus()));
    }

    @AfterEach
    void cleanUp() {
        ConversationCleanup.delete(jdbc, created);
        created.clear();
    }

    @Test
    @DisplayName("upsert 后读回来：公司 join 出名称，数组与年份不丢")
    void upsertThenFind() {
        long id = created.get(0);
        repository.upsert(TENANT, new ConversationContext(id, TENANT, 2L, null, null,
                2024, 2025, List.of(7L, 38L), "营业总收入", null, null));

        ConversationContext saved = repository.find(id).orElseThrow();
        assertThat(saved.companyId()).isEqualTo(2L);
        assertThat(saved.companyCode()).isEqualTo("600519");
        assertThat(saved.companyName()).isEqualTo("贵州茅台");
        assertThat(saved.yearFrom()).isEqualTo(2024);
        assertThat(saved.yearTo()).isEqualTo(2025);
        assertThat(saved.documentIds()).containsExactly(7L, 38L);
        assertThat(saved.currentMetric()).isEqualTo("营业总收入");
        assertThat(saved.updatedAt()).isNotNull();
    }

    @Test
    @DisplayName("同会话再写一次 → 覆盖，不新增行")
    void secondUpsertOverwrites() {
        long id = created.get(0);
        repository.upsert(TENANT, new ConversationContext(id, TENANT, 2L, null, null,
                2024, 2024, List.of(38L), "营业总收入", null, null));
        repository.upsert(TENANT, new ConversationContext(id, TENANT, 7L, null, null,
                2025, 2025, List.of(7L), "净利润", null, null));

        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM conversation_context WHERE conversation_id = ?", Integer.class, id))
                .isEqualTo(1);
        ConversationContext saved = repository.find(id).orElseThrow();
        assertThat(saved.companyId()).isEqualTo(7L);
        assertThat(saved.documentIds()).containsExactly(7L);
        assertThat(saved.currentMetric()).isEqualTo("净利润");
    }

    @Test
    @DisplayName("空数组也能存（{} 字面量而不是 NULL）")
    void emptyArrayStored() {
        long id = created.get(0);
        repository.upsert(TENANT, new ConversationContext(id, TENANT, null, null, null,
                null, null, List.of(), null, null, null));

        assertThat(repository.find(id).orElseThrow().documentIds()).isEmpty();
    }

    @Test
    @DisplayName("数组字面量格式")
    void arrayLiteralFormat() {
        assertThat(ConversationContextRepository.toArrayLiteral(List.of())).isEqualTo("{}");
        assertThat(ConversationContextRepository.toArrayLiteral(List.of(7L))).isEqualTo("{7}");
        assertThat(ConversationContextRepository.toArrayLiteral(List.of(7L, 38L))).isEqualTo("{7,38}");
    }
}
