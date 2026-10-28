# ST8 · 阶段 8：权限与多租户

> 前置：阶段 1~7 已完成（解析 / 切块 / 混合检索 / 问答引用 / 评测 / 异步任务化）。
> 本文档是**实施计划**，不是设计文档；设计依据见《金融研报智能分析 Agent 系统架构设计 v0.10》§9.5、§15.2、§15.4、§17、§18.3。
> 项目约定：计划不勾 checkbox，执行偏差登记在 §5；阶段结束后写独立报告（`docs/阶段8-权限与多租户报告.md`）。

---

## 0. 为什么这一阶段值得单独做

阶段 7 结束时，这条链路是**完全裸奔**的：

| 位置 | 现状 | 后果 |
|---|---|---|
| `ChatController.java:34` 的 `POST /api/chat` | 没有任何身份概念，谁都能问 | 任何人拿到地址就能读库里所有年报 |
| `ChatController.java:42` 的 `GET /api/scope/documents` | 直接返回全库可检索文档 | **信息泄漏**：连「有哪些文档」都藏不住 |
| `ScopeAuthorizer` | 唯一实现是 `AllowAllAuthorizer`（阶段 5 的占位，自己注释写着「阶段 8 换掉」） | 权限过滤接口是空的 |
| `company / document / document_chunk / financial_fact / task_message` | 没有 `tenant_id` 列 | 租户之间连数据行都无法区分 |
| `DocumentController`（阶段 7） | `POST /api/documents` 无认证 | 谁都能往库里灌 PDF |

设计文档的要求很硬：

- §9.5：**ACL / tenant 一律不由 Router 生成**，必须由应用层从可信上下文注入（LLM 抽出公司名可以，抽出 tenantId 不行）；
- §15.2：默认可见性「TENANT 级文档在租户内按 company 授权可读；PRIVATE 文档仅 owner 与显式被授权对象可读；跨租户一律不可见」；
- §15.4：共享表 + `tenant_id` 行级隔离，**应用层强制注入 + PostgreSQL RLS 作为第二道防线**；
- §18.3 验收门限：**越权泄漏 = 0**。

所以这一阶段的验收不是「功能能用」，而是**一组越权负样本必须全部拿不到数据**。

---

## 1. 范围

### 1.1 本阶段做

| # | 交付 | 对应设计条款 |
|---|---|---|
| 1 | Flyway V8：`tenant` / `app_user` / `role` / `user_role` / `resource_grant` 五张表；五张业务表补 `tenant_id` + 以 tenant 打头的复合索引 | §15.2、§15.4 |
| 2 | PostgreSQL RLS：五张业务表 `ENABLE ROW LEVEL SECURITY` + 租户策略 + 独立运行时角色 `finagent_app` | §15.4 |
| 3 | 认证：Bearer token → `app_user`（SHA-256 查表）→ `CurrentUser` + `TenantContext`；401 语义 | §17 |
| 4 | 租户感知数据源：连接借出时 `set_config('app.tenant_id', …)`，归还时清空 | §15.4 |
| 5 | 写路径补 `tenant_id`（`company` / `document` / `document_chunk` / `task_message` 的全部 INSERT） | §15.4 |
| 6 | `AclScopeAuthorizer`：租户 + visibility + `resource_grant`（USER/ROLE × COMPANY/DOCUMENT） | §15.2 |
| 7 | 作用域三条路径（DOCUMENTS / FILTER / CORPUS）与「可选文档」列表全部接入 ACL | §9.5、§15.2 |
| 8 | 越权用例集（4 类负样本）+ 零泄漏门限测试 | §15.4、§18.3 |
| 9 | 契约 C11 + 术语表补词 + README + 阶段报告 | — |

### 1.2 本阶段不做（每条都有理由）

| 不做 | 理由 |
|---|---|
| JWT / OIDC / 登录页 | 设计文档只说「Spring Boot 负责认证」，没规定协议。V1 用「库里的 API token」证明**可信上下文**这一层，换成 JWT 只是换 token 的解析方式，不动下游 |
| 用户管理 CRUD / 注册 | 属于 V2 的管理后台；V8 直接种子里放好 demo 用户 |
| 配额与限流（§4.1 单用户并发 ≤ 3） | 需要 Redis 或本地令牌桶，属于 V2 工程化；本阶段只做「能不能看」 |
| 审计表（`model_run` / `tool_execution` / `retrieval_trace` 补 tenant_id） | 审计实体在 V2（§15.3 的字段补齐），本阶段不建空表 |
| 缓存穿透防护 | 项目目前**没有任何缓存**（Redis 容器是别的项目的），没有缓存就没有穿透，用例 ③ 改为「RLS 直接验证」 |
| COS 签名直链 | 引用只带 `page/bbox/chunkId`，不返回任何 COS URL（阶段 5 就是这么设计的），用例 ④ 改为「响应里不得出现对象键」 |
| 表分区（§15.4 的 tenant 分区） | 数据量 6000 行量级，分区纯属仪式；索引前缀已按 tenant 排 |
| Spring Security | 一个 40 行的 `Filter` 就够，引入 Security 会顺带接管 CSRF / 默认登录页，与当前无状态 API 冲突 |

---

## 2. 契约 C11 · 租户隔离与 ACL 不变式

### 2.1 可信上下文只有一个来源

`CurrentUser`（`userId / tenantId / username / roles`）**只能**由 `AuthenticationFilter` 从 Bearer token 查库得到，写进 `TenantContext`（ThreadLocal），请求结束在 `finally` 里清掉。

**永远不从这些地方取 tenant/user**：

- 请求体字段（`ChatRequest` 里就算出现 `tenantId`，也必须被忽略）；
- Query Understanding 的输出（§9.5 明文禁止）；
- 路径参数、查询参数；
- Cookie。

### 2.2 三道防线，职责不重叠

| 层 | 做什么 | 不做什么 |
|---|---|---|
| ① 作用域解析（`ScopeResolver` + `AclScopeAuthorizer`） | 把「请求想要的范围」与「调用方能看的文档」求交，得到 `resolvedDocumentIds` | 不判「这份文档解析完没」以外的业务 |
| ② 检索 SQL | 只按 `documentIds IN (…)` 过滤 —— 集合已经是授权过的 | **不再做第二遍鉴权**（两处都判，漏一处就等于没判） |
| ③ PostgreSQL RLS | 兜底：即使上两层被绕过（写错 SQL、新增一个没走 scope 的接口），跨租户行在数据库层就不返回 | 不做文档级 ACL（RLS 只认 tenant，不认 grant） |

### 2.3 默认拒绝（fail closed）

- 没有 `app.tenant_id` 会话变量 → `current_setting` 返回 `NULL`/`''` → `tenant_id = NULL` 为假 → **0 行**。
- 没有任何 `resource_grant` 命中 → 文档不可读，**不是**默认全租户可读。
- 唯一的例外是 `ADMIN` 角色：本租户内全部可读（§15.2 的「租户内」仍然是硬边界）。

### 2.4 两条角色分工（数据库层）

| 角色 | 用途 | 是否受 RLS |
|---|---|---|
| `finagent`（表 owner） | Flyway 迁移、CLI（`ReindexRunner`）、集成测试 | **否**（owner 默认绕过 RLS） |
| `finagent_app`（非 owner，仅 DML 权限） | Web 运行时（`spring.datasource`） | **是** |

不用 `FORCE ROW LEVEL SECURITY` 的原因：一旦 FORCE，连 Flyway 里的数据迁移语句也会被拦（迁移时没有租户上下文）。用两个角色换取「迁移照常、运行时受控」，并且**启动时自检**：运行时用户若等于表 owner，记一条 WARN —— 因为它意味着 RLS 被静默绕过。

### 2.5 文档可见性

| `document.visibility` | 谁能读 |
|---|---|
| `TENANT`（默认） | 本租户内，对 `document.company_id` 或该文档本身有 `READ` 授权的用户；ADMIN 全部 |
| `PRIVATE` | 仅 `owner_user_id` 本人 + 对**该文档**有显式授权的用户/角色；公司级授权**不**生效 |

---

## 3. 任务分解

### 依赖图

