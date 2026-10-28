-- 阶段 8（ST8 Task 1）：租户、用户、角色、资源授权，以及行级安全（RLS）。
--
-- 顺序很重要：先建表并回填 tenant_id，再 ENABLE ROW LEVEL SECURITY，
-- 否则回填语句自己会被策略拦住（策略生效时 app.tenant_id 还没设）。
--
-- 本版只加**可空** tenant_id：此刻代码还没有地方写 tenant_id，
-- 一加 NOT NULL 就会把所有写路径（解析、切块、Kafka worker）打断。
-- 收紧到 NOT NULL 放在 V9（ST8 Task 4）。

-- ---------- 1) 租户 ----------
CREATE TABLE IF NOT EXISTS tenant (
    id         BIGSERIAL PRIMARY KEY,
    name       VARCHAR(128) NOT NULL,
    status     VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE'
               CONSTRAINT chk_tenant_status CHECK (status IN ('ACTIVE', 'SUSPENDED')),
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- ---------- 2) 用户（token 认证，不存明文 token） ----------
CREATE TABLE IF NOT EXISTS app_user (
    id             BIGSERIAL PRIMARY KEY,
    tenant_id      BIGINT      NOT NULL REFERENCES tenant(id),
    username       VARCHAR(64) NOT NULL,
    display_name   VARCHAR(128),
    status         VARCHAR(16) NOT NULL DEFAULT 'ACTIVE'
                   CONSTRAINT chk_app_user_status CHECK (status IN ('ACTIVE', 'DISABLED')),
    -- SHA-256(api token) 的十六进制串；token 本身只在签发时出现一次
    api_token_hash CHAR(64)    NOT NULL UNIQUE,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, username)
);

-- ---------- 3) 角色与绑定（V1 内置 ADMIN / ANALYST / VIEWER） ----------
CREATE TABLE IF NOT EXISTS role (
    id         BIGSERIAL PRIMARY KEY,
    tenant_id  BIGINT      NOT NULL REFERENCES tenant(id),
    code       VARCHAR(32) NOT NULL,
    name       VARCHAR(64) NOT NULL,
    is_system  BOOLEAN     NOT NULL DEFAULT false,
    UNIQUE (tenant_id, code)
);

CREATE TABLE IF NOT EXISTS user_role (
    user_id BIGINT NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    role_id BIGINT NOT NULL REFERENCES role(id)     ON DELETE CASCADE,
    PRIMARY KEY (user_id, role_id)
);

-- ---------- 4) 显式资源授权 ----------
-- permission_scope 由本表解析得出，不接受调用方传入（设计 §15.2）。
CREATE TABLE IF NOT EXISTS resource_grant (
    id            BIGSERIAL PRIMARY KEY,
    tenant_id     BIGINT      NOT NULL REFERENCES tenant(id),
    subject_type  VARCHAR(8)  NOT NULL
                  CONSTRAINT chk_grant_subject CHECK (subject_type IN ('USER', 'ROLE')),
    subject_id    BIGINT      NOT NULL,
    resource_type VARCHAR(16) NOT NULL
                  CONSTRAINT chk_grant_resource CHECK (resource_type IN ('COMPANY', 'DOCUMENT', 'SECTION')),
    resource_id   BIGINT      NOT NULL,
    permission    VARCHAR(16) NOT NULL DEFAULT 'READ'
                  CONSTRAINT chk_grant_permission CHECK (permission IN ('READ', 'READ_WRITE')),
    granted_by    BIGINT      REFERENCES app_user(id),
    expires_at    TIMESTAMPTZ,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_grant_lookup
    ON resource_grant (tenant_id, subject_type, subject_id, resource_type, resource_id);
CREATE INDEX IF NOT EXISTS idx_grant_resource
    ON resource_grant (tenant_id, resource_type, resource_id);

-- ---------- 5) 业务表补 tenant_id（可空，下一步回填） ----------
ALTER TABLE company        ADD COLUMN IF NOT EXISTS tenant_id BIGINT REFERENCES tenant(id);
ALTER TABLE document       ADD COLUMN IF NOT EXISTS tenant_id BIGINT REFERENCES tenant(id);
ALTER TABLE document       ADD COLUMN IF NOT EXISTS visibility VARCHAR(16) NOT NULL DEFAULT 'TENANT';
ALTER TABLE document       ADD COLUMN IF NOT EXISTS owner_user_id BIGINT REFERENCES app_user(id);
ALTER TABLE document_chunk ADD COLUMN IF NOT EXISTS tenant_id BIGINT REFERENCES tenant(id);
ALTER TABLE financial_fact ADD COLUMN IF NOT EXISTS tenant_id BIGINT REFERENCES tenant(id);
ALTER TABLE task_message   ADD COLUMN IF NOT EXISTS tenant_id BIGINT REFERENCES tenant(id);

