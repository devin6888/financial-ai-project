# 金融研报智能分析 Agent

基于 **Spring Boot + Spring AI** 的金融研报 RAG 问答系统。

- 设计文档：`金融研报智能分析Agent_系统架构设计_v0.10.docx`
- 开发路线图与阶段计划：`docs/superpowers/plans/`
- 环境搭建手册：`docs/阶段1-环境搭建手册.md`
- **术语表（第一次做 RAG 就先读这份）**：`docs/术语表.md` —— 每个缩写都有中文全称、为什么要它、在本项目里长什么样
- 文档写作规范：`docs/文档写作规范.md`

**当前进度：阶段 8（权限与多租户）已完成。** 各阶段报告：阶段 2 `docs/阶段2-解析结果报告.md`、阶段 3 `docs/阶段3-切块与入库报告.md`、阶段 4 `docs/阶段4-检索报告.md`、阶段 5 `docs/阶段5-答案生成报告.md`、阶段 6 `docs/阶段6-评测报告.md`、阶段 7 `docs/阶段7-异步任务化报告.md`、阶段 8 `docs/阶段8-权限与多租户报告.md`。

---

## 环境要求

- JDK 21
- Maven 3.9+
- Docker Desktop

## 快速开始

```bash
docker compose up -d          # 启动 PostgreSQL（含 pgvector + zhparser）与 Kafka
bash scripts/verify-db.sh     # 断言验证：6 项检查，全 PASS 才算通过
mvn test                     # 集成测试
```

Windows 下执行脚本用 Git Bash：

```powershell
& "D:\Program Files\Git\bin\bash.exe" scripts/verify-db.sh
```

## 环境变量（凭据）

凭据一律通过环境变量注入，**不写入仓库**。

| 变量 | 必需 | 说明 |
|---|---|---|
| `SPRING_AI_OPENAI_API_KEY` | 是 | 阿里云百炼 API Key（宽松绑定自动映射到 `spring.ai.openai.api-key`） |
| `FINAGENT_COS_SECRET_ID` | 是 | 腾讯云 SecretId |
| `FINAGENT_COS_SECRET_KEY` | 是 | 腾讯云 SecretKey |
| `FINAGENT_COS_REGION` | 是 | 例如 `ap-guangzhou` |
| `FINAGENT_COS_BUCKET` | 是 | 形如 `mybucket-1251234567`（**必须带 APPID 后缀**） |
| `FINAGENT_COS_KEY_PREFIX` | 否 | 对象键前缀，便于多环境隔离，例如 `dev/` |
| `FINAGENT_RERANK_API_KEY` | 否 | 百炼 Rerank Key（与 `SPRING_AI_OPENAI_API_KEY` 是同一把）。**缺失或为空时装配 NoopRerankModel**：检索照常返回 RRF 结果，trace 里 `degraded=true`、模型名 `none` |

推荐做法：在项目根目录创建 `.env`（已在 `.gitignore` 中），然后加载后运行：

```powershell
. .\scripts\load-env.ps1     # 把 .env 读入当前进程环境
mvn test
```

`.env` 内容示例：

```dotenv
SPRING_AI_OPENAI_API_KEY=sk-xxxxxxxx
FINAGENT_COS_SECRET_ID=AKIDxxxxxxxx
FINAGENT_COS_SECRET_KEY=xxxxxxxx
FINAGENT_COS_REGION=ap-guangzhou
FINAGENT_COS_BUCKET=mybucket-1251234567
FINAGENT_COS_KEY_PREFIX=dev/
FINAGENT_RERANK_API_KEY=sk-xxxxxxxx
```

**缺少凭据时的行为**：应用仍可正常启动，相关测试**自动跳过**（不会失败）。

| 测试 | 需要的变量 |
|---|---|
| `VectorStoreSmokeTest#dashscopeEmbeddingReturns1024Dimensions` | `SPRING_AI_OPENAI_API_KEY` |
| `CosObjectStorageTest`（整类） | `FINAGENT_COS_*` 四项 |
| `DashScopeRerankModelLiveTest` | `FINAGENT_RERANK_API_KEY` |

## 阶段 1 已验证的环境指纹

| 组件 | 版本 |
|---|---|
| PostgreSQL | 16.15 (Debian 16.15-1.pgdg12+2) |
| pgvector | 0.8.7 |
| zhparser | 2.4（commit `2e995c4df672563992b4d7a147b8fa2d0d4cda6c`） |
| Spring Boot | 4.0.8 |
| Spring AI | 2.0.1 |
| Java | 21 |

验收结果：

