-- V17: 入库执行记录（阶段 19）
--
-- 为什么需要两张表：run 是「这一次入库」，step 是「这一次里的每一步」。
-- 只存 step 也能跑，但「第 3/8 步」需要一个地方写死总步数 —— 总步数来自流程模板，
-- 不是运行时数出来的（重试会加行，数出来就会漂移）。
--
-- 为什么失败也要留：FAILED 的那份流水是排障证据，用户反馈「传上去不动」时直接截图即可。

CREATE TABLE document_ingest_run (
    id           bigserial PRIMARY KEY,
    tenant_id    bigint      NOT NULL REFERENCES tenant (id),
    document_id  bigint      NOT NULL REFERENCES document (id) ON DELETE CASCADE,
    attempt      smallint    NOT NULL,
    flow         varchar(16) NOT NULL,
    total_steps  smallint    NOT NULL,
    current_step smallint    NOT NULL DEFAULT 0,
    status       varchar(16) NOT NULL DEFAULT 'RUNNING',
    summary_json jsonb,
    error        text,
    started_at   timestamptz NOT NULL DEFAULT now(),
    finished_at  timestamptz,
    CONSTRAINT uq_ingest_run_attempt UNIQUE (document_id, attempt),
    CONSTRAINT chk_ingest_run_flow CHECK (flow IN ('PARSE', 'REINDEX', 'RESUME')),
    CONSTRAINT chk_ingest_run_status CHECK (status IN ('RUNNING', 'DONE', 'FAILED')),
    CONSTRAINT chk_ingest_run_current CHECK (current_step >= 0 AND current_step <= total_steps)
);

CREATE TABLE document_ingest_step (
    id          bigserial PRIMARY KEY,
    tenant_id   bigint      NOT NULL REFERENCES tenant (id),
    run_id      bigint      NOT NULL REFERENCES document_ingest_run (id) ON DELETE CASCADE,
    seq         smallint    NOT NULL,
    step        varchar(32) NOT NULL,
    status      varchar(16) NOT NULL DEFAULT 'RUNNING',
    detail      text,
    retry       smallint    NOT NULL DEFAULT 0,
    started_at  timestamptz NOT NULL DEFAULT now(),
    finished_at timestamptz,
    duration_ms integer,
    CONSTRAINT uq_ingest_step_seq UNIQUE (run_id, seq),
    CONSTRAINT chk_ingest_step_status CHECK (status IN ('RUNNING', 'OK', 'FAILED', 'SKIPPED'))
);

CREATE INDEX idx_ingest_run_document ON document_ingest_run (document_id, attempt DESC);
CREATE INDEX idx_ingest_run_running ON document_ingest_run (status) WHERE status = 'RUNNING';
CREATE INDEX idx_ingest_step_run ON document_ingest_step (run_id, seq);

COMMENT ON TABLE document_ingest_run IS '一次文档入库的执行记录（run）：总步数由流程模板写死';
COMMENT ON COLUMN document_ingest_run.attempt IS '第几次入库：重试/重新解析各算一次，从 1 开始';
COMMENT ON COLUMN document_ingest_run.flow IS 'PARSE（上传解析）/ REINDEX（重切重嵌）/ RESUME（断点恢复）';
COMMENT ON COLUMN document_ingest_run.total_steps IS '流程模板的步数：PARSE=8、REINDEX=4。重试不增加';
COMMENT ON COLUMN document_ingest_run.summary_json IS '成功后的摘要（页数/块数/各阶段耗时），DONE 时只回它';
COMMENT ON TABLE document_ingest_step IS '入库过程中的单步：一行一个步骤，心跳只更新 detail 不新增行';
COMMENT ON COLUMN document_ingest_step.detail IS '附注：job_id / 已嵌入 20/38 / 12 页 · docling 2.138.0';
COMMENT ON COLUMN document_ingest_step.retry IS '同一步重试次数（重试不新增行，否则总步数会漂移）';

-- RLS 与 task_message 同构：应用层不带 tenant 条件也不会串租户
ALTER TABLE document_ingest_run ENABLE ROW LEVEL SECURITY;
ALTER TABLE document_ingest_step ENABLE ROW LEVEL SECURITY;

CREATE POLICY p_ingest_run_tenant ON document_ingest_run
    USING (tenant_id = (NULLIF(current_setting('app.tenant_id', true), ''))::bigint)
    WITH CHECK (tenant_id = (NULLIF(current_setting('app.tenant_id', true), ''))::bigint);

CREATE POLICY p_ingest_step_tenant ON document_ingest_step
    USING (tenant_id = (NULLIF(current_setting('app.tenant_id', true), ''))::bigint)
    WITH CHECK (tenant_id = (NULLIF(current_setting('app.tenant_id', true), ''))::bigint);

GRANT SELECT, INSERT, UPDATE, DELETE ON document_ingest_run, document_ingest_step TO finagent_app;
GRANT USAGE, SELECT ON SEQUENCE document_ingest_run_id_seq, document_ingest_step_id_seq TO finagent_app;
