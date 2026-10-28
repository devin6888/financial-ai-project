# 金融研报智能分析 Agent

基于 **Spring Boot + Spring AI** 的金融研报 RAG 问答系统。

- 设计文档：`金融研报智能分析Agent_系统架构设计_v0.10.docx`
- 开发路线图与阶段计划：`docs/superpowers/plans/`
- 环境搭建手册：`docs/阶段1-环境搭建手册.md`
- **术语表（第一次做 RAG 就先读这份）**：`docs/术语表.md` —— 每个缩写都有中文全称、为什么要它、在本项目里长什么样
- 文档写作规范：`docs/文档写作规范.md`

**当前进度：阶段 13（首屏反馈：状态行 / 证据先行）已完成。** 各阶段报告：阶段 2 `docs/阶段2-解析结果报告.md`、阶段 3 `docs/阶段3-切块与入库报告.md`、阶段 4 `docs/阶段4-检索报告.md`、阶段 5 `docs/阶段5-答案生成报告.md`、阶段 6 `docs/阶段6-评测报告.md`、阶段 7 `docs/阶段7-异步任务化报告.md`、阶段 8 `docs/阶段8-权限与多租户报告.md`、阶段 9 `docs/阶段9-Memory与会话报告.md`、阶段 10 `docs/阶段10-前端报告.md`、阶段 12 `docs/阶段12-体验补强报告.md`、阶段 13 `docs/阶段13-首屏时延报告.md`。

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
│   ├── 阶段1~13-*.md                  # 各阶段报告（阶段 10 另有一份前端验收清单）
│   ├── 技术选型.md                    # 选了什么 / 没选什么 / 为什么 / 何时该改
│   │                                  #   附录 A：为什么不用 ORM（MyBatis-Plus / JPA 实测对照）
│   ├── 关键契约.md                    # C1~C16 跨阶段不变式（C12 记忆 / C13 公司名→代码 / C14 登录 / C15 流式 / C16 首屏）
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

## 阶段 9：会话与记忆（多轮 / 摘要 / 长期偏好）

阶段 8 之前每一轮都是「第一次问」。阶段 9 让系统记住三件事：**刚才聊了什么**（短期）、**更早聊了什么**（摘要）、**用户明确要求过什么**（长期偏好）。四层记忆的分工、预算与「为什么记忆不能当引用」见 `docs/阶段9-Memory与会话报告.md` 与 `docs/关键契约.md`（C12）。

> 公司名与证券代码的分工也在这一阶段定稿（C13）：**模型只抽「原文里出现的公司名」，代码由程序在作用域内查库**。理由见报告 6.6 —— 让模型输出代码，等于让它凭训练记忆背答案，实测会给「东方财富」配一个 600519。

| 层 | 存在哪 | 进 Prompt | 能当引用 |
|---|---|---|---|
| 短期记忆（默认 6 轮） | `conversation_message` | 是 | 否 |
| 会话摘要（≤200 字） | `conversation_summary` | 是 | 否 |
| 结构化上下文（公司/年份/指标） | `conversation_context` | 是（一行） | 否 |
| 长期记忆（偏好 / 关注点） | `long_term_memory` | 按需（Gate 通过才查） | 否 |
| 本轮证据 | `document_chunk` | 是 | **是** |

### 四条 curl（建会话 → 追问 → 写偏好 → 查记忆）

```powershell
# 1) 建会话，记下返回的 conversationId
curl.exe -s -X POST http://localhost:8080/api/conversations -H 'Content-Type: application/json' `
  -H 'Authorization: Bearer dev-analyst-token' -d '{"title":"东方财富 2025 年报问答"}'

# 2) 问一句，再追问「那净利润呢」——第二轮的检索查询会被补成「东方财富（300059） 2025年 净利润」
curl.exe -s -X POST http://localhost:8080/api/chat -H 'Content-Type: application/json' `
  -H 'Authorization: Bearer dev-analyst-token' `
  -d '{"question":"东方财富2025年营业总收入是多少","conversationId":1}'

curl.exe -s -X POST http://localhost:8080/api/chat -H 'Content-Type: application/json' `
  -H 'Authorization: Bearer dev-analyst-token' `
  -d '{"question":"那净利润呢？","conversationId":1}'

# 3) 写一条长期偏好（句首命令即可；也可以 POST /api/memories 显式写）
curl.exe -s -X POST http://localhost:8080/api/chat -H 'Content-Type: application/json' `
  -H 'Authorization: Bearer dev-analyst-token' -d '{"question":"记住：回答先给结论，再列数字"}'