| 命令 | 结果 |
|---|---|
| `bash scripts/verify-db.sh` | 6/6 PASS，退出码 0 |
| `mvn test`（未配置凭据） | Tests run: 4, Failures: 0, Errors: 0, Skipped: 2 |
| `mvn test`（配置凭据后） | Tests run: 4, Failures: 0, Errors: 0, **Skipped: 0** |

**已实证的关键假设**：

- 百炼 `text-embedding-v4` 经 OpenAI 兼容接口返回 **1024 维**（`dimensions=1024` 参数生效）
- 腾讯云 COS 经官方 SDK 完成 put → exists → get → 生成签名 URL → delete 全链路往返

## 镜像构成与体积

Dockerfile 采用**多阶段构建**：

| 阶段 | 内容 | 是否进入最终镜像 |
|---|---|---|
| builder | 编译工具链（build-essential / autoconf / automake / libtool / postgresql-server-dev-16）+ SCWS + zhparser | ❌ 否 |
| 运行 | pgvector 基础镜像 + 仅拷入 SCWS 运行库与 zhparser 产物 | ✅ 是 |

- 最终镜像 **652 MB**（单阶段方案为 2 GB，差额是约 1.3 GB 的编译工具链）
- 运行镜像不含任何编译工具链，attack surface 更小
- ⚠️ **本机磁盘净占用变化不大**：被裁掉的图层会留在 Docker 构建缓存里（约 1.37 GB），以便快速重建。
  想真正回收：执行 `docker builder prune -f`（代价是下次重建需重新编译，约 5 分钟）

## 目录结构

```
financial-ai-project/
├── docker-compose.yml                 # 本地依赖编排
├── docker/postgres/
│   ├── Dockerfile                     # pgvector + zhparser 镜像
│   └── init/
│       ├── 01-extensions.sql          # 扩展与中文分词配置
│       └── 02-schema.sql              # 4 张业务表
├── scripts/verify-db.sh               # 数据库断言脚本
├── docs/
│   ├── 术语表.md                      # 所有缩写的解释（先读这份）
│   ├── 文档写作规范.md                # 报告/计划怎么写才看得懂
│   ├── 阶段1~8-*.md                   # 各阶段报告
│   ├── 技术选型.md                    # 选了什么 / 没选什么 / 为什么 / 何时该改
│   │                                  #   附录 A：为什么不用 ORM（MyBatis-Plus / JPA 实测对照）
│   ├── 关键契约.md                    # C1~C11 跨阶段不变式（C11 = 认证/租户/可见性）
│   ├── pgvector-工作机制.md           # 向量库机制与实测
│   ├── PG文本索引-tsv与trgm.md        # 中文全文检索机制与实测
│   ├── 权限与多租户-工作机制.md       # 认证 / 租户 / RLS / ACL 四层机制与实测
│   └── superpowers/plans/             # 路线图与各阶段计划
├── src/main/java/com/finagent/        # Spring Boot 应用
├── src/main/resources/application.yml
└── src/test/java/com/finagent/        # 集成测试
```

## 阶段 2：文档解析服务（Python / Docling）

解析跑在一个**独立的 Python 服务**里，Java 侧通过 HTTP 调用。
边界很清楚：**Python 只负责「看懂 PDF」，不接触数据库与对象存储**；
结果写哪、状态存哪，全由 Java 侧决定。

### 启动

```powershell
cd document-parser-service
$env:HF_ENDPOINT = 'https://hf-mirror.com'   # 必须！本机 huggingface.co 不可达
.\.venv\Scripts\python.exe -m uvicorn app.main:app --host 127.0.0.1 --port 8000
```

### 接口

| 接口 | 用途 |
|---|---|
| GET /health | 健康检查 |
| POST /parse | 同步解析，小页范围与手工验证用 |
| POST /jobs | **提交作业**，立即返回 job_id（202）。长文档走这条 |
| GET /jobs/{id} | 查询状态、耗时、错误与结果 |

> **为什么有 /jobs**：181 页约 25 分钟。同步 HTTP 在这个量级上是缺陷 ——
> 网络抖动全部重来、worker 被占死、客户端看不到进度。作业化之后提交耗时从
> 15.6 秒（3 页同步）降到 **0.07 秒**。阶段 7 已用 Kafka 把这一层换成事件驱动（见下节）。

### 端到端验证

```powershell
# 1) 启动解析服务（见上）
# 2) 跑端到端：COS -> Java -> Python -> 产物回 COS -> 更新 document
. .\scripts\load-env.ps1
mvn -B -Dsurefire.excludedGroups= -Dgroups=e2e -Dtest=FullDocumentPipelineTest test
```

