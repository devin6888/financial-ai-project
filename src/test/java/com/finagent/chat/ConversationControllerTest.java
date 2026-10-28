package com.finagent.chat;

import com.finagent.memory.Conversation;
import com.finagent.memory.ConversationContext;
import com.finagent.memory.ConversationContextService;
import com.finagent.memory.ConversationMessage;
import com.finagent.memory.ConversationNotFoundException;
import com.finagent.memory.ConversationService;
import com.finagent.scope.ChatScope;
import com.finagent.scope.ScopeKind;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.json.JsonMapper;

import java.time.OffsetDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 会话端点：路由 / 状态码 / JSON 形状。业务在 ConversationServiceTest 里测。 */
class ConversationControllerTest {

    private MockMvc mvc;
    private ConversationService conversations;
    private ConversationContextService contexts;

    @BeforeEach
    void setUp() {
        conversations = mock(ConversationService.class);
        contexts = mock(ConversationContextService.class);
        mvc = MockMvcBuilders
                .standaloneSetup(new ConversationController(conversations, contexts,
                        JsonMapper.builder().build()))
                .build();
    }

    @Test
    @DisplayName("建会话 → 201 + conversationId")
    void createReturns201() throws Exception {
        when(conversations.create(any(), any())).thenReturn(conversation(11L, "新会话"));

        mvc.perform(post("/api/conversations").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"新会话\",\"scope\":{\"kind\":\"DOCUMENTS\",\"documentIds\":[7]}}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.conversationId").value(11))
                .andExpect(jsonPath("$.scope.kind").value("DOCUMENTS"))
                .andExpect(jsonPath("$.scope.documentIds[0]").value(7));
    }

    @Test
    @DisplayName("会话列表 → 200 + 数组")
    void listReturnsArray() throws Exception {
        when(conversations.list(20)).thenReturn(List.of(conversation(11L, "a"), conversation(12L, "b")));

        mvc.perform(get("/api/conversations"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].conversationId").value(11))
                .andExpect(jsonPath("$[1].title").value("b"));
    }

    @Test
    @DisplayName("消息最新在前，nextBeforeId 是最后一条的 id（翻页用）")
    void messagesAreNewestFirst() throws Exception {
        when(conversations.messages(11L, null, 50)).thenReturn(List.of(
                new ConversationMessage(102L, 11L, "ASSISTANT", "答案 [E1]", "[{\"childChunkId\":7}]",
                        "{}", "trace-1", 12, OffsetDateTime.parse("2026-10-27T12:00:02+08:00")),
                new ConversationMessage(101L, 11L, "USER", "问题", null,
                        "{}", "trace-1", 5, OffsetDateTime.parse("2026-10-27T12:00:01+08:00"))));

        mvc.perform(get("/api/conversations/11/messages"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.conversationId").value(11))
                .andExpect(jsonPath("$.messages[0].role").value("ASSISTANT"))
                .andExpect(jsonPath("$.messages[0].citations[0].childChunkId").value(7))
                .andExpect(jsonPath("$.messages[1].role").value("USER"))
                .andExpect(jsonPath("$.nextBeforeId").value(101));
    }

    @Test
    @DisplayName("别人的会话 → 404，且不透露是否存在")
    void unknownConversationIs404() throws Exception {
        when(conversations.requireOwned(99L)).thenThrow(new ConversationNotFoundException(99L));

        mvc.perform(get("/api/conversations/99"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("CONVERSATION_NOT_FOUND"));
    }

    @Test
    @DisplayName("删除 → 204，调用逻辑删除")
    void deleteReturns204() throws Exception {
        mvc.perform(delete("/api/conversations/11"))
                .andExpect(status().isNoContent());

        verify(conversations).delete(11L);
    }

    @Test
    @DisplayName("读上下文 → 200，公司名称与年份都在")
    void contextIsReturned() throws Exception {
        when(conversations.requireOwned(11L)).thenReturn(conversation(11L, "a"));
        when(contexts.find(11L)).thenReturn(java.util.Optional.of(context()));

        mvc.perform(get("/api/conversations/11/context"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.conversationId").value(11))
                .andExpect(jsonPath("$.companyCode").value("300059"))
                .andExpect(jsonPath("$.companyName").value("东方财富"))
                .andExpect(jsonPath("$.yearFrom").value(2025))
                .andExpect(jsonPath("$.documentIds[0]").value(7))
                .andExpect(jsonPath("$.currentMetric").value("营业总收入"));
    }

    @Test
    @DisplayName("还没聊过 → 200 + 空视图（不是 404）")
    void emptyContextIsOk() throws Exception {
        when(conversations.requireOwned(11L)).thenReturn(conversation(11L, "a"));
        when(contexts.find(11L)).thenReturn(java.util.Optional.empty());

        mvc.perform(get("/api/conversations/11/context"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.conversationId").value(11))
                .andExpect(jsonPath("$.companyId").doesNotExist())
                .andExpect(jsonPath("$.documentIds").isEmpty());
    }

    @Test
    @DisplayName("PATCH 上下文 → 200，clear 透传给服务")
    void patchContextDelegates() throws Exception {
        Conversation session = conversation(11L, "a");
        when(conversations.requireOwned(11L)).thenReturn(session);
        when(contexts.patch(any(), any())).thenReturn(context());

        mvc.perform(patch("/api/conversations/11/context").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"companyId\":7,\"currentMetric\":\"净利润\",\"clear\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.companyId").value(2));

        verify(contexts).patch(session,
                new com.finagent.memory.ContextPatch(7L, null, null, java.util.List.of(), "净利润", true));
    }

    @Test
    @DisplayName("别人的会话 PATCH → 404")
    void patchUnknownConversationIs404() throws Exception {
        when(conversations.requireOwned(99L)).thenThrow(new ConversationNotFoundException(99L));

        mvc.perform(patch("/api/conversations/99/context").contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isNotFound());
    }

    private static ConversationContext context() {
        return new ConversationContext(11L, 1L, 2L, "300059", "东方财富", 2025, 2025,
                List.of(7L), "营业总收入", "{}", OffsetDateTime.parse("2026-10-27T12:00:00+08:00"));
    }

    private static Conversation conversation(long id, String title) {
        return new Conversation(id, 1L, 1L, title, ScopeKind.DOCUMENTS,
                ChatScope.documents(List.of(7L)), Conversation.STATUS_ACTIVE,
                OffsetDateTime.parse("2026-10-27T12:00:00+08:00"),
                OffsetDateTime.parse("2026-10-27T12:00:00+08:00"));
    }
}