# 4) 看当前记忆（同 key 只有一条 ACTIVE；改写会生成 version+1，旧行 SUPERSEDED）
curl.exe -s http://localhost:8080/api/memories -H 'Authorization: Bearer dev-analyst-token'
```

> 追问能生效的前提是 `warnings` 里出现 `query_inferred_from_context` —— 它表示这一轮的公司/年份是**推出来的**，不是用户说的。
> 反过来，如果模型判断上下文不足以补全，会原样检索并置 `resolved=false`，不会编造年份（编造的年份会变成一个真实的过滤条件，把正确答案过滤掉）。

### 开关（只用于本地调试 / 基线对照）

| 配置 | 默认 | 作用 |
|---|---|---|
| `finagent.memory.enabled` | `true` | 关掉后行为与阶段 8 **完全一致**（不装配记忆、不落库、不写 trace 记忆段）。测试 profile 默认 `false`，这是回归门限 |
| `finagent.memory.gate-enabled` | `true` | 关掉后一律不召回长期记忆（也不调 embedding），用于「记忆到底有没有用」的基线对照 |
| `finagent.memory.summary-trigger-messages` | `12` | 未汇总消息数超过它就触发摘要（1 轮 = 2 条） |
| `finagent.memory.short-term-rounds` | `6` | 短期窗口最多 6 轮 |

### 验证

```powershell
. .\scripts\load-env.ps1
mvn test "-Dsurefire.excludedGroups=" "-Dgroups=e2e" "-Dtest=ConversationE2ETest"
```

6 个用例全部调真模型（多轮指代消解 4 + 偏好跨会话 1 + 记忆不冒充证据 1）：

1. 「那净利润呢」→ 补全成 300059 / 2025 / 净利润，答案数字落在 golden set 里；
2. 「比去年增长多少」→ 答案给出增长率（实测 38.46%）并把两个年度写清楚；
3. 「它现在的现金流怎么样」→ 引用仍只来自 2025 年报（document 7）；
4. 「那毛利率呢」→ 允许证据不足，但不许 500；
5. 写一条偏好 → 新会话带「按我的」提问 → `trace.memory.gateHit=true` 且 `longTermItems>=1`；
6. 记忆里的数字若被复述 → 必须留下 `answer_ungrounded` 痕迹；引用全部来自真实文档行。

机制与设计取舍见 `docs/阶段9-Memory与会话报告.md`，契约条款见 `docs/关键契约.md`（C12）。


## 阶段 10：Web 前端（不再用 curl 才能问问题）

阶段 1~9 的全部能力都只有 API。阶段 10 加了浏览器界面：**四页**（问答 / 文档 / 记忆 / 登录），技术栈 Vue 3 + TypeScript + Vite + Element Plus，产物直接输出到 `src/main/resources/static`，单端口即可使用。

> 为什么自己写而不套后台模板：实测 `vue-pure-admin` 是 41,250 行 src、64 个运行时依赖，而我们要写的只有 4 页 + 1 个 API 客户端（约 3,100 行，含 24 个文件）。脚手架用官方的 `create-vue --bare`（23 个文件、0 业务代码），模板不用。实测数据与取舍见 `docs/阶段10-前端报告.md`。

![问答页](docs/screenshots/chat-answer.png)

> 界面是「纸墨·朱批」：暖米纸面 + 发丝线 + 宋体答案 + 朱砂只用于强调。三栏 = 68px 图标条 / 248px 会话栏 / 主区（正文 960px）/ 424px **浮层**证据面板（打开时盖在正文右侧，不挤压正文）（截图 1440×980，脚本 `target/ui-shots.js` 生成）。

| 页面 | 地址 | 能做什么 |
|---|---|---|
| 概览 | `#/overview` | 4 个 KPI（可检索文档 / 活跃子块 / 我的会话 / 长期记忆）、语料状态、最近会话、长期记忆；数据来自 `GET /api/stats`，只统计当前账号可读的文档 |
| 问答 | `#/chat`、`#/chat/{id}` | 提问、追问、引用卡片、数值表、诊断面板、作用域三模式、会话改名/删除 |
| 文档 | `#/documents` | 上传 PDF（带进度）、看 PENDING/PARSING/DONE/FAILED、2 秒自动刷新 |
| 记忆 | `#/memories` | 写/删长期偏好（同 key 自动升版本）、按类型筛选 |
| 登录 | `#/login` | **用户名 + 密码登录**（默认账号已填好），也可切到 API token 方式粘贴脚本用的 token |