> -Dsurefire.excludedGroups= **不能省**：-Dgroups=e2e 是包含语义，
> 而配置里的 excludedGroups 仍在生效，两者叠加会一条都不跑。

### 数据库迁移

从阶段 2 起 schema 由 **Flyway** 管理，只有一个来源：src/main/resources/db/migration/。

**改表只需新增 V*.sql，不要再 docker compose down -v。**

## 阶段 7：异步任务化（Kafka）

解析与重索引不再是「测试里手动调的两个方法」，而是两条 Kafka 事件：

| Topic | 谁投 | 谁消费 | 干什么 |
|---|---|---|---|
| `document.parse` | `POST /api/documents` | `DocumentParseListener` | 从 COS 读回 PDF → Docling 解析 → 切块 → 增量嵌入 → 入库 |
| `document.reindex` | 人工投递（后续可加计划任务） | `DocumentReindexListener` | 从已存解析产物重切 + 增量重嵌，**不重新解析 PDF** |

失败处理：瞬时错误（超时、COS 5xx）重试 3 次、间隔 1 秒；仍失败或业务/数据错误 → 消息进
`document.parse.dlt` / `document.reindex.dlt`，同时 `task_message` 表留一行（事件去重 + 失败原因）。

### 上传并轮询

```powershell
. .\scripts\load-env.ps1

# 上传（multipart）。docType 只能是 ANNUAL / SEMI_ANNUAL / Q1 / Q3
curl.exe -X POST http://localhost:8080/api/documents `
  -F "file=@docs/东方财富2025年年度报告.pdf" `
  -F "companyCode=300059" -F "companyName=东方财富" -F "market=SH" `
  -F "docType=ANNUAL" -F "docYear=2025" -F "title=东方财富2025年年度报告"
# -> {"documentId":7,"parseStatus":"DONE"}   同一份 PDF 重复上传命中幂等快路径，0 秒返回

# 查状态与元数据
curl.exe http://localhost:8080/api/documents/7
```

> **幂等快路径**：`content_hash` 已解析完成且存在 ACTIVE chunk 时，直接返回旧 `documentId`，
> 不落 COS、不投递、不解析。反过来说 —— 想真正触发一次解析，必须传一份**内容不同**的 PDF：
> 181 页在 CPU 上的 Docling 约 50 分钟，别拿大文件试速度。

### 验证

```powershell
. .\scripts\load-env.ps1
mvn test "-Dsurefire.excludedGroups=" "-Dgroups=e2e" "-Dtest=DocumentTaskE2ETest"
```

四个用例约 35 秒跑完：上传幂等（同 hash 只一次解析）、状态查询（DONE + 公司信息）、
真重索引（版本号恰好 +1，654 个块 100% 复用旧向量、0 次 embedding 调用）、
坏消息进 DLQ 后改 documentId 重放（`FAILED` → `SUCCESS`）。

> Kafka 按**开发规格**配置：堆 256M（镜像默认 1G）、健康检查用 `nc -z`（不用 Java 客户端，否则每 10 秒起一个 JVM）、
> 数据落在 `kafkadata` 卷上（镜像默认写 `/tmp/kafka-logs`，容器一重建就丢）。实测常驻内存 **约 260MB**、空闲 CPU **< 3%**。
> 代价：日志只保留 24 小时 —— 想看更久的死信要把 `KAFKA_LOG_RETENTION_HOURS` 改回去。

机制细节、实测数字与已知限制见 `docs/阶段7-异步任务化报告.md`。

## 阶段 8：权限与多租户（认证 / RLS / ACL）

所有对外端点都要带 `Authorization: Bearer <token>`。**身份只从 token 来** ——
请求体或查询串里塞 `tenantId` / `userId` 一律无效（有专门的测试盯着这一条）。

| 层 | 管什么 | 破了会怎样 | 失败时返回 |
|---|---|---|---|
| ① 认证 | 有没有身份、token 是否有效 | 谁都能读 | **401** `AUTH_REQUIRED` / `AUTH_INVALID` |
| ② 租户（数据库 RLS） | 跨租户读写（硬边界） | 跨客户数据泄漏 | 查不到任何行（不是报错，是空） |
| ③ ACL（应用层） | 同租户内的公司级授权 / 私有文档 | 同事之间互相看到不该看的 | **409** `SCOPE_EMPTY`，或列表里少几份 |

### 本地体验用的三个 token（V8 种子数据）

| token | 用户 | 角色 | 能看到什么 |
|---|---|---|---|
| `dev-admin-token` | admin | ADMIN | 租户 1 的全部文档 |
| `dev-analyst-token` | analyst | ANALYST | 只有 300059（东方财富）的两份年报 |
| `dev-outsider-token` | outsider | VIEWER | 什么都没有（无任何授权）→ 409 |

```powershell
# 有权限：正常回答，带引用
curl.exe -s -X POST http://localhost:8080/api/chat -H "Content-Type: application/json" `
  -H "Authorization: Bearer dev-analyst-token" -d "{\"question\":\"东方财富2025年营业收入是多少\"}"

