-- 阶段 9（ST9 Task 1）：会话、四层 Memory 与长期记忆。
--
-- 设计依据：架构设计 §11.2（分层）、§11.6（数据模型）、§11.9（Token 配额）、§11.10（Structured Context）、
--           §11.11（LTM Gate 与组织级记忆）；docs/文档作用域设计.md §6.2~§6.3（conversation / message / scope_snapshot）。
--
-- 三条总原则：
--   1) 记忆与证据分离：本文件里的表**都不参与引用编号**，[E1..En] 只来自 document_chunk（契约 C12 第 2 条）；
--   2) 真相源在 PostgreSQL：摘要可从消息重算，指令类记忆必须能每轮重新读到（窗口里没有 != 丢了）；
--   3) 租户边界照 V8：每张表都带 tenant_id 并各自建 RLS 策略（子表也带，RLS 不会替我们 join 父表）。

-- ---------- 1) 会话 ----------
CREATE TABLE IF NOT EXISTS conversation (
    id            BIGSERIAL PRIMARY KEY,
    tenant_id     BIGINT      NOT NULL REFERENCES tenant(id),
    owner_user_id BIGINT      NOT NULL REFERENCES app_user(id),
    title         VARCHAR(200),
    scope_kind    VARCHAR(16) NOT NULL DEFAULT 'CORPUS'
                  CONSTRAINT chk_conversation_scope_kind CHECK (scope_kind IN ('DOCUMENTS', 'FILTER', 'CORPUS')),
    -- 会话级研究范围（sessionScope）。effectiveScope 每轮现算，不进这张表。
    scope_json    JSONB       NOT NULL DEFAULT '{}'::jsonb,
    status        VARCHAR(16) NOT NULL DEFAULT 'ACTIVE'
                  CONSTRAINT chk_conversation_status CHECK (status IN ('ACTIVE', 'DELETED')),
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_conversation_owner
    ON conversation (tenant_id, owner_user_id, updated_at DESC);

-- ---------- 2) 完整聊天历史（审计与回放的真相源） ----------
CREATE TABLE IF NOT EXISTS conversation_message (
    id              BIGSERIAL PRIMARY KEY,
    tenant_id       BIGINT      NOT NULL REFERENCES tenant(id),
    conversation_id BIGINT      NOT NULL REFERENCES conversation(id) ON DELETE CASCADE,
    role            VARCHAR(16) NOT NULL
                    CONSTRAINT chk_message_role CHECK (role IN ('USER', 'ASSISTANT', 'TOOL')),
    content         TEXT        NOT NULL,
    citations_json  JSONB,
    -- 本轮实际用了什么范围：{session, effective, chunkVersions, resolverVersion}
    -- chunkVersions 必须存：文档重切后 chunk_version 会变，历史引用要能指回「当时」那一版。
    scope_snapshot  JSONB       NOT NULL DEFAULT '{}'::jsonb,
    trace_id        VARCHAR(64),
    token_count     INT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_message_conversation
    ON conversation_message (conversation_id, id);
CREATE INDEX IF NOT EXISTS idx_message_tenant
    ON conversation_message (tenant_id, conversation_id, id DESC);

-- ---------- 3) 较早对话的压缩摘要（每会话一行：当前状态，不是事件） ----------
CREATE TABLE IF NOT EXISTS conversation_summary (
    conversation_id           BIGINT PRIMARY KEY REFERENCES conversation(id) ON DELETE CASCADE,
    tenant_id                 BIGINT      NOT NULL REFERENCES tenant(id),
    summary                   TEXT        NOT NULL,
    -- 已汇总到哪条消息；下一轮只汇总它之后的消息（增量，不重复总结）
    summary_until_message_id  BIGINT,
    -- 每次重写 +1；不保留历史摘要行（摘要可由消息重算，见 ST9 §2.6）
    version                   INT         NOT NULL DEFAULT 1,
    updated_at                TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- ---------- 4) 会话结构化上下文（每会话一行） ----------
