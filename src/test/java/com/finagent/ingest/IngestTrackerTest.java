package com.finagent.ingest;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 埋点门面（{@link IngestTracker}）的单元测试。
 *
 * <p>为什么这里全是 mock：本类只负责「判断该不该写、写哪一步、怎么节流」，
 * SQL 语义交给 {@code IngestRunDbTest} 用真库验 —— 两侧各测自己那一半。
 */
class IngestTrackerTest {

    private IngestRunRepository runs;
    private IngestTracker tracker;

    @BeforeEach
    void setUp() {
        runs = mock(IngestRunRepository.class);
        tracker = new IngestTracker(runs, JsonMapper.builder().build());
    }

    private static IngestRun run(long id, IngestFlow flow, int currentStep) {
        return new IngestRun(id, 7L, 1, flow.name(), flow.totalSteps(), currentStep,
                "RUNNING", null, null, OffsetDateTime.now(), null);
    }

    @Test
    @DisplayName("start：没有在跑的 run 时直接开一条，并返回 runId")
    void startCreatesNewRun() {
        when(runs.start(7L, IngestFlow.PARSE)).thenReturn(99L);

        assertThatCode(() -> tracker.start(7L, IngestFlow.PARSE)).doesNotThrowAnyException();

        verify(runs).start(7L, IngestFlow.PARSE);
        verify(runs, never()).fail(anyLong(), any());
    }

    @Test
    @DisplayName("start：上一轮还挂在 RUNNING 时先收成 FAILED（避免两条 RUNNING）")
    void startClosesStaleRun() {
        when(runs.findRunning(7L)).thenReturn(Optional.of(run(50L, IngestFlow.PARSE, 3)));
        when(runs.start(7L, IngestFlow.PARSE)).thenReturn(99L);

        tracker.start(7L, IngestFlow.PARSE);

        verify(runs).fail(eq(50L), any());
        verify(runs).start(7L, IngestFlow.PARSE);
    }

    @Test
    @DisplayName("begin：序号由流程模板决定（CHUNKING 在 PARSE 里是第 5 步）")
    void beginResolvesSeqFromFlow() {
        when(runs.findRunning(7L)).thenReturn(Optional.of(run(50L, IngestFlow.PARSE, 4)));

        tracker.begin(7L, IngestStep.CHUNKING);

        verify(runs).beginStep(50L, 5, IngestStep.CHUNKING);
    }

    @Test
    @DisplayName("begin：步骤不属于当前流程时忽略（不抛异常、不写库）")
    void beginIgnoresStepOutsideFlow() {
        // SKIPPED 只用于「已存在，跳过」的说明，不在任何流程模板里
        when(runs.findRunning(7L)).thenReturn(Optional.of(run(50L, IngestFlow.PARSE, 1)));

        tracker.begin(7L, IngestStep.SKIPPED);

        verify(runs, never()).beginStep(anyLong(), anyInt(), any());
    }

    @Test
    @DisplayName("begin：没有在跑的 run 时静默跳过（老文档 / 已完成的文档）")
    void beginDoesNothingWithoutRun() {
        when(runs.findRunning(7L)).thenReturn(Optional.empty());

        assertThatCode(() -> tracker.begin(7L, IngestStep.PARSING)).doesNotThrowAnyException();

        verify(runs, never()).beginStep(anyLong(), anyInt(), any());
    }

    @Test
    @DisplayName("heartbeat：30 秒内重复调用只写一次库")
    void heartbeatIsThrottled() {
        when(runs.findRunning(7L)).thenReturn(Optional.of(run(50L, IngestFlow.PARSE, 3)));

        tracker.heartbeat(7L, "解析中 · 已用 0m10s");
        tracker.heartbeat(7L, "解析中 · 已用 0m20s");
        tracker.heartbeat(7L, "解析中 · 已用 0m30s");

        verify(runs, times(1)).heartbeat(eq(50L), any());
    }

    @Test
    @DisplayName("heartbeatNow：不节流，切阶段时立刻可见")
    void heartbeatNowBypassesThrottle() {
        when(runs.findRunning(7L)).thenReturn(Optional.of(run(50L, IngestFlow.PARSE, 3)));

        tracker.heartbeatNow(7L, "已嵌入 0/38");
        tracker.heartbeatNow(7L, "已嵌入 20/38");

        verify(runs, times(2)).heartbeat(eq(50L), any());
    }

