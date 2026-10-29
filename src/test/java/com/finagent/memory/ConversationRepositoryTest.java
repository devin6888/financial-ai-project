package com.finagent.memory;

import com.finagent.scope.ChatScope;
import com.finagent.scope.ScopeKind;
import org.junit.jupiter.api.AfterEach;
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

/**
 * 真库测 conversation / conversation_message 的读写。
 *
 * <p>为什么不用 H2 / mock：这里的正确性靠 SQL 本身（jsonb 读写、RETURNING id、
 * 复合索引），mock 掉 JdbcTemplate 等于只测了自己写的 Java。
 */
@Tag("db")
@SpringBootTest
@ActiveProfiles("test")
class ConversationRepositoryTest {

    private static final long TENANT = 1L;
    private static final long OWNER = 1L;
    private static final String TITLE_PREFIX = "test-conv-";

    @Autowired
    private ConversationRepository repository;

    @Autowired
    private JdbcTemplate jdbc;

    /** 用例里建的会话 id：清理只按 id，不按标题（docs/测试规范.md 7.3）。 */
    private final List<Long> created = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        ConversationCleanup.delete(jdbc, created);
        created.clear();
    }

    /** 建会话并登记 id，供 @AfterEach 按主键清理。 */
    private long create(String suffix, ChatScope scope) {
        long id = repository.create(TENANT, OWNER, TITLE_PREFIX + suffix, scope);
        created.add(id);
        return id;
    }

    @Test
    @DisplayName("建会话 → 读回来：作用域（jsonb）原样保存")
    void createAndFindOwned() {
        long id = create("scope", ChatScope.documents(List.of(7L, 38L)));

        Conversation saved = repository.findOwned(id, TENANT, OWNER).orElseThrow();
        assertThat(saved.scopeKind()).isEqualTo(ScopeKind.DOCUMENTS);
        assertThat(saved.scope().documentIds()).containsExactly(7L, 38L);
        assertThat(saved.status()).isEqualTo(Conversation.STATUS_ACTIVE);
        assertThat(saved.createdAt()).isNotNull();

        assertThat(repository.findOwned(id, TENANT, 999L)).isEmpty();
    }

    @Test
    @DisplayName("列表按 updated_at 倒序：刚说过话的排最前")
    void listOwnedOrdersByUpdatedAt() {
        long older = create("older", ChatScope.corpus());
        long newer = create("newer", ChatScope.corpus());
        repository.touch(older);

        List<Conversation> list = repository.listOwned(TENANT, OWNER, 50);
        List<Long> ids = list.stream().map(Conversation::id).toList();
        assertThat(ids).contains(older, newer);
        assertThat(ids.indexOf(older)).isLessThan(ids.indexOf(newer));
    }

    @Test
    @DisplayName("消息倒序 + beforeId 翻页不重不漏")
    void messagesAreNewestFirstAndPageByBeforeId() {
        long id = create("paging", ChatScope.corpus());
        long first = repository.appendMessage(TENANT, id, ConversationMessage.ROLE_USER, "q1", null, "{}", "t", 1);
        long second = repository.appendMessage(TENANT, id, ConversationMessage.ROLE_ASSISTANT, "a1", "[]", "{}", "t", 1);
        long third = repository.appendMessage(TENANT, id, ConversationMessage.ROLE_USER, "q2", null, "{}", "t", 1);
        long fourth = repository.appendMessage(TENANT, id, ConversationMessage.ROLE_ASSISTANT, "a2", "[]", "{}", "t", 1);

        List<Long> page1 = repository.messages(id, null, 2).stream().map(ConversationMessage::id).toList();
        assertThat(page1).containsExactly(fourth, third);

        List<Long> page2 = repository.messages(id, third, 2).stream().map(ConversationMessage::id).toList();
        assertThat(page2).containsExactly(second, first);
    }

    @Test
    @DisplayName("逻辑删除后查不到，但消息还在（审计要留）")
    void deletedConversationIsInvisibleButKeepsMessages() {
        long id = create("deleted", ChatScope.corpus());
        repository.appendMessage(TENANT, id, ConversationMessage.ROLE_USER, "q", null, "{}", null, 1);

        repository.markDeleted(id);

        assertThat(repository.findOwned(id, TENANT, OWNER)).isEmpty();
        assertThat(repository.messages(id, null, 10)).hasSize(1);
    }
}