CREATE TABLE IF NOT EXISTS conversation_context (
    conversation_id BIGINT PRIMARY KEY REFERENCES conversation(id) ON DELETE CASCADE,
    tenant_id       BIGINT      NOT NULL REFERENCES tenant(id),
    company_id      BIGINT      REFERENCES company(id),
    year_from       INT,
    year_to         INT,
    document_ids    BIGINT[],
    current_metric  VARCHAR(64),
    -- 完整快照（含 sourceMessageId / confidence / 各字段来源），便于回放「当时为什么这么理解」
    context_json    JSONB       NOT NULL DEFAULT '{}'::jsonb,
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- ---------- 5) 长期记忆（跨会话；带向量） ----------
CREATE TABLE IF NOT EXISTS long_term_memory (
    id               BIGSERIAL PRIMARY KEY,
    tenant_id        BIGINT       NOT NULL REFERENCES tenant(id),
    -- USER：subject_id = app_user.id（本阶段只启用它）；ORG：subject_id = tenant.id（留字段不启用，见 D6）
    subject_type     VARCHAR(8)   NOT NULL DEFAULT 'USER'
                     CONSTRAINT chk_memory_subject_type CHECK (subject_type IN ('USER', 'ORG')),
    subject_id       BIGINT       NOT NULL,
    -- INSTRUCTION：用户指令（进独立预算，只合并压缩不丢弃）；CONTEXTUAL / WATCHLIST：情境类（进 5% 配额）
    memory_type      VARCHAR(16)  NOT NULL
                     CONSTRAINT chk_memory_type CHECK (memory_type IN ('INSTRUCTION', 'CONTEXTUAL', 'WATCHLIST')),
    -- 结构化键：同一 (subject, type, key) 只允许一条 ACTIVE，命中时可直接精确读取，不必走向量
    memory_key       VARCHAR(128) NOT NULL,
    content          TEXT         NOT NULL,
    -- 可结构化的偏好优先存这里（如 {"style":"conclusion_first"}），content 只作人类可读副本
    value_json       JSONB,
    embedding        vector(1024),
    embedding_model  VARCHAR(64),
    embedding_version VARCHAR(32),
    -- 本阶段只有两类高置信来源；模型推断式写入刻意不做（ST9 D2）
    source_type      VARCHAR(24)  NOT NULL DEFAULT 'USER_API'
                     CONSTRAINT chk_memory_source CHECK (source_type IN ('EXPLICIT_COMMAND', 'USER_API')),
    source_ref       VARCHAR(256)
                     CONSTRAINT chk_memory_source_ref CHECK (source_type <> 'EXPLICIT_COMMAND' OR source_ref IS NOT NULL),
    confidence       NUMERIC(3,2) NOT NULL DEFAULT 1.00
                     CONSTRAINT chk_memory_confidence CHECK (confidence >= 0 AND confidence <= 1),
    importance       SMALLINT     NOT NULL DEFAULT 3
                     CONSTRAINT chk_memory_importance CHECK (importance BETWEEN 1 AND 5),
    status           VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE'
                     CONSTRAINT chk_memory_status CHECK (status IN ('ACTIVE', 'SUPERSEDED', 'DELETED')),
    -- 同 key 被改写时：旧行 SUPERSEDED 保留（「三月说过什么、五月改成什么」不可重算），新行 version = 旧 + 1
    version          INT          NOT NULL DEFAULT 1,
    expires_at       TIMESTAMPTZ,
    last_accessed_at TIMESTAMPTZ,
    retrieval_count  INT          NOT NULL DEFAULT 0,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- 同一 (租户, 主体, 类型, key) 只能有一条 ACTIVE：部分唯一索引（不是约束，ON CONFLICT 要带同样的 WHERE）
CREATE UNIQUE INDEX IF NOT EXISTS uq_memory_active
    ON long_term_memory (tenant_id, subject_id, memory_type, memory_key)
    WHERE status = 'ACTIVE';

CREATE INDEX IF NOT EXISTS idx_memory_lookup
    ON long_term_memory (tenant_id, subject_id, status, memory_type);
CREATE INDEX IF NOT EXISTS idx_memory_expiry
    ON long_term_memory (tenant_id, expires_at) WHERE status = 'ACTIVE';

-- 与 document_chunk 同一形态（默认参数）。数据量小时优化器仍走精确扫描，交叉点约 1 万行，见 docs/pgvector-工作机制.md
CREATE INDEX IF NOT EXISTS idx_memory_embedding ON long_term_memory
    USING hnsw (embedding vector_cosine_ops);

-- ---------- 6) 记忆变更审计（追加型，不可改不可删） ----------
CREATE TABLE IF NOT EXISTS memory_event (
    id          BIGSERIAL PRIMARY KEY,
    tenant_id   BIGINT      NOT NULL REFERENCES tenant(id),
    -- 可空：同一张表既要记「长期记忆变更」，也要记「会话上下文/摘要被改」
    memory_id   BIGINT      REFERENCES long_term_memory(id),
    target_type VARCHAR(24) NOT NULL
                CONSTRAINT chk_memory_event_target CHECK (target_type IN ('MEMORY', 'CONVERSATION_CONTEXT', 'CONVERSATION_SUMMARY')),
    target_id   BIGINT      NOT NULL,
    action      VARCHAR(24) NOT NULL
                CONSTRAINT chk_memory_event_action CHECK (action IN ('CREATE', 'UPDATE', 'SUPERSEDE', 'DELETE', 'EXPIRE', 'CONTEXT_UPDATE', 'SUMMARY_REWRITE')),
    old_value   TEXT,
    new_value   TEXT,
    reason      TEXT,
    trace_id    VARCHAR(64),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_memory_event_target
    ON memory_event (tenant_id, target_type, target_id, created_at DESC);

-- ---------- 7) 行级安全（照 V8 的写法；子表也带 tenant_id，不依赖 join 父表） ----------
ALTER TABLE conversation         ENABLE ROW LEVEL SECURITY;
ALTER TABLE conversation_message ENABLE ROW LEVEL SECURITY;
ALTER TABLE conversation_summary ENABLE ROW LEVEL SECURITY;
ALTER TABLE conversation_context ENABLE ROW LEVEL SECURITY;
ALTER TABLE long_term_memory     ENABLE ROW LEVEL SECURITY;
ALTER TABLE memory_event         ENABLE ROW LEVEL SECURITY;