ALTER TABLE document ADD CONSTRAINT chk_document_visibility
    CHECK (visibility IN ('TENANT', 'PRIVATE'));

-- ---------- 6) 种子租户 + 存量数据回填 ----------
INSERT INTO tenant (name, status)
SELECT 'demo', 'ACTIVE'
 WHERE NOT EXISTS (SELECT 1 FROM tenant WHERE name = 'demo');

UPDATE company        SET tenant_id = (SELECT min(id) FROM tenant) WHERE tenant_id IS NULL;
UPDATE document       SET tenant_id = (SELECT min(id) FROM tenant) WHERE tenant_id IS NULL;
UPDATE document_chunk SET tenant_id = (SELECT min(id) FROM tenant) WHERE tenant_id IS NULL;
UPDATE financial_fact SET tenant_id = (SELECT min(id) FROM tenant) WHERE tenant_id IS NULL;
UPDATE task_message   SET tenant_id = (SELECT min(id) FROM tenant) WHERE tenant_id IS NULL;

-- ---------- 7) 以 tenant_id 打头的复合索引（设计 §15.4） ----------
CREATE INDEX IF NOT EXISTS idx_company_tenant   ON company (tenant_id, code);
CREATE INDEX IF NOT EXISTS idx_document_tenant  ON document (tenant_id, company_id, doc_year);
CREATE INDEX IF NOT EXISTS idx_chunk_tenant     ON document_chunk (tenant_id, document_id, chunk_level, status, chunk_version);
CREATE INDEX IF NOT EXISTS idx_fact_tenant      ON financial_fact (tenant_id, company_id, metric, period);
CREATE INDEX IF NOT EXISTS idx_task_message_tenant
    ON task_message (tenant_id, document_id, created_at DESC);

-- ---------- 8) 运行时角色（非 owner -> RLS 生效） ----------
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'finagent_app') THEN
        CREATE ROLE finagent_app LOGIN PASSWORD 'finagent_app';
    END IF;
END $$;

GRANT USAGE ON SCHEMA public TO finagent_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO finagent_app;
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO finagent_app;
ALTER DEFAULT PRIVILEGES IN SCHEMA public
    GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO finagent_app;
ALTER DEFAULT PRIVILEGES IN SCHEMA public
    GRANT USAGE, SELECT ON SEQUENCES TO finagent_app;

-- ---------- 9) 行级安全策略 ----------
-- 关键写法：nullif(current_setting('app.tenant_id', true), '')::bigint
--   - 变量没设   -> current_setting 返回 NULL -> 比较结果是 NULL -> 0 行（fail closed）
--   - 变量被清空 -> 返回 '' -> nullif 变 NULL -> 同样 0 行
-- USING 管读，WITH CHECK 管写；两者都要，否则可以插别人租户的行。
--
-- 故意不加 FORCE ROW LEVEL SECURITY：加了连 Flyway 的迁移数据语句也会被拦
--（迁移时没有租户上下文）。改用「owner 迁移 + finagent_app 运行时」双角色。
ALTER TABLE company        ENABLE ROW LEVEL SECURITY;
ALTER TABLE document       ENABLE ROW LEVEL SECURITY;
ALTER TABLE document_chunk ENABLE ROW LEVEL SECURITY;
ALTER TABLE financial_fact ENABLE ROW LEVEL SECURITY;
ALTER TABLE task_message   ENABLE ROW LEVEL SECURITY;

DO $$
DECLARE
    t text;
BEGIN
    FOREACH t IN ARRAY ARRAY['company', 'document', 'document_chunk', 'financial_fact', 'task_message']
    LOOP
        EXECUTE format('DROP POLICY IF EXISTS p_%I_tenant ON %I', t, t);
        EXECUTE format(
            'CREATE POLICY p_%I_tenant ON %I FOR ALL '
            'USING (tenant_id = nullif(current_setting(''app.tenant_id'', true), '''')::bigint) '
            'WITH CHECK (tenant_id = nullif(current_setting(''app.tenant_id'', true), '''')::bigint)',
            t, t);
    END LOOP;
END $$;