> **前端是独立仓库**：`D:\Project\myproject\financial-ai-web`（与本仓库 `financial-ai-project` 平级）。2026-10-28 从本仓库的 `web/` 拆出——前端有自己的 `package.json` / 锁文件 / IDE 配置，独立仓库能单独 clone、单独发版，也不让 `node_modules` 混进后端的 `git status`。两边的连接只有一个：`financial-ai-web/vite.config.ts` 的 `build.outDir` 指回本仓库的 `src/main/resources/static`。

### 默认账号

登录页默认填好这个账号，点「进入工作台」即可（密码由 V11 迁移用 PostgreSQL `pgcrypto` 的 bcrypt 存哈希，登录成功才签发会话 token）：

| 账号 | 密码 | 角色 | 能看到 |
| --- | --- | --- | --- |
| `demo` | `demo123456` | ADMIN + ANALYST | 本租户全部文档（两份东方财富年报）、记忆、会话 |

改密码 = 改库：`UPDATE app_user SET password_hash = crypt('新密码', gen_salt('bf', 10)) WHERE username = 'demo';`。
登录接口与撤销机制见 `docs/关键契约.md`（C14）；想对比权限差异时，登录页切到「API token」仍可用三个 `dev-*` 种子 token。

### 两条跑法

**A. 开发模式（前端热更新，改完立刻看到）**

```powershell
# 终端 1：后端
. .\scripts\load-env.ps1
mvn spring-boot:run

# 终端 2：前端（Vite，5173；/api 自动代理到 8080）
# 前端是独立仓库，与本仓库平级：D:\Project\myproject\financial-ai-web
cd ..\financial-ai-web
npm install        # 首次；走该仓库 .npmrc 里的 registry.npmmirror.com
npm run dev
```

打开 <http://localhost:5173>。开发模式不需要给后端加 CORS：Vite 代理把 `/api` 转到 8080，浏览器眼里始终同源。

**B. 单端口（一条命令打包，打开 8080 就能用）**

```powershell
.\scripts\build-ui.ps1      # npm ci（首次）+ npm run build，产物落到 src/main/resources/static
. .\scripts\load-env.ps1
mvn spring-boot:run          # 打开 http://localhost:8080
```

`mvn -B clean package` 产出的 jar 里已含 `BOOT-INF/classes/static/`，`java -jar` 一样可用。

> 改了前端但只跑 `mvn package`（不 clean）时，`target/classes` 里会残留旧 hash 的 chunk —— `build-ui.ps1` 已经会先删掉 `target/classes/static`，手工打包时请用 `clean`。

### 五分钟演示路线（面试用）

1. 登录页直接用默认账号 `demo / demo123456` 登录 → 概览；先说清「这些数字来自真实可检索语料，不是写死的；换 outsider 账号会变成 0」。
2. 问答页点示例问题「东方财富 2025 年营业总收入是多少？」→ 答案 + 数值表 + 证据 chip + 右侧面板（页码 / 章节 / 块 id）。
3. 追问「那净利润呢？」→ 界面提示主语来自上文（`query_inferred_from_context`），不是模型猜的。
4. 展开「诊断」→ 查询理解路由、Dense/Sparse 召回条数、精排状态、分项耗时（这是最难造假的部分）。
5. 文档页看 PENDING→DONE 四态；记忆页写「回答先给结论」再问一句，观察它进背景信息、但不产生引用。

| 概览 | 登录 |
| --- | --- |
| ![概览](docs/screenshots/overview.png) | ![登录](docs/screenshots/login.png) |

设计与实测（三栏宽度、字体命中、去重、无溢出）见 `docs/阶段10-前端报告.md` §10。

### 界面上三个值得注意的地方

1. **引用是可点的**：答案里的 `[E1]` 是按钮，点它高亮下方对应证据卡（文档名 / 页码 / 章节 / 类型 / chunk id）。若模型没在正文标编号，卡片上方会明确写出「正文未标注编号」—— 界面**不替模型补标**，补标等于编造归因。
2. **诊断面板**（默认折叠）暴露检索细节：查询理解路由、dense/sparse 召回条数、rerank 状态、各段耗时、记忆闸门是否命中。答案不对时先看这里。
3. **作用域改完从下一轮生效**：后端 `request.scope` 优先，会话只在创建时存一份默认范围（见 C12）。界面上有这句提示。

### 验证

```powershell
cd ..\financial-ai-web   # 前端独立仓库
npx vitest run           # 16 个前端单测（答案 tokenizer 7 + SSE 解析 9）
```