# 没带 token：401
curl.exe -s -X POST http://localhost:8080/api/chat -H "Content-Type: application/json" `
  -d "{\"question\":\"东方财富2025年营业收入是多少\"}"

# 同租户但无授权：409（正文里一个数字都没有）
curl.exe -s -X POST http://localhost:8080/api/chat -H "Content-Type: application/json" `
  -H "Authorization: Bearer dev-outsider-token" -d "{\"question\":\"东方财富2025年营业收入是多少\"}"
```

### 开关（只用于本地调试）

| 配置 | 默认 | 作用 |
|---|---|---|
| `finagent.tenant.auth-enabled` | `true` | 关掉后不校验 token。**只给本地调试**，对外必须为 true |
| `finagent.tenant.acl-enabled` | `true` | 关掉后装配「全部放行」的实现。测试 profile 默认关（否则每个聊天测试都要先造授权行） |

> 关掉 ACL 的代价：**测试环境不验 ACL**。所以 ACL 有独立的数据库级测试（`AclScopeAuthorizerTest`、
> `ScopeResolverAclTest`）和真 HTTP 越权用例（`TenantIsolationE2ETest`、`ZeroLeakGateTest`）。

### 验证

```powershell
. .\scripts\load-env.ps1
mvn test "-Dsurefire.excludedGroups=" "-Dgroups=e2e" "-Dtest=TenantIsolationE2ETest,ZeroLeakGateTest"
```

7 个用例（约 60 秒，其中一条调真模型）：

1. 同租户无授权 → 409，正文无数字；
2. 传他人 `documentId` → 409，不返回引用；
3. 跨租户枚举文档 → `[]`；
4. 跨租户查详情 → **404**（不是 403：403 等于确认这个 id 存在）；
5. 请求体伪造 `tenantId` → 结果不变；
6. 对照组：分析师能正常拿到带引用的答案；
7. 零泄漏门限：对象键 / 他人标题 / 他人正文片段，命中数全为 0。

机制细节见 `docs/权限与多租户-工作机制.md`，实测数字与已知限制见 `docs/阶段8-权限与多租户报告.md`。

## 已知限制（阶段 1）

- 尚未接入 Docling 解析服务，**问答链路不可用**（见阶段 2~5 计划）。已接入并验证：百炼 embedding（1024 维）、腾讯云 COS。
- `mvn test` 需要先 `docker compose up -d`（测试依赖本地 PostgreSQL）。
- 中文分词目前只用 zhparser 默认词典；金融专有名词（如「扣非净利润」）的切分效果需在阶段 4 用真实语料验证并补充自定义词典。
- 设计文档 §4.1（容量/SLO）与 §21.3（容灾 RPO/RTO）属生产运维目标，开发阶段不以此作为验收条件；**功能范围仍按设计文档完整建设**。

## ⚠️ 三个容易踩的坑

**1. init SQL 只在数据卷为空时执行一次。**

改完 `docker/postgres/init/` 下的 SQL 再 `docker compose up -d`，**改动不会生效**。必须：

```powershell
docker compose down -v
docker compose up -d
```

**2. 本机 Docker 加速器拉不到 pgvector 镜像。**

加速器 `docker.m.daocloud.io` 对 `library/*` 正常，但对 `pgvector/pgvector` 命名空间返回 401。
若换了机器或删除了该镜像，需手动拉取并改回原标签：

```powershell
docker pull docker.1ms.run/pgvector/pgvector:pg16
docker tag docker.1ms.run/pgvector/pgvector:pg16 pgvector/pgvector:pg16
```

**3. 百炼的 `base-url` 必须包含 `/v1`。**

Spring AI 2.0 改用官方 OpenAI Java SDK，该 SDK 的 `base-url` 需**包含** `/v1`（SDK 自行拼接 `/chat/completions`、`/embeddings`）：

| 配置 | 结果 |
|---|---|
| `https://dashscope.aliyuncs.com/compatible-mode/v1` | ✅ 正常 |
| `https://dashscope.aliyuncs.com/compatible-mode` | ❌ `404 NotFound` |

⚠️ 这个 404 **不会提示缺少 `/v1`**，很容易误判成模型名写错或 Key 无效。
（这是从 Spring AI 1.0 升级到 2.0 的破坏性变更：1.0 的 base-url 不含 `/v1`。）