-- ---------- 10) demo 用户与角色（token 哈希由 sha256 生成） ----------
-- 明文 token 只出现在下面这行注释与 README 里，库里只有哈希：
--   dev-admin-token    -> ADMIN   （本租户全部可读）
--   dev-analyst-token  -> ANALYST （需要 company/document 授权；种子给了 300059）
--   dev-outsider-token -> VIEWER  （没有 300059 的授权，越权用例默认用它）
INSERT INTO app_user (tenant_id, username, display_name, api_token_hash)
SELECT t.id, v.username, v.display_name, encode(sha256(convert_to(v.raw, 'UTF8')), 'hex')
  FROM tenant t,
       (VALUES ('admin',    '演示管理员',     'dev-admin-token'),
               ('analyst',  '演示分析师',     'dev-analyst-token'),
               ('outsider', '演示无权限用户', 'dev-outsider-token')) AS v(username, display_name, raw)
 WHERE t.name = 'demo'
   AND NOT EXISTS (SELECT 1 FROM app_user u WHERE u.username = v.username);

INSERT INTO role (tenant_id, code, name, is_system)
SELECT t.id, v.code, v.name, true
  FROM tenant t,
       (VALUES ('ADMIN', '管理员'), ('ANALYST', '分析师'), ('VIEWER', '只读')) AS v(code, name)
 WHERE t.name = 'demo'
   AND NOT EXISTS (SELECT 1 FROM role r WHERE r.tenant_id = t.id AND r.code = v.code);

INSERT INTO user_role (user_id, role_id)
SELECT u.id, r.id
  FROM app_user u
  JOIN role r ON r.tenant_id = u.tenant_id
 WHERE u.username = 'admin' AND r.code = 'ADMIN'
   AND NOT EXISTS (SELECT 1 FROM user_role ur WHERE ur.user_id = u.id AND ur.role_id = r.id);

INSERT INTO user_role (user_id, role_id)
SELECT u.id, r.id
  FROM app_user u
  JOIN role r ON r.tenant_id = u.tenant_id
 WHERE u.username = 'analyst' AND r.code = 'ANALYST'
   AND NOT EXISTS (SELECT 1 FROM user_role ur WHERE ur.user_id = u.id AND ur.role_id = r.id);

INSERT INTO user_role (user_id, role_id)
SELECT u.id, r.id
  FROM app_user u
  JOIN role r ON r.tenant_id = u.tenant_id
 WHERE u.username = 'outsider' AND r.code = 'VIEWER'
   AND NOT EXISTS (SELECT 1 FROM user_role ur WHERE ur.user_id = u.id AND ur.role_id = r.id);

-- analyst 对 300059（东方财富）有 COMPANY 级 READ 授权；outsider 故意不给
INSERT INTO resource_grant (tenant_id, subject_type, subject_id, resource_type, resource_id, permission)
SELECT r.tenant_id, 'ROLE', r.id, 'COMPANY', c.id, 'READ'
  FROM role r
  JOIN company c ON c.code = '300059' AND c.tenant_id = r.tenant_id
 WHERE r.code = 'ANALYST'
   AND NOT EXISTS (
       SELECT 1 FROM resource_grant g
        WHERE g.tenant_id = r.tenant_id AND g.subject_type = 'ROLE'
          AND g.subject_id = r.id AND g.resource_type = 'COMPANY' AND g.resource_id = c.id);

-- ---------- 11) 注释 ----------
COMMENT ON TABLE tenant IS '租户根；所有业务表带 tenant_id 并参与每一条检索过滤（设计 §15.2）';
COMMENT ON COLUMN tenant.name IS '租户名（唯一性由应用保证）';
COMMENT ON COLUMN tenant.status IS 'ACTIVE/SUSPENDED；SUSPENDED 后不允许登录';
COMMENT ON TABLE app_user IS '用户主体；api_token_hash 是 SHA-256(token)，不存明文';
COMMENT ON COLUMN app_user.api_token_hash IS 'SHA-256(token) 的十六进制（64 字符），token 明文只在签发时出现';
COMMENT ON TABLE role IS '角色：ADMIN/ANALYST/VIEWER（is_system=true 表示内置不可删）';
COMMENT ON TABLE user_role IS '用户与角色多对多';
COMMENT ON TABLE resource_grant IS '显式资源授权：USER/ROLE × COMPANY/DOCUMENT/SECTION × READ/READ_WRITE';
COMMENT ON COLUMN resource_grant.expires_at IS '到期时间；NULL 表示永久有效';
COMMENT ON COLUMN document.visibility IS 'TENANT=租户内按 company 授权可读；PRIVATE=仅 owner 与显式授权';
COMMENT ON COLUMN document.owner_user_id IS 'PRIVATE 文档的属主；TENANT 文档为空';
COMMENT ON COLUMN document_chunk.tenant_id IS '冗余自 document.tenant_id：RLS 要能直接过滤 chunk，不 join document';
