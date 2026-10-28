-- 阶段 8（ST8 Task 4）：租户隔离收尾
--
-- 三件事：
--   1) 把「全局唯一」改成「租户内唯一」——否则第二租户加同一家公司/同一份 PDF 必然冲突，
--      而且 ON CONFLICT 撞上别的租户的行时，RLS 会直接报 42501（new row violates row-level security policy）。
--   2) tenant_id 收紧为 NOT NULL（V8 故意留可空，等所有写路径都带上值）。
--   3) 收窄 finagent_app 的权限：认证相关的五张表只给 SELECT。

-- ---------- 1) 唯一约束改为租户内唯一 ----------
ALTER TABLE company DROP CONSTRAINT IF EXISTS company_code_key;
ALTER TABLE company ADD CONSTRAINT uq_company_tenant_code UNIQUE (tenant_id, code);

ALTER TABLE document DROP CONSTRAINT IF EXISTS document_content_hash_key;
ALTER TABLE document ADD CONSTRAINT uq_document_tenant_hash UNIQUE (tenant_id, content_hash);

DROP INDEX IF EXISTS uq_document_identity;
CREATE UNIQUE INDEX uq_document_identity
    ON document (tenant_id, company_id, doc_year, doc_type, version)
 WHERE company_id IS NOT NULL AND doc_year IS NOT NULL;

-- ---------- 2) tenant_id 收紧 ----------
-- task_message 故意**不收紧**：坏消息（documentId 指向不存在的文档）根本推不出租户，
-- 而「坏消息也要留痕」是阶段 7 的硬要求（V7 注释）。收紧会让 DLQ 链路直接写不进。
ALTER TABLE company        ALTER COLUMN tenant_id SET NOT NULL;
ALTER TABLE document       ALTER COLUMN tenant_id SET NOT NULL;
ALTER TABLE document_chunk ALTER COLUMN tenant_id SET NOT NULL;
ALTER TABLE financial_fact ALTER COLUMN tenant_id SET NOT NULL;

COMMENT ON COLUMN task_message.tenant_id IS '所属租户；可为空——坏消息（文档不存在）推不出租户，但必须留痕';

-- ---------- 3) 收窄运行时角色权限 ----------
-- V8 用 GRANT ... ON ALL TABLES 一次给了读写，把认证五表也带进去了；
-- 那五张表没有 RLS，一旦有 SQL 注入就能读走全租户的 api_token_hash（等价于读走所有 token）。
REVOKE INSERT, UPDATE, DELETE ON tenant, app_user, role, user_role, resource_grant FROM finagent_app;
GRANT SELECT ON tenant, app_user, role, user_role, resource_grant TO finagent_app;

-- ---------- 4) 允许 owner 切换成运行时角色 ----------
-- 运行时连接以 owner 建池，处理 HTTP 请求时 SET ROLE finagent_app 让 RLS 生效；
-- worker / CLI 不切角色（owner 视角），否则「按 document 行推导租户」会读不到父行。
GRANT finagent_app TO finagent;