界面的手工验收清单（10 条，含四类失败态与三种作用域）在 `docs/阶段10-前端验收清单.md`，每条都写了实测输出。

## 阶段 12：体验补强（流式 / 引用点开原文 / 切块可视）

阶段 10 让界面「能用」，阶段 12 让它「敢拿去演示」。触发点是 `docs/竞品对比与技术债.md` 的结论：与 RAGFlow / FastGPT 的差距集中在三项**用户看得见**的能力上 —— 回答是憋 8 秒一次蹦出来的、引用给了页码但点不开、切块只有数据库里的行。

| 加的什么 | 以前 | 现在 |
|---|---|---|
| 流式输出 | 等 7~11 秒，屏幕静止，后面整段出现 | 首字 **7.87 秒**、逐字到达（实测 60 个 delta），结束用 `final` 整条替换 |
| 引用点开原文 | 只有「第 66 页」这行字 | 点 `[E1]` → 后端把第 66 页渲成 PNG（1240×1754），朱砂框落在该块真实位置 |
| 切块可视 | 只有 `chunk_index` / `token_count` | 文档页「看切块」抽屉：104 个父块 / 630 个子块，选中即看正文 + 原文位置 |

![原文高亮](docs/screenshots/page-highlight.png)

![切块抽屉](docs/screenshots/chunk-browser.png)

### 流式接口

```powershell
$body = '{"question":"东方财富2025年营业总收入是多少"}'
curl.exe -N -X POST http://localhost:8080/api/chat/stream `
  -H "Authorization: Bearer dev-analyst-token" `
  -H "Content-Type: application/json" --data $body
```

| 事件 | 什么时候发 | 里面有什么 |
|---|---|---|
| `meta` | 检索完、模型开始生成前 | traceId、作用域、证据清单（编号 / 块 id / 页码 / 章节 / 类型）、上下文 token 数 |
| `delta` | 模型每吐一段 | `{text}`；**这里的编号是临时编号**，会被 `final` 重排 |
| `final` | 四层校验全部完成 | 与 `POST /api/chat` 逐字段相同的完整响应（引用 / 置信度 / 警告 / 诊断 / messageId） |
| `error` | 任一阶段失败 | `{code, message}` |
| `: ping` | 每 15 秒 | 注释行，防中间层空闲断流 |

> 前端只用两件事：`delta` 让字动起来，`final` 覆盖真相。流式不可用（404 / 断流 / 5xx）时自动回落一次到 `POST /api/chat`；`409 SCOPE_EMPTY` 与 `401` 是业务结论，不回落。

### 两个新端点

| 端点 | 作用 | 规则 |
|---|---|---|
| `GET /api/documents/{id}/pages/{page}?dpi=150` | 渲出 PDF 某页的 PNG | 先过 ACL；不可读 / 越界 / 无对象键 **全部同一个 404**（403 等于确认存在）；dpi 夹紧 72~300；进程内 LRU 48 页 |
| `GET /api/documents/{id}/chunks?limit=800&offset=0` | 当前 ACTIVE 版本的切块扁平列表 | Parent 在前、Child 紧随；只带 220 字缩略；不可读返回空数组 |

### 演示时怎么讲这三项

1. **流式**：「你现在看到的字是模型一个字一个字吐的，不是我编好的动画」—— 顺手点开诊断，指出首字 7.9 秒里 QU 和精排各占多少。
2. **原文**：「截图谁都会放，这份 PDF 是从私有 COS 桶里按需渲的，框的位置来自切块时存下的归一化 bbox，不是人工标的」。
3. **切块**：「104 个父块是章节分组，630 个子块才是真正被检索的单位；你点任意一个子块，我都能告诉你它来自哪一页哪一段」。

### 一个计划外的必要修复：引用编号重排

模型会给 `[E2][E7][E11][E13]`，而 `citations` 只收「用到的」4 条 —— 编号对不上，角标就点不开。现在 `AnswerService` 在引用校验之后统一重排成 `[E1..En]`，越界编号直接从正文删掉。不变量写在契约 C15：**正文里出现的最大编号 ≤ `citations.size()`**。

实测数据、六个踩坑与 7 条限制见 `docs/阶段12-体验补强报告.md`；契约条款见 `docs/关键契约.md`（C15）。

### 验证

