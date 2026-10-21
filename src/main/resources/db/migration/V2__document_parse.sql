-- ============================================================
-- V2__document_parse.sql
-- 合并方案：解析状态与结果直接落在 document 上，不单独建 parse_job 表。
--
-- 列分成两组，因为寿命不同：
--   上面 5 列 =「关于文档的事实」，阶段 7 上 Kafka 后依然要保留；
--   下面 4 列 =「在途状态」，阶段 7 会被通用异步流水线取代。
--
-- 不建 parse_job 的理由（详见计划「追加变更 B」）：
--   1) 阶段 1 建表时把 parse_status 放在 document 上，答案已经给出；
--   2) parse-specific 的作业表注定是"专表"，而未来需要的是"通表"。
-- ============================================================

ALTER TABLE document
    ADD COLUMN IF NOT EXISTS parsed_object_key  VARCHAR(512),
    ADD COLUMN IF NOT EXISTS parser             VARCHAR(32),
    ADD COLUMN IF NOT EXISTS parser_version     VARCHAR(32),
    ADD COLUMN IF NOT EXISTS pages_parsed       INT,
    ADD COLUMN IF NOT EXISTS parsed_at          TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS parse_job_id       VARCHAR(64),
    ADD COLUMN IF NOT EXISTS parse_error        TEXT,
    ADD COLUMN IF NOT EXISTS parse_started_at   TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS parse_finished_at  TIMESTAMPTZ;

COMMENT ON COLUMN document.parse_status IS 'PENDING / RUNNING / DONE / FAILED';

-- 启动恢复扫描走这条（部分索引，只覆盖在途行，几乎不占空间）
CREATE INDEX IF NOT EXISTS idx_document_parse_active
    ON document (parse_status)
    WHERE parse_status IN ('PENDING', 'RUNNING');
