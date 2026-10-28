-- V11：账号密码登录与会话 token（阶段 10 §11）
--
-- 背景：此前 app_user.api_token_hash 一列一个明文预置 token（dev-analyst-token 等），
-- 没有密码、也无法按设备签发与撤销。本迁移补两件事：
--   1) app_user.password_hash：bcrypt 哈希（pgcrypto 的 crypt + bf 盐），校验也在库里做；
--   2) api_session：登录后签发的会话 token，只存 SHA-256，可撤销、可过期。
--
-- 同时创建默认演示用户 demo / demo123456，并把现有数据挂到它名下（文档、长期记忆、会话）。
-- company 不动：它是租户级共享数据，没有 owner 列（见 docs/关键契约.md C13）。

CREATE EXTENSION IF NOT EXISTS pgcrypto;

ALTER TABLE app_user ADD COLUMN IF NOT EXISTS password_hash TEXT;

COMMENT ON COLUMN app_user.password_hash IS 'bcrypt 哈希（pgcrypto: crypt(pw, gen_salt(''bf'', 10))）；为空表示该账号只能用它自己的 api_token_hash 登录';

CREATE TABLE api_session (
    id           BIGSERIAL PRIMARY KEY,
    user_id      BIGINT      NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    tenant_id    BIGINT      NOT NULL REFERENCES tenant (id),
    token_hash   CHAR(64)    NOT NULL UNIQUE,
    label        VARCHAR(64),
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_used_at TIMESTAMPTZ,
    expires_at   TIMESTAMPTZ NOT NULL,
    revoked_at   TIMESTAMPTZ
);

CREATE INDEX idx_api_session_user ON api_session (user_id, created_at DESC);

COMMENT ON TABLE api_session IS '登录后签发的会话 token；库里只存 SHA-256(token)，登出 = 写 revoked_at（不物理删）';
COMMENT ON COLUMN api_session.token_hash IS 'SHA-256(明文 token) 的十六进制；与 app_user.api_token_hash 同一口径（ApiTokenRepository.sha256Hex）';
COMMENT ON COLUMN api_session.label IS '签发来源，例如 web-login / cli；仅用于审计与排查';
COMMENT ON COLUMN api_session.expires_at IS '过期时间；过期后 AuthenticationFilter 直接当无效，不依赖定时任务';
COMMENT ON COLUMN api_session.revoked_at IS '撤销时间；登出即写，保留行以便审计「这个 token 什么时候被谁撤销」';

-- 运行时角色需要写会话（登录 INSERT、每次请求 touch last_used_at、登出 UPDATE revoked_at）
GRANT SELECT, INSERT, UPDATE ON api_session TO finagent_app;
GRANT USAGE, SELECT ON SEQUENCE api_session_id_seq TO finagent_app;

-- 默认演示用户：密码 demo123456（bcrypt cost 10），api_token_hash 随机生成以保证唯一约束成立
INSERT INTO app_user (tenant_id, username, display_name, status, api_token_hash, password_hash)
SELECT t.id,
       'demo',
       '演示用户',
       'ACTIVE',
       md5(random()::text) || md5(gen_random_uuid()::text),
       crypt('demo123456', gen_salt('bf', 10))
  FROM tenant t
 WHERE t.id = 1
   AND NOT EXISTS (SELECT 1 FROM app_user u WHERE u.tenant_id = t.id AND u.username = 'demo');

INSERT INTO user_role (user_id, role_id)
SELECT u.id, r.id
  FROM app_user u
  JOIN role r ON r.tenant_id = u.tenant_id AND r.code IN ('ADMIN', 'ANALYST')
 WHERE u.tenant_id = 1
   AND u.username = 'demo'
ON CONFLICT DO NOTHING;

-- 现有数据挂到 demo 名下：文档归属、长期记忆主体、会话归属
UPDATE document
   SET owner_user_id = (SELECT id FROM app_user WHERE tenant_id = 1 AND username = 'demo')
 WHERE owner_user_id IS NULL;

UPDATE long_term_memory
   SET subject_id = (SELECT id FROM app_user WHERE tenant_id = 1 AND username = 'demo')
 WHERE subject_type = 'USER';

UPDATE conversation
   SET owner_user_id = (SELECT id FROM app_user WHERE tenant_id = 1 AND username = 'demo');