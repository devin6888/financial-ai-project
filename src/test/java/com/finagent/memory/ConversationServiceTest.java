package com.finagent.memory;

import com.finagent.chat.ChatResponse;
import com.finagent.scope.ChatScope;
import com.finagent.scope.DocMeta;
import com.finagent.scope.ResolvedScope;
import com.finagent.scope.ScopeKind;
import com.finagent.security.CurrentUser;
import com.finagent.security.TenantContext;
import com.finagent.support.ConversationCleanup;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 会话业务：归属、作用域兜底、一轮落两条消息。 */
@Tag("db")
@SpringBootTest
@ActiveProfiles("test")
class ConversationServiceTest {

    private static final CurrentUser ANALYST =
            new CurrentUser(1L, 1L, "analyst", "分析师", Set.of("ANALYST"));
    private static final CurrentUser ADMIN =
            new CurrentUser(3L, 1L, "admin", "管理员", Set.of("ADMIN"));

    @Autowired
    private ConversationService service;

    @Autowired
    private ConversationRepository repository;

    @Autowired
    private JdbcTemplate jdbc;

    /** 用例里建的会话 id：清理只按 id，不按标题（docs/测试规范.md 7.3）。 */
    private final List<Long> created = new ArrayList<>();

    @BeforeEach
    void setUp() {
        TenantContext.set(ANALYST);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
        ConversationCleanup.delete(jdbc, created);
        created.clear();
    }

    @Test
    @DisplayName("新建会话：owner 是当前用户，作用域可选")
    void createAssignsOwnerAndScope() {
        Conversation saved = create("test-conv-create", ChatScope.documents(List.of(7L)));

        assertThat(saved.ownerUserId()).isEqualTo(ANALYST.userId());
        assertThat(saved.scope().documentIds()).containsExactly(7L);
    }

    @Test
    @DisplayName("别人的会话 → 404（不是 403，不确认存在性）")
    void requireOwnedRejectsOtherUsersConversation() {
        TenantContext.set(ADMIN);
        Conversation admins = create("test-conv-other", ChatScope.corpus());

        TenantContext.set(ANALYST);
        assertThatThrownBy(() -> service.requireOwned(admins.id()))
                .isInstanceOf(ConversationNotFoundException.class);
    }

    @Test
    @DisplayName("一轮问答落两条消息，快照里带这一轮的 chunk 版本")
    void recordTurnWritesTwoMessagesAndSnapshot() {
        Conversation conversation = create(ConversationService.DEFAULT_TITLE, ChatScope.corpus());
        ResolvedScope scope = ResolvedScope.of(ScopeKind.DOCUMENTS,
                List.of(new DocMeta(7L, 7L, "300059", "东方财富", 2025, "ANNUAL",
                        null, null, 3, "东方财富2025年年度报告")),
                ResolvedScope.REASON_SESSION, List.of());
        ChatResponse response = new ChatResponse(true, "营业总收入 120.8 亿元 [E1]",
                List.of(), List.of(), "HIGH", null, null, List.of(), null, null);

        long assistantId = service.recordTurn(conversation, "  2025 年  营业总收入是多少  ", response, scope);

        List<ConversationMessage> messages = service.messages(conversation.id(), null, 10);
        assertThat(messages).hasSize(2);
        assertThat(messages.get(0).role()).isEqualTo(ConversationMessage.ROLE_ASSISTANT);
        assertThat(messages.get(0).id()).isEqualTo(assistantId);
        assertThat(messages.get(0).citationsJson()).contains("[]");
        assertThat(messages.get(1).role()).isEqualTo(ConversationMessage.ROLE_USER);
        // 快照用 jsonb 存，读回来的空格数不固定 → 断言前把空白去掉
        assertThat(messages.get(1).scopeSnapshot().replaceAll("\\s+", ""))
                .contains("\"chunkVersions\":{\"7\":3}")
                .doesNotContain("\"empty\"");

        Conversation reloaded = service.requireOwned(conversation.id());
        assertThat(reloaded.title()).isEqualTo("2025 年 营业总收入是多少");
    }