~~~text
Task 1 (V8 迁移) ──┬─> Task 2 (认证) ──┐
                   │                    ├─> Task 7 (REST 收口)
                   ├─> Task 3 (租户数据源/RLS 验证) ──┐
                   │                                ├─> Task 8 (越权用例集)
                   ├─> Task 4 (写路径补 tenant_id) ──┤
                   │                                │
                   └─> Task 5 (ACL 解析) ──> Task 6 (作用域接入) ──┘
                                                              
Task 9 (契约/文档/报告) 依赖全部完成
~~~

### File Structure

~~~text
src/main/resources/db/migration/
└── V8__tenant_and_acl.sql                     # 新：五张表 + tenant_id + 索引 + RLS + 角色 + 种子

src/main/java/com/finagent/security/           # 新包：认证与租户上下文
├── CurrentUser.java                           # record：userId/tenantId/username/displayName/roles
├── TenantContext.java                         # ThreadLocal 存取 + require() 抛 UnauthorizedException
├── UnauthorizedException.java                 # 401
├── ApiTokenRepository.java                    # token hash → CurrentUser（一次 JOIN 拿角色）
├── AuthenticationFilter.java                  # 解析 Bearer → 建上下文 → finally 清理
└── TenantAwareDataSource.java                 # 连接借出时 set_config，归还时清空

src/main/java/com/finagent/scope/
├── AclScopeAuthorizer.java                    # 新：真实 ACL 实现（默认 profile）
└── AllowAllAuthorizer.java                    # 改：只保留在 test profile

src/main/java/com/finagent/config/
└── TenantDataSourceConfig.java                # 新：raw DataSource + 包装后的 @Primary

src/main/java/com/finagent/document/
├── DocumentRepository.java                    # 改：所有 INSERT 带 tenant_id；查询依赖 RLS
└── DocumentController.java                    # 改：认证 + 上传带 tenant_id

src/main/java/com/finagent/chunk/ChunkPersister.java       # 改：INSERT 带 tenant_id
src/main/java/com/finagent/task/TaskMessageRepository.java  # 改：INSERT 带 tenant_id

src/test/resources/application-rls.yml         # 新：运行时用户 finagent_app（RLS 生效）
src/test/java/com/finagent/security/            # 新：认证单测
src/test/java/com/finagent/scope/AclScopeAuthorizerTest.java
src/test/java/com/finagent/tenant/TenantIsolationE2ETest.java   # 越权用例集
src/test/java/com/finagent/tenant/RlsEnforcementTest.java       # RLS 直接验证
~~~

---

## 3.1 Task 1：V8 迁移（租户、用户、角色、授权、RLS）

**Files**

- Create: `src/main/resources/db/migration/V8__tenant_and_acl.sql`

**为什么先做这一版**：表结构是后面所有任务的地基。这一版**只加可空列 + 回填 + 策略**，不加 `NOT NULL` —— 因为此刻代码还没有地方写 `tenant_id`，一加就会把所有写路径打断。收紧放到 Task 4 的 V9。

- [ ] **Step 1：写迁移文件**

~~~sql
-- 阶段 8：租户、用户、角色、资源授权，以及行级安全（RLS）。
--
-- 顺序很重要：先建表并回填 tenant_id，再 ENABLE ROW LEVEL SECURITY，
-- 否则回填语句自己会被策略拦住（策略生效时 app.tenant_id 还没设）。

-- ---------- 1) 租户 ----------
CREATE TABLE IF NOT EXISTS tenant (
    id         BIGSERIAL PRIMARY KEY,
    name       VARCHAR(128) NOT NULL,
    status     VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE'
               CONSTRAINT chk_tenant_status CHECK (status IN ('ACTIVE', 'SUSPENDED')),
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- ---------- 2) 用户（token 认证，不存明文 token）----------
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

-- ---------- 3) 角色与绑定（V1 内置 ADMIN / ANALYST / VIEWER）----------
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
-- permission_scope 由本表解析得出，不接受调用方传入（§15.2）。
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

-- ---------- 5) 业务表补 tenant_id（可空，下一步回填）----------
ALTER TABLE company         ADD COLUMN IF NOT EXISTS tenant_id BIGINT REFERENCES tenant(id);
ALTER TABLE document        ADD COLUMN IF NOT EXISTS tenant_id BIGINT REFERENCES tenant(id);
ALTER TABLE document        ADD COLUMN IF NOT EXISTS visibility VARCHAR(16) NOT NULL DEFAULT 'TENANT';
ALTER TABLE document        ADD COLUMN IF NOT EXISTS owner_user_id BIGINT REFERENCES app_user(id);
ALTER TABLE document_chunk  ADD COLUMN IF NOT EXISTS tenant_id BIGINT REFERENCES tenant(id);
ALTER TABLE financial_fact  ADD COLUMN IF NOT EXISTS tenant_id BIGINT REFERENCES tenant(id);
ALTER TABLE task_message    ADD COLUMN IF NOT EXISTS tenant_id BIGINT REFERENCES tenant(id);

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

-- ---------- 7) 以 tenant_id 打头的复合索引（§15.4）----------
CREATE INDEX IF NOT EXISTS idx_company_tenant   ON company (tenant_id, code);
CREATE INDEX IF NOT EXISTS idx_document_tenant  ON document (tenant_id, company_id, doc_year);
CREATE INDEX IF NOT EXISTS idx_chunk_tenant     ON document_chunk (tenant_id, document_id, chunk_level, status, chunk_version);
CREATE INDEX IF NOT EXISTS idx_fact_tenant      ON financial_fact (tenant_id, company_id, metric, period);
CREATE INDEX IF NOT EXISTS idx_task_message_tenant
    ON task_message (tenant_id, document_id, created_at DESC);

-- ---------- 8) 运行时角色（非 owner → RLS 生效）----------
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
--   - 变量没设      -> current_setting 返回 NULL -> NULL = ... 为 NULL -> 0 行（fail closed）
--   - 变量被清空    -> 返回 '' -> nullif 变 NULL -> 同样 0 行
-- USING 管读，WITH CHECK 管写；两者都要，否则可以插别人租户的行。
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

