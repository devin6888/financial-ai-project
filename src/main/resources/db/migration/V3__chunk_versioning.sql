-- ============================================================
-- V3__chunk_versioning.sql
-- 阶段 3：chunk 版本化 + 补齐设计文档 §8.8 要求的字段
--
-- 为什么需要：
--   1) §8.6 的「重索引原子切换」依赖 chunk_version + document.active_chunk_version，
--      而阶段 1 建表时只留了 status / chunk_strategy_version，没有版本键；
--   2) §8.8 要求 chunk 带 caption / unit / headers_json / bbox 等表格与定位字段；
--   3) 并发重索引需要唯一索引兜底（评审 C1）。
--
-- 注：加列用 ADD COLUMN IF NOT EXISTS，可重复执行。
-- ============================================================

-- ---------- document：当前生效的 chunk 版本 ----------
ALTER TABLE document
    ADD COLUMN IF NOT EXISTS active_chunk_version   INT,
    ADD COLUMN IF NOT EXISTS chunk_strategy_version VARCHAR(32);

COMMENT ON COLUMN document.active_chunk_version IS
    '检索只读该版本对应的 ACTIVE chunk；重索引时原子切换，失败则保留旧值';
COMMENT ON COLUMN document.chunk_strategy_version IS
    '产出 active_chunk_version 的切块策略版本，便于判断是否需要 RECHUNK';

-- ---------- document_chunk：版本键 + §8.8 字段 ----------
ALTER TABLE document_chunk
    -- 版本键（重索引原子切换的核心）
    ADD COLUMN IF NOT EXISTS chunk_version        INT,
    -- 定位
    ADD COLUMN IF NOT EXISTS bbox                 JSONB,
    ADD COLUMN IF NOT EXISTS source_offset        TEXT,
    -- 表格元数据（§8.7：TABLE Child 必须携带）
    ADD COLUMN IF NOT EXISTS caption              TEXT,
    ADD COLUMN IF NOT EXISTS unit                 VARCHAR(32),
    ADD COLUMN IF NOT EXISTS currency             VARCHAR(8),
    ADD COLUMN IF NOT EXISTS headers_json         JSONB,
    -- 关联与审计
    ADD COLUMN IF NOT EXISTS related_footnote_ids TEXT,
    ADD COLUMN IF NOT EXISTS restatement_of       BIGINT REFERENCES document_chunk(id),
    ADD COLUMN IF NOT EXISTS embedding_text_hash  VARCHAR(64);

COMMENT ON COLUMN document_chunk.chunk_version IS
    '所属 chunk 版本；配合 document.active_chunk_version 实现原子切换';
COMMENT ON COLUMN document_chunk.bbox IS
    '归一化到 [0,1] 的页面矩形，供前端高亮。跨页 chunk 只代表首页区域，必须配合 page_start/page_end';
COMMENT ON COLUMN document_chunk.source_offset IS
    '字符级偏移。阶段 2 的产物没有这个粒度 —— 本阶段【只建列不写入】，值恒为 NULL（评审 Y3）';
COMMENT ON COLUMN document_chunk.restatement_of IS
    '重述/追溯调整指向。本阶段【只建列不写入】（评审 Y3）';
COMMENT ON COLUMN document_chunk.related_footnote_ids IS
    '关联脚注的 block_id。本阶段【只建列不写入】—— 脚注关联需要解析侧的语义链接（评审 Y3）';

-- ---------- 索引 ----------

-- 检索主路径：文档 + 版本 + 层级 + 状态
CREATE INDEX IF NOT EXISTS idx_chunk_active
    ON document_chunk (document_id, chunk_version, chunk_level, status);

-- 重嵌入扫描：找出某模型/版本下缺失或不匹配的向量
CREATE INDEX IF NOT EXISTS idx_chunk_embedding_meta
    ON document_chunk (embedding_model, embedding_version)
    WHERE embedding IS NOT NULL;

-- 【评审 C1】并发兜底：同一版本内 (层级, 顺序号) 必须唯一。
-- 没有它，两个并发重索引会把两套数据混进同一 chunk_version ——
-- 全是 ACTIVE、全部可检索、且不报任何错，正是最怕的「新老混用」。
-- 带 chunk_level 是必须的：PARENT 与 CHILD 的 chunk_index 各自从 0 编号。
CREATE UNIQUE INDEX IF NOT EXISTS uq_chunk_version_index
    ON document_chunk (document_id, chunk_version, chunk_level, chunk_index);
