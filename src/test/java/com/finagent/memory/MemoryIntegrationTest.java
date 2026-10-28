package com.finagent.memory;

import com.finagent.answer.AnswerPrompts;
import com.finagent.chat.ChatResponse;
import com.finagent.context.ContextPackage;
import com.finagent.scope.ChatScope;
import com.finagent.scope.ResolvedScope;
import com.finagent.scope.ScopeKind;
import com.finagent.security.CurrentUser;
import com.finagent.security.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 任务 3 的验收集成测试：真库写两轮，第二轮 Prompt 里要出现第一轮的内容。
 *
 * <p>为什么不用 ChatService 跑：那会真调模型（慢、花钱、结果不可断言）。
 * 这里测的是「落库 → 窗口 → 渲染 → 进 Prompt」这条链，正是记忆层自己的职责；
 * 编排是否把 memory 传下去由 ChatServiceTest 的 verify 覆盖。
 */
@SpringBootTest(properties = "finagent.memory.enabled=true")
@ActiveProfiles("test")
class MemoryIntegrationTest {

    private static final CurrentUser ANALYST =
            new CurrentUser(1L, 1L, "analyst", "分析师", Set.of("ANALYST"));

    @Autowired
    private ConversationService conversations;

    @Autowired
    private MemoryAssembler assembler;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        TenantContext.set(ANALYST);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
        jdbc.update("DELETE FROM conversation WHERE title LIKE 'test-mem-%'");
    }

    @Test
    @DisplayName("第二轮 Prompt 含第一轮问答，且背景段与证据段互不混入")
    void secondTurnSeesFirstTurn() {
        Conversation conversation = conversations.create("test-mem-1", ChatScope.corpus());
        ResolvedScope scope = ResolvedScope.of(ScopeKind.CORPUS, List.of(),
                ResolvedScope.REASON_SESSION, List.of());
        ChatResponse first = new ChatResponse(true, "2025 年营业总收入 120.8 亿元 [E1]",
                List.of(), List.of(), "HIGH", null, null, List.of(), null, null);
        conversations.recordTurn(conversation, "2025 年营业总收入是多少？", first, scope);

        MemoryContext memory = assembler.assembleMemory(conversation);
        assertThat(memory.isEmpty()).isFalse();
        assertThat(memory.stats().shortTermMessages()).isEqualTo(2);

        String prompt = AnswerPrompts.user("那净利润呢？", ContextPackage.empty(), memory.text());
        assertThat(prompt).contains("2025 年营业总收入是多少？").contains("120.8 亿元");
        assertThat(prompt.indexOf("【背景信息")).isLessThan(prompt.indexOf("【证据】"));
        // 记忆只进 Prompt，不进证据包：引用编号的来源仍然只有 ContextPackage.blocks()
        assertThat(ContextPackage.empty().text()).doesNotContain("2025 年营业总收入是多少？");
    }

    @Test
    @DisplayName("没有消息的新会话 → 没有背景段（不产生只有标题的空段落）")
    void emptyConversationHasNoBackground() {
        Conversation conversation = conversations.create("test-mem-empty", ChatScope.corpus());

        assertThat(assembler.assembleMemory(conversation)).isEqualTo(MemoryContext.EMPTY);
    }
}