```powershell
. .\scripts\load-env.ps1
mvn -B test                                    # 560 通过 / 1 跳过（需要解析服务的那条）
mvn -B test "-Dsurefire.excludedGroups=" "-Dgroups=e2e" "-Dtest=ChatStreamE2ETest"   # 流式 e2e，约 30 秒
cd ..\financial-ai-web; npx vitest run         # 16 个前端单测
```

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

## 阶段 13：首屏反馈（状态行 + 证据先行）

阶段 12 解决了「有字在动」，但没解决「**什么时候**有东西动」：第一个 `delta` 实测 7.87 秒才到，前面这 7.87 秒屏幕上只有一个静态骨架屏。阶段 13 补的不是速度，是**感知** —— 同样的 12 秒总耗时，现在 0.06 秒就有反应。

| 时刻 | 用户看到 | 系统在做 |
|---|---|---|
| 0.06 s | 四个点第一个亮起「正在理解问题 · 已用 0.1s」 | Query Understanding（规则命中或调模型解析公司/年份/指标） |
| 3.5 s | 「正在检索证据」 | Dense + Sparse 双路召回、RRF 融合 |
| 4.3 s | 「正在精排证据」 | qwen3-rerank 对候选重排 |
| 4.9 s | **证据条出现**（E1 表格 · 第 66 页 …），答案区仍是骨架 | 上下文装配完成，`meta` 已发 |
| 5.5 s | 首字出现，逐字生长 | 模型生成第一个 token |
| 12.5 s | 状态行消失、证据条按 `citations` 重绘 | 四层校验完成，`final` |

### 四个阶段事件

```
stage(understanding) → stage(retrieving) → stage(reranking) → meta → stage(generating) → delta* → final
```

| phase | 谁发 | 带什么 |
|---|---|---|
| `understanding` | `ChatService.askStreaming` 第一行 | 无 |
| `retrieving` | 作用域解析完、检索前 | `{documents:N}` |
| `reranking` | `HybridRetriever` 融合后、调精排前（无候选则不出现） | `{candidates:N}` |
| `generating` | `meta` 之后、调模型前 | `{evidence:N, contextTokens:N}` |

三条最容易被做错的规矩（完整五条见契约 C16）：

1. **stage 只说「正在做什么」**：不带答案文本、证据正文、页码、chunk id。带了就等于把结果从流里提前漏出去。
2. **必须真实进入该段才发**：禁止定时器补发、禁止前端按时间伪造。假进度条在演示时被追问「现在到哪一步」就崩。
3. **发过 stage 就必须有终止事件**：`meta` / `final` / `error` 至少一个，不允许停在「正在精排」。

### 实测（2026-10-28，热路径）

| 指标 | 阶段 12 | 阶段 13 | 目标 |
|---|---|---|---|
| 首屏有反应 | 7,870 ms（首字） | **16~19 ms**（Java e2e 两次）/ **59 ms**（真浏览器） | ≤ 300 ms |
| 证据可见 | 无（只有骨架） | **4,288~4,904 ms** | ≤ 5,000 ms |
| 首字 | 7,870 ms | **5,275~6,250 ms** | ≤ 8,000 ms |
| 总耗时 | 10,994 ms | 11,377~12,862 ms | 观察值 |

![证据先行](docs/screenshots/stage-evidence-first.png)

### 前端怎么实现的

- `StageLine.vue`：四个点 + 后端 `label` + `已用 x.xs`（本地 200 ms 计时，`clearInterval` 在组件卸载时执行）。**阶段名不自己编**，直接用后端文案，避免两边说法分叉。
- `EvidenceStrip.vue`：单行证据条，超出 4 条折叠成 `+N`。生成中不可点（数据来自 `meta.evidence` 候选全集），`final` 后按 `citations` 重绘并可点。
- `ChatView.vue`：发消息时先把阶段设为 `understanding`（它永远是第一段），所以后端第一帧到达前也不会空白；`final` 时清掉状态行、记下首屏时延。
- 滚动策略：只有用户本来就在底部 40 px 内才跟随滚动，否则往上翻正文时不会被拽回底部。

### 验证

```powershell
. .\scripts\load-env.ps1
mvn -B test                                    # 568 通过 / 1 跳过
mvn -B test "-Dsurefire.excludedGroups=" "-Dgroups=e2e" "-Dtest=ChatStreamE2ETest"   # 断言 firstStage ≤ 300ms，约 56 秒
cd ..\financial-ai-web; npx vitest run         # 17 个前端单测
```

观测值落盘在 `target/stream-timing.json`；完整分析见 `docs/阶段13-首屏时延报告.md`，契约条款见 `docs/关键契约.md`（C16）。
