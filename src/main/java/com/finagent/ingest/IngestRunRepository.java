package com.finagent.ingest;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 执行记录两张表的仓储（阶段 19）。
 *
 * <p>为什么不用 JPA：与项目其余部分一致，SQL 全部手写；这里的语句也不适合 ORM ——
 * 「收尾上一步 + 开下一步 + 更新 current_step」是三条必须在一起的写，
 * 用实体映射反而看不清实际执行的顺序。
 *
 * <p>所有写语句都只影响 {@code status = 'RUNNING'} 的行：重复收尾（心跳与正常结束撞车、
 * 重试与恢复撞车）时第二条语句影响 0 行，天然幂等，不需要额外加锁。
 */
@Repository
public class IngestRunRepository {

    private static final RowMapper<IngestRun> RUN_MAPPER = (rs, n) -> new IngestRun(
            rs.getLong("id"),
            rs.getLong("document_id"),
            rs.getInt("attempt"),
            rs.getString("flow"),
            rs.getInt("total_steps"),
            rs.getInt("current_step"),
            rs.getString("status"),
            rs.getString("summary_json"),
            rs.getString("error"),
            rs.getObject("started_at", OffsetDateTime.class),
            rs.getObject("finished_at", OffsetDateTime.class));

    private static final RowMapper<IngestStepRow> STEP_MAPPER = (rs, n) -> new IngestStepRow(
            rs.getInt("seq"),
            rs.getString("step"),
            rs.getString("status"),
            rs.getString("detail"),
            rs.getInt("retry"),
            rs.getObject("started_at", OffsetDateTime.class),
            rs.getObject("finished_at", OffsetDateTime.class),
            (Integer) rs.getObject("duration_ms"));

    private static final String SELECT_RUN = """
            SELECT id, document_id, attempt, flow, total_steps, current_step, status,
                   summary_json::text AS summary_json, error, started_at, finished_at
              FROM document_ingest_run
            """;

    /** 算耗时：只在这三条写语句里出现，故抽成常量，避免三处写法漂移（取整到毫秒，null 安全）。 */
    private static final String DURATION_MS =
            "(EXTRACT(EPOCH FROM (now() - started_at)) * 1000)::int";

    private final JdbcTemplate jdbc;

    public IngestRunRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 开一次新的入库记录，并开始流程的第一步。
     *
     * <p>attempt 用「当前最大值 + 1」而不是 count：中途删过记录时 count 会撞唯一键
     * (document_id, attempt)。tenant_id 直接取自 document，调用方不需要带租户上下文 ——
     * CLI、MQ worker 里没有 SecurityContext。
     *
     * @return 新 run 的 id；文档不存在时返回 -1
     */
    public long start(long documentId, IngestFlow flow) {
        Long id = jdbc.queryForObject("""
                INSERT INTO document_ingest_run
                       (tenant_id, document_id, attempt, flow, total_steps, current_step, status)
                SELECT d.tenant_id, d.id,
                       COALESCE((SELECT max(r.attempt) FROM document_ingest_run r
                                  WHERE r.document_id = d.id), 0) + 1,
                       ?, ?, 0, 'RUNNING'
                  FROM document d
                 WHERE d.id = ?
                RETURNING id
                """, Long.class, flow.name(), flow.totalSteps(), documentId);
        if (id == null || id < 0) {
            return -1L;
        }
        beginStep(id, 1, flow.first());
        return id;
    }