    @Test
    @DisplayName("标题已改过就不再被后续问题覆盖")
    void explicitTitleIsNotOverwritten() {
        Conversation conversation = create("test-conv-titled", ChatScope.corpus());
        ChatResponse response = new ChatResponse(false, "证据不足", List.of(), List.of(), "LOW",
                null, null, List.of(), null, null);

        service.recordTurn(conversation, "随便问问", response, ResolvedScope.of(ScopeKind.CORPUS, List.of(),
                ResolvedScope.REASON_SESSION, List.of()));

        assertThat(service.requireOwned(conversation.id()).title()).isEqualTo("test-conv-titled");
    }

    @Test
    @DisplayName("删除后列表里没有了")
    void deleteHidesConversation() {
        Conversation conversation = create("test-conv-del", ChatScope.corpus());

        service.delete(conversation.id());

        assertThatThrownBy(() -> service.requireOwned(conversation.id()))
                .isInstanceOf(ConversationNotFoundException.class);
        assertThat(service.list(50)).noneMatch(c -> c.id() == conversation.id());
    }

    @Test
    @DisplayName("改名：标题立即生效，且不会被下一轮问题覆盖")
    void renameUpdatesTitleAndSurvivesNextTurn() {
        Conversation conversation = create(ConversationService.DEFAULT_TITLE, ChatScope.corpus());

        Conversation renamed = service.rename(conversation.id(), "  东方财富 2025 年报  ");

        assertThat(renamed.title()).isEqualTo("东方财富 2025 年报");
        ChatResponse response = new ChatResponse(false, "证据不足", List.of(), List.of(), "LOW",
                null, null, List.of(), null, null);
        service.recordTurn(renamed, "随便问问", response, ResolvedScope.of(ScopeKind.CORPUS, List.of(),
                ResolvedScope.REASON_SESSION, List.of()));

        assertThat(service.requireOwned(conversation.id()).title()).isEqualTo("东方财富 2025 年报");
    }

    @Test
    @DisplayName("改名超 60 字被截断（与新建会话同一口径）")
    void renameTruncatesToSixty() {
        Conversation conversation = create("test-conv-long", ChatScope.corpus());
        String longTitle = "长".repeat(80);

        Conversation renamed = service.rename(conversation.id(), longTitle);

        assertThat(renamed.title()).hasSize(60);
    }

    @Test
    @DisplayName("改名传空 → IllegalArgumentException（映射成 400）")
    void renameRejectsBlankTitle() {
        Conversation conversation = create("test-conv-blank", ChatScope.corpus());

        assertThatThrownBy(() -> service.rename(conversation.id(), "   "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("标题不能为空");
    }

    @Test
    @DisplayName("改别人的会话 → 404")
    void renameOtherUsersConversationIs404() {
        TenantContext.set(ADMIN);
        Conversation admins = create("test-conv-admin-rename", ChatScope.corpus());

        TenantContext.set(ANALYST);
        assertThatThrownBy(() -> service.rename(admins.id(), "抢改"))
                .isInstanceOf(ConversationNotFoundException.class);
    }

    @Test
    @DisplayName("请求带 scope 用它，不带用会话配置")
    void effectiveScopePrefersRequest() {
        Conversation conversation = create("test-conv-scope", ChatScope.documents(List.of(7L)));

        assertThat(service.effectiveScope(null, conversation).documentIds()).containsExactly(7L);
        assertThat(service.effectiveScope(ChatScope.documents(List.of(38L)), conversation)
                .documentIds()).containsExactly(38L);
        assertThat(service.effectiveScope(null, null)).isNull();
    }

    /** 建会话并登记 id，供 @AfterEach 按主键清理。 */
    private Conversation create(String title, ChatScope scope) {
        Conversation saved = service.create(title, scope);
        created.add(saved.id());
        return saved;
    }
}
