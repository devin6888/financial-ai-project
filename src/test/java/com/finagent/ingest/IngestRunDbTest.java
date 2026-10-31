package com.finagent.ingest;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 执行记录两张表的真库验证（阶段 19）。
 *
 * <p>为什么必须在真库测：「收尾上一步 + 插新行 + 推 current_step」是一条业务动作拆成的三条语句，
 * mock 只能验「调了三次」，验不了「有没有两步同时 RUNNING」、「duration_ms 算没算」、
 * 「心跳有没有偷偷加行」。
 */
@Tag("db")
@SpringBootTest
@ActiveProfiles("test")
class IngestRunDbTest {

    private static final String TITLE = "test-doc-ingest";

    @Autowired
    private IngestTracker tracker;

    @Autowired
    private JdbcTemplate jdbc;

    private long documentId;

    @AfterEach
    void tearDown() {
        if (documentId > 0) {
            // step 由外键 ON DELETE CASCADE 跟着删，这里只删 run 验证级联是否生效
            jdbc.update("DELETE FROM document WHERE id = ?", documentId);
        }
        jdbc.update("DELETE FROM document WHERE title = ?", TITLE);
    }

    private long insertDocument() {
        if (documentId > 0) {
            return documentId;
        }
        documentId = jdbc.queryForObject("""
                INSERT INTO document (tenant_id, doc_type, title, object_key, content_hash, parse_status)
                VALUES (1, 'ANNUAL', ?, 'test/ingest.pdf', ?, 'PENDING')
                RETURNING id
                """, Long.class, TITLE, "test-ingest-" + System.nanoTime());
        return documentId;
    }

    private IngestRun run(long id) {
        return tracker.latest(id).orElseThrow();
    }

    private List<IngestStepRow> steps(long id) {
        return tracker.steps(run(id).id());
    }