    /** 当前在跑的 run（可能为 null —— 老数据、CLI 触发、已完成后又收到心跳）。 */
    public Optional<IngestRun> findRunning(long documentId) {
        List<IngestRun> rows = jdbc.query(SELECT_RUN + """
                 WHERE document_id = ? AND status = 'RUNNING'
                 ORDER BY attempt DESC
                 LIMIT 1
                """, RUN_MAPPER, documentId);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    /** 最近一次 run，不论成败：DONE 时前端只看它的摘要，FAILED 时看它的 steps。 */
    public Optional<IngestRun> findLatest(long documentId) {
        List<IngestRun> rows = jdbc.query(SELECT_RUN + """
                 WHERE document_id = ?
                 ORDER BY attempt DESC
                 LIMIT 1
                """, RUN_MAPPER, documentId);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    public List<IngestStepRow> steps(long runId) {
        return jdbc.query("""
                SELECT seq, step, status, detail, retry, started_at, finished_at, duration_ms
                  FROM document_ingest_step
                 WHERE run_id = ?
                 ORDER BY seq
                """, STEP_MAPPER, runId);
    }

    /**
     * 开一步：先把还在 RUNNING 的步收成 OK，再插新行、推 current_step。
     *
     * <p>三步一起做是因为它们描述同一件事「上一个动作结束了、下一个动作开始了」；
     * 只做一半会出现「两步同时 RUNNING」的流水，前端只能自己猜哪步是真的。
     */
    public void beginStep(long runId, int seq, IngestStep step) {
        closeRunningSteps(runId, "OK", null);
        // 同一步重跑（MQ 重投、断点恢复）复用原行而不是再插一行：
        // UNIQUE(run_id, seq) 决定了「一步一行」是表结构事实，重跑只该留下 retry 计数
        jdbc.update("""
                INSERT INTO document_ingest_step (tenant_id, run_id, seq, step, status)
                SELECT tenant_id, ?, ?, ?, 'RUNNING'
                  FROM document_ingest_run
                 WHERE id = ?
                ON CONFLICT (run_id, seq) DO UPDATE
                   SET status = 'RUNNING',
                       detail = NULL,
                       started_at = now(),
                       finished_at = NULL,
                       duration_ms = NULL,
                       retry = document_ingest_step.retry
                               + CASE WHEN document_ingest_step.status = 'RUNNING' THEN 0 ELSE 1 END
                """, runId, seq, step.name(), runId);
        jdbc.update("UPDATE document_ingest_run SET current_step = ? WHERE id = ? AND status = 'RUNNING'",
                seq, runId);
    }

    /**
     * 收尾某一步（不改 run 状态）。
     *
     * @param detail 附注，可空；空时不覆盖已有值（心跳写过的 detail 更具体，不要被清掉）
     */
    public void finishStep(long runId, IngestStep step, String detail) {
        jdbc.update("""
                UPDATE document_ingest_step
                   SET status = 'OK',
                       detail = COALESCE(?, detail),
                       finished_at = now(),
                       duration_ms = %s
                 WHERE run_id = ? AND step = ? AND status = 'RUNNING'
                """.formatted(DURATION_MS), detail, runId, step.name());
    }

    /**
     * 心跳：只改正在跑的那一步的 detail。
     *
     * <p>不新增行、不推进 current_step —— 否则「第 3/8 步」会随心跳膨胀，
     * 而总步数是模板常量，最后变成 3/8、7/8、15/8 这种荒谬数字。
     */
    public void heartbeat(long runId, String detail) {
        jdbc.update("""
                UPDATE document_ingest_step
                   SET detail = ?
                 WHERE run_id = ? AND status = 'RUNNING'
                """, detail, runId);
    }

    /** 全部成功：收尾在跑步、写摘要、标 DONE。 */
    public void succeed(long runId, String summaryJson) {
        closeRunningSteps(runId, "OK", null);
        jdbc.update("""
                UPDATE document_ingest_run
                   SET status = 'DONE', summary_json = ?::jsonb, error = NULL, finished_at = now()
                 WHERE id = ?
                """, summaryJson, runId);
    }

    /**
     * 失败：正在跑的步标 FAILED 并把错误写进 detail，整条 run 标 FAILED。
     *
     * <p>后续步骤不会补行 —— 前端看到「3/8 失败」后，第 4~8 步自然显示为「未执行」。
     */
    public void fail(long runId, String error) {
        jdbc.update("""
                UPDATE document_ingest_step
                   SET status = 'FAILED',
                       detail = CASE WHEN ? IS NULL THEN detail ELSE ? END,
                       finished_at = now(),
                       duration_ms = %s
                 WHERE run_id = ? AND status = 'RUNNING'
                """.formatted(DURATION_MS), error, error, runId);
        jdbc.update("""
                UPDATE document_ingest_run
                   SET status = 'FAILED', error = ?, finished_at = now()
                 WHERE id = ? AND status = 'RUNNING'
                """, error, runId);
    }

    /**
     * 把一条 FAILED 的 run 改回 RUNNING（同一条 attempt 内重跑）。
     *
     * <p>为什么需要它：MQ 重投 / 消费失败重试都发生在**同一次入库**里，
     * 若每次都新开 attempt，用户点一次上传会在页面上看到「第 4 次」。
     * 只有显式点「重试」才该产生新 attempt（走 {@link #start}）。
     */
    public void reopen(long runId) {
        jdbc.update("""
                UPDATE document_ingest_run
                   SET status = 'RUNNING', error = NULL, finished_at = NULL
                 WHERE id = ? AND status = 'FAILED'
                """, runId);
    }

    /** 重试计数 +1（同一步复用原行）。 */
    public void bumpRetry(long runId, IngestStep step) {
        jdbc.update("""
                UPDATE document_ingest_step
                   SET retry = retry + 1
                 WHERE run_id = ? AND step = ?
                """, runId, step.name());
    }

    private void closeRunningSteps(long runId, String status, String detail) {
        jdbc.update("""
                UPDATE document_ingest_step
                   SET status = ?,
                       detail = COALESCE(?, detail),
                       finished_at = now(),
                       duration_ms = %s
                 WHERE run_id = ? AND status = 'RUNNING'
                """.formatted(DURATION_MS), status, detail, runId);
    }

    /** 供 DB 测试断言表结构用，不参与业务。 */
    static IngestRun mapRun(ResultSet rs) throws SQLException {
        return RUN_MAPPER.mapRow(rs, 0);
    }
}