DO $$
DECLARE
    t text;
BEGIN
    FOREACH t IN ARRAY ARRAY['conversation', 'conversation_message', 'conversation_summary',
                             'conversation_context', 'long_term_memory', 'memory_event']
    LOOP
        EXECUTE format('DROP POLICY IF EXISTS p_%I_tenant ON %I', t, t);
        EXECUTE format(
            'CREATE POLICY p_%I_tenant ON %I FOR ALL '
            'USING (tenant_id = nullif(current_setting(''app.tenant_id'', true), '''')::bigint) '
            'WITH CHECK (tenant_id = nullif(current_setting(''app.tenant_id'', true), '''')::bigint)',
            t, t);
    END LOOP;
END $$;

-- ---------- 8) 运行时权限：审计表只许写，不许改 ----------
-- V8 的 ALTER DEFAULT PRIVILEGES 已给新表自动授权；这里只在它之上收紧 memory_event。
REVOKE UPDATE, DELETE ON memory_event FROM finagent_app;

-- ---------- 9) demo 种子（analyst 的会话与两条记忆；不含 embedding，避免迁移时调外部 API） ----------
INSERT INTO conversation (tenant_id, owner_user_id, title, scope_kind, scope_json)
SELECT u.tenant_id, u.id, '演示会话', 'CORPUS', '{"kind":"CORPUS"}'::jsonb
  FROM app_user u
 WHERE u.username = 'analyst'
   AND NOT EXISTS (SELECT 1 FROM conversation c WHERE c.owner_user_id = u.id AND c.title = '演示会话');

INSERT INTO long_term_memory (tenant_id, subject_type, subject_id, memory_type, memory_key,
                              content, value_json, source_type, source_ref, importance)
