package com.finagent.agent;

import com.finagent.chat.ChatRequest;
import com.finagent.chat.ChatStreamEvent;
import com.finagent.scope.ChatScope;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/** 事件翻译层的单测：不启 Spring、不碰数据库，只验「状态 → C15/C16 事件」的形状与顺序。 */
@ExtendWith(MockitoExtension.class)
class AgentStreamServiceTest {

    @Mock
    private AgentRunService runs;

    @Mock
    private com.finagent.memory.ConversationService conversations;

    @Mock
    private com.finagent.memory.MemoryAssembler memoryAssembler;

    @Mock
    private com.finagent.chat.TurnRecorder turnRecorder;

    @Mock
    private com.finagent.chat.ChatTraceWriter traceWriter;

    @Mock
    private AmbiguityDetector ambiguity;

    @Mock
    private com.finagent.memory.ConversationContextService conversationContexts;

    private AnswerStreamBridge bridge;
    private AgentStreamService service;
    private final List<ChatStreamEvent> events = new ArrayList<>();

    @BeforeEach
    void setUp() {
        bridge = new AnswerStreamBridge();
        // resume 用例不经过歧义判定 → 用 lenient，免得被 StrictStubs 判成多余桩
        org.mockito.Mockito.lenient()
                .when(ambiguity.detect(any(), anyString()))
                .thenReturn(AmbiguityDetector.Decision.confident());
        service = new AgentStreamService(runs, bridge, conversations, memoryAssembler, turnRecorder,
                traceWriter, ambiguity, conversationContexts);
    }

