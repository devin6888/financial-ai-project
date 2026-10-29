package com.finagent.memory;

import com.finagent.scope.ChatScope;
import com.finagent.scope.DocMeta;
import com.finagent.scope.ResolvedScope;
import com.finagent.scope.ScopeKind;
import com.finagent.security.CurrentUser;
import com.finagent.security.TenantContext;
import com.finagent.understanding.QueryType;
import com.finagent.understanding.QueryUnderstanding;
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
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** 结构化上下文：按轮合并、用户纠错、审计事件。 */
@Tag("db")
@SpringBootTest
@ActiveProfiles("test")
class ConversationContextServiceTest {

    private static final CurrentUser ANALYST =
            new CurrentUser(1L, 1L, "analyst", "分析师", Set.of("ANALYST"));

    @Autowired
    private ConversationContextService service;

    @Autowired
    private ConversationService conversations;

    @Autowired
    private ConversationContextRepository repository;

    @Autowired
    private JdbcTemplate jdbc;

    private final List<Long> created = new ArrayList<>();

    @BeforeEach
    void setUp() {
        TenantContext.set(ANALYST);
        created.add(conversations.create("test-ctx-svc", ChatScope.corpus()).id());
        created.add(conversations.create("test-ctx-svc2", ChatScope.corpus()).id());
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
        created.forEach(id -> {
            jdbc.update("DELETE FROM memory_event WHERE target_id = ?", id);
            ConversationCleanup.delete(jdbc, java.util.List.of(id));
        });
    }

    @Test
    @DisplayName("一轮问答复：公司 / 年份 / 文档 / 指标都落库，并写一条审计")
    void applyTurnPersistsFields() {
        Conversation conversation = conversation(0);
        QueryUnderstanding qu = qu(List.of("300059"), 2025, 2025, List.of("营业总收入"));

        service.applyTurn(conversation, qu, scope(List.of(document(7L, 7L, "300059", "东方财富"))));

        ConversationContext saved = repository.find(conversation.id()).orElseThrow();
        assertThat(saved.companyId()).isEqualTo(7L);
        assertThat(saved.companyName()).isEqualTo("东方财富");
        assertThat(saved.yearFrom()).isEqualTo(2025);
        assertThat(saved.yearTo()).isEqualTo(2025);
        assertThat(saved.documentIds()).containsExactly(7L);
        assertThat(saved.currentMetric()).isEqualTo("营业总收入");
        assertThat(events(conversation.id())).isEqualTo(1);
    }

    @Test
    @DisplayName("重复同样的一轮 → 不再写事件（值没变就不该说改了）")
    void unchangedTurnWritesNoEvent() {
        Conversation conversation = conversation(0);
        QueryUnderstanding qu = qu(List.of("300059"), 2025, 2025, List.of("营业总收入"));
        ResolvedScope scope = scope(List.of(document(7L, 7L, "300059", "东方财富")));

        service.applyTurn(conversation, qu, scope);
        service.applyTurn(conversation, qu, scope);

        assertThat(events(conversation.id())).isEqualTo(1);
    }

    @Test
    @DisplayName("作用域外的公司不写进上下文（随口提到的公司不算）")
    void companyOutsideScopeIsIgnored() {
        Conversation conversation = conversation(0);
        QueryUnderstanding qu = qu(List.of("000858"), 2024, 2024, List.of());

        service.applyTurn(conversation, qu, scope(List.of(document(7L, 7L, "300059", "东方财富"))));

        ConversationContext saved = repository.find(conversation.id()).orElseThrow();
        assertThat(saved.companyId()).isNull();
        assertThat(saved.yearFrom()).isEqualTo(2024);
    }

    @Test
    @DisplayName("CORPUS 作用域不写 documentIds（全库不是「选了哪几份」）")
    void corpusScopeDoesNotRecordDocuments() {
        Conversation conversation = conversation(0);
        ResolvedScope corpus = ResolvedScope.of(ScopeKind.CORPUS, List.of(),
                ResolvedScope.REASON_SESSION, List.of());

        service.applyTurn(conversation, qu(List.of("300059"), 2025, 2025, List.of()),
                ResolvedScope.of(ScopeKind.CORPUS,
                        List.of(document(7L, 7L, "300059", "东方财富")),
                        ResolvedScope.REASON_SESSION, List.of()));

        assertThat(repository.find(conversation.id()).orElseThrow().documentIds()).isEmpty();
        assertThat(corpus.isEmpty()).isTrue();
    }

    @Test
    @DisplayName("用户纠错：clear=true 先清空，只留本体给的字段")
    void patchClearsThenRebuilds() {
        Conversation conversation = conversation(0);
        service.applyTurn(conversation, qu(List.of("300059"), 2025, 2025, List.of("营业总收入")),
                scope(List.of(document(7L, 7L, "300059", "东方财富"))));

        service.patch(conversation, new ContextPatch(2L, null, null, List.of(), null, true));

        ConversationContext saved = repository.find(conversation.id()).orElseThrow();
        assertThat(saved.companyId()).isEqualTo(2L);
        assertThat(saved.yearFrom()).isNull();
        assertThat(saved.documentIds()).isEmpty();
        assertThat(saved.currentMetric()).isNull();
        assertThat(events(conversation.id())).isEqualTo(2);
    }

    @Test
    @DisplayName("用户纠错：不 clear 时是逐字段覆盖")
    void patchWithoutClearKeepsOthers() {
        Conversation conversation = conversation(0);
        service.applyTurn(conversation, qu(List.of("300059"), 2025, 2025, List.of("营业总收入")),
                scope(List.of(document(7L, 7L, "300059", "东方财富"))));

        service.patch(conversation, new ContextPatch(null, null, null, List.of(), "净利润", false));

        ConversationContext saved = repository.find(conversation.id()).orElseThrow();
        assertThat(saved.companyId()).isEqualTo(7L);
        assertThat(saved.yearFrom()).isEqualTo(2025);
        assertThat(saved.currentMetric()).isEqualTo("净利润");
    }

    private Conversation conversation(int index) {
        return conversations.requireOwned(created.get(index));
    }

    private int events(long conversationId) {
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM memory_event WHERE target_type = 'CONVERSATION_CONTEXT' AND target_id = ?",
                Integer.class, conversationId);
        return count == null ? 0 : count;
    }

    private static QueryUnderstanding qu(List<String> companies, Integer from, Integer to,
                                         List<String> metrics) {
        return new QueryUnderstanding(QueryType.EXACT_FACT, "q", companies,
                new int[]{from, to}, metrics, false, false, null, false, "LLM");
    }

    private static ResolvedScope scope(List<DocMeta> docs) {
        return ResolvedScope.of(ScopeKind.DOCUMENTS, docs, ResolvedScope.REASON_SESSION, List.of());
    }

    private static DocMeta document(long id, Long companyId, String code, String name) {
        return new DocMeta(id, companyId, code, name, 2025, "ANNUAL", null, null, 7, name + "年报");
    }
}