-- ---------- 10) demo 用户与角色（token 哈希由 sha256 生成）----------
-- 明文 token 只出现在 .env.example 与本注释里，库里只有哈希：
--   dev-admin-token   -> ADMIN  （本租户全部可读）
--   dev-analyst-token -> ANALYST（需要 company/document 授权）
--   dev-outsider-token-> VIEWER （阶段 8 越权用例默认用它：没有 300059 的授权）
INSERT INTO app_user (tenant_id, username, display_name, api_token_hash)
SELECT t.id, v.username, v.display_name, encode(sha256(v.raw::bytea), 'hex')
  FROM tenant t,
       (VALUES ('admin',    '演示管理员', 'dev-admin-token'),
               ('analyst',  '演示分析师', 'dev-analyst-token'),
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
COMMENT ON TABLE tenant IS '租户根；所有业务表带 tenant_id 并参与每一条检索过滤（§15.2）';
COMMENT ON TABLE app_user IS '用户主体；api_token_hash 是 SHA-256(token)，不存明文';
COMMENT ON TABLE role IS '角色：ADMIN/ANALYST/VIEWER（is_system=true 表示内置不可删）';
COMMENT ON TABLE resource_grant IS '显式资源授权：USER/ROLE × COMPANY/DOCUMENT/SECTION × READ/READ_WRITE';
COMMENT ON COLUMN document.visibility IS 'TENANT=租户内按 company 授权可读；PRIVATE=仅 owner 与显式授权';
COMMENT ON COLUMN document.owner_user_id IS 'PRIVATE 文档的属主；TENANT 文档为空';
COMMENT ON COLUMN document_chunk.tenant_id IS '冗余自 document.tenant_id：RLS 要能直接过滤 chunk，不 join document';
~~~

- [ ] **Step 2：让 Flyway 执行它**

~~~powershell
. .\scripts\load-env.ps1
mvn test "-Dtest=VectorStoreSmokeTest"
~~~

期望：日志出现 `Migrating schema "public" to version "8 - tenant and acl"` 与 `Successfully applied 1 migration`。

- [ ] **Step 3：用 psql 断言结构（不要只看「没报错」）**

~~~powershell
docker exec finagent-postgres psql -U finagent -d finagent -c "SELECT count(*) AS tenants FROM tenant;"
docker exec finagent-postgres psql -U finagent -d finagent -c "SELECT count(*) AS docs_without_tenant FROM document WHERE tenant_id IS NULL;"
docker exec finagent-postgres psql -U finagent -d finagent -c "SELECT tablename, policyname FROM pg_policies ORDER BY tablename;"
docker exec finagent-postgres psql -U finagent -d finagent -c "SELECT rolname FROM pg_roles WHERE rolname = 'finagent_app';"
docker exec finagent-postgres psql -U finagent -d finagent -c "SELECT username, left(api_token_hash, 8) FROM app_user ORDER BY id;"
~~~

期望：`tenants=1`；`docs_without_tenant=0`；5 条策略；`finagent_app` 存在；3 个用户。

- [ ] **Step 4：手工验证 RLS 真的生效（这一步最容易假绿）**

~~~powershell
# 用非 owner 角色 + 显式设置租户变量：应该看到 2 份文档
docker exec finagent-postgres psql -U finagent -d finagent -c "SET ROLE finagent_app; SELECT set_config('app.tenant_id','1',false); SELECT count(*) FROM document;"
# 不设租户变量：应该是 0
docker exec finagent-postgres psql -U finagent -d finagent -c "SET ROLE finagent_app; SELECT count(*) FROM document;"
~~~

期望：第一句 `2`（doc 7 与 doc 38），第二句 `0`。**如果第二句不是 0，说明策略没生效**（最常见原因：连接用的是 owner，或策略写成了 `USING (true)`）。

- [ ] **Step 5：回归**

~~~powershell
mvn test
~~~

期望：306 通过 / 1 跳过。此刻代码还没写 `tenant_id`，但列是**可空**的，所以插入不会被拦。

---

## 3.2 Task 2：认证（Bearer token → CurrentUser → TenantContext）

**Files**

- Create: `src/main/java/com/finagent/security/CurrentUser.java`
- Create: `src/main/java/com/finagent/security/TenantContext.java`
- Create: `src/main/java/com/finagent/security/UnauthorizedException.java`
- Create: `src/main/java/com/finagent/security/ApiTokenRepository.java`
- Create: `src/main/java/com/finagent/security/AuthenticationFilter.java`
- Test: `src/test/java/com/finagent/security/ApiTokenRepositoryTest.java`、`AuthenticationFilterTest.java`、`TenantContextTest.java`

**为什么是 Filter 而不是 Spring Security**：需求只有「解析 token → 建上下文 → 401」三件事。Spring Security 会接管过滤器链、默认登录页、CSRF，对一个无状态 JSON API 是负资产。等真要做 OIDC 时，只换「怎么拿到 userId」这一段，下游一行不改。

- [ ] **Step 1：先写测试（红）**

`TenantContextTest`（纯单元，3 个用例）：

~~~java
class TenantContextTest {

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    @Test
    void requireWithoutContextThrows() {
        assertThrows(UnauthorizedException.class, TenantContext::require);
    }

    @Test
    void setThenRequireReturnsSameUser() {
        CurrentUser u = new CurrentUser(7L, 1L, "analyst", "演示分析师", Set.of("ANALYST"));
        TenantContext.set(u);
        assertSame(u, TenantContext.require());
    }

    @Test
    void clearRemovesContext() {
        TenantContext.set(new CurrentUser(7L, 1L, "analyst", "演示分析师", Set.of("ANALYST")));
        TenantContext.clear();
        assertNull(TenantContext.current());
    }
}
~~~

`ApiTokenRepositoryTest`（真库，2 个用例）：用 `dev-analyst-token` 的 SHA-256 查得到用户、角色是 `ANALYST`、tenantId 非空；随便一个 hash 查不到。

`AuthenticationFilterTest`（不启动 Spring，直接 new Filter + MockHttpServletRequest）（4 个用例）：

| 用例 | 期望 |
|---|---|
| 无 `Authorization` 头 | 401 + body 含 `AUTH_REQUIRED` |
| `Bearer garbage` | 401 + `AUTH_INVALID` |
| `Bearer dev-analyst-token` | 放行，且链下游能读到 `TenantContext.require().username() == "analyst"` |
| 下游抛异常 | 上下文仍被清空（`TenantContext.current() == null`） |

- [ ] **Step 2：实现**

`CurrentUser`：

~~~java
package com.finagent.security;

import java.util.Set;

/** 可信调用方。只能由 {@link AuthenticationFilter} 构造，绝不从请求体/QU 拼出来（契约 C11）。 */
public record CurrentUser(long userId, long tenantId, String username,
                          String displayName, Set<String> roles) {

    public boolean hasRole(String code) {
        return roles != null && roles.contains(code);
    }

    public boolean isAdmin() {
        return hasRole("ADMIN");
    }
}
~~~

`TenantContext`：

~~~java
package com.finagent.security;

import java.util.Optional;

/** 请求级租户上下文。由 Filter 写入、finally 清理；Kafka worker / CLI 里没有上下文（契约 C11）。 */
public final class TenantContext {

    private static final ThreadLocal<CurrentUser> CURRENT = new ThreadLocal<>();

    private TenantContext() {
    }

    public static void set(CurrentUser user) {
        CURRENT.set(user);
    }

    public static CurrentUser current() {
        return CURRENT.get();
    }

    public static Optional<CurrentUser> optional() {
        return Optional.ofNullable(CURRENT.get());
    }

    /** 取当前用户；没有就抛 401 —— 需要身份的路径一律走这里，不要自己判 null。 */
    public static CurrentUser require() {
        CurrentUser user = CURRENT.get();
        if (user == null) {
            throw new UnauthorizedException("AUTH_REQUIRED", "缺少认证上下文");
        }
        return user;
    }

    public static void clear() {
        CURRENT.remove();
    }
}
~~~

`ApiTokenRepository`（一个 SQL 拿齐用户与角色）：

~~~java
@Repository
public class ApiTokenRepository {

    private static final String SQL = """
            SELECT u.id AS user_id, u.tenant_id, u.username, u.display_name, u.status, r.code
              FROM app_user u
              LEFT JOIN user_role ur ON ur.user_id = u.id
              LEFT JOIN role r ON r.id = ur.role_id
             WHERE u.api_token_hash = ?
            """;

    private final JdbcTemplate jdbc;

    public ApiTokenRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 按 token 的 SHA-256 十六进制串查用户；查不到或已停用返回 empty。 */
    public Optional<CurrentUser> findByTokenHash(String hexHash) {
        Set<String> roles = new LinkedHashSet<>();
        long[] ids = {-1, -1};
        String[] names = {null, null};
        String status = jdbc.query(SQL, rs -> {
            String s = null;
            ...
        }, hexHash);
        ...
    }
}
~~~

> 上面这段故意没写满 —— 用 `jdbc.query(sql, ResultSetExtractor, args)` 逐行累积即可（一个用户可能有多行角色）。实现时**不要在 lambda 里改外部基本类型**，用 `AtomicReference<CurrentUser>` 或先收集成 `List<Row>` 再组装；后者更直白。

`AuthenticationFilter`：

~~~java
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class AuthenticationFilter extends OncePerRequestFilter {

    private final ApiTokenRepository tokens;
    private final ObjectMapper json;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        // /error 是容器内部转发，再拦一次会把 401 变成 401 的无限循环
        return request.getRequestURI().startsWith("/error");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header == null || !header.startsWith("Bearer ")) {
            reject(response, "AUTH_REQUIRED", "缺少 Authorization: Bearer <token>");
            return;
        }
        String token = header.substring("Bearer ".length()).trim();
        String hash = sha256Hex(token);
        Optional<CurrentUser> user = tokens.findByTokenHash(hash);
        if (user.isEmpty()) {
            reject(response, "AUTH_INVALID", "token 无效或已停用");
            return;
        }
        TenantContext.set(user.get());
        try {
            chain.doFilter(request, response);
        } finally {
            TenantContext.clear();
        }
    }

    private void reject(HttpServletResponse response, String code, String message) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json;charset=UTF-8");
        json.writeValue(response.getWriter(), Map.of("error", code, "message", message));
    }
}
~~~