SELECT u.tenant_id, 'USER', u.id, v.memory_type, v.memory_key, v.content, v.value_json::jsonb,
       'EXPLICIT_COMMAND', v.source_ref, v.importance
  FROM app_user u,
       (VALUES
         ('INSTRUCTION', 'answer.style',
          '回答先给结论，再给证据；金额统一用亿元并保留两位小数。',
          '{"style":"conclusion_first","currency_unit":"亿元","decimals":2}',
          'conversation:demo#turn1', 5),
         ('WATCHLIST', 'focus.company.300059',
          '长期关注东方财富（300059）的年报与季度报告。',
          '{"companyId":300059,"name":"东方财富"}',
          'conversation:demo#turn1', 3)
       ) AS v(memory_type, memory_key, content, value_json, source_ref, importance)
 WHERE u.username = 'analyst'
   AND NOT EXISTS (
       SELECT 1 FROM long_term_memory m
        WHERE m.tenant_id = u.tenant_id AND m.subject_id = u.id
          AND m.memory_type = v.memory_type AND m.memory_key = v.memory_key
          AND m.status = 'ACTIVE');

-- ---------- 10) 注释 ----------
COMMENT ON TABLE conversation IS '会话（阶段 9）：一面是审计/回放的容器，一面是 sessionScope 的持有者';
COMMENT ON COLUMN conversation.scope_kind IS '会话级研究范围类型：DOCUMENTS / FILTER / CORPUS（sessionScope）';
COMMENT ON COLUMN conversation.scope_json IS 'sessionScope 的完整 JSON；effectiveScope 每轮现算，不存这里';
COMMENT ON COLUMN conversation.status IS 'ACTIVE / DELETED（逻辑删除；消息保留 30 天后物理清理，见设计 §14.3）';
COMMENT ON TABLE conversation_message IS '完整聊天历史：审计与回放的唯一真相源；不直接全部进模型上下文';
COMMENT ON COLUMN conversation_message.scope_snapshot IS '本轮作用域快照 {session, effective, chunkVersions, resolverVersion}；chunkVersions 用于让历史引用指回当时那一版';
COMMENT ON COLUMN conversation_message.token_count IS '该条消息的 token 估算，供短期窗口预算裁剪用';
COMMENT ON TABLE conversation_summary IS '较早对话的压缩摘要：每会话一行，覆盖写，version 递增（不保留历史行）';
COMMENT ON COLUMN conversation_summary.summary_until_message_id IS '已汇总到哪条消息；下一轮只汇总它之后的消息，避免重复总结';
COMMENT ON TABLE conversation_context IS '会话结构化上下文：company / yearRange / documentIds / currentMetric（每会话一行）';
COMMENT ON COLUMN conversation_context.context_json IS '完整快照，含每个字段的来源（用户显式 / 模型推断）与 confidence';
COMMENT ON TABLE long_term_memory IS '长期记忆：跨会话复用；INSTRUCTION 进独立预算，CONTEXTUAL/WATCHLIST 进 5% 配额';
COMMENT ON COLUMN long_term_memory.memory_type IS 'INSTRUCTION=用户指令（只合并压缩不丢弃）；CONTEXTUAL/WATCHLIST=情境记忆（按 importance 丢弃）';
COMMENT ON COLUMN long_term_memory.memory_key IS '结构化键（如 answer.style）；同 subject+type+key 只允许一条 ACTIVE';
COMMENT ON COLUMN long_term_memory.value_json IS '可结构化的偏好优先存这里，content 只作人类可读副本';
COMMENT ON COLUMN long_term_memory.embedding IS '1024 维（与文档同一模型）；普通列，不会自动更新 —— 换模型必须重算（契约 C10 同理）';
COMMENT ON COLUMN long_term_memory.source_type IS '本阶段只允许显式命令与显式 API；模型推断式写入刻意不做（误写会污染之后所有回答）';
COMMENT ON COLUMN long_term_memory.status IS 'ACTIVE / SUPERSEDED（被新版本替代，保留审计）/ DELETED（用户撤销，不再召回）';
COMMENT ON COLUMN long_term_memory.expires_at IS '到期时间；NULL 表示长期有效。过期后不参与召回（在 SQL 里过滤，不靠模型）';
COMMENT ON TABLE memory_event IS '记忆/会话状态变更审计：追加型，运行时可写不可改不可删';
COMMENT ON COLUMN memory_event.target_type IS 'MEMORY / CONVERSATION_CONTEXT / CONVERSATION_SUMMARY —— 同一张表覆盖三类变更';
COMMENT ON COLUMN memory_event.target_id IS '对应目标行的 id（MEMORY 时为 long_term_memory.id）';
