package com.finagent.chat;

import com.finagent.answer.AnswerResult;
import com.finagent.citation.BBox;
import com.finagent.citation.Citation;
import com.finagent.document.DocumentRepository;
import com.finagent.retrieval.RerankStatus;
import com.finagent.retrieval.RetrievalTrace;
import com.finagent.scope.ScopeEmptyException;
import com.finagent.scope.ScopeKind;
import com.finagent.scope.SelectableDocument;
import com.finagent.understanding.QueryType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HTTP 层：状态码与错误码。业务细节在 ChatServiceTest 里测。
 *
 * <p>用 standaloneSetup 而不是 @WebMvcTest：Spring Boot 4 把测试切片拆到了独立模块，
 * 本项目没有引入那个模块。standalone 只验路由 / 绑定 / 状态码，正好是这层要验的东西。
 */
class ChatControllerTest {

    private MockMvc mvc;
    private ChatService chatService;
    private DocumentRepository documents;

    @BeforeEach
    void setUp() {
        chatService = mock(ChatService.class);
        documents = mock(DocumentRepository.class);
        mvc = MockMvcBuilders.standaloneSetup(new ChatController(chatService, documents)).build();
    }

    @Test
    @DisplayName("正常问答 → 200，带引用与 traceId")
    void chatReturnsAnswer() throws Exception {
        when(chatService.ask(any())).thenReturn(response());

        mvc.perform(post("/api/chat").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"2024年营业总收入是多少\",\"scope\":{\"kind\":\"DOCUMENTS\",\"documentIds\":[38]}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.answer").value("2024年营业总收入 12,084,594,947.22 元 [E1]"))
                .andExpect(jsonPath("$.citations[0].childChunkId").value(1610))
                .andExpect(jsonPath("$.trace.traceId").value("trace-1"))
                .andExpect(jsonPath("$.scope.resolvedDocumentIds[0]").value(38));
    }

    @Test
    @DisplayName("作用域解析出 0 份 → 409 + SCOPE_EMPTY（不是 200 空答案）")
    void emptyScopeIsConflict() throws Exception {
        when(chatService.ask(any()))
                .thenThrow(new ScopeEmptyException("范围内没有可检索的文档", List.of("scope_not_retrievable_99_FAILED")));

        mvc.perform(post("/api/chat").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"q\",\"scope\":{\"kind\":\"DOCUMENTS\",\"documentIds\":[99]}}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("SCOPE_EMPTY"))
                .andExpect(jsonPath("$.warnings[0]").value("scope_not_retrievable_99_FAILED"));
    }

    @Test
    @DisplayName("question 为空 → 400，且不进 service")
    void blankQuestionIsBadRequest() throws Exception {
        mvc.perform(post("/api/chat").contentType(MediaType.APPLICATION_JSON).content("{\"question\":\"   \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("BAD_REQUEST"));

        verify(chatService, never()).ask(any());
    }

    @Test
    @DisplayName("GET /api/scope/documents → 文档列表（含 chunkCount）")
    void listsSelectableDocuments() throws Exception {
        when(documents.findSelectableDocuments()).thenReturn(List.of(
                new SelectableDocument(38, "东方财富2024年年度报告", "300059", "东方财富", 2024, "ANNUAL", 177, 630)));

        mvc.perform(get("/api/scope/documents"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(38))
                .andExpect(jsonPath("$[0].docYear").value(2024))
                .andExpect(jsonPath("$[0].chunkCount").value(630));
    }

    @Test
    @DisplayName("请求体缺失 → 400")
    void missingBodyIsBadRequest() throws Exception {
        mvc.perform(post("/api/chat").contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest());
    }

    // ------------------------------------------------------------ fixtures

    private static ChatResponse response() {
        Citation citation = new Citation(38L, 512L, 1610L, 47, 47,
                List.of(new BBox(47, 0.1, 0.41, 0.81, 0.09)), null, Citation.TYPE_TABLE, "元", "合并",
                "东方财富2024年年度报告", "第二节 > 六、主要会计数据");
        RetrievalTrace retrieval = new RetrievalTrace("q", QueryType.EXACT_FACT, "LLM", false,
                30, 12, 40, 8, RerankStatus.APPLIED, "qwen3-rerank", 180, 4, 1, 260, List.of());
        ChatTrace trace = new ChatTrace("trace-1", retrieval,
                new ScopeTrace(ScopeKind.DOCUMENTS, List.of(38L), "scope-v1", "SESSION", false),
                3, 1840, 1, 0, 41, 4200, List.of());
        return new ChatResponse(true, "2024年营业总收入 12,084,594,947.22 元 [E1]",
                List.of(new AnswerResult.Fact("营业总收入", "2024", "12,084,594,947.22", "元", "合并", "E1", "PDF")),
                List.of(citation), "high", null,
                new ScopeInfo(new ScopeInfo.SessionScope(ScopeKind.DOCUMENTS, List.of(38L)),
                        new ScopeInfo.EffectiveScope(ScopeKind.DOCUMENTS, List.of(38L), "SESSION"),
                        List.of(38L), "scope-v1"),
                List.of(), trace, null);
    }
}