- [ ] **Step 3：跑测试（绿）+ 手工验证 401**

~~~powershell
mvn test "-Dtest=TenantContextTest,ApiTokenRepositoryTest,AuthenticationFilterTest"
# 无 token 应该 401（应用没起也可以先跳过这条，Step 4 之后一起验）
~~~

- [ ] **Step 4：全量回归**

~~~powershell
mvn test
~~~

期望：此时会因为「所有旧测试都没有带 token」而**大面积 401** —— 这是正常的，Task 7 之前先不动旧测试；如果想让它们继续绿，在 Task 7 的同一批改动里统一加认证头。

> 执行顺序提示：Task 2 与 Task 7 之间会有「测试暂时红」的窗口。若不想有红窗口，把 Task 2 的 Filter 先加 `@ConditionalOnProperty(finagent.tenant.auth-enabled)`（默认 false），Task 7 再打开并修测试。

---

## 3.3 Task 3：租户感知数据源 + RLS 生效验证

**Files**

- Create: `src/main/java/com/finagent/security/TenantAwareDataSource.java`
- Create: `src/main/java/com/finagent/config/TenantDataSourceConfig.java`
- Create: `src/test/resources/application-rls.yml`
- Test: `src/test/java/com/finagent/tenant/RlsEnforcementTest.java`

- [ ] **Step 1：先写 RLS 测试（红）**

~~~java
@SpringBootTest
@ActiveProfiles("rls")
class RlsEnforcementTest {

    @Autowired
    private JdbcTemplate jdbc;

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    @Test
    void tenantOneSeesOwnDocuments() {
        TenantContext.set(new CurrentUser(2L, 1L, "analyst", "演示分析师", Set.of("ANALYST")));
        assertTrue(jdbc.queryForObject("SELECT count(*) FROM document", Integer.class) >= 2);
    }

    @Test
    void tenantTwoSeesNothing() {
        TenantContext.set(new CurrentUser(99L, 2L, "other", "别的租户", Set.of("ADMIN")));
        // 注意：这条 SQL 里没有 WHERE tenant_id —— 拦住它的是 RLS，不是应用层
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM document", Integer.class));
    }

    @Test
    void noContextFailsClosed() {
        TenantContext.clear();
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM document", Integer.class));
    }

    @Test
    void cannotInsertIntoAnotherTenant() {
        TenantContext.set(new CurrentUser(99L, 2L, "other", "别的租户", Set.of("ADMIN")));
        assertThrows(DataAccessException.class, () -> jdbc.update(
                "INSERT INTO document (tenant_id, doc_type, object_key, content_hash) VALUES (1, 'ANNUAL', 'x', 'y')"));
    }
}
~~~

`application-rls.yml`：

~~~yaml
# 只在 RLS 相关的 e2e / 集成测试里激活：运行时用非 owner 角色，行级安全才真的生效。
spring:
  datasource:
    url: jdbc:postgresql://localhost:5432/finagent
    username: finagent_app
    password: finagent_app
  flyway:
    # Flyway 必须用 owner：迁移要改表结构，finagent_app 没有 DDL 权限
    user: finagent
    password: finagent
    enabled: true
~~~

- [ ] **Step 2：实现数据源包装**

~~~java
/**
 * 让每一条借出的连接都带着 app.tenant_id。
 *
 * <p>为什么不用 SET LOCAL：SET LOCAL 只在事务内有效，而本项目大量 JdbcTemplate 调用是
 * autocommit 的单语句 —— 那样变量在语句结束就消失，RLS 会永远看到 NULL（= 0 行）。
 * 所以用 session 级 set_config(..., false)，并在连接归还时清空，避免污染池里的下一位使用者。
 */
public class TenantAwareDataSource implements DataSource {

    private static final String SET_TENANT = "SELECT set_config('app.tenant_id', ?, false)";
    private static final String CLEAR_TENANT = "SELECT set_config('app.tenant_id', '', false)";

    private final DataSource delegate;
    private final long fallbackTenantId;   // 没有上下文时用它（开发/CLI 兜底，见 §5 偏差）

    @Override
    public Connection getConnection() throws SQLException {
        Connection raw = delegate.getConnection();
        try {
            applyTenant(raw);
        } catch (SQLException e) {
            raw.close();
            throw e;
        }
        return wrap(raw);
    }

    private void applyTenant(Connection raw) throws SQLException {
        CurrentUser user = TenantContext.current();
        String tenant = user != null ? String.valueOf(user.tenantId())
                : (fallbackTenantId > 0 ? String.valueOf(fallbackTenantId) : "");
        try (PreparedStatement ps = raw.prepareStatement(SET_TENANT)) {
            ps.setString(1, tenant);
            ps.execute();
        }
    }

    /** 只拦 close()：归还前清空变量，其余方法直接转发。 */
    private Connection wrap(Connection raw) {
        return (Connection) Proxy.newProxyInstance(
                Connection.class.getClassLoader(),
                new Class<?>[]{Connection.class},
                (proxy, method, args) -> {
                    if ("close".equals(method.getName())) {
                        try (PreparedStatement ps = raw.prepareStatement(CLEAR_TENANT)) {
                            ps.execute();
                        } catch (SQLException ignored) {
                            // 清不掉不影响正确性：下一位使用者借出时会重新 set
                        }
                        raw.close();
                        return null;
                    }
                    try {
                        return method.invoke(raw, args);
                    } catch (InvocationTargetException e) {
                        throw e.getTargetException();
                    }
                });
    }

    // 其余 DataSource 方法（getConnection(user, pwd)、getLogWriter、getParentLogger…）一律委托 delegate
}
~~~

`TenantDataSourceConfig`：

~~~java
@Configuration(proxyBeanMethods = false)
public class TenantDataSourceConfig {

    /** 原始连接池：交给 Boot 按 spring.datasource.* 绑定。 */
    @Bean
    @ConfigurationProperties("spring.datasource")
    public DataSource rawDataSource() {
        return DataSourceBuilder.create().type(HikariDataSource.class).build();
    }

    @Bean
    @Primary
    public DataSource dataSource(@Qualifier("rawDataSource") DataSource raw,
                                 TenantProperties props) {
        return new TenantAwareDataSource(raw, props.getFallbackTenantId());
    }
}
~~~

> 注意两点：① `@Bean @Primary` 只打在包装后的那个上，否则 JdbcTemplate 注入会有两个候选；② Boot 的 `DataSourceAutoConfiguration` 在容器已有 `DataSource` 时会退让，所以不会冲突 —— 但要**实测启动**确认（见 Step 3）。

- [ ] **Step 3：跑测试（绿）**

~~~powershell
. .\scripts\load-env.ps1
mvn test "-Dsurefire.excludedGroups=" "-Dgroups=e2e" "-Dtest=RlsEnforcementTest"
~~~

期望：4 个用例全绿。若 `tenantTwoSeesNothing` 看到 35 行，说明连接仍是 owner（检查 `application-rls.yml` 是否被加载、`/actuator` 无关）。

- [ ] **Step 4：不做 RLS 的旁路自检（可选但推荐）**

在 `TenantDataSourceConfig` 里加一个 `ApplicationRunner`：启动时查 `SELECT current_user, (SELECT rolname FROM pg_roles WHERE oid = (SELECT relowner FROM pg_class WHERE relname = 'document'))`，若两者相同则 `log.warn("运行时用户 {} 是 document 表的 owner，RLS 会被绕过", user)`。这条日志在事故复盘时非常值钱。

---

## 3.4 Task 4：写路径补 tenant_id（V9 收紧）

**Files**

- Modify: `src/main/java/com/finagent/document/DocumentRepository.java`（`upsertCompany` / `upsertDocument`）
- Modify: `src/main/java/com/finagent/chunk/ChunkPersister.java`
- Modify: `src/main/java/com/finagent/task/TaskMessageRepository.java`
- Create: `src/main/resources/db/migration/V9__tenant_not_null.sql`

**关键设计：写路径的 tenant 从「所属文档」推导，不从线程上下文猜。**

理由：解析/重切的真正执行者是 Kafka listener 线程和 CLI，它们**没有** HTTP 上下文（契约 C11 明确 ThreadLocal 只覆盖请求线程）。若在写 chunk 时读 `TenantContext`，worker 会写不进去或者写错租户。所以：

