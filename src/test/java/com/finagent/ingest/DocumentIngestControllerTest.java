package com.finagent.ingest;

import com.finagent.document.DocumentProgress;
import com.finagent.document.DocumentRepository;
import com.finagent.scope.ScopeAuthorizer;
import com.finagent.task.DocumentTaskProducer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.json.JsonMapper;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 执行记录两个端点的 HTTP 层测试（阶段 19）。
 *
 * <p>重点不是「200 了」，而是三种返回形状：<b>NONE</b>（老文档）、<b>RUNNING</b>（完整模板 +
 * 未执行步骤补 PENDING）、<b>DONE</b>（只回摘要）—— 前端靠它决定画什么，错了页面就空白。
 */
class DocumentIngestControllerTest {

    private IngestTracker tracker;
    private DocumentRepository documents;
    private DocumentTaskProducer producer;
    private ScopeAuthorizer authorizer;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        tracker = mock(IngestTracker.class);
        documents = mock(DocumentRepository.class);
        producer = mock(DocumentTaskProducer.class);
        authorizer = mock(ScopeAuthorizer.class);
        when(authorizer.filterReadable(any())).thenReturn(List.of(42L));
        mvc = MockMvcBuilders.standaloneSetup(new DocumentIngestController(
                tracker, documents, producer, authorizer,
                mock(DocumentProgress.class), JsonMapper.builder().build())).build();
    }

    private static DocumentRepository.ParseState state(String parseStatus) {
        return new DocumentRepository.ParseState(42L, parseStatus, "parsed/x/normalized.json",
                "docling", "2.138.0", 181, "boom", "东方财富2025年年度报告");
    }

    private static IngestRun run(String status, int currentStep) {
        return new IngestRun(5L, 42L, 1, "PARSE", 8, currentStep, status, "{\"chunks\": 654}",
                null, OffsetDateTime.parse("2026-10-31T10:00:00+08:00"),
                "DONE".equals(status) ? OffsetDateTime.parse("2026-10-31T10:03:20+08:00") : null);
    }

    @Test
    @DisplayName("文档不存在 → 404")
    void missingDocumentIs404() throws Exception {
        when(documents.findParseState(42L)).thenThrow(new RuntimeException("empty"));

        mvc.perform(get("/api/documents/42/ingest")).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("不可读的文档 → 404（不泄漏存在性）")
    void unreadableDocumentIs404() throws Exception {
        when(documents.findParseState(42L)).thenReturn(state("DONE"));
        when(authorizer.filterReadable(any())).thenReturn(List.of());

        mvc.perform(get("/api/documents/42/ingest")).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("没有执行记录的存量文档 → state=NONE，前端退回按 document 状态展示")
    void noRunReturnsNone() throws Exception {
        when(documents.findParseState(42L)).thenReturn(state("DONE"));
        when(tracker.latest(42L)).thenReturn(Optional.empty());

        mvc.perform(get("/api/documents/42/ingest"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("NONE"))
                .andExpect(jsonPath("$.steps").isEmpty());
    }

    @Test
    @DisplayName("RUNNING → 回完整 8 步模板，还没走到的步骤是 PENDING")
    void runningReturnsFullTemplate() throws Exception {
        when(documents.findParseState(42L)).thenReturn(state("DONE"));
        when(tracker.latest(42L)).thenReturn(Optional.of(run("RUNNING", 3)));
        when(tracker.steps(5L)).thenReturn(List.of(
                new IngestStepRow(1, "RECEIVED", "OK", null, 0,
                        OffsetDateTime.now(), OffsetDateTime.now(), 12),
                new IngestStepRow(2, "SUBMITTED", "OK", "job=abc", 0,
                        OffsetDateTime.now(), OffsetDateTime.now(), 30),
                new IngestStepRow(3, "PARSING", "RUNNING", "解析中 · 已用 1m30s", 0,
                        OffsetDateTime.now(), null, null)));

        mvc.perform(get("/api/documents/42/ingest"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("RUNNING"))
                .andExpect(jsonPath("$.currentStep").value(3))
                .andExpect(jsonPath("$.totalSteps").value(8))
                .andExpect(jsonPath("$.steps.length()").value(8))
                .andExpect(jsonPath("$.steps[1].detail").value("job=abc"))
                .andExpect(jsonPath("$.steps[2].status").value("RUNNING"))
                .andExpect(jsonPath("$.steps[2].label").value("解析中"))
                .andExpect(jsonPath("$.steps[7].status").value("PENDING"))
                .andExpect(jsonPath("$.steps[7].label").value("切换 ACTIVE"));
    }

    @Test
    @DisplayName("DONE → 只回摘要，steps 为空（已完成的任务不看中间步骤）")
    void doneReturnsSummaryOnly() throws Exception {
        when(documents.findParseState(42L)).thenReturn(state("DONE"));
        when(tracker.latest(42L)).thenReturn(Optional.of(run("DONE", 8)));
        when(tracker.steps(5L)).thenReturn(List.of(
                new IngestStepRow(3, "PARSING", "OK", null, 0,
                        OffsetDateTime.now(), OffsetDateTime.now(), 180_000),
                new IngestStepRow(7, "EMBEDDING", "OK", null, 0,
                        OffsetDateTime.now(), OffsetDateTime.now(), 6_000)));

        mvc.perform(get("/api/documents/42/ingest"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("DONE"))
                .andExpect(jsonPath("$.steps").isEmpty())
                .andExpect(jsonPath("$.summary.chunks").value(654))
                .andExpect(jsonPath("$.summary.pages").value(181))
                .andExpect(jsonPath("$.summary.parseMs").value(180000))
                .andExpect(jsonPath("$.summary.embedMs").value(6000))
                .andExpect(jsonPath("$.summary.totalMs").value(200000));
    }

    @Test
    @DisplayName("FAILED → 回模板 + error；失败那一步是 FAILED，其余后半段 PENDING")
    void failedReturnsTemplateAndError() throws Exception {
        when(documents.findParseState(42L)).thenReturn(state("FAILED"));
        when(tracker.latest(42L)).thenReturn(Optional.of(new IngestRun(5L, 42L, 1, "PARSE", 8, 3,
                "FAILED", null, "IllegalStateException: boom",
                OffsetDateTime.now(), OffsetDateTime.now())));
        when(tracker.steps(5L)).thenReturn(List.of(
                new IngestStepRow(3, "PARSING", "FAILED", "解析作业失败: boom", 0,
                        OffsetDateTime.now(), OffsetDateTime.now(), 5000)));

        mvc.perform(get("/api/documents/42/ingest"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("FAILED"))
                .andExpect(jsonPath("$.error").value("IllegalStateException: boom"))
                .andExpect(jsonPath("$.steps[2].status").value("FAILED"))
                .andExpect(jsonPath("$.steps[2].detail").value("解析作业失败: boom"))
                .andExpect(jsonPath("$.steps[3].status").value("PENDING"));
    }

    @Test
    @DisplayName("重试非 FAILED 文档 → 409 NOT_RETRYABLE，且不投递事件")
    void retryRejectsNonFailed() throws Exception {
        when(documents.findParseState(42L)).thenReturn(state("RUNNING"));

        mvc.perform(post("/api/documents/42/retry"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("NOT_RETRYABLE"));

        verify(producer, never()).sendParse(anyLong());
        verify(documents, never()).markPending(anyLong());
    }

    @Test
    @DisplayName("重试 FAILED 文档 → 复位 + 投递 + 返回新 attempt")
    void retryFailedDocument() throws Exception {
        when(documents.findParseState(42L)).thenReturn(state("FAILED"));
        when(tracker.start(42L, IngestFlow.PARSE)).thenReturn(5L);
        when(tracker.latest(42L)).thenReturn(Optional.of(new IngestRun(5L, 42L, 2, "PARSE", 8, 1,
                "RUNNING", null, null, OffsetDateTime.now(), null)));

        mvc.perform(post("/api/documents/42/retry"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.parseStatus").value("PENDING"))
                .andExpect(jsonPath("$.attempt").value(2));

        verify(documents).markPending(42L);
        verify(producer).sendParse(42L);
        verify(tracker).start(42L, IngestFlow.PARSE);
    }

    @Test
    @DisplayName("重试不存在的文档 → 404")
    void retryMissingDocumentIs404() throws Exception {
        when(documents.findParseState(42L)).thenThrow(new RuntimeException("empty"));

        mvc.perform(post("/api/documents/42/retry")).andExpect(status().isNotFound());
        verify(producer, never()).sendParse(anyLong());
    }

    @Test
    @DisplayName("模板补位：没有行的步骤按 flow 顺序给 PENDING，seq 连续")
    void templateFillsPendingSteps() {
        List<DocumentIngestController.StepView> steps = DocumentIngestController.template(
                new IngestRun(5L, 42L, 1, "REINDEX", 4, 1, "RUNNING", null, null,
                        OffsetDateTime.now(), null),
                List.of(new IngestStepRow(1, "CHUNKING", "RUNNING", null, 0,
                        OffsetDateTime.now(), null, null)));

        org.assertj.core.api.Assertions.assertThat(steps)
                .extracting(DocumentIngestController.StepView::seq)
                .containsExactly(1, 2, 3, 4);
        org.assertj.core.api.Assertions.assertThat(steps.get(3).label()).isEqualTo("切换 ACTIVE");
    }
}