    @Test
    @DisplayName("succeed：摘要序列化后写 summary_json（null 值自动丢弃）")
    void succeedWritesSummary() {
        when(runs.findRunning(7L)).thenReturn(Optional.of(run(50L, IngestFlow.PARSE, 8)));

        tracker.succeed(7L, IngestTracker.summary("chunks", 654, "version", 2, "pages", null));

        verify(runs).succeed(eq(50L), eq("{\"chunks\":654,\"version\":2}"));
    }

    @Test
    @DisplayName("fail：detail 超长会截断，避免整段异常栈撑爆表格")
    void failClipsLongError() {
        when(runs.findRunning(7L)).thenReturn(Optional.of(run(50L, IngestFlow.PARSE, 3)));

        tracker.fail(7L, "x".repeat(1000));

        verify(runs).fail(eq(50L), org.mockito.ArgumentMatchers.argThat((String s) -> s.length() <= 301));
    }

    @Test
    @DisplayName("仓储抛异常时埋点只记日志，不把异常抛给调用方")
    void neverPropagatesRepositoryFailure() {
        when(runs.findRunning(7L)).thenReturn(Optional.of(run(50L, IngestFlow.PARSE, 3)));
        org.mockito.Mockito.doThrow(new IllegalStateException("db down"))
                .when(runs).beginStep(anyLong(), anyInt(), any());
        when(runs.start(7L, IngestFlow.PARSE)).thenThrow(new IllegalStateException("db down"));

        assertThatCode(() -> {
            tracker.begin(7L, IngestStep.PARSING);
            tracker.start(7L, IngestFlow.PARSE);
        }).doesNotThrowAnyException();
    }

    private static IngestRun failedRun(long id, OffsetDateTime finishedAt) {
        return new IngestRun(id, 7L, 1, IngestFlow.PARSE.name(), 8, 2,
                "FAILED", null, "boom", OffsetDateTime.now().minusMinutes(1), finishedAt);
    }

    @Test
    @DisplayName("startOrResume：刚失败的 run 直接复用（同一次入库的内部重试不新开 attempt）")
    void startOrResumeReopensRecentFailure() {
        when(runs.findRunning(7L)).thenReturn(Optional.empty());
        when(runs.findLatest(7L)).thenReturn(Optional.of(failedRun(50L, OffsetDateTime.now().minusSeconds(3))));

        tracker.startOrResume(7L, IngestFlow.RESUME);

        verify(runs).reopen(50L);
        verify(runs, never()).start(anyLong(), any());
    }

    @Test
    @DisplayName("startOrResume：失败超过 5 分钟的宽限窗则算新一次尝试")
    void startOrResumeStartsNewAfterGrace() {
        when(runs.findRunning(7L)).thenReturn(Optional.empty());
        when(runs.findLatest(7L)).thenReturn(Optional.of(failedRun(50L, OffsetDateTime.now().minusMinutes(30))));

        tracker.startOrResume(7L, IngestFlow.RESUME);

        verify(runs, never()).reopen(anyLong());
        verify(runs).start(7L, IngestFlow.RESUME);
    }

    @Test
    @DisplayName("startOrResume：已有 RUNNING 时既不新开也不 reopen")
    void startOrResumeReusesRunning() {
        when(runs.findRunning(7L)).thenReturn(Optional.of(run(50L, IngestFlow.PARSE, 3)));

        tracker.startOrResume(7L, IngestFlow.RESUME);

        verify(runs, never()).start(anyLong(), any());
        verify(runs, never()).reopen(anyLong());
    }

    @Test
    @DisplayName("recentlyFailed：DONE / 没有结束时间都不算可复用")
    void recentlyFailedChecksStatusAndTime() {
        org.assertj.core.api.Assertions.assertThat(IngestTracker.recentlyFailed(failedRun(1L, null))).isFalse();
        org.assertj.core.api.Assertions.assertThat(IngestTracker.recentlyFailed(run(2L, IngestFlow.PARSE, 1))).isFalse();
        org.assertj.core.api.Assertions.assertThat(
                IngestTracker.recentlyFailed(failedRun(3L, OffsetDateTime.now()))).isTrue();
    }

    @Test
    @DisplayName("summary：按 kv 顺序组装，null 值不进 Map")
    void summarySkipsNulls() {
        Map<String, Object> map = IngestTracker.summary("a", 1, "b", null, "c", true);

        org.assertj.core.api.Assertions.assertThat(map)
                .containsExactly(Map.entry("a", 1), Map.entry("c", true));
    }
}