| 写什么 | tenant 从哪来 |
|---|---|
| `company`（新建公司） | `TenantContext.require().tenantId()` —— 只有上传接口会建公司 |
| `document`（新建文档） | 同上（上传接口）；更新时不动 tenant_id |
| `document_chunk` | `SELECT tenant_id FROM document WHERE id = ?`（即父行） |
| `task_message` | 同上（消息里只有 documentId） |
| `financial_fact`（暂未写入） | 同上 |

- [ ] **Step 1：先写测试（红）**

在 `ChunkWriterTest` 里加一条：新写入的 chunk 行 `tenant_id` 等于所属文档的 `tenant_id`。在 `TaskMessageRepositoryTest` 里加一条：`upsert` 之后该行 `tenant_id` 等于 document 的 tenant。

- [ ] **Step 2：改 SQL**

`ChunkPersister` 的 INSERT … SELECT 里加一列：

~~~sql
INSERT INTO document_chunk (document_id, tenant_id, parent_id, chunk_level, ...)
SELECT ?, d.tenant_id, ?, ?, ...
  FROM document d WHERE d.id = ?
~~~

（若当前是批量 `batchUpdate(sql, args)` 形式，最简单的做法是先把 tenant 查出来放进参数，避免为每一行改写成 INSERT … SELECT。两条路都对，选改动小的。）

`TaskMessageRepository.upsert/insert` 同理：参数表里加 `(SELECT tenant_id FROM document WHERE id = ?)` 作为子查询，或先查再加参。

- [ ] **Step 3：V9 收紧为 NOT NULL**

~~~sql
-- tenant_id 收紧：此时所有写路径都已带值，再加约束才不会打断解析链路。
ALTER TABLE company        ALTER COLUMN tenant_id SET NOT NULL;
ALTER TABLE document       ALTER COLUMN tenant_id SET NOT NULL;
ALTER TABLE document_chunk ALTER COLUMN tenant_id SET NOT NULL;
ALTER TABLE financial_fact ALTER COLUMN tenant_id SET NOT NULL;
ALTER TABLE task_message   ALTER COLUMN tenant_id SET NOT NULL;
~~~

- [ ] **Step 4：跑测试（绿）+ 全量回归**

~~~powershell
. .\scripts\load-env.ps1
mvn test "-Dtest=ChunkWriterTest,TaskMessageRepositoryTest,ReindexServiceTest"
mvn test
~~~

期望：306 通过 / 1 跳过。

---

## 3.5 Task 5：ACL 解析（`AclScopeAuthorizer`）

**Files**

- Create: `src/main/java/com/finagent/scope/AclScopeAuthorizer.java`
- Modify: `src/main/java/com/finagent/scope/AllowAllAuthorizer.java`（加 `@Profile("test")`）
- Test: `src/test/java/com/finagent/scope/AclScopeAuthorizerTest.java`

**为什么现在才动它**：Task 2 之前「当前用户是谁」根本拿不到，ACL 写了也只能返回空。到这一步身份链路已通，才谈得上「谁能看哪份文档」。

**为什么保留 `AllowAllAuthorizer`**：它只服务于 `test` profile 下的 300 多个既有测试。删掉它，每个聊天相关测试都要先造 token、建上下文，噪音会盖过被测逻辑。代价是「测试环境不验 ACL」—— 所以 ACL 必须有**自己的**数据库级测试（本任务）与越权 e2e（Task 8），否则就是真漏。

- [ ] **Step 1：先写测试（红）**

~~~java
@SpringBootTest
@ActiveProfiles("test")
class AclScopeAuthorizerTest {

    @Autowired AclScopeAuthorizer authorizer;
    @Autowired JdbcTemplate jdbc;

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    @Test
    void adminSeesAllDocumentsInTenant() {
        asAdmin();
        assertEquals(Set.of(7L, 38L), readable(7L, 38L));
    }

    @Test
    void analystSeesCompanyGrantedDocuments() {
        asAnalyst();
        // analyst 只有 300059 的 COMPANY 授权，doc 7/38 都属于 300059
        assertEquals(Set.of(7L, 38L), readable(7L, 38L));
    }

    @Test
    void userWithoutGrantSeesNothing() {
        asOutsider();
        assertEquals(Set.of(), readable(7L, 38L));
    }

    @Test
    void privateDocumentIsInvisibleWithoutExplicitGrant() {
        asOutsider();
        long privateDoc = insertDocument("PRIVATE", analystId);
        assertEquals(Set.of(), readable(privateDoc));
    }

    @Test
    void privateDocumentOwnerStillSeesIt() {
        asAnalyst();
        long privateDoc = insertDocument("PRIVATE", analystId);
        assertEquals(Set.of(privateDoc), readable(privateDoc));
    }

    @Test
    void expiredGrantIsIgnored() {
        asOutsider();
        long doc = insertDocument("TENANT", null);
        jdbc.update("""
                INSERT INTO resource_grant
                    (tenant_id, subject_type, subject_id, resource_type, resource_id, permission, expires_at)
                VALUES (1, 'USER', ?, 'DOCUMENT', ?, 'READ', now() - interval '1 day')
                """, outsiderId, doc);
        assertEquals(Set.of(), readable(doc));
    }
}
~~~

> 测试助手 `readable(long...)` 内部就是 `TenantContext.set(...)` 后调 `authorizer.filterReadable(List.of(ids))` 再装成 `Set`。三个用户 id 用 `SELECT id FROM app_user WHERE username = ?` 查出来，**不要硬编码**「admin=1」（种子自增值不保证）。

- [ ] **Step 2：实现**

~~~java
@Component
@Profile("!test")
public class AclScopeAuthorizer implements ScopeAuthorizer {

    private final JdbcTemplate jdbc;