    private static AnalysisState state(Map<String, Object> extra) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put(AnalysisState.QUESTION, "复杂问题");
        data.putAll(extra);
        return new AnalysisState(data);
    }

    private static AgentRunService.RunView view(UUID runId, String status, String answer) {
        return new AgentRunService.RunView(runId, status, "approve", "复杂问题",
                List.of("理解问题", "拆成 2 个子问题"), null, null, null,
                answer, List.of(), List.of(Map.of("chunkId", 101, "documentId", 7, "page", 61)),
                List.of());
    }


    private static AgentRunService.RunView rejectedView(UUID runId, List<String> warnings) {
        return new AgentRunService.RunView(runId, AgentRun.STATUS_REJECTED, "respond_insufficient", "复杂问题",
                List.of("理解问题"), null, null, null,
                "无法回答：知识库中没有当前账号可检索的文档。", warnings, List.of(), List.of());
    }

    @Test
    @DisplayName("作用域为空被拒答 → 发 error(SCOPE_EMPTY)，不再发 final（与 chat 的 409 对齐）")
    void rejectedWithScopeEmptyEmitsErrorEvent() {
        UUID runId = UUID.randomUUID();
        when(runs.create(eq("复杂问题"), any(), any())).thenReturn(runId);
        when(runs.execute(eq(runId), anyString(), any(), any(), anyString(), anyBoolean(), any(AgentProgress.class)))
                .thenReturn(new AgentRunService.Result(runId, AgentRun.STATUS_REJECTED,
                        "无法回答：知识库中没有当前账号可检索的文档。", List.of("scope_empty"), List.of(),
                        "respond_insufficient"));
        when(runs.view(runId)).thenReturn(rejectedView(runId, List.of("scope_empty")));

        service.startStream(request(), events::add);

        // 开跑前先发一条 understand（首屏），随后直接 error —— 空作用域没有可检索的东西
        assertThat(events).extracting(ChatStreamEvent::event).containsExactly("stage", "error");
        assertThat(events.get(1).data().toString()).contains("SCOPE_EMPTY").contains("可检索");
    }

    @Test
    @DisplayName("开跑：四个节点的 stage（phase 就是节点 id）+ 一条 approval")
    void startEmitsStagesThenApproval() {
        UUID runId = UUID.randomUUID();
        when(runs.create(eq("复杂问题"), any(), any())).thenReturn(runId);
        when(runs.execute(eq(runId), anyString(), any(), any(), anyString(), anyBoolean(), any(AgentProgress.class)))
                .thenAnswer(inv -> {
                    AgentProgress progress = inv.getArgument(6);
                    progress.onNode("understand", state(Map.of(AnalysisState.QUERY_TYPE, "COMPLEX_ANALYSIS")));
                    progress.onNode("plan", state(Map.of(AnalysisState.SUB_QUESTIONS, List.of("a", "b"))));
                    progress.onNode("retrieve", state(Map.of(AnalysisState.EVIDENCE_COUNT, 6)));
                    progress.onNode("verify", state(Map.of(AnalysisState.INSUFFICIENT, false)));
                    return new AgentRunService.Result(runId, AgentRun.STATUS_WAITING_APPROVAL, "",
                            List.of(), List.of(), "approve");
                });
        when(runs.view(runId)).thenReturn(view(runId, AgentRun.STATUS_WAITING_APPROVAL, ""));

        service.startStream(request(), events::add);

        // verify 之后发 meta（证据先行），approval 最后
        assertThat(events).extracting(ChatStreamEvent::event)
                .containsExactly("stage", "stage", "stage", "stage", "meta", "approval");
        assertThat(phases()).containsExactly("understand", "plan", "retrieve", "verify");
        assertThat(events.get(1).data().toString()).contains("DECOMPOSE").contains("subQuestions");
        assertThat(events.get(4).data().toString()).contains("evidence");
        assertThat(events.get(5).data().toString()).contains("runId").contains("拆成 2 个子问题");
    }

    @Test
    @DisplayName("恢复：answer → cite_check 两个 stage，delta 被转发，最后一条是 final")
    void resumeForwardsDeltasThenFinal() {
        UUID runId = UUID.randomUUID();
        when(runs.resume(eq(runId), eq("APPROVE"), any(), any(AgentProgress.class)))
                .thenAnswer(inv -> {
                    AgentProgress progress = inv.getArgument(3);
                    bridge.delta(runId.toString(), "东方财富");
                    progress.onNode("answer", state(Map.of(AnalysisState.ANSWER, "东方财富")));
                    progress.onNode("cite_check", state(Map.of(AnalysisState.CITATION_COUNT, 4)));
                    bridge.delta(runId.toString(), " 答案");
                    return new AgentRunService.Result(runId, AgentRun.STATUS_DONE, "东方财富 答案",
                            List.of("ok"), List.of(), "cite_check");
                });
        when(runs.view(runId)).thenReturn(view(runId, AgentRun.STATUS_DONE, "东方财富 答案"));

        service.resumeStream(runId, "APPROVE", null, events::add);

        assertThat(events).extracting(ChatStreamEvent::event)
                .containsExactly("delta", "stage", "stage", "delta", "final");
        assertThat(events.get(0).data().toString()).contains("东方财富");
        assertThat(phases()).containsExactly("answer", "cite_check");
        assertThat(events.get(4).data().toString()).contains("DONE").contains("东方财富 答案");
        assertThat(bridge.isBound(runId.toString())).isFalse();
    }

    @Test
    @DisplayName("P2：final 里嵌一份标准 ChatResponse —— facts / citations / trace 都来自 answer 节点")
    void finalEmbedsChatResponseFromAnswerResult() {
        UUID runId = UUID.randomUUID();
        when(runs.create(anyString(), any(), any())).thenReturn(runId);
        com.finagent.answer.AnswerResult result = new com.finagent.answer.AnswerResult(true,
                "营业总收入 16,067,741,039.51 元 [E1]",
                List.of(new com.finagent.answer.AnswerResult.Fact("营业总收入", "2025",
                        "16,067,741,039.51", "元", "合并", "PDF", "PDF")),
                List.of(new com.finagent.citation.Citation(7L, 100L, 101L, 61, 61, null, null,
                        "TABLE", null, null, "东方财富2025年年度报告", "第二节")),
                "high", null, List.of(), 2);
        when(runs.execute(eq(runId), anyString(), any(), any(), anyString(), anyBoolean(), any(AgentProgress.class)))
                .thenAnswer(inv -> {
                    bridge.result(runId.toString(), result);
                    return new AgentRunService.Result(runId, AgentRun.STATUS_DONE,
                            "营业总收入 16,067,741,039.51 元 [E1]", List.of(), List.of(), "cite_check");
                });
        when(runs.view(runId)).thenReturn(view(runId, AgentRun.STATUS_DONE,
                "营业总收入 16,067,741,039.51 元 [E1]"));
        when(turnRecorder.record(any(), anyString(), any(), any())).thenAnswer(inv -> inv.getArgument(2));

        service.startStream(request(), events::add);

        ChatStreamEvent last = events.get(events.size() - 1);
        assertThat(last.event()).isEqualTo("final");
        com.finagent.chat.ChatResponse response =
                (com.finagent.chat.ChatResponse) ((Map<?, ?>) last.data()).get("response");
        assertThat(response).isNotNull();
        assertThat(response.facts()).hasSize(1);
        assertThat(response.citations()).hasSize(1);
        assertThat(response.confidence()).isEqualTo("high");
        assertThat(response.trace()).isNotNull();
        assertThat(response.trace().traceId()).isEqualTo(runId.toString());
        assertThat(response.trace().citationsDropped()).isEqualTo(2);
        org.mockito.Mockito.verify(traceWriter).write(response.trace());
    }

    private List<String> phases() {
        return events.stream()
                .filter(e -> ChatStreamEvent.STAGE.equals(e.event()))
                .map(e -> String.valueOf(((Map<?, ?>) e.data()).get("phase")))
                .toList();
    }

    private static ChatRequest request() {
        return new ChatRequest("复杂问题", ChatScope.corpus(), null, null);
    }
}
