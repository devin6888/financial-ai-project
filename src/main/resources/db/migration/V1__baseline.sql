-- ============================================================
-- V1__baseline.sql
-- 阶段 1 的基线 schema，由 Flyway 接管后的第一次迁移。
-- 内容 = 原 docker/postgres/init/01-extensions.sql + 02-schema.sql
--
-- 从阶段 2 起 schema 只有一个来源（Flyway）：
--   改表请新增 V*.sql，不要再 docker compose down -v。
-- ============================================================

-- ============================================================
-- 01-extensions.sql
-- 安装扩展并创建中文文本搜索配置
-- 只在数据卷为空时自动执行一次（docker-entrypoint-initdb.d 机制）
-- ============================================================

-- 向量检索（pgvector 镜像已自带该扩展）
CREATE EXTENSION IF NOT EXISTS vector;

-- 中文分词（基于 SCWS，镜像中已编译安装）
CREATE EXTENSION IF NOT EXISTS zhparser;

-- 中文文本搜索配置：以 zhparser 为解析器
-- 词性映射：n 名词 / v 动词 / a 形容词 / i 成语 / e 叹词 / l 习用语 / j 简称
-- 若重复执行，先删后建以保证幂等
DROP TEXT SEARCH CONFIGURATION IF EXISTS chinese_zh;
CREATE TEXT SEARCH CONFIGURATION chinese_zh (PARSER = zhparser);
ALTER TEXT SEARCH CONFIGURATION chinese_zh ADD MAPPING FOR n,v,a,i,e,l,j WITH simple;

-- ============================================================
-- 02-schema.sql
-- 业务表结构（阶段 1 只建表，不写数据）
-- 对应设计文档 §15 核心数据模型
-- 执行顺序必须在 01-extensions.sql 之后（依赖 chinese_zh 配置）
-- ============================================================

-- ---------- 公司 ----------
CREATE TABLE IF NOT EXISTS company (
    id          BIGSERIAL PRIMARY KEY,
    code        VARCHAR(16) NOT NULL UNIQUE,
    name        VARCHAR(128) NOT NULL,
    market      VARCHAR(16),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- ---------- 文档 ----------
CREATE TABLE IF NOT EXISTS document (
    id           BIGSERIAL PRIMARY KEY,
    company_id   BIGINT REFERENCES company(id),
    doc_type     VARCHAR(32)  NOT NULL,
    doc_year     INT,
    title        VARCHAR(512),
    object_key   VARCHAR(512) NOT NULL,
    content_hash VARCHAR(64)  NOT NULL UNIQUE,
    parse_status VARCHAR(16)  NOT NULL DEFAULT 'PENDING',
    version      INT          NOT NULL DEFAULT 1,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_document_company ON document(company_id, doc_year);

-- ---------- 切块：Parent（上下文单元）与 Child（检索单元）----------
CREATE TABLE IF NOT EXISTS document_chunk (
    id                     BIGSERIAL PRIMARY KEY,
    document_id            BIGINT NOT NULL REFERENCES document(id),
    parent_id              BIGINT REFERENCES document_chunk(id),
    chunk_level            VARCHAR(8)  NOT NULL,          -- PARENT / CHILD
    chunk_type             VARCHAR(16) NOT NULL,          -- TEXT / TABLE / LIST
    section_path           TEXT,
    title                  TEXT,
    content                TEXT NOT NULL,
    page_start             INT,
    page_end               INT,
    source_block_ids       TEXT,
    token_count            INT,
    chunk_index            INT,
    status                 VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    parser_version         VARCHAR(32),
    chunk_strategy_version VARCHAR(32),
    embedding              vector(1024),                  -- 百炼 text-embedding-v4 锁定 1024 维
    embedding_model        VARCHAR(64),
    embedding_version      VARCHAR(32),
    metadata               JSONB,
    created_at             TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_chunk_document ON document_chunk(document_id, chunk_level, status);
CREATE INDEX IF NOT EXISTS idx_chunk_parent   ON document_chunk(parent_id);

-- 全文检索列（Sparse 路）：使用中文配置，随内容自动生成
ALTER TABLE document_chunk ADD COLUMN IF NOT EXISTS content_tsv tsvector
    GENERATED ALWAYS AS (to_tsvector('chinese_zh', coalesce(title,'') || ' ' || content)) STORED;
CREATE INDEX IF NOT EXISTS idx_chunk_tsv ON document_chunk USING GIN(content_tsv);

-- 向量索引：1024 维 HNSW 余弦距离
CREATE INDEX IF NOT EXISTS idx_chunk_embedding ON document_chunk
    USING hnsw (embedding vector_cosine_ops);

-- ---------- 结构化事实（阶段 1 只用 PDF_EXTRACT）----------
-- source_type 的取值域保留 XBRL / PROVIDER 作为扩展点，见设计文档 §8.7
CREATE TABLE IF NOT EXISTS financial_fact (
    id             BIGSERIAL PRIMARY KEY,
    document_id    BIGINT NOT NULL REFERENCES document(id),
    chunk_id       BIGINT REFERENCES document_chunk(id),
    company_id     BIGINT REFERENCES company(id),
    metric         VARCHAR(64) NOT NULL,
    period         VARCHAR(32),
    value          NUMERIC(24,4),
    unit           VARCHAR(16),
    scope          VARCHAR(32),                           -- 合并 / 母公司
    evidence_pages TEXT,
    confidence     NUMERIC(4,3),
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_fact_company_metric ON financial_fact(company_id, metric, period);