    public AclScopeAuthorizer(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<Long> filterReadable(List<Long> documentIds) {
        if (documentIds == null || documentIds.isEmpty()) {
            return List.of();
        }
        CurrentUser user = TenantContext.require();
        // 管理员也只在本租户内全可见：租户边界是硬边界，不因角色放宽
        String inClause = placeholders(documentIds.size());
        if (user.isAdmin()) {
            return jdbc.query("SELECT d.id FROM document d WHERE d.tenant_id = ? AND d.id IN (" + inClause + ")",
                    (rs, i) -> rs.getLong(1), withTenant(user, documentIds));
        }
        String sql = """
                SELECT d.id FROM document d
                 WHERE d.tenant_id = ?
                   AND d.id IN (%s)
                   AND (
                        (d.visibility = 'PRIVATE' AND d.owner_user_id = ?)
                     OR EXISTS (
                          SELECT 1 FROM resource_grant g
                           WHERE g.tenant_id = d.tenant_id
                             AND g.resource_type = CASE d.visibility
                                                     WHEN 'TENANT' THEN 'COMPANY'
                                                     ELSE 'DOCUMENT'
                                                   END
                             AND g.resource_id = CASE d.visibility
                                                   WHEN 'TENANT' THEN d.company_id
                                                   ELSE d.id
                                                 END
                             AND g.permission IN ('READ', 'READ_WRITE')
                             AND (g.expires_at IS NULL OR g.expires_at > now())
                             AND ((g.subject_type = 'USER' AND g.subject_id = ?)
                               OR (g.subject_type = 'ROLE' AND g.subject_id IN (%s)))
                        )
                   )
                """.formatted(inClause, placeholders(user.roles().size()));
        return jdbc.query(sql, (rs, i) -> rs.getLong(1), argsFor(user, documentIds));
    }
}
~~~

四个要点，缺一条就会出安全漏洞：

1. **`d.tenant_id = ?` 显式写在 SQL 里**：不能只依赖 RLS —— CLI 与测试用 owner 连接时 RLS 是绕过的；显式条件让两种连接下结果一致。
2. **`CASE d.visibility`**：TENANT 文档查 COMPANY 授权，PRIVATE 文档查 DOCUMENT 授权 —— 公司级授权**不能**穿透到私有文档，否则同事能看到别人的私有笔记。
3. **`expires_at > now()`**：过期授权必须当不存在。
4. **角色用 `IN (…)`**：一个用户可能既是 ANALYST 又是 VIEWER，任一命中即可。

- [ ] **Step 3：跑测试（绿）**

~~~powershell
. .\scripts\load-env.ps1
mvn test "-Dtest=AclScopeAuthorizerTest"
~~~

- [ ] **Step 4：确认 Bean 不冲突**

~~~powershell
mvn test "-Dtest=KafkaTopicConfigTest"
~~~

若启动报 `NoUniqueBeanDefinitionException: ScopeAuthorizer`，说明 `AllowAllAuthorizer` 少了 `@Profile("test")`。

---

## 3.6 Task 6：作用域三条路径 + 可选文档列表接入 ACL

**Files**

- Modify: `src/main/java/com/finagent/scope/ScopeResolver.java`
- Modify: `src/main/java/com/finagent/chat/ChatController.java`（`GET /api/scope/documents`）
- Test: `src/test/java/com/finagent/scope/ScopeResolverAclTest.java`

**现状（实测代码位置）**：只有 `ScopeResolver.java:79` 的 DOCUMENTS 路径调了 `filterReadable`；`resolveFilter`（:126）与 `resolveCorpus`（:58）**完全没有鉴权** —— 也就是「按公司+年份筛」和「不限范围」这两条路，任何调用方都能拿到全库。

- [ ] **Step 1：先写测试（红）**

| 用例 | 期望（当前实际值） |
|---|---|
| `resolveCorpus` 以 outsider 调用 | 0 份（现在是 2） |
| `resolveFilter`（company=300059）以 outsider 调用 | 0 份（现在是 2） |
| `resolveFilter`（company=300059）以 analyst 调用 | 2 份 |
| `resolveDocuments`（显式 [7, 38]）以 outsider 调用 | 空作用域（抛 `ScopeEmptyException`） |

- [ ] **Step 2：改三处**

~~~java
// ① resolveCorpus：拿到候选后立刻求交，后续逻辑只用授权过的 id
List<DocMeta> candidates = documents.findRetrievable(List.of(), List.of(), List.of(), List.of(), List.of());
Set<Long> readable = Set.copyOf(authorizer.filterReadable(candidates.stream().map(DocMeta::id).toList()));
List<DocMeta> docs = candidates.stream().filter(d -> readable.contains(d.id())).toList();

// ② resolveFilter：同样在 findRetrievable 之后立刻 filterReadable
// ③ resolveDocuments：已有（ScopeResolver.java:79），只需确认它在「解析请求里的 id」之后、
//    「判断空作用域」之前
~~~

**顺序很重要**：先得到候选（含公司/年份/类型的业务筛选），再做 ACL 求交，最后判空。反过来先判空，会让「有权限但筛选条件不匹配」误报成权限错误，排查时会浪费很多时间。

- [ ] **Step 3：`GET /api/scope/documents` 也要过滤**

这个接口返回「可选择的文档列表」，是最容易被忘的泄漏点（设计 §15.4 第 2 类越权：枚举出不该看到的 documentId）。

~~~java
@GetMapping("/api/scope/documents")
public List<SelectableDocument> documents() {
    List<SelectableDocument> all = documents.findSelectableDocuments();
    Set<Long> readable = Set.copyOf(
            authorizer.filterReadable(all.stream().map(SelectableDocument::id).toList()));
    return all.stream().filter(d -> readable.contains(d.id())).toList();
}
~~~

- [ ] **Step 4：跑测试（绿）+ 全量回归**

~~~powershell
mvn test "-Dtest=ScopeResolverAclTest"
mvn test
~~~

期望：全量仍 306+ 通过 —— `test` profile 用 `AllowAllAuthorizer`，原有作用域测试行为不变。

---

## 3.7 Task 7：REST 收口（401 语义 + 请求体注入无效）

**Files**

- Modify: `src/main/resources/application.yml`（去掉 Task 2 可能加的 `auth-enabled` 开关）
- Modify: 现有 MockMvc 测试（加 `@AutoConfigureMockMvc(addFilters = false)` 或在请求里补认证头）
- Test: `src/test/java/com/finagent/security/RestAuthContractTest.java`

- [ ] **Step 1：明确「哪些端点要认证」**

| 端点 | 认证 | 说明 |
|---|---|---|
| `POST /api/chat` | 必须 | 核心数据出口 |
| `GET /api/scope/documents` | 必须 | 枚举泄漏点 |
| `POST /api/documents` | 必须 | 写入 |
| `GET /api/documents/{id}` | 必须 | 状态查询也会泄漏标题 |
| `/error` | 不拦 | 容器内部转发，拦了会 401 循环 |

- [ ] **Step 2：写 `RestAuthContractTest`（4 个用例，走真 Filter）**

| 用例 | 期望 |
|---|---|
| 无 token `POST /api/chat` | 401 + `{"error":"AUTH_REQUIRED"}` |
| 错 token | 401 + `AUTH_INVALID` |
| `dev-outsider-token` 问全库问题 | 200，但响应是拒答，正文不含任何财务数字 |
| 请求体里塞 `"tenantId": 1, "userId": 1` | 行为与不塞完全一致 |

第 4 条是契约 C11 §2.1 的唯一可执行证据，别省。

- [ ] **Step 3：修既有测试的认证**

原则：**单元/切片测试关掉过滤器（`addFilters = false`），e2e 走真过滤器带 token**。不要在单元测试里 mock 整条认证链路 —— 那只会测出「mock 会返回什么」。

- [ ] **Step 4：全量回归 + 手工实测**

~~~powershell
. .\scripts\load-env.ps1
mvn test
~~~

~~~powershell
# 起应用后各发一次：第一条应拒答，第二条应 401
curl -s -X POST http://localhost:8080/api/chat -H "Content-Type: application/json" -H "Authorization: Bearer dev-outsider-token" -d '{"question":"东方财富2025年营业收入是多少"}'
curl -s -X POST http://localhost:8080/api/chat -H "Content-Type: application/json" -d '{"question":"东方财富2025年营业收入是多少"}'
~~~

---

## 3.8 Task 8：越权用例集（4 类负样本）+ 零泄漏门限

**Files**

- Create: `src/test/java/com/finagent/tenant/TenantIsolationE2ETest.java`
- Create: `src/test/java/com/finagent/tenant/TenantFixture.java`（造第二个租户、用户，幂等）
- Create: `src/test/java/com/finagent/tenant/ZeroLeakGateTest.java`

**这是本阶段真正的验收**。设计 §15.4 的四类越权，逐类落成可执行用例：

| # | 设计原文的越权类型 | 本项目怎么落 | 期望 |
|---|---|---|---|
| ① | 语义高度相关但无权限 | outsider 问「东方财富 2025 年营业收入」 | 拒答，正文 0 个财务数字 |
| ② | 直接传他人 documentId / chunkId | outsider 以 documents 模式传 `[7, 38]` | 拒答，引用为空 |
| ③ | 缓存穿透 | 项目**没有缓存** → 改为「他人租户枚举文档」返回空列表 | 0 条 |
| ④ | COS 直链 | 断言响应里不出现 `parsed/`、`cos.` 等对象存储痕迹 | 0 命中 |

- [ ] **Step 1：造第二个租户（幂等，可重复跑）**

~~~java
static long ensureOtherTenant(JdbcTemplate jdbc) {
    jdbc.update("""
            INSERT INTO tenant (name, status)
            SELECT 'other-test', 'ACTIVE'
             WHERE NOT EXISTS (SELECT 1 FROM tenant WHERE name = 'other-test')
            """);
    return jdbc.queryForObject("SELECT id FROM tenant WHERE name = 'other-test'", Long.class);
}
~~~

同法造 `intruder` 用户（token `dev-intruder-token`）与 ADMIN 角色。**这个租户下没有文档** —— 这正是「跨租户什么都看不到」的前提。

- [ ] **Step 2：四个用例**

~~~java
@Test
void crossTenantQuestionIsRefused() {
    ChatResponse r = ask("dev-intruder-token", "东方财富2025年营业收入是多少");
    assertTrue(r.refused());
}

@Test
void foreignDocumentIdsResolveToEmptyScope() {
    ChatResponse r = askWithDocuments("dev-outsider-token", List.of(7L, 38L), "2025年营业收入");
    assertTrue(r.refused());
    assertTrue(r.citations().isEmpty());
}

@Test
void otherTenantCannotEnumerateDocuments() {
    assertEquals(List.of(), listDocuments("dev-intruder-token"));
}

@Test
void responseNeverLeaksObjectStorageKeys() {
    String raw = rawAsk("dev-analyst-token", "东方财富2025年营业收入");
    assertFalse(raw.contains("parsed/"));
    assertFalse(raw.contains("cos."));
}
~~~

- [ ] **Step 3：零泄漏门限**

`ZeroLeakGateTest` 把 4 类用例的响应正文拼起来，断言：
① 不含被保护文档原文里的任意 40 字以上片段（用 `document_chunk.content` 抽样比对）；② 不含任意 `object_key`；③ 不含其他租户的 `title`。
门槛：**泄漏计数 = 0**（设计 §18.3）。

- [ ] **Step 4：跑（绿）**

~~~powershell
. .\scripts\load-env.ps1
mvn test "-Dsurefire.excludedGroups=" "-Dgroups=e2e" "-Dtest=TenantIsolationE2ETest,ZeroLeakGateTest,RlsEnforcementTest"
~~~

⚠️ 这一批会调真模型判拒答，单题约 3~5 秒，整套约 1 分钟。

---

## 3.9 Task 9：契约 C11 落文档 + 术语 + README + 报告

**Files**

- Modify: `docs/关键契约.md`（新增 C11，正文搬本计划 §2 五条 + 「写路径 tenant 从文档推导」+ 三道防线表）
- Modify: `docs/术语表.md`（新增一节「权限与多租户」：租户 / RLS / ACL / 可信上下文 / Bearer token / 行级安全策略 / 越权泄漏，速查表补 5 行）
- Modify: `README.md`（认证说明、三个 demo token、阶段 8 进度）
- Modify: `docs/superpowers/plans/2026-10-05-demo-roadmap.md`（阶段 8 标完成 + 计划索引加 ST8）
- Create: `docs/阶段8-权限与多租户报告.md`（骨架同阶段 7：怎么读 → 一句话结论 → 为什么裸奔不能上线 → 交付物 → 契约 C11 → 实测（4 类越权响应对照）→ 踩坑 → 已知限制 → 验收对照 → 下一步）

---

## 4. 验收口径

| # | 项 | 门槛 | 验证 |
|---|---|---|---|
| 1 | 未认证访问 | 全部受保护端点 401 | `RestAuthContractTest` |
| 2 | 请求体注入 tenantId/userId | 对结果零影响 | `RestAuthContractTest` 第 4 条 |
| 3 | 跨租户读 | 0 行（应用层 + RLS 各验一次） | `TenantIsolationE2ETest` + `RlsEnforcementTest` |
| 4 | 无授权用户召回 | 0 条且拒答 | Task 8 用例 ① |
| 5 | 他人 documentId | 作用域空 + 引用空 | Task 8 用例 ② |
| 6 | 文档枚举 | 只列可读的 | Task 6 Step 3 + 用例 ③ |
| 7 | 对象存储泄漏 | 0 处对象键 | Task 8 用例 ④ |
| 8 | 越权泄漏总数（§18.3） | **= 0** | `ZeroLeakGateTest` |
| 9 | 既有能力不回归 | 306 通过 / 1 跳过 | `mvn test` |
| 10 | 写路径 | 新行 tenant_id 非空且等于所属文档 | `ChunkWriterTest` + `TaskMessageRepositoryTest` 新增用例 |

---

## 5. 偏差登记（执行时逐条追加）

| # | 计划原文 | 实际做法 | 原因 |
|---|---|---|---|
| 1 | 计划初稿把 `SET NOT NULL` 放在 V8 | 拆成 V8（可空 + 回填 + RLS）与 V9（收紧） | 加列与改代码同版时，中间态会让所有写路径报错；分两版才能每个 Task 结束都是绿的 |
| 2 | 设计 §15.4 写 `FORCE ROW LEVEL SECURITY` | 不 FORCE，改用「owner 迁移 + `finagent_app` 运行时」双角色 | FORCE 会连 Flyway 的数据迁移语句一起拦（迁移时没有租户上下文） |
| 3 | 计划初稿打算「无上下文一律 0 行」 | 增加 `finagent.tenant.fallback-tenant-id`（默认 1，仅开发） | Kafka worker / CLI 没有 HTTP 上下文；没有兜底值，重切与任务消费会全部写不进去。生产必须关掉，C11 里标注 |
| 4 | 设计 §15.4 的 4 类越权含「缓存穿透」「COS 直链」 | 改为「枚举最小化」+「响应无对象键」 | 项目无缓存、引用不带 URL，原用例在本项目没有攻击面；换成等价且可执行的断言 |
| 5 | — | 待执行时补 | — |

---

## 6. 执行顺序建议

1. Task 1 → 2 → 3（地基，每个 Task 结束跑一次全量）
2. Task 4（写路径，必须紧跟 Task 1，否则 V9 加不上）
3. Task 5 → 6（ACL 生效）
4. Task 7（收口，修既有测试）
5. Task 8（越权验收）
6. Task 9（文档与报告）

预计工作量：Task 1~4 约 1.5 天，Task 5~7 约 1 天，Task 8~9 约 1 天。
---

## 7. 执行前修订（2026-10-27，Task 5~9）

> 这一节是 Task 1~4 之后、继续动手之前**先定方案**的产物。规则：**任何代码改动前，先把「改哪些文件 / 为什么 / 怎么验收」写进计划，再动手。**
> 下面 R1~R5 是相对 §3.5~§3.8 原文的修订，执行时**以本节为准**；原文保留以便对照「为什么改主意」。

### R1 Bean 装配：改用单个工厂方法 + 开关，不用成对 `@Profile`

**问题**：`AllowAllAuthorizer.java:13` 有 `@Component` 无 profile，`AclScopeAuthorizer` 标 `@Profile("!test")`。非 `test` profile 下两个候选 → `NoUniqueBeanDefinitionException: ScopeAuthorizer`（§3.5 Step 4 就是查这个）。而且 `@Profile` 是埋在类上的开关：以后任何一个新 profile（`acl` / `rls` / `dev`）都要重新推一遍谁会装配。

**方案（照抄 rerank 那次的经验）**：

| 文件 | 改动 |
|---|---|
| `src/main/java/com/finagent/scope/AllowAllAuthorizer.java` | 删 `@Component` 与 import（变成普通类，由配置类 new） |
| `src/main/java/com/finagent/scope/AclScopeAuthorizer.java` | 删 `@Component` 与 `@Profile` |
| `src/main/java/com/finagent/scope/ScopeAuthorizerConfig.java` | **新建**：`@Configuration(proxyBeanMethods = false)` + `@EnableConfigurationProperties(TenantProperties.class)`，一个 `@Bean ScopeAuthorizer scopeAuthorizer(TenantProperties p, JdbcTemplate jdbc)`，`p.aclEnabled() ? new AclScopeAuthorizer(jdbc) : new AllowAllAuthorizer()` |
| `src/main/java/com/finagent/security/TenantProperties.java` | 加 `boolean aclEnabled`（默认 `true`） |
| `src/main/resources/application.yml` | `finagent.tenant` 下加 `acl-enabled: true` |
| `src/test/resources/application-test.yml` | `finagent.tenant` 下加 `acl-enabled: false`（紧邻 `auth-enabled: false`，写明「300+ 既有测试不关心 ACL；ACL 由 AclScopeAuthorizerTest 与越权 e2e 覆盖」） |

**为什么不用成对 `@ConditionalOnProperty`**：阶段 4 的 rerank 已经踩过——`havingValue` 不写时「存在且不是 false」都算命中，空串也算，于是两个条件同时成立、两个 Bean 同时注册；反过来值写成 `TRUE` 又会两个都不命中。**单工厂方法从结构上只可能产出一个 Bean。**

**验收**：任意 `@SpringBootTest`（如 `-Dtest=KafkaTopicConfigTest`）不报 `NoUniqueBeanDefinitionException`；`-Dtest=AclScopeAuthorizerTest` 跑到真实 ACL 实现。

### R2 `AclScopeAuthorizerTest` 的 profile：用 properties 开关，不新造 `acl` profile

§3.5 原文写 `@ActiveProfiles("test")`，但 `AclScopeAuthorizer` 在 `test` 下被排除；已写的实现改用 `@ActiveProfiles("acl")`，而仓库里**没有** `application-acl.yml`，配置来源不明确。

**方案**：测试类用 `@SpringBootTest(properties = "finagent.tenant.acl-enabled=true")` + `@ActiveProfiles("test")`。`test` 里 `auth-enabled=false` 不影响本测试——ACL 只依赖 `TenantContext`，测试自己在 `@BeforeEach` 里 `TenantContext.set(...)`（三个用户 id 用 `SELECT id FROM app_user WHERE username = ?` 查，不硬编码）。

**反向验证（手工跑一次，不入库）**：把开关临时改成 false，同一测试应因「拿到的是 AllowAll」而失败——这能在 1 分钟内证明开关真的生效。

### R3 CORPUS 路径：ACL 收窄必须下推到检索（否则 ACL 形同虚设）

**问题**：`HybridRetriever.applyScope`（`src/main/java/com/finagent/retrieval/HybridRetriever.java:361-364`）对 `ScopeKind.CORPUS` **直接原样返回**，理由在 `:356` 的注释里——「全库不该把 654 个 id 拼进 `IN`，那会让优化器放弃索引」。这条优化在有 ACL 之后变成漏洞：analyst 在 CORPUS 模式下只应看到 1 份文档，但检索照样全库扫，ACL 只在「提示词里列了哪些文档」生效，没在「能取到哪些块」生效。

**方案**（最小改动，不新建概念）：

| 文件 | 改动 |
|---|---|
| `src/main/java/com/finagent/scope/ResolvedScope.java` | 加 `boolean aclNarrowed` + `withAclNarrowed(boolean)`；`of(...)` 签名不变、默认 `false`，避免动现有调用与测试 |
| `src/main/java/com/finagent/scope/ScopeResolver.java` | 三条路径各算 `narrowed = readable.size() < candidates.size()`，C 模式与 B 模式在过滤后 `withAclNarrowed(narrowed)` |
| `src/main/java/com/finagent/retrieval/HybridRetriever.java` | `applyScope` 首行改成 `if (scope == null \|\| scope.isEmpty() \|\| (scope.kind() == CORPUS && !scope.aclNarrowed()))` |

**为什么不干脆在 CORPUS 时总推 id**：现在只有 4 份文档当然无所谓，但这是「到 500 份就炸」的写法，而 `:356` 的注释已经把理由写清楚了；用 `aclNarrowed` 把「没被 ACL 卡过」与「被卡过」区分开，既留住优化又堵住漏洞。

**已知限制（登记，不现在做）**：ACL 真的收窄时仍会拼较长的 `IN`；触发条件是「租户内可检索文档 > 500 份」，届时改成 `dc.document_id IN (SELECT ... ACL 子查询)` 下推。

**验收**：新增 `ScopeResolverAclTest` 三条 —— ① CORPUS 下 analyst 只拿到被授权文档且 `aclNarrowed=true`；② CORPUS 下 admin 拿到全部且 `aclNarrowed=false`；③ 全被拒时抛 `ScopeEmptyException`（不是返回空列表）。再加 `HybridRetriever` 单测一条：`applyScope(CORPUS + aclNarrowed=true)` 必须把 ids 写进过滤条件。

### R4 FILTER 路径：确认已推 id，只需补 ACL

`applyScope` 只跳过 `CORPUS`，`FILTER` / `DOCUMENTS` 都会把 id 写进过滤条件，所以 FILTER 只要在**解析阶段**补 `filterReadable` 即可（§3.6 原文正确）。`resolveFilter` 现在的顺序是「查候选 → 直接返回」，改成「查候选 → 取 id → `filterReadable` → 按原顺序回填 → 空则抛」。

### R5 Task 8 越权用例：三处口径纠正

| 原文写法 | 问题 | 改成 |
|---|---|---|
| 断言 `response.refused()` | `ChatResponse` 没有 `refused()` 访问器 | 断言 `!response.sufficient()`（拒答时 `sufficient=false` 且 `answer` 为空串） |
| 外租户 `documentId` 走 DOCUMENTS 模式期望「拒答」 | 该路径先抛 `ScopeEmptyException` → HTTP **409**，不是 200 + 拒答 | 断言 409 + 错误码 `SCOPE_EMPTY` |
| 用 `POST /api/chat` 测「对象键不泄漏」 | 聊天响应里根本不含对象键 | `GET /api/documents/{id}` 跨租户 → **404**（不用 403：不能泄漏「这个 id 存在」） |

另补两类负样本的断言口径：**请求体里塞 `tenantId` / `userId` 一律无效**（服务端只认 `TenantContext`）；**跨租户 `documentId` 出现在 `scope.documentIds` 里 → 409**。

### R6 Task 6 Step 3 + Task 7 的三处口径（2026-10-27，动手前确认）

| 计划原文 | 处理 | 理由 |
|---|---|---|
| Task 7 Step 1「去掉 `auth-enabled` 开关」 | **保留开关**（生产默认 true，`application-test.yml` 为 false） | 300+ 既有测试不会带 token；Task 7 的契约测试用 `properties="finagent.tenant.auth-enabled=true"` 单独打开真链路，e2e 走生产默认值。删掉开关等于把「测试环境可关认证」这个后门藏起来，不如显式留着并写进文档。 |
| Task 6 Step 3（`GET /api/scope/documents` 过 ACL） | **并入本次步骤 3 一起做**（此前只做了 ScopeResolver 三条路，控制器漏了） | 这个端点是「枚举泄漏点」：不按 ACL 过滤，任何租户内用户都能把全库文档列表拉走，用不着猜 id。 |
| Task 7 用例③「outsider 问全库 → 200 但拒答」 | 改成 **409 + `SCOPE_EMPTY`** | 租户 1 的候选文档 ∩ outsider 的 ACL = 空集 → `ScopeEmptyException` 先于检索抛出。语义是对的：「你没有任何可读文档」比「范围内没答案」更准确。零财务数字的断言保留（扫响应体里的数字）。 |
| Task 7「4 个用例」 | 拆成 5 个，且**全部不调真模型**（非 e2e） | 401/401/409 分支都在检索与生成之前，用 MockMvc 走真过滤器即可；真模型的拒答语义由 Task 8 的 e2e 覆盖。 |
### 执行顺序（确认后按此推进）

| 步骤 | 内容 | 结束标志 |
|---|---|---|
| 1 | R1 Bean 装配 + R2 测试 profile | `AclScopeAuthorizerTest` 8 绿；随机 `@SpringBootTest` 不报 Bean 冲突 |
| 2 | R3 + R4 作用域三条路接 ACL | 新增 4 条测试绿 |
| 3 | Task 7 REST 收口（401 语义、请求体注入无效） | 3 条测试绿 |
| 4 | Task 8 越权用例集（按 R5 口径） | 4 类负样本全绿 + 零泄漏 |
| 5 | Task 9 契约 C11 + 术语 + README + 阶段 8 报告 | 文档齐全 + 全量测试绿 |
### 执行结果（2026-10-27 收尾）

| 步骤 | 内容 | 结果 |
| --- | --- | --- |
| 1 | R1 Bean 装配 + R2 测试 profile | `AclScopeAuthorizerTest` 9 绿 |
| 2 | R3 + R4 作用域三条路接 ACL | `ScopeResolverAclTest` 5 + `HybridRetrieverScopeTest` 16 绿 |
| 3 | Task 6 Step 3 + Task 7 REST 收口 | `RestAuthContractTest` 6 + `ChatControllerTest` 6 绿 |
| 4 | Task 8 越权用例集（按 R5 口径） | `TenantIsolationE2ETest` 6 + `ZeroLeakGateTest` 1 绿，`ZERO-LEAK|objectKeys=0|titles=0|content=0` |
| 5 | Task 9 契约 C11 + 术语 + README + 报告 | 全部落地；全量 **343 通过 / 0 失败 / 1 跳过**（阶段 7 是 306，本阶段 +37） |

实测数字、7 个踩坑与 8 条已知限制见 `docs/阶段8-权限与多租户报告.md`。

