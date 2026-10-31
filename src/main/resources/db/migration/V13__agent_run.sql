-- V13 · Agent 运行与步骤（阶段 11 Task 2）
--
-- 两张表分工：
--   agent_run      一次 Agent 运行的「当前状态」（一行，随进度被 UPDATE）
--   agent_run_step 每一步的「流水」（追加型，只 INSERT，用于时间线与排查）
--
-- 与 LG4JThread / LG4JCheckpoint（V12）的关系：
--   LG4J* 是 LangGraph4j 自己的存档（状态字节），这两张表是「给人看的」业务视图；
--   对应关系是 agent_run.id = LG4JThread.thread_name（都是 UUID）。
--   租户隔离靠 agent_run.tenant_id + RLS；LG4J* 不含租户，只由 owner 连接读写（ST11 D5）。

CREATE TABLE IF NOT EXISTS agent_run (
    id              UUID PRIMARY KEY,
    tenant_id       BIGINT NOT NULL REFERENCES tenant(id),
    user_id         BIGINT NOT NULL REFERENCES app_user(id),
    conversation_id BIGINT REFERENCES conversation(id) ON DELETE SET NULL,
    question        TEXT NOT NULL,
    status          VARCHAR(20) NOT NULL DEFAULT 'RUNNING',
    current_node    VARCHAR(64),
    plan_json       JSONB,
    decision        VARCHAR(16),
    decision_note   TEXT,
    error           TEXT,
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    updated_at      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    CONSTRAINT chk_agent_run_status CHECK (status IN
        ('RUNNING', 'WAITING_APPROVAL', 'DONE', 'FAILED', 'REJECTED')),
    CONSTRAINT chk_agent_run_decision CHECK (decision IS NULL OR decision IN
        ('APPROVE', 'EDIT', 'REJECT'))
);

CREATE INDEX IF NOT EXISTS idx_agent_run_tenant_created
    ON agent_run (tenant_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_agent_run_user_created
    ON agent_run (user_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_agent_run_conversation
    ON agent_run (conversation_id) WHERE conversation_id IS NOT NULL;
-- 等待审批的运行要能被快速捞出来（运维/超时清理用）
CREATE INDEX IF NOT EXISTS idx_agent_run_waiting
    ON agent_run (updated_at) WHERE status = 'WAITING_APPROVAL';

CREATE TABLE IF NOT EXISTS agent_run_step (
    id          BIGSERIAL PRIMARY KEY,
    tenant_id   BIGINT NOT NULL,
    run_id      UUID NOT NULL REFERENCES agent_run(id) ON DELETE CASCADE,
    seq         INTEGER NOT NULL,
    node        VARCHAR(64) NOT NULL,
    status      VARCHAR(16) NOT NULL,
    summary     TEXT,
    duration_ms INTEGER,
    created_at  TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    CONSTRAINT uq_agent_run_step UNIQUE (run_id, seq),
    CONSTRAINT chk_agent_run_step_status CHECK (status IN ('OK', 'FAILED', 'SKIPPED'))
);

CREATE INDEX IF NOT EXISTS idx_agent_run_step_run
    ON agent_run_step (run_id, seq);

-- ---------- 行级安全（与 V8 同一套表达式，子表也带 tenant_id，RLS 不会替我们 join 父表） ----------
ALTER TABLE agent_run      ENABLE ROW LEVEL SECURITY;
ALTER TABLE agent_run_step ENABLE ROW LEVEL SECURITY;

DO $$
DECLARE
    t text;
BEGIN
    FOREACH t IN ARRAY ARRAY['agent_run', 'agent_run_step']
    LOOP
        EXECUTE format('DROP POLICY IF EXISTS p_%I_tenant ON %I', t, t);
        EXECUTE format(
            'CREATE POLICY p_%I_tenant ON %I FOR ALL '
            'USING (tenant_id = nullif(current_setting(''app.tenant_id'', true), '''')::bigint) '
            'WITH CHECK (tenant_id = nullif(current_setting(''app.tenant_id'', true), '''')::bigint)',
            t, t);
    END LOOP;
END $$;

GRANT SELECT, INSERT, UPDATE, DELETE ON agent_run, agent_run_step TO finagent_app;
GRANT USAGE, SELECT ON SEQUENCE agent_run_step_id_seq TO finagent_app;

-- ---------- 注释 ----------
COMMENT ON TABLE agent_run IS
    $$一次 Agent 运行（复杂问题才走这条路；简单问题仍走阶段 5 的直线链路）$$;
COMMENT ON COLUMN agent_run.id IS
    $$运行 id（UUID，服务端生成；同时作为 LangGraph4j 的 thread_name，不可猜）$$;
COMMENT ON COLUMN agent_run.tenant_id IS
    $$所属租户；RLS 依据$$;
COMMENT ON COLUMN agent_run.user_id IS
    $$发起人；归属校验用它，别人的 run 一律 404$$;
COMMENT ON COLUMN agent_run.conversation_id IS
    $$所属会话（可空）；会话被删时置空而不是删运行记录$$;
COMMENT ON COLUMN agent_run.question IS
    $$用户原始问题（永不改写；改写结果进 plan_json）$$;
COMMENT ON COLUMN agent_run.status IS
    $$RUNNING 运行中 / WAITING_APPROVAL 等人批 / DONE 完成 / FAILED 失败 / REJECTED 被驳回$$;
COMMENT ON COLUMN agent_run.current_node IS
    $$最后跑完（或停在）的节点 id$$;
COMMENT ON COLUMN agent_run.plan_json IS
    $$规划结果（子问题、改写后的检索式、预计步数）；JSON 便于前端直接渲染计划卡$$;
COMMENT ON COLUMN agent_run.decision IS
    $$人工决定：APPROVE / EDIT / REJECT；未审批时为 NULL$$;
COMMENT ON COLUMN agent_run.decision_note IS
    $$人工备注；EDIT 时是要点补充，REJECT 时是驳回原因$$;
COMMENT ON COLUMN agent_run.error IS
    $$失败原因（异常类名 + 消息），成功时为空$$;
COMMENT ON COLUMN agent_run.created_at IS
    $$创建时间$$;
COMMENT ON COLUMN agent_run.updated_at IS
    $$最后更新时间（每次状态变化都刷新）$$;

COMMENT ON TABLE agent_run_step IS
    $$Agent 每一步的流水（只追加不改；前端时间线、排查耗时都读它）$$;
COMMENT ON COLUMN agent_run_step.run_id IS
    $$所属运行；运行删除时级联删除$$;
COMMENT ON COLUMN agent_run_step.tenant_id IS
    $$冗余租户（与 agent_run 一致）；子表也带是为了让 RLS 直接生效，不必 join 父表$$;
COMMENT ON COLUMN agent_run_step.seq IS
    $$步序（从 1 开始，运行内唯一）$$;
COMMENT ON COLUMN agent_run_step.node IS
    $$节点 id：understand / plan / retrieve / verify / rewrite / answer / cite_check / respond_insufficient$$;
COMMENT ON COLUMN agent_run_step.status IS
    $$OK 成功 / FAILED 失败 / SKIPPED 跳过（例如没走的分支）$$;
COMMENT ON COLUMN agent_run_step.summary IS
    $$一句话摘要（给时间线显示，不是完整状态）$$;
COMMENT ON COLUMN agent_run_step.duration_ms IS
    $$该节点耗时（毫秒）；未知时为空$$;