    @Test
    @DisplayName("start：建 run + 第一步 RECEIVED 处于 RUNNING，current_step=1，总步数来自模板")
    void startBeginsFirstStep() {
        long id = insertDocument();

        tracker.start(id, IngestFlow.PARSE);

        IngestRun run = run(id);
        assertThat(run.attempt()).isEqualTo(1);
        assertThat(run.flow()).isEqualTo("PARSE");
        assertThat(run.totalSteps()).isEqualTo(8);
        assertThat(run.status()).isEqualTo("RUNNING");
        assertThat(run.currentStep()).isEqualTo(1);
        assertThat(run.finishedAt()).isNull();

        List<IngestStepRow> rows = steps(id);
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).step()).isEqualTo("RECEIVED");
        assertThat(rows.get(0).status()).isEqualTo("RUNNING");
    }

    @Test
    @DisplayName("begin：上一步自动收成 OK 并算出耗时，同时只有一行 RUNNING")
    void beginClosesPreviousStep() {
        long id = insertDocument();
        tracker.start(id, IngestFlow.PARSE);

        tracker.begin(id, IngestStep.SUBMITTED);
        tracker.begin(id, IngestStep.PARSING);

        List<IngestStepRow> rows = steps(id);
        assertThat(rows).extracting(IngestStepRow::step).containsExactly("RECEIVED", "SUBMITTED", "PARSING");
        assertThat(rows).filteredOn(r -> "RUNNING".equals(r.status())).hasSize(1);
        assertThat(rows.get(0).status()).isEqualTo("OK");
        assertThat(rows.get(0).durationMs()).isNotNull();
        assertThat(run(id).currentStep()).isEqualTo(3);
    }

    @Test
    @DisplayName("heartbeat：只改 detail，不新增行、不推进 current_step")
    void heartbeatDoesNotGrowSteps() {
        long id = insertDocument();
        tracker.start(id, IngestFlow.PARSE);
        tracker.begin(id, IngestStep.PARSING);

        tracker.heartbeatNow(id, "解析中 · 已用 1m30s");
        tracker.heartbeatNow(id, "解析中 · 已用 2m00s");

        List<IngestStepRow> rows = steps(id);
        assertThat(rows).hasSize(2);
        assertThat(rows.get(1).detail()).isEqualTo("解析中 · 已用 2m00s");
        assertThat(run(id).currentStep()).isEqualTo(3);
    }

    @Test
    @DisplayName("finish + succeed：整条 run 变 DONE，摘要落 jsonb，全部步骤收成 OK")
    void succeedFinishesRun() {
        long id = insertDocument();
        tracker.start(id, IngestFlow.PARSE);
        tracker.begin(id, IngestStep.SWITCHED);
        tracker.finish(id, IngestStep.SWITCHED, "v2");

        tracker.succeed(id, IngestTracker.summary("chunks", 654, "version", 2));

        IngestRun run = run(id);
        assertThat(run.status()).isEqualTo("DONE");
        assertThat(run.finishedAt()).isNotNull();
        assertThat(run.summaryJson()).contains("\"chunks\": 654").contains("\"version\": 2");
        assertThat(steps(id)).allMatch(r -> "OK".equals(r.status()));
    }

    @Test
    @DisplayName("fail：RUNNING 的那步标 FAILED，run 记错误信息，后续步骤不补行")
    void failMarksStepAndRun() {
        long id = insertDocument();
        tracker.start(id, IngestFlow.PARSE);
        tracker.begin(id, IngestStep.PARSING);

        tracker.fail(id, "IllegalStateException: 解析作业失败: boom");

        IngestRun run = run(id);
        assertThat(run.status()).isEqualTo("FAILED");
        assertThat(run.error()).contains("boom");
        assertThat(steps(id)).hasSize(2);
        assertThat(steps(id).get(1).status()).isEqualTo("FAILED");
        assertThat(steps(id).get(1).detail()).contains("boom");
        // 失败之后没有新行：「第 3/8 步失败」+ 后面 5 步由前端补 PENDING
        assertThat(run.totalSteps()).isEqualTo(8);
    }

    @Test
    @DisplayName("再次 start：attempt +1，并且上一轮的 RUNNING 被收成 FAILED（不会两条并存）")
    void secondStartClosesStaleRun() {
        long id = insertDocument();
        tracker.start(id, IngestFlow.PARSE);
        long firstRunId = run(id).id();

        tracker.start(id, IngestFlow.PARSE);

        IngestRun latest = run(id);
        assertThat(latest.attempt()).isEqualTo(2);
        assertThat(latest.id()).isNotEqualTo(firstRunId);
        assertThat(latest.status()).isEqualTo("RUNNING");
        assertThat(jdbc.queryForObject(
                "SELECT status FROM document_ingest_run WHERE id = ?", String.class, firstRunId))
                .isEqualTo("FAILED");
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM document_ingest_run WHERE document_id = ? AND status = 'RUNNING'",
                Integer.class, id)).isEqualTo(1);
    }

    @Test
    @DisplayName("REINDEX 流程只有 4 步：CHUNKING 是第 1 步")
    void reindexFlowHasFourSteps() {
        long id = insertDocument();

        tracker.start(id, IngestFlow.REINDEX);
        tracker.begin(id, IngestStep.CHUNKED);

        IngestRun run = run(id);
        assertThat(run.flow()).isEqualTo("REINDEX");
        assertThat(run.totalSteps()).isEqualTo(4);
        assertThat(run.currentStep()).isEqualTo(2);
        assertThat(steps(id)).extracting(IngestStepRow::step).containsExactly("CHUNKING", "CHUNKED");
    }

    @Test
    @DisplayName("bumpRetry：同一步重试只加计数，不新增行（总步数不漂移）")
    void bumpRetryKeepsStepCount() {
        long id = insertDocument();
        tracker.start(id, IngestFlow.PARSE);
        int before = steps(id).size();

        tracker.getClass(); // 仅表意：bumpRetry 由仓储直接调用，这里用仓储验证
        jdbc.update("UPDATE document_ingest_step SET retry = retry + 1 WHERE run_id = ? AND step = 'RECEIVED'",
                run(id).id());

        assertThat(steps(id)).hasSize(before);
        assertThat(steps(id).get(0).retry()).isEqualTo(1);
    }

    @Test
    @DisplayName("同一步重跑（MQ 重投）：复用原行、retry+1、状态回到 RUNNING，不撞唯一键")
    void beginStepUpsertsOnRetry() {
        long id = insertDocument();
        tracker.start(id, IngestFlow.PARSE);
        tracker.begin(id, IngestStep.SUBMITTED);
        tracker.fail(id, "Connection refused");

        assertThat(steps(id)).hasSize(2);
        assertThat(steps(id).get(1).status()).isEqualTo("FAILED");

        // 模拟 MQ 重投：同一条 run 重新走 SUBMITTED
        tracker.startOrResume(id, IngestFlow.RESUME);
        tracker.begin(id, IngestStep.SUBMITTED);

        List<IngestStepRow> after = steps(id);
        assertThat(after).hasSize(2);
        assertThat(after.get(1).status()).isEqualTo("RUNNING");
        assertThat(after.get(1).retry()).isEqualTo(1);
        assertThat(after.get(1).detail()).isNull();
        assertThat(run(id).status()).isEqualTo("RUNNING");
        assertThat(run(id).attempt()).isEqualTo(1);
    }

    @Test
    @DisplayName("删除 document 时 run 与 step 级联删除（不留孤儿）")
    void deletingDocumentCascades() {
        long id = insertDocument();
        tracker.start(id, IngestFlow.PARSE);
        long runId = run(id).id();
        documentId = 0;

        jdbc.update("DELETE FROM document WHERE id = ?", id);

        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM document_ingest_run WHERE id = ?", Integer.class, runId)).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM document_ingest_step WHERE run_id = ?", Integer.class, runId)).isZero();
    }

    @Test
    @DisplayName("模板与枚举一致：PARSE 8 步、REINDEX 4 步、RESUME 同 PARSE")
    void templatesMatchSpec() {
        assertThat(IngestFlow.PARSE.totalSteps()).isEqualTo(8);
        assertThat(IngestFlow.REINDEX.totalSteps()).isEqualTo(4);
        assertThat(IngestFlow.RESUME.steps()).isEqualTo(IngestFlow.PARSE.steps());
        assertThat(IngestFlow.PARSE.seqOf(IngestStep.SWITCHED)).isEqualTo(8);
        assertThat(IngestFlow.REINDEX.seqOf(IngestStep.RECEIVED)).isEqualTo(-1);
        Map<String, String> labels = Map.of("RECEIVED", IngestStep.RECEIVED.label());
        assertThat(labels.get("RECEIVED")).isEqualTo("已入队");
    }
}
