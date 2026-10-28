-- ============================================================
-- V7__task_messaging.sql
-- 阶段 7：异步任务消息表 —— 事件去重 + 重试计数 + DLQ 留痕。
--
-- 为什么需要这张表：Kafka 是 at-least-once 投递，同一条消息可能被重投
-- （网络抖动、消费者崩溃重放、手动重放 DLQ）。没有消息级去重，同一份 PDF
-- 会被解析/切块多次，产生重复 chunk / 重复向量。
--
-- 两级去重：
--   1) 消息级：event_id UNIQUE —— 同一条 Kafka 事件只落一行（本表）。
--   2) 业务级：document(content_hash, parse_status) + 版本化切块（阶段 3 已有）。
--
-- status 状态机（复用设计文档 §13.1）：RECEIVED → PROCESSING → SUCCESS | FAILED | DLQ
-- ============================================================

CREATE TABLE IF NOT EXISTS task_message (
    id           BIGSERIAL PRIMARY KEY,
    -- Kafka 事件 UUID，消息级幂等去重
    event_id     VARCHAR(64)  NOT NULL UNIQUE,
    -- 来源 topic：document.parse / document.reindex
    topic        VARCHAR(64)  NOT NULL,
    -- Kafka 消息 key，即 documentId 字符串
    message_key  VARCHAR(64)  NOT NULL,
    -- 故意不加外键：坏消息（documentId 不存在）也要能落表留痕，进 DLQ 等人工修复
    document_id  BIGINT       NOT NULL,
    -- RECEIVED → PROCESSING → SUCCESS | FAILED | DLQ
    status       VARCHAR(16)  NOT NULL DEFAULT 'RECEIVED'
                 CONSTRAINT chk_task_message_status
                 CHECK (status IN ('RECEIVED', 'PROCESSING', 'SUCCESS', 'FAILED', 'DLQ')),
    -- 瞬时错误的重试次数（业务错误不重试，见 RetryClassifier）
    retry_count  INT          NOT NULL DEFAULT 0,
    -- 最近一次错误信息（诊断 + DLQ 人工排查）
    last_error   TEXT,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- 按文档查它的任务历史（诊断「这份 PDF 到底被处理了几次」）
CREATE INDEX IF NOT EXISTS idx_task_message_document
    ON task_message (document_id, created_at DESC);

COMMENT ON TABLE task_message IS '异步任务消息：Kafka 事件级去重 + 重试计数 + 死信留痕';
COMMENT ON COLUMN task_message.event_id IS 'Kafka 事件 UUID，UNIQUE 保证同一条事件只落一行（消息级幂等）';
COMMENT ON COLUMN task_message.topic IS '来源 topic：document.parse / document.reindex';
COMMENT ON COLUMN task_message.message_key IS 'Kafka 消息 key，即 documentId 字符串';
COMMENT ON COLUMN task_message.document_id IS '目标文档 id。不加外键：坏消息（id 不存在）也要能留痕进 DLQ';
COMMENT ON COLUMN task_message.status IS 'RECEIVED/PROCESSING/SUCCESS/FAILED/DLQ（CHECK 约束枚举）';
COMMENT ON COLUMN task_message.retry_count IS '瞬时错误的重试次数（业务错误不重试，见 RetryClassifier）';
COMMENT ON COLUMN task_message.last_error IS '最近一次错误信息，用于诊断与 DLQ 人工排查';
