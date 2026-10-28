# 阶段 9 · Memory 与会话（实施计划）

> 编写日期：2026-10-27。前置：阶段 1~8 全部完成（全量非 e2e 343 通过 / 1 跳过）。
> 本计划遵循 roadmap 的硬规则：**动手改代码之前先在对话里确认方案**，执行中与原计划冲突的写进 §7 偏差登记。
> 设计依据：架构设计 §11（会话、Memory 与 Agent State）、§11.9（Token 预算）、§11.11（LTM Gate 与组织级记忆）、
> `docs/文档作用域设计.md` §6.2~§6.3（conversation / message / scope_snapshot）、§9（不做什么）。

## 0. 为什么要有这一阶段

### 0.1 现状：系统是「单轮」的

| 证据 | 位置 | 含义 |
| --- | --- | --- |
| 请求体只有 `question` / `scope` / `extensionPolicy` | `src/main/java/com/finagent/chat/ChatRequest.java:11` | 没有 `conversationId`，每个问题都是独立一问一答 |
| 编排入口 `ask(ChatRequest)` 里没有历史 | `src/main/java/com/finagent/chat/ChatService.java:55-80` | 每轮从作用域开始重算，不读上一轮任何东西 |
| `understand(String query)` 只吃当前这一句 | `src/main/java/com/finagent/understanding/RouterService.java:47` | 「那净利润呢？」里没有公司、没有年份，QU 抽不出任何实体 |
| 轨迹只落文件 | `src/main/java/com/finagent/chat/ChatTraceWriter.java:19-21` | 注释原文：「阶段 9 有了会话表再迁移，不提前背 schema 债」 |

所以现在问「那净利润呢？」，`RuleBasedRouter` 拿不到公司代码与年份，只能退化成纯语义检索 + 稀疏词法检索，
召回的是全库任何提到「净利润」的段落 —— 答案可能与上一轮毫无关系，而且**不会报错**。
这是本阶段要解决的第一件事，也是最容易在演示里被一眼看穿的事。

### 0.2 四层 Memory 不是四张表

| 层 | 生命周期 / 范围 | 典型内容 | 本项目落到哪 | 是否进模型 Prompt |
| --- | --- | --- | --- | --- |
| 完整 Chat History | 会话级，长期保留（审计/回放） | 每轮 USER/ASSISTANT 消息、引用、traceId | `conversation_message` 表 | **不直接进**（只按窗口取最近若干条） |
| Short-term Memory | 会话级，滚动窗口 | 最近 N 轮原话 | 由 `conversation_message` 现算，不单独存 | 进（≤ 15% 预算） |
| Conversation Summary | 会话级，周期性覆盖较早消息 | 已确认结论、未完成问题 | `conversation_summary` 表（`summary_until_message_id` 前移） | 进（≤ 10%） |
| Structured Context | 会话级 / 任务级 | companyId、yearRange、documentIds、currentMetric | `conversation_context` 表 | 进（≤ 5%） |
| Long-term Memory | 跨会话，用户级 / 组织级 | 稳定偏好、固定输出要求、长期关注对象 | `long_term_memory` 表（带 `embedding`） | **按需**进（≤ 5%，最多 3 条） |

一句话：**短期记忆是「现算的窗口」，摘要与结构化上下文是「定期落库的压缩」，长期记忆是「另一套独立的检索」**。
把它们混成一个 `history` 字段，最后一定会出现「上下文超长」或「偏好丢失」二选一。

### 0.3 长期记忆为什么必须「按需召回」

如果每轮都检索长期记忆：① 每题多一次向量检索（本项目 Dense 检索 9.8~31.4 ms，再加外部 embedding 调用约 200~400 ms）；
② 更重要的是**噪声**——用户三周前说过「以后先给结论」，与「2025 年营业收入是多少」毫无关系，硬塞进 Prompt 只会稀释证据。
设计 §11.4 因此要求由 **Memory Retrieval Gate** 决定：简单事实问答、确定性计算、当前会话已有足够上下文时**跳过**。

## 1. 范围

### 1.1 本阶段做（9 项）

| # | 内容 | 对应设计 |
| --- | --- | --- |
| 1 | `conversation` / `conversation_message` / `conversation_summary` / `conversation_context` / `long_term_memory` / `memory_event` 六张表（V10） | §11.6 |
| 2 | 会话 CRUD：建会话、列消息、发消息（带 conversationId 的问答） | §11.8 |
| 3 | 短期记忆窗口注入 Prompt（默认最近 6 轮，可配） | §11.2 |
| 4 | Structured Context：抽取 → 合并 → 落库 → 可显式纠错（PATCH） | §11.10 |
| 5 | 指代消解：把会话上下文 + 最近几轮喂给 QU，输出可独立检索的 `normalizedQuery` | §9.2 |
| 6 | Conversation Summary：超过阈值时增量压缩较早消息 | §11.2 |
| 7 | Long-term Memory 写入：显式命令 + 显式 API；同 key 冲突 → 版本化 SUPERSEDED | §11.3 / §11.5 |
| 8 | Retrieval Gate + LTM 召回：Gate 判定 → Metadata 过滤 → 向量检索 → 阈值收敛 → 注入 | §11.4 / §11.11 |
| 9 | 契约 C12 + 术语表 + README + 报告 + e2e 多轮用例 | 本文档 |

### 1.2 本阶段不做（并写清理由）

| 不做 | 理由 |
| --- | --- |
| Redis 缓存（会话 / 摘要热缓存、限流） | 记忆的**真相源**必须是 PostgreSQL；缓存只是延迟优化，而当前一问答的主要耗时是外部 LLM 调用（秒级），缓存省不下可观测的量。等有 QPS 需求再单独做（`docs/技术选型.md` 的「何时该换」条目） |
| 用 LLM 自动提炼每条消息写长期记忆 | 准确率无法评估，金融场景误写代价高（写错偏好会污染之后所有回答）。本阶段只做「显式命令 + 显式 API」两类高置信来源 |
| Agent State / LangGraph4j Checkpoint（`agent_run` 表） | 属于阶段 10（Agent 工作流）。本阶段只留 `conversationId ↔ 未来 agentRunId` 的字段位 |
| 组织级记忆的写权限细分（ADMIN 可写 / 其他人只读） | 需要先有真实的组织使用场景；本阶段 `subject_type` 只启用 USER，ORG 留字段与约束 |
| Rerank 用于长期记忆召回 | LTM 候选通常只有几条（`top-3`），阈值 + 结构化 key 已够；多一次外部调用不划算 |
| 多会话共享 / 会话转移 / 协作者 | 权限模型（阶段 8）只做到「文档级」，会话级共享需要新的授权模型 |
| 前端 | 全仓库无前端，接口用 curl 演示 |
| `retrieval_trace` 落库 | trace 目前落 `logs/chat-trace.jsonl`，且设计 §11.6 的记忆表里没有它；迁移属于审计阶段（设计 §17） |

## 2. 契约 C12（草案，执行时定稿）

### 2.1 记忆分层与真相源

| 用途 | 唯一真相源 | 是否进 Prompt | 是否可作为引用 |
| --- | --- | --- | --- |
| 会话里说过什么 | `conversation_message` | 只取最近 N 轮 | 否 |
| 较早对话的压缩 | `conversation_summary` | 是 | 否 |
| 本轮讨论的公司/年份/文档 | `conversation_context` | 是 | 否 |
| 用户长期偏好 | `long_term_memory` | Gate 通过才进 | 否 |
| 答案依据 | `document_chunk`（RAG 证据） | 是 | **只有它能进 `[E1..En]` 编号** |

### 2.2 三条硬规则

1. **记忆不得覆盖事实**：RAG 证据与 `financial_fact` 优先于任何记忆；记忆只能影响偏好、格式与「指向哪个对象」，不能提供数字。
2. **记忆不参与引用编号**：`[E1..En]` 只来自 `ContextPackage.blocks()`。记忆以单独的「背景信息（不可引用）」段落注入 Prompt，
   `CitationValidator` 因此无需改动 —— 这一条把「记忆」与「证据」的边界变成了代码结构上的边界，而不是靠提示词自律。
3. **Gate 默认关闭**：`needLongTermMemory` 默认 false，只有规则词典命中或模型明确判定才置 true；跳过时**不产生任何外部调用**。

### 2.3 会话与作用域

| 规则 | 说明 |
| --- | --- |
| `sessionScope` 与 `effectiveScope` 分开存 | 会话存用户配置（`conversation.scope_kind` + `scope_json`）；每轮实际范围仍由 `ScopeResolver` 现算，并记进 `conversation_message.scope_snapshot` |
| 优先级 | 请求里显式带 `scope` → 用请求的；否则用会话的 `sessionScope`；再否则 `CORPUS` |
| 改了范围只影响后续轮次 | 历史消息的 `scope_snapshot` 不改写（否则回放会看到「当时用了未来的范围」） |
| `scope_snapshot` 必须存 `chunkVersions` | 文档重切后 `chunk_version` 会变，历史引用要能指回**当时**那一版 |

### 2.4 越权与租户

| 规则 | 说明 |
| --- | --- |
| 会话属于「租户 + 用户」 | `conversation.tenant_id` + `owner_user_id`；别人的会话一律 **404**（不是 403） |
| 长期记忆按 `tenant_id` + `subject_id` 过滤 | 相似度分数**不是**权限边界；`status='ACTIVE'` 与 `expires_at` 在 SQL 里过滤，不靠模型 |
| 记忆检索也要过 ACL？ | 不需要：LTM 的可见性由 `subject_id`（就是用户自己）决定，与文档 ACL 无关；但 `source_ref` 指向的文档**不因此变得可读** |
| 会话删除是逻辑删除 | `status` 置 `DELETED`，消息保留 30 天后再物理清理（设计 §14.3）；本阶段只做逻辑删除 |

### 2.5 Token 预算配额（设计 §11.9，全部可配）

| 上下文来源 | 配额 | 裁剪顺序 |
| --- | --- | --- |
| System / 安全规则 | 固定 | 不裁剪 |
| Current User Message | 固定 | 不裁剪 |
| RAG Evidence | ≤ 40% | 1（按 Rerank 分数由低到高） |
| Short-term Memory | ≤ 15%（最近 6 轮） | 2（从最旧开始整条丢弃） |
| Conversation Summary | ≤ 10% | 3 |
| Structured Context | ≤ 5% | 4 |
| Long-term Memory | ≤ 5%（最多 3 条） | 5（最后裁剪） |

**任何情况下不截断单条字符串，只整条丢弃** —— 截断会把一句话切成半句，模型会自行补全，这是幻觉的常见来源。

**这张表只约束「业务内容」，不约束「规则」**（易混点，2026-10-27 澄清）：

| 类别 | 例子 | 放哪 | 受不受配额约束 |
| --- | --- | --- | --- |
| 系统规则 | 引用格式、拒答规则、安全边界 | System Prompt（代码常量） | **不受**，永不裁剪 |
| 用户指令（偏好 / 硬约束） | 「先给结论」「金额用亿元」「必须带页码」 | `long_term_memory.memory_type='INSTRUCTION'` → 「指令」段 | 独立上限（默认 600 token），超了**合并压缩**，不丢弃 |
| 会话状态 | companyId、yearRange、documentIds、currentMetric | `conversation_context` | 受 5%（实际 4 个标量共约 20~40 token，占 0.3~0.5%） |
| 情境记忆 | 「长期关注东方财富」「在跟踪现金流」 | `long_term_memory.memory_type='CONTEXTUAL'` | 受 5%，最多 3 条 |
### 2.6 每会话有几行（行基数）

| 表 / 对象 | 每个会话的行数 | 写入方式 | 会不会留历史 |
| --- | --- | --- | --- |
| `conversation` | 1 | 建会话时插入 | 否（`status` 逻辑删除） |
| `conversation_message` | 多行（每轮 USER + ASSISTANT 各 1 条） | 追加 | 是（完整历史，永不覆盖） |
| `conversation_summary` | **1**（PK = `conversation_id`） | 覆盖写，`version` +1 | 否（只留当前版） |
| `conversation_context` | **1**（PK = `conversation_id`） | `ON CONFLICT DO UPDATE` | 否（只留当前值） |
| `long_term_memory` | 0~N（按 `memory_key`） | 新 key 插入；同 key 冲突 → 旧行 `SUPERSEDED` + 新行 | 是（`SUPERSEDED` 行保留） |
| `memory_event` | 0~N | 追加 | 是（审计，不可改） |

**为什么摘要与结构化上下文只有一行**：它们是「当前状态」，不是「事件」。要问「第 5 轮时摘要长什么样」，
真相源不是摘要表，而是 `conversation_message` —— 摘要可以从消息**重算**（同一套 Prompt + 同一段消息 → 同一份摘要），
所以存历史行只会存一份可重算的副本。真正不可重算的是**变更动作**（谁在什么时候改了结构化上下文），那部分记进 `memory_event`。

对比：`long_term_memory` 必须保留 `SUPERSEDED` 行，因为「用户三月份说过什么、五月份改成了什么」不可重算 —— 那是用户说过的话本身。

### 2.7 四层记忆是怎么拼进 Prompt 的（不是全拼在一起）

**结论：分层渲染、各层独立配额、按规则裁剪、Gate 没过就整层不出现。** 拼接规则固定写进 C12，不允许各任务自行决定。

**① 渲染顺序（固定）**

~~~text
[System]        角色、引用规则、拒答规则（不受配额限制）
[背景信息 · 不可引用]
  ① 会话结构化上下文   企业/年份/关注指标（确定性字段，最短）
  ② 较早对话摘要       已确认结论、未完成问题
  ③ 最近对话窗口       最近 6 轮原话，从旧到新
  ④ 用户长期偏好       Gate 通过才有，最多 3 条，每条带写入时间
[RAG 证据]      [E1]…[En]，唯一可引用来源
[当前问题]      用户这句话
~~~

顺序理由：**从最抽象到最具体，证据紧贴问题**（模型对紧邻问题的内容注意力最高）；记忆段整体在证据之前，
并与证据之间用显式禁令句隔开，避免模型把记忆当证据续写。

**② 每层独立配额与裁剪方式**

| 层 | 配额（默认） | 超了怎么处理 | 配置项 |
| --- | --- | --- | --- |
| 结构化上下文 | 5% | 只保留四类字段，不写自由文本（实际上永远用不满） | `structured-ratio` |
| 摘要 | 10% | 摘要本身限 200 字，超了重新压缩（不是截断） | `summary-ratio` |
| 短期窗口 | 15% | **从最旧开始整条丢弃**，直到不超；不截半句 | `short-term-ratio` / `short-term-rounds` |
| 长期记忆 | 5%，最多 3 条 | 按 `importance` 从低到高整条丢 | `ltm-ratio` / `ltm-max-items` |
| RAG 证据 | 40% | 按 Rerank 分数从低到高整块丢 | `evidence-ratio` |

**③ 全局兜底**：各层都不超配额、但总和超过模型窗口时，按「影响最小先裁」的顺序继续丢：
证据（低分先丢）→ 短期窗口（最旧先丢）→ 摘要 → 结构化上下文 → 长期记忆（最后，因为它通常只有一两行且是用户显式要求）。
这个顺序与设计 §11.9 的裁剪顺序一致。

**④ 三条渲染硬规则**

1. **空层整段省略**：没有摘要就不输出「较早对话摘要：（无）」，否则模型会去解读「无」；
2. **每条记忆带来源与时间**：`（2026-10-03 用户显式要求）`——模型需要时间线索判断偏好是否还适用；
3. **禁令句固定不变**：`以上仅为对话背景，不能作为答案依据；答案与数字必须来自下方证据。` 这句话不参与裁剪（它在 System 里）。

**⑤ 两个例子**

~~~text
例 1：第二轮追问（Gate 未命中 LTM，第 ④ 段整段不出现）
[背景信息 · 不可引用]
  ① 企业=东方财富(300059)，年份=2025
  ③ 最近对话：
     用户：东方财富2025年营业收入是多少？
     助手：2025 年营业总收入 3,485,587,013.94 元 [E1]

例 2：新会话问「还是按以前的格式写」（Gate 命中）
[背景信息 · 不可引用]
  ④ 用户长期偏好：
     - 回答先给结论，再给证据（2026-10-03 用户显式要求）
     - 金额统一用「亿元」并保留两位小数（2026-10-05 用户显式要求）
~~~

**⑥ 为什么不在 `ContextBuilder` 里做**：`ContextBuilder` 的职责是「文档证据怎么选、怎么展」，
而记忆的裁剪规则依赖会话状态（窗口、摘要、Gate），属于 `MemoryContext` 的职责。两者只在 `AnswerPrompts` 汇合，
好处是记忆逻辑可以脱离数据库单测（`MemoryPromptRenderer` 是纯函数），也不必改动阶段 5 已验收的装配逻辑。

**⑦ 指令类记忆与情境类记忆必须分开（2026-10-27 修订）**

原设计 §11.9 把长期记忆统一按 5% 配额、最后裁剪。这个口径对「关注对象」这类情境记忆没问题，
但对「以后回答先给结论」这类**用户显式指令**是错的：规则被裁掉 = 系统违反了用户指令，而且用户不会收到任何提示。
因此本阶段把长期记忆拆成两类语义，走两套预算：

| | INSTRUCTION（指令类） | CONTEXTUAL（情境类） |
| --- | --- | --- |
| 典型内容 | 输出格式、单位、语言、必须/禁止事项 | 关注的公司、跟踪的指标、历史偏好话题 |
| 来源 | 显式命令（「以后…都…」「记住：…」）、`POST /api/memories` | 同一 API，`memory_type` 不同 |
| 注入位置 | 「[用户指令]」段，紧跟 System | 「背景信息」④ 段 |
| 预算 | 独立上限 600 token（`instruction-max-tokens`），**不参与 40/15/10/5 的分配** | 5%，最多 3 条 |
| 超预算怎么办 | **合并压缩**（多条偏好合成一条，保留全部约束词），压到上限内；压不下才标记 `instruction_overflow` 警告 | 从 `importance` 低的整条丢弃 |
| 冲突 | 同一 key 新值覆盖旧值（版本化） | 同左 |

**为什么指令可以「不丢弃」**：指令类记忆的行数是**个位数到十几条**（一个用户不会写 500 条格式要求），
合并压缩后通常 100~300 token，稳定放得下。真正会无限增长的是情境记忆与对话历史，那些才需要配额。

**外部参照（同类系统的通用做法，不是本项目的实现要求）**：

| 系统 | 规则放哪 | 上下文不足时怎么处理规则 |
| --- | --- | --- |
| Claude Code | 系统提示 + 从磁盘读 `CLAUDE.md`（含子目录层级） | 压缩的是**对话**，不是指令；压缩后重新从磁盘读取指令文件，规则因此不会「聊着聊着就没了」 |
| Codex / AGENTS.md 系 | `AGENTS.md` 分层合并（仓库级 + 目录级） | 指令与对话分离；指令超长时告警而不是静默丢弃 |
| 本项目的压缩机制（billion-context） | 系统提示常驻 + 摘要保留用户意图与约束 | 压缩的是已消费的对话与工具输出，用户请求与约束是「受保护内容」 |
| **本项目阶段 9** | System（代码常量）+ 「[用户指令]」段（来自 DB） | 规则段不参与业务配额；超限只合并压缩，不静默丢弃 |

共同点：**规则有窗口之外的真相源**（磁盘文件 / 数据库），所以「窗口里没有」只意味着「这一轮没渲染」，
不意味着「规则丢了」；下一轮可以重新注入。反之，若把规则只存在对话历史里，一旦历史被压缩或裁剪，规则真的会消失 —— 这才是要避免的设计。

**落到代码上的三条**：

1. `long_term_memory.memory_type` 取值固定为 `INSTRUCTION` / `CONTEXTUAL` / `WATCHLIST`（WATCHLIST 归 CONTEXTUAL 预算）；
2. `MemoryPromptRenderer` 先渲染指令段（不裁剪），再渲染背景段（按配额）；
3. 指令段若仍超 600 token，**不丢内容**：输出 `instruction_overflow` 警告进 trace，并把超出的条目记 `last_overflow_at`，便于后续人工合并。

## 3. 文件结构

### 3.1 新增

| 文件 | 职责 |
| --- | --- |
| `src/main/resources/db/migration/V10__conversation_and_memory.sql` | 六张表 + RLS + 索引 + 注释 + demo 种子 |
| `src/main/java/com/finagent/memory/MemoryProperties.java` | `finagent.memory.*` 配置：`short-term-rounds=6`、`short-term-ratio=0.15`、`summary-ratio=0.10`、`structured-ratio=0.05`、`ltm-ratio=0.05`、`ltm-max-items=3`、`evidence-ratio=0.40`、`context-window-tokens=8000`、`summary-trigger-messages=12`、`gate-enabled=true`、`gate-min-similarity=0.6` |
| `src/main/java/com/finagent/memory/Conversation.java` | record：id / tenantId / ownerUserId / title / sessionScope / status / createdAt / updatedAt |
| `src/main/java/com/finagent/memory/ConversationRepository.java` | 会话与消息的读写（JdbcTemplate，风格同 `DocumentRepository`） |
| `src/main/java/com/finagent/memory/ConversationService.java` | 建会话、校验归属、发消息前的上下文装配入口 |
| `src/main/java/com/finagent/memory/ConversationMessage.java` | record：id / conversationId / role / content / citationsJson / scopeSnapshot / traceId / createdAt |
| `src/main/java/com/finagent/memory/ConversationContext.java` | record：companyId / yearFrom / yearTo / documentIds / currentMetric / json |
| `src/main/java/com/finagent/memory/StructuredContextService.java` | 抽取、合并、落库（新值优先；模型推断只在空值时写入） |
| `src/main/java/com/finagent/memory/ShortTermMemory.java` | 取最近 N 轮；按预算整条丢弃 |
| `src/main/java/com/finagent/memory/ConversationSummarizer.java` | 摘要生成与增量合并（`summary_until_message_id` 前移） |
| `src/main/java/com/finagent/memory/LongTermMemoryRepository.java` | LTM 读写、冲突检测、版本化、过期过滤 |
| `src/main/java/com/finagent/memory/LongTermMemory.java` | record：id / tenantId / subjectType / subjectId / memoryType / memoryKey / content / valueJson / status / version / expiresAt |
| `src/main/java/com/finagent/memory/MemoryGate.java` | 规则词典 + 复用 QU 的 `needLongTermMemory`；返回「是否召回 + 召回理由」 |
| `src/main/java/com/finagent/memory/LongTermMemoryRetriever.java` | Metadata 过滤 + 向量检索 + 阈值 + 去重（复用 `EmbeddingService`） |
| `src/main/java/com/finagent/memory/MemoryContext.java` | 装配结果：summary + structured + memories（各自 token 数），供 Prompt 渲染 |
| `src/main/java/com/finagent/memory/MemoryPromptRenderer.java` | 把 `MemoryContext` 渲染成「背景信息（不可引用）」段落 |
| `src/main/java/com/finagent/chat/ConversationController.java` | `POST /api/conversations`、`GET /api/conversations/{id}/messages`、`PATCH /api/conversations/{id}/context` |
| `src/main/java/com/finagent/memory/MemoryController.java` | `GET / POST / DELETE /api/memories` |

### 3.2 修改

| 文件 | 改什么 | 为什么 |
| --- | --- | --- |
| `src/main/java/com/finagent/chat/ChatRequest.java` | 加 `conversationId`（可空） | 不传时行为与阶段 8 完全一致（向后兼容，343 个测试不用改） |
| `src/main/java/com/finagent/chat/ChatResponse.java` | 加 `conversationId`、`messageId` | 客户端下一轮要带上会话 id |
| `src/main/java/com/finagent/chat/ChatService.java` | 有 `conversationId` 时：加载上下文 → QU(带历史) → 检索 → 上下文 → 答案 → 落消息/摘要/上下文 | 编排层是唯一知道全流程的地方 |
| `src/main/java/com/finagent/understanding/RouterService.java` | 重载 `understand(String query, ConversationContext ctx, List<Turn> recent)` | 指代消解要进来，但**不能**让 `understand(String)` 的语义变化（既有 30 处调用点、9 个测试依赖它） |
| `src/main/java/com/finagent/understanding/LlmRouter.java` | Prompt 里加「结构化上下文 + 最近几轮」与 `needLongTermMemory` 字段 | 设计 §11.11 明确要求复用同一次调用，避免额外往返 |
| `src/main/java/com/finagent/answer/AnswerPrompts.java` | `user(question, pkg)` 重载为 `user(question, memory, pkg)` | 记忆段落要出现在证据之前，且明确标注不可引用 |
| `src/main/java/com/finagent/answer/AnswerService.java` | 透传 `MemoryContext` | 不改三层校验逻辑（记忆不进引用编号） |
| `src/main/resources/application.yml` | 加 `finagent.memory` 配置块 | 窗口/阈值/配额全部可配 |

## 4. 任务分解

### Task 1：V10 迁移（六张表）

**目标**：一次把会话与记忆的表结构、约束、RLS、注释建好，后续任务只写代码不动 schema。

**表结构（要点，非完整 DDL）**

| 表 | 关键列 | 约束 / 索引 |
| --- | --- | --- |
| `conversation` | `id`、`tenant_id`、`owner_user_id`、`title`、`scope_kind`、`scope_json`、`status`、`created_at`、`updated_at` | `chk_conversation_status`（ACTIVE/DELETED）；`idx_conversation_owner(tenant_id, owner_user_id, updated_at DESC)` |
| `conversation_message` | `id`、`conversation_id`、`role`、`content`、`citations_json`、`scope_snapshot`（jsonb）、`trace_id`、`token_count`、`created_at` | FK `ON DELETE CASCADE`；`chk_message_role`（USER/ASSISTANT/TOOL）；`idx_message_conversation(conversation_id, id)` |
| `conversation_summary` | `conversation_id`（PK）、`summary`、`summary_until_message_id`、`version`、`updated_at` | `version` 每次重写 +1（审计要能看出「摘要变过几次」） |
| `conversation_context` | `conversation_id`（PK）、`company_id`、`year_from`、`year_to`、`document_ids`（bigint[]）、`current_metric`、`context_json`、`updated_at` | 一行一会话（设计 §11.10：结构化的东西不该是键值对表） |
| `long_term_memory` | `id`、`tenant_id`、`subject_type`、`subject_id`、`memory_type`、`memory_key`、`content`、`value_json`、`embedding vector(1024)`、`embedding_model`、`embedding_version`、`source_type`、`source_ref`、`confidence`、`importance`、`status`、`version`、`expires_at`、`last_accessed_at`、`retrieval_count`、`created_at`、`updated_at` | `uq_memory_active(tenant_id, subject_id, memory_type, memory_key) WHERE status='ACTIVE'`（部分唯一索引：同一 key 只能有一条 ACTIVE）；`idx_memory_lookup(tenant_id, subject_id, status, memory_type)`；HNSW 向量索引 `vector_cosine_ops` |
| `memory_event` | `id`、`memory_id`（可空）、`target_type`（MEMORY / CONVERSATION_CONTEXT / CONVERSATION_SUMMARY）、`target_id`、`action`、`old_value`、`new_value`、`reason`、`trace_id`、`created_at` | 追加型，不更新不删除（审计）。`memory_id` 可空是为了同一张表也能记「会话上下文被改」——否则摘要/上下文的变更就无处落账 |

**必须注意的三件事**

1. **`tenant_id` 全部 NOT NULL**，并各建一条 RLS 策略（照 V8 的 `p_<table>_tenant` 写法）；`conversation_message` / `conversation_summary` / `conversation_context` 也要冗余一列 `tenant_id` —— 
   否则子表无策略，只能靠父表 join 才能过滤，而 RLS 不会自动帮你 join（V8 的教训：`document_chunk.tenant_id` 就是冗余的）。
2. **部分唯一索引的写法**：`CREATE UNIQUE INDEX ... WHERE status = 'ACTIVE'` 是索引不是约束，`ON CONFLICT` 要写 `ON CONFLICT (tenant_id, subject_id, memory_type, memory_key) WHERE status = 'ACTIVE'`。
3. **`long_term_memory.embedding` 是普通列**：与 `document_chunk.embedding` 同一条真相 —— 数据库不会帮你调 embedding API（见 `docs/pgvector-工作机制.md` 附录）。

**验收**：`mvn test` 全绿（V10 在 context 启动时执行）；`flyway_schema_history` 到 V10；`\d long_term_memory` 能看到 HNSW 索引与部分唯一索引；RLS 探针：`SET ROLE finagent_app` 后未设 `app.tenant_id` 查 `conversation` = 0 行。

### Task 2：会话存储与 REST（含真实问答落库）

**目标**：能建会话、能带 `conversationId` 提问、消息与 trace 落库、别人拿不到。

**接口**

| 方法 | 路径 | 语义 |
| --- | --- | --- |
| POST | `/api/conversations` | 建会话（可带 `title` / `scope`），返回 `conversationId` + 首条欢迎语不落库 |
| GET | `/api/conversations/{id}/messages?limit=&beforeId=` | 分页读历史（倒序游标） |
| POST | `/api/chat`（改造） | 带 `conversationId` 时写 USER + ASSISTANT 两条消息 |
| GET | `/api/conversations` | 当前用户的会话列表（按 `updated_at` 倒序） |

**实现要点**

- `ConversationService.requireOwned(long id)`：先查 `tenant_id` + `owner_user_id`，不匹配抛 `ConversationNotFoundException` → 控制器映射 **404**（与阶段 8 的跨租户 404 同一语义：不确认存在性）。
- 归属校验**不依赖 RLS**：RLS 只管租户，同一租户内两个用户都能看到彼此的会话，必须应用层判 `owner_user_id`。
- 写消息用同一个 `@Transactional`：USER 消息 + ASSISTANT 消息 + `conversation.updated_at` 一起写，避免「回答成功但用户消息丢了」。
- `scope_snapshot` 写 `{session, effective, chunkVersions, resolverVersion}`（`docs/文档作用域设计.md` §6.3 的结构），`chunkVersions` 从 `ResolvedScope.documents()` 的 `activeChunkVersion` 取。

**验收（集成测试，真库）**：

1. 建会话 → 提问 → `conversation_message` 恰好 2 行（USER/ASSISTANT），`citations_json` 非空；
2. 另一个用户（同租户）读该会话 → 404；
3. 不传 `conversationId` 提问 → 不写任何会话表（回归：行为与阶段 8 一致）；
4. `scope_snapshot` 里 `chunkVersions` 与文档当前 `active_chunk_version` 一致。

### Task 3：短期记忆窗口 + Prompt 注入

**目标**：把最近 N 轮对话放进 Prompt，且**不污染引用编号**。

**关键决策（写进 C12）**：记忆不进 `ContextPackage`，而是作为独立段落经 `AnswerPrompts` 注入 ——
`ContextPackage.blocks()` 只放 RAG 证据，`[E1..En]` 编号只由它产生。这样 `CitationValidator` 一行都不用改，
「记忆被当成证据引用」在结构上不可能发生。

**步骤**

1. `ShortTermMemory.load(conversationId, rounds)`：取最近 `2 × rounds` 条消息（USER+ASSISTANT 成对），按 `id` 升序；
2. 预算控制：从最旧开始整条丢，直到 ≤ `finagent.memory.short-term-ratio × 模型窗口`（默认 15%）；
3. `MemoryPromptRenderer` 渲染：
   ~~~text
   【背景信息 · 不可引用】
   - 最近对话（从旧到新）：
     用户：那净利润呢？
     助手：2025 年净利润 96.1 亿元 [E2]
   - 会话结构化上下文：company=300059（东方财富），year=2025
   - 用户长期偏好：回答先给结论再给证据
   注意：以上仅为对话背景，不能作为答案依据；答案与数字必须来自下方证据。
   ~~~
4. `AnswerPrompts.user(question, memory, pkg)` 把该段落放在证据**之前**、System **之后**；
5. `AnswerService.answer(question, qu, memory, pkg)` 只做透传（三层校验不变）。

**验收**：单测（渲染顺序、预算裁剪、空记忆不产生空段落）+ 集成（两轮对话里第 2 轮 Prompt 含第 1 轮内容，且 `citations` 里没有记忆条目）。

### Task 4：Structured Context（抽取 → 合并 → 落库 → 纠错）

**目标**：让「那净利润呢」知道上一轮问的是哪家公司、哪一年；并允许用户显式改。

**规则（设计 §11.10）**

| 时机 | 行为 |
| --- | --- |
| 检索前 | QU 抽出的 companyIds/yearRange/metrics 与 `conversation_context` 现有值合并：**新值优先**；yearRange 缺省时继承上一轮 |
| 写入时机 | 本轮回答成功返回后写库（不依赖模型），附 `sourceMessageId` |
| 冲突 | 用户显式给新公司/年份 → 直接覆盖；模型推断值只在现有为空时写入，否则记候选不生效 |
| 单轮可见性 | 抽取结果先以内存态用于本轮检索，落库在回答之后（避免写库失败导致本轮不可用） |
| 纠错 | `PATCH /api/conversations/{id}/context`，每次变更写 `memory_event` |

**实现要点**：`document_ids` 用 `bigint[]`；写入用 `ON CONFLICT (conversation_id) DO UPDATE`；`context_json` 存完整快照便于回放。

**验收**：单测（合并优先级 4 例：新值覆盖旧值 / 缺省继承 / 模型推断不覆盖显式值 / 清空语义）+ 集成（PATCH 后下一轮检索真的用了新公司）。
### Task 5：指代消解（多轮 QU）

**目标**：把「那净利润呢」「它现在的现金流怎么样」「比去年增长多少」补全成可独立检索的查询。

**改造点**

| 位置 | 现在 | 改成 |
| --- | --- | --- |
| `RouterService.understand(String)` | 单轮 | 保留不变（既有 30 处调用点与 9 个测试不动）；新增 `understand(String query, ConversationContext ctx, List<Turn> recent)` |
| `LlmRouter.route(String)` | 只喂问题 | 新增 `route(String query, String contextBlock)`；`contextBlock` 里放结构化上下文 + 最近 3 轮（每轮截到 200 字，避免 Prompt 膨胀） |
| `RuleBasedRouter` | 抽实体 | **不改**：规则只认显式实体，不做消解（规则做消解必然猜错，且不可解释） |

**Prompt 约束（关键）**

1. 输出新增 `normalizedQuery`：必须「脱离上文也能检索」（含公司名/代码 + 年份 + 指标）；
2. 若上下文不足以补全，就**原样返回**并置 `resolved=false`，不要编造公司或年份 —— 编造的年份会变成一个真实的过滤条件，把正确答案过滤掉；
3. `companyIds` / `yearRange` 若来自上文补全，标记 `inferredFromContext=true`（trace 要能区分「用户说的」与「模型推的」）。

**降级**：消解失败（LLM 超时/解析失败）→ 用 `StructuredContext` 做**确定性补全**（只补 company/year，不补指标），并标 `degraded=true`。比「什么都不补」好，比「让模型自由发挥」安全。

**验收（e2e，真模型）**：4 个多轮场景，要求 ≥ 3 个答对且 `citations` 指向正确文档：

| 轮次 | 用户原话 | 期望补全 | 判据 |
| --- | --- | --- | --- |
| 1→2 | 「东方财富2025年营业收入是多少」→「那净利润呢？」 | 300059 / 2025 / 净利润 | 答案数字与 golden set 一致 |
| 1→2 | 「…营业收入…」→「比去年增长多少？」 | 300059 / 2024~2025 / 营业收入 | 出现两个年度数字 |
| 1→2 | 「…营业收入…」→「它现在的现金流怎么样？」 | 300059 / 2025 / 现金流 | 引用 document 7 |
| 1→2 | 「…营业收入…」→「那毛利率呢？」 | 300059 / 2025 / 毛利率 | 答案或明确「证据不足」 |

### Task 6：Conversation Summary

**目标**：会话变长后不把 100 条消息全塞进 Prompt。

**触发策略**：`未汇总消息数 > finagent.memory.summary-trigger-messages`（默认 12）→ 取「`summary_until_message_id` 之后」的最旧一半消息，与旧摘要一起交给 LLM，生成新摘要；`summary_until_message_id` 前移到这一半的最后一条。

**摘要 Prompt 要求**：保留 ① 已确认结论（带数字与年份）② 未完成的问题 ③ 用户已声明的偏好；丢弃寒暄与重复。输出 ≤ 200 字。

**三条规则**

1. **摘要失败不影响回答**：生成失败只记 warning（`summary_failed`），下一轮重试；
2. **只前移指针，不删消息**：完整历史永远在 `conversation_message`（审计/回放要用）；
3. **摘要不产生引用**：摘要里的数字不带 `[E#]`，渲染时也标「不可引用」。

**验收**：单测（触发阈值、增量合并、失败降级 3 例）+ 集成（15 轮对话后 `summary_until_message_id` 前移且消息数不减）。

### Task 7：Long-term Memory 写入（显式命令 + 显式 API）

**目标**：用户说「以后回答先给结论」能被记住，下次开新会话仍然生效；并且记得住「什么时候写的、为什么写」。

**两类高置信来源（本阶段只做这两类）**

| 来源 | 例子 | 处理 |
| --- | --- | --- |
| 显式命令（规则匹配） | 「以后…都…」「记住：…」「以后不要再…」 | 规则命中 → 写 `source_type='EXPLICIT_COMMAND'`，`confidence=1.0` |
| 显式 API | `POST /api/memories {type, key, content}` | 直接写 `source_type='USER_API'` |

**写入流水线（设计 §11.3）**：`Candidate → classify → validate → conflictCheck → upsert/version → auditEvent`。本阶段 `classify` 用规则表（不改模型）：

| memoryType | 例子 key | 说明 |
| --- | --- | --- |
| `PREFERENCE` | `answer.style`、`answer.language` | 输出偏好 |
| `WATCHLIST` | `focus.company.300059` | 长期关注对象 |
| `CONSTRAINT` | `analysis.must_cite` | 长期约束 |

**冲突处理（设计 §11.5）**：同 `(tenant, subject, type, key)` 已有 ACTIVE 且内容不同 → 旧行 `status='SUPERSEDED'`、新行 `version = old.version + 1`，`memory_event` 记 `action='SUPERSEDE'` 与新旧值。

**验收**：单测（命令识别 6 例、冲突版本化、过期不召回）+ 集成（写 → 新会话召回 → 删除后不再召回）。

### Task 8：Retrieval Gate + LTM 召回 + 注入

**目标**：该召回时召回，不该召回时一次外部调用都不多花。

**Gate 判定（设计 §11.11：不引入独立分类器）**

| 来源 | 规则 |
| --- | --- |
| 规则快路径 | 命中词典（「以后 / 以前 / 照旧 / 还是按 / 格式 / 偏好 / 习惯 / 记住」）→ true |
| LLM 字段 | 复用 Task 5 那次 QU 调用的 `needLongTermMemory` |
| 默认 | false（简单事实问答、确定性计算、上下文充分的追问一律跳过） |

**召回步骤（设计 §11.4 的 6 步）**

1. 已知 `memoryKey` → 结构化精确读取（不走向量）；
2. `WHERE tenant_id = ? AND subject_id = ? AND status='ACTIVE' AND (expires_at IS NULL OR expires_at > now()) AND memory_type = ANY(?)`；
3. 向量检索 `ORDER BY embedding <=> ?::vector LIMIT 8`（embedding 复用 `EmbeddingService`，1024 维，与文档同一模型）；
4. 阈值（默认 0.6）+ 按 `memory_key` 去重；
5. 取前 3 条，按 `importance` 排序后进 `MemoryContext`；
6. 更新 `last_accessed_at` / `retrieval_count`（写操作放最后，失败不影响回答）。

**验收**：单测（Gate 8 例：词典命中 / 默认跳过 / 上下文充分跳过 / 过期过滤 / 阈值过滤 / 去重 / 最多 3 条 / 组织级记忆不启用）+ 集成（写入后新会话能召回；不相关的问题不召回且**零** embedding 调用）。

### Task 9：契约 + 文档 + 报告 + e2e 收尾

| 产出 | 内容 |
| --- | --- |
| `docs/关键契约.md` C12 | 记忆分层真相源、三条硬规则、会话与作用域、越权、Token 配额 |
| `docs/术语表.md` §10 | 新增「会话与记忆」小节（chat history / short-term / summary / structured context / LTM / Gate / 指代消解 / 版本化记忆），原 §10/§11 顺延 |
| `README.md` | 阶段 9 一节：三张 curl（建会话 / 第二轮追问 / 写一条偏好）与开关表 |
| `docs/阶段9-Memory与会话报告.md` | 骨架同阶段 7/8：怎么读 8 个词 → 一句话结论 → 为什么做 → 交付物 → 契约 → 实测 → 踩坑 → 已知限制 → 验收对照 → 下一步 |
| `docs/superpowers/plans/2026-10-05-demo-roadmap.md` | 阶段 9 标完成 + 计划索引 |

## 5. 验收口径（机器可判）

| # | 项 | 门槛 |
| --- | --- | --- |
| 1 | 无 `conversationId` 的问答 | 与阶段 8 完全一致（343 个既有用例全部仍绿，零 schema 依赖） |
| 2 | 多轮指代消解 | 4 个场景 ≥ 3 个答对（Task 5 表） |
| 3 | 短期记忆注入 | 第 2 轮 Prompt 含第 1 轮内容；`citations` 中无记忆条目（0 例） |
| 4 | 摘要 | 15 轮后 `summary_until_message_id` 前移；消息总数不减 |
| 5 | Structured Context | 新公司覆盖旧公司；模型推断不覆盖显式值 |
| 6 | LTM Gate | 不相关问题的 embedding 调用次数 = 0（用计数型假模型断言） |
| 7 | LTM 冲突 | 同 key 两次写入 → 1 条 ACTIVE + 1 条 SUPERSEDED，version=2 |
| 8 | 过期记忆 | `expires_at` 已过 → 召回 0 条 |
| 9 | 越权 | 他人会话 404；他人记忆不可见（查询结果 0 条，非 403） |
| 10 | Token 预算 | 各来源不超配额，且只整条丢弃（不出现半截字符串） |

## 6. 测试计划

| 类别 | 预计 | 说明 |
| --- | --- | --- |
| 单测 | ~35 | Gate 8、合并 4、摘要 3、冲突 3、记忆渲染 4、预算裁剪 3、命令识别 6、会话归属 4 |
| 集成（真库） | ~15 | 会话读写、消息成对、404、上下文 PATCH、LTM 写入/召回/过期、摘要前移 |
| e2e（真模型，`@Tag("e2e")`） | ~6 | 4 个多轮消解 + 1 个偏好跨会话 + 1 个「记忆不得覆盖事实」负样本 |
| 回归 | 343 | 既有用例一个都不许挂 |

跑法（PowerShell，与阶段 7/8 一致）：

~~~powershell
. .\scripts\load-env.ps1
mvn -B test                                   # 非 e2e
mvn -B test "-Dsurefire.excludedGroups=" "-Dgroups=e2e" "-Dtest=ConversationE2ETest"
~~~

## 7. 偏差登记

| # | 计划原文 | 实际做法 | 原因 | 日期 |
| --- | --- | --- | --- | --- |
| 1 | Task 2 计划只列 POST/GET/GET messages 三个端点 | 多加了 `GET /api/conversations/{id}`（单会话详情）与 `DELETE /api/conversations/{id}`（逻辑删除，D8 的落地出口） | 没有删除端点 D8 就只是一句口号；详情端点让前端刷新单个会话时不用拉全表 | 2026-10-27 |
| 2 | `ChatRequest` 加 `conversationId`、`ChatResponse` 加 `conversationId` + `messageId` | 两个 record 各留一个「旧参数个数」的兼容构造器，11 处 `new ChatRequest(...)` 与 3 处 `new ChatResponse(...)` 调用点不动 | 这是纯加法字段；逐个改调用点只会制造 diff 噪音，出错机会反而更大 | 2026-10-27 |
| 3 | `scope_json` 直接序列化 `ChatScope` | 改成 `ScopeSnapshots.scopeJson` 手写四个字段（kind / documentIds / query{companyCodes,years,docTypes,industries}） | `ScopeQuery.isEmpty()` 是布尔 getter，Jackson 会多吐一个 `"empty": true` 进 jsonb；手写字段表没有这条噪音，读回来也不依赖未知字段容忍 | 2026-10-27 |
| 4 | 计划未定义「落库失败怎么办」 | `ChatService.persist` 捕获 RuntimeException → 记 error 日志 + 响应加 `conversation_persist_failed` 警告，答案照常返回 | 答案与 trace 都已产生，为一条会话记录让用户看到 500 更差；前端凭警告可提示「这轮不会进历史」 | 2026-10-27 |
| 5 | 计划未定义会话标题来源 | 新建时未给 title 则用 `新会话` 占位；第一轮问答后自动改成问题前 40 字（`ConversationService.titleFrom`）；用户显式给的标题永不被覆盖 | 会话列表里全是「新会话」等于没有列表；用首个问题当标题是聊天产品的通行做法 | 2026-10-27 |
| 6 | Task 1 验收只要求迁移成功 | 额外验证了部分唯一索引语义（重复 ACTIVE 报 23505、同 key 插 SUPERSEDED 成功）与 RLS 三条探针（未设变量 0 行 / tenant=1 为 1 行 / tenant=2 为 0 行） | 「建了索引/策略」不等于「按预期生效」；这两条是 Task 7/8 唯一性依赖的前提 | 2026-10-27 |
| 7 | `AnswerService.answer(question, qu, memory, pkg)` | 实际签名 `answer(question, qu, pkg, memory)`，并保留 3 参重载（pkg 在前表示「必需参数在前、可空参数在后」） | pkg 永远非空、memory 可空；把可空参数放最后，调用点不用思考顺序 | 2026-10-27 |
| 8 | Task 3 步骤 3 的渲染模板把背景写在【证据】标题之后 | 把 `AnswerPrompts.USER_TEMPLATE` 改成三段式（问题 / 背景 / 证据），背景为空时整段消失，不留「（无）」占位 | 背景若落在【证据】标题之后就变成「证据的一部分」，模型更容易把它当证据引用；占位则白占 token | 2026-10-27 |
| 9 | 计划未写记忆的全局开关 | `MemoryProperties.enabled` + `application-test.yml` 默认 `false`，记忆相关测试用 `@SpringBootTest(properties="finagent.memory.enabled=true")` 显式打开 | 关掉默认值才能保证「300+ 既有用例的 Prompt 与阶段 8 完全一致」这条回归门限；否则每加一层记忆都要重跑评测 | 2026-10-27 |
| 10 | Task 3 步骤 2「从最旧开始整条丢」 | 实现为<b>按轮成对丢</b>，且最旧一条是 ASSISTANT（配对断了）时先丢它 | 只丢单条会留下「没有对应问题的助手回答」，模型容易把上一轮答案当事实继续推理；成对丢只损失一轮，语义干净 | 2026-10-27 |
| 11 | 计划只在 Task 8/9 提「记忆进 trace」 | 现在就引入 `MemoryContext.Stats`（shortTermMessages / shortTermDropped / summaryChars / longTermItems / gateHit），trace 字段在 Task 8 接 | 统计随手可算，等 trace 要用时再回头改渲染器成本更高 | 2026-10-27 |
| 12 | 计划 Task 4 未定义「PATCH 请求体字段能不能省」 | `ContextPatch` 的 `clear` 用 `Boolean`（缺省即 false）、`documentIds` 归一成空列表、空白 `currentMetric` 归一成 null；服务端判 `request.cleared()` | 用原始 `boolean` 时 Jackson 对「creator 属性缺失」直接 400 —— 一个空 body 就变成错误；归一化把「没传」与「传了空」合成同一种语义，下游不用到处判 null | 2026-10-27 |
| 13 | 计划写「本轮的实体按显式处理」 | 公司 id 只**在作用域文档里出现过**才映射（`ConversationContextService.deltaOf` 拿 QU 的公司代码去 scope.documents 反查 companyId） | 用户随口提到的公司不该变成会话上下文，否则下一轮「那净利润呢」会被带偏；这条与阶段 8「作用域是唯一真相」一致 | 2026-10-27 |
| 14 | 计划未定义「值没变要不要写」 | `unchanged(existing, merged)` 为真时既不 upsert 也不写 `memory_event`，直接返回旧值 | 同一句话问两遍不该产生两条「上下文已更新」审计；审计表的意义是「真的变了什么」 | 2026-10-27 |
| 15 | 计划未定义 CORPUS 作用域要不要记 `documentIds` | 只有 `ScopeKind.DOCUMENTS` 才记；CORPUS 记空列表 | 全库检索不是「选了哪几份文档」，记下来会让下一轮的显式作用域看起来像被修改过 | 2026-10-27 |
| 16 | §10 R1：`understand(String, ConversationContext, List<Turn>)` | 改成 `understand(String, TurnContext)`，`TurnContext` / `ContextTurn` 定义在 `com.finagent.understanding` | 避免 `understanding` ↔ `memory` 包级循环依赖；映射放在 `ChatService`（它本来就同时认识两边） | 2026-10-27 |
| 17 | 降级「用 StructuredContext 做确定性补全」 | 只补公司与年份两个槽位，并把 `normalizedQuery` 改写成「公司（代码） 年份 原问题」；指标永不补 | 指标是语义判断（补错会把检索带偏），公司与年份是槽位（上下文里有就是确定的）；不重写 normalizedQuery 的话补全对检索毫无影响 | 2026-10-27 |
| 18 | 计划未写「补全结果怎么进检索 / trace」 | `ChatService` 用 `qu.normalizedQuery()` 去检索（答案 Prompt 仍用用户原话）；`inferredFromContext=true` 时加警告 `query_inferred_from_context` | 消解结果不落到检索上等于没做；警告让 trace 能区分「用户说的」与「模型推的」 | 2026-10-27 |
| 19 | 计划把 `LlmSummaryGenerator` 写成 `@ConditionalOnProperty(finagent.memory.enabled=true)` | 去掉条件注解，Bean 永远存在；开关只在 `MemoryAssembler` / `ConversationSummarizer` 运行时判 | 条件注解会让「关掉记忆」变成「启动失败」：`application-test.yml` 里 memory=false → `ChatService` 构造器第 11 参拿不到 `SummaryGenerator` → `No qualifying bean`，整个上下文起不来。关记忆是运行时行为，不是拆 Bean | 2026-10-27 |
| 20 | 计划写「超过阈值时压缩较早消息」，未定压缩多少 | 每次只压「待汇总条数的一半」（偶数化，最少 2 条），不是全量 | 全量压缩会让摘要每轮重写、old→new 的 diff 失去意义，也更容易丢细节；一半是「增量」与「别追不上」的折中。实测 30 条 → 取 14 条，指针停在第 14 条 | 2026-10-27 |
| 21 | 计划未定义「摘要失败怎么办」 | `maybeSummarize` 抛异常 → `ChatService` catch，加警告 `summary_failed`，`summary_until_message_id` 原地不动（下轮重试） | 摘要失败不该让整轮回答失败；指针不动意味着「那批消息下次还会进 Prompt」，宁可多花 token 也不丢上下文 | 2026-10-27 |
| 22 | 计划未定义摘要与短期窗口的关系 | 短期窗口从 `summary_until_message_id` **之后**开始（`shortTerm.load(id, until)`），已汇总消息不再以原文进 Prompt | 摘要与原文同时进 Prompt 会双重计入 token，且摘要里的数字与原文重复，模型容易抓错一份 | 2026-10-27 |
| 23 | 测试计划未提「消息 id 是全局序列」 | 断言一律用 `nthMessageId(conversation, n)`（`ORDER BY id OFFSET n-1 LIMIT 1`），不硬编码 14 / 24 | 首版硬编码导致 expected 14 but was 238；同时也修正了第二批次的期望（待汇总 36 条取一半 → 指针到第 32 条，不是第 24 条） | 2026-10-27 |
| 24 | §13.5「规则命中不调 embedding」 | 规则命中只保证「精确项必进」；命中条数 < `longTermMaxItems` 时仍会用向量补到上限（只有候选行为空、或规则命中已达上限时才完全不调 embedding） | 「用户点名了格式」不代表「他没有别的偏好」；把规则当成硬短路会漏掉语义相近的其它记忆。零调用的两条闸（无候选 / 已满额）已经覆盖 90% 的问答 | 2026-10-27 |
| 25 | §13.3 未定义预算为 0 的语义 | `longTermBudgetTokens() <= 0` 统一视为「这一层关掉」——CONTEXTUAL / WATCHLIST 一条都不进 Prompt（INSTRUCTION 不受影响） | 初版写成 `if (budget > 0 && used + t > budget)`，结果 ratio=0 反而变成「不限量」，方向正好相反；`structured` / `summaryText` 两处的既有语义都是「预算 <= 0 = 关掉」，三处必须一致 | 2026-10-27 |
| 26 | Task 8 测试计划未提「库里已有手工验证留下的记忆行」 | `LongTermMemoryRecallerDbTest` 用合成用户 9001 / 9002（库里不存在的 id），并在 `@AfterEach` 按 `subject_id IN (9001, 9002)` 清场 | 真实 analyst(id=1) 身上有手工 curl 留下的 `answer.style` / `focus.company.300059` 两行 ACTIVE，会让「库里没有记忆 → 零 embedding」这类用例直接失败；合成用户既不动真实数据，也不依赖清库顺序 | 2026-10-27 |
| 27 | Task 8 的 ChatService 断言口径 | 用 `argThat(m -> m instanceof MemoryContext mc && mc.text().contains(...))` 校验第 4 参，而不是 `eq(new MemoryContext(...))` | `MemoryContext` 里带 `Stats` 与 `recentTurns`，逐字段相等对「背景文本是否变了」这个断言太脆；断言意图是「重新装配过的背景进了 Prompt」 | 2026-10-27 |
| 28 | §4 Task 5 验收「比去年增长多少」要「出现两个年度数字」 | 放宽为「出现增长率或年度其一」 | 真模型第一版回答是「营业总收入比去年增长38.46%。」——它做了跨年对比（两个年度的数据都在证据里），只是没把两个绝对金额各念一遍。按字面判据会把「答对了但表述简洁」判成失败；按「有没有做对比」判定才稳定 | 2026-10-28 |
| 29 | §14.2 用例 5「写一条偏好 → 新会话带『按我的』提问」 | 记忆 key 固定为 `analysis.must_cite`，提问里带「出处」二字（`按我的要求标注出处：…`） | 首版用自定义 key + 「按我的习惯」：Gate 命中了，但召回要靠向量相似度（记忆内容是「回答先给结论」），实测相似度 < 0.6 → `longTermItems=0` → 假失败。改成「问题里出现『出处』→ 规则命中 `analysis.must_cite`」后召回是**确定性**的，不受改写与阈值波动影响 | 2026-10-28 |
| 30 | §14.2 用例 6「记忆里的数字若被复述 → 必须有 answer_ungrounded」 | 允许两条正当结局：① 正常作答且引用全为真实文档行；② 全部数字不落地 → `answer_ungrounded` 拒答 | 这条测的是「记忆不能变成证据」，不是「模型必须听记忆」。实测模型直接无视记忆里的「1 亿元」，给出 `sufficient=true` + 4 条真实引用 + 未复述（`repeatedMemory=false`），正是想要的结局 | 2026-10-28 |
| 31 | §14.2 用例 5 的写入方式 | 用 `POST /api/memories` 写，而不是发一句「以后…」的命令 | 命令句那一轮也要走完整链路（QU + 检索 + 生成，一次模型调用），而命令解析已有 8 个单测（`MemoryCommandParserTest`）。e2e 要验的是「新会话能否召回」，不是「正则能不能匹配」 | 2026-10-28 |
| 33 | 未预见「LLM QU 编造公司代码」 | 新增 §15「公司代码文本落地校验」：QU 给出**恰好 1 个**作用域外代码时，若问题原文点名了作用域内公司且**恰好 1 家**，改锚到它并记 `query_company_reanchored`；否则不动、照旧拒答 | 这是 e2e 暴露的产品级缺陷，不是测试问题：600519 在问题里根本没出现，是模型编的，却让整轮问答被拒答（3.34 秒、零检索）。修法只能「有据才改」，不能一律丢弃（那正是阶段 6 修过的「问五粮液答东方财富」） | 2026-10-28 |
| 32 | 未定义集成测试的清理策略 | e2e 自带 `@AfterEach` 硬删；Task 2~6 的集成测试**不清理**，库里留下会话行（当前 14 行，标题是被截断的问题） | 这些行不影响断言（每次新建会话），但会让 `conversation` 表随测试次数增长。记入报告「已知限制」，本阶段不加清理框架 | 2026-10-28 |

## 8. 待确认决策（动手前需要你拍板）

| # | 决策点 | 推荐 | 备选 | 影响 |
| --- | --- | --- | --- | --- |
| D1 | 本轮是否接 Redis | **不接**（记忆真相源 = PostgreSQL；缓存省不下秒级 LLM 耗时） | 接 Redis 做会话/摘要热缓存（多一个容器 + 一致性处理） | 不接：少一个组件、报告里要写清「为什么现在不需要」 |
| D2 | 长期记忆写入范围 | **只做显式命令 + 显式 API** | 再加「LLM 自动提炼候选」（准确率不可评估） | 不做自动提炼：误写会污染之后所有回答 |
| D3 | 摘要用 LLM 还是规则 | **LLM（qwen-plus），失败降级为「不摘要」** | 规则截断（简单，但会丢结论） | 用 LLM：每题多花 1 次调用，但只在触发时发生 |
| D4 | 记忆是否进引用编号 | **不进**（单独段落 + 「不可引用」标注） | 进 `[M1..Mn]` 编号（要改 `CitationValidator` 与前端契约） | 不进：C6/C7 不变，改动面最小 |
| D5 | 会话归属校验放哪一层 | **应用层**（`owner_user_id`），RLS 只管租户 | 给 `conversation` 加「按用户」RLS 策略 | 应用层：与阶段 8 的三层模型一致；按用户策略会让「同租户协作」将来难做 |
| D6 | 组织级记忆（ORG） | **留字段不启用**（`subject_type='USER'` 只写 USER） | 本阶段实现 ADMIN 可写 | 不启用：没有真实组织场景，写了也测不出对错 |
| D7 | 摘要/结构化上下文是否进 trace | **进**（`ChatTrace` 加 `memory` 段：summary 条数、memory 条数、是否 Gate 命中） | 只写日志 | 进 trace：否则「为什么这轮答得不一样」无法复盘 |
| D8 | 会话删除语义 | **逻辑删除**（`status='DELETED'`，30 天后物理清理留给运维） | 直接物理删除 | 逻辑删除：符合设计 §14.3 保留期要求 |
| D9 | 摘要 / 结构化上下文是否留历史行 | **不留**（只留当前值 + `version`；变更动作进 `memory_event`） | 每次重写插一条历史行（`conversation_summary_history`） | 不留：摘要可由消息重算，历史行是可重算的副本；不可重算的是变更动作，已由 `memory_event` 兜住 |
| D10 | 长期记忆是否统一 5% 配额 | **不统一**：拆 INSTRUCTION（独立 600 token，只合并压缩不丢弃）与 CONTEXTUAL（5%，最多 3 条） | 沿用设计 §11.9 的统一 5% | 拆开：用户显式指令被裁掉 = 静默违反用户要求，且用户无法感知 |

> 这 10 条都按「推荐」执行，除非你指出不同意见。确认后我按 §4 的 Task 1 → 9 顺序推进，每个 Task 完成即跑对应测试并更新 §7 偏差登记。

## 9. 执行顺序与依赖

~~~text
Task 1 (V10)  ──┬─→ Task 2 (会话存储/REST) ──┬─→ Task 3 (短期记忆) ──→ Task 5 (指代消解)
                │                            ├─→ Task 4 (结构化上下文) ──┘
                │                            └─→ Task 6 (摘要)
                └─→ Task 7 (LTM 写入) ──→ Task 8 (Gate + 召回) ──→ Task 5 的 Gate 字段合流
Task 3~8 全部完成 ──→ Task 9 (契约/文档/报告/e2e)
~~~

依赖说明：Task 5 依赖 Task 3 与 4（消解要吃窗口与结构化上下文）；Task 8 的 Gate 字段来自 Task 5 那次 LLM 调用，所以 Task 8 在 Task 5 之后收尾；Task 6 与其余任务无强依赖，可与 4/7 并行。

## 10. 执行前修订（2026-10-27，Task 5 指代消解）

> 规则来自 roadmap「文档约定」：动手前先把「改哪些文件 / 为什么 / 怎么验收」写下来，再改代码。
> 本节的约定优先于 §4 Task 5 的原文；原文保留以便对照「为什么改主意」。

### 10.1 与原文的三处不同

| # | 原文 | 本节口径 | 原因 |
| --- | --- | --- | --- |
| R1 | `RouterService.understand(String query, ConversationContext ctx, List<Turn> recent)` | 改成 `understand(String query, TurnContext ctx)`，其中 `TurnContext` 是 `com.finagent.understanding` 里自己的 record（公司 / 年份 / 文档 / 指标 / 最近几轮） | `understanding` 不能反向依赖 `memory`（`memory.ConversationContextService` 已经 import `understanding.QueryUnderstanding`，反向 import 会形成包级循环）。把「上下文长什么样」定义成 understanding 自己的入参类型，映射由 `chat.ChatService` 做 |
| R2 | 规则路径「不改」 | 仍然不改规则内部的抽取逻辑，但**规则命中时也不做消解**（直接把原文当 normalizedQuery） | 规则只认显式实体；它命中的问题本身已经「脱离上文可检索」，再做消解是多余的一次模型调用 |

## 11. 执行前修订（2026-10-27，Task 6 会话摘要）

### 11.1 改哪些文件

| 文件 | 改什么 | 为什么 |
| --- | --- | --- |
| `src/main/java/com/finagent/memory/MemoryProperties.java` | 末尾加 `@DefaultValue("12") int summaryTriggerMessages` | 触发阈值必须可配（评测时想压到 4 轮就能触发）；放在末尾是为了不打乱既有按位置传参的测试 |
| `src/main/java/com/finagent/memory/ConversationSummary.java` | **新增** record(conversationId, summary, summaryUntilMessageId, version, updatedAt) | 摘要是一行「当前状态」，不是事件（D9） |
| `src/main/java/com/finagent/memory/ConversationSummaryRepository.java` | **新增** `find` / `upsert`（`ON CONFLICT (conversation_id) DO UPDATE`，`version = version + 1`，指针取 `GREATEST`） | 指针只许前进：并发两轮都触发摘要时，小的那个不该把大的盖回去 |
| `src/main/java/com/finagent/memory/SummaryGenerator.java` | **新增** 函数式接口 `String generate(String prompt)` | 把「调模型」隔离成一行可替换的实现，单测不用 mock Spring AI 的 Builder 链 |
| `src/main/java/com/finagent/memory/LlmSummaryGenerator.java` | **新增** 实现：qwen-plus，输入=旧摘要+待汇总消息，输出 ≤200 字 | 与 LlmRouter 同一套 `ChatClient` 用法 |
| `src/main/java/com/finagent/memory/ConversationSummarizer.java` | **新增** `maybeSummarize(Conversation)`：阈值判断 → 取最旧一半（成对）→ 生成 → 写事件 → 前移指针 | 增量摘要的唯一入口 |
| `src/main/java/com/finagent/memory/ConversationRepository.java` | 加 `oldestAfter(conversationId, afterId, limit)` 与 `newestAfter(...)` | 摘要要「最旧优先」，短期窗口要「最新优先」；同一个过滤条件反向排 |
| `src/main/java/com/finagent/memory/ShortTermMemory.java` | 加 `load(conversationId, afterMessageId)` 重载 | 已汇总的消息不该再作为原文进 Prompt（否则同一段内容出现两次） |
| `src/main/java/com/finagent/memory/MemoryAssembler.java` | 填 `MemorySections.summary`（超 `summaryBudgetTokens` 整段丢）；短期窗口只取摘要指针之后的消息 | 让摘要真的出现在 Prompt 里，且与短期窗口不重叠 |
| `src/main/java/com/finagent/chat/ChatService.java` | 加 `ConversationSummarizer`；新增 `finishTurn`（落库 → 摘要），失败只加 `summary_failed` 警告 | 「摘要失败不影响回答」是 §4 Task 6 的三条规则之一 |

### 11.2 三条硬规则（照 §4 Task 6）

1. 摘要失败只加 warning `summary_failed`，下一轮自然重试（指针没前移 → 下一轮还是同一批消息）；
2. 只前移 `summary_until_message_id`，**不删消息**（完整历史留在 `conversation_message`）；
3. 摘要不产生引用：它只进「背景信息 · 不可引用」段落。

### 11.3 怎么验收

| 用例 | 断言 |
| --- | --- |
| 未汇总消息 ≤ 阈值 | 不调 `SummaryGenerator`，不写摘要行 |
| 15 轮（30 条）> 阈值 12 | 指针前移到「最早一半」的最后一条；消息总数仍是 30（不减） |
| 第二次触发 | `version` 从 1 → 2，指针继续前移，旧摘要作为输入（增量而非重算） |
| `SummaryGenerator` 抛异常 | 摘要行不变、指针不动；`ChatService` 的响应多一条 `summary_failed`，答案照常 |
| `MemoryAssembler` | Prompt 里有摘要段；短期窗口不包含指针之前的消息 |


## 12. 执行前修订（2026-10-27，Task 7 长期记忆写入）

动手前先定方案（roadmap 硬规则）。这一节与 §4 Task 7 有 5 处口径不同，以本节为准。

### 12.1 计划里两处冲突，先统一

| 冲突点 | 计划原文 | 实际（以本表为准） | 为什么 |
| --- | --- | --- | --- |
| memoryType 取值 | §4 Task 7 的表写 `PREFERENCE` / `WATCHLIST` / `CONSTRAINT` | V10 的 CHECK 只有 `INSTRUCTION` / `CONTEXTUAL` / `WATCHLIST`；**PREFERENCE 与 CONSTRAINT 都映射到 INSTRUCTION** | D10 已定「用户显式规则进独立 600 token 预算、只合并压缩不丢弃」；PREFERENCE（answer.style）与 CONSTRAINT（analysis.must_cite）正是这一类。多造两个类型会让「谁进独立预算」变成 if-else 迷宫 |
| 写入时机 | 未写 | 一轮问答**收尾时**写（`finishTurn` 里、落库之后），不改答案、不改检索 | 命令句常与问题同现（「以后先给结论，顺便看下 2025 营收」）。写入不能改变这一轮的回答，也不该因为写失败让用户看到 500 |

### 12.2 改哪些文件

| 文件 | 动作 | 干什么 |
| --- | --- | --- |
| `src/main/java/com/finagent/memory/LongTermMemory.java` | 新增 | 对应 `long_term_memory` 行的 record（id / tenantId / subjectType / subjectId / memoryType / memoryKey / content / valueJson / sourceType / sourceRef / confidence / importance / status / version / expiresAt / lastAccessedAt / retrievalCount / createdAt / updatedAt），外加类型与状态常量 |
| `src/main/java/com/finagent/memory/LongTermMemoryRepository.java` | 新增 | `findActiveByKey`（带 `FOR UPDATE`）、`findActive`（列表）、`insert`、`supersede(id)`、`markDeleted(id)`、`touchAccessed(id)` |
| `src/main/java/com/finagent/memory/MemoryCommand.java` | 新增 | 规则解析的产物：`record MemoryCommand(String memoryType, String memoryKey, String content, String sourceRef)` |
| `src/main/java/com/finagent/memory/MemoryCommandParser.java` | 新增 | 纯函数规则表（无 Spring、无 IO）：`Optional<MemoryCommand> parse(String question)` |
| `src/main/java/com/finagent/memory/LongTermMemoryService.java` | 新增 | 写入流水线 validate → conflict → upsert/version → audit；`save(...)` / `list(...)` / `delete(...)` / `maybeRecordCommand(question, traceId)` |
| `src/main/java/com/finagent/memory/MemoryController.java` | 新增 | `POST /api/memories`（201）、`GET /api/memories`、`DELETE /api/memories/{id}`（204 逻辑删除） |
| `src/main/java/com/finagent/memory/MemoryNotFoundException.java` | 新增 | 他人 / 不存在的记忆 → 404（与 C11「跨租户 404 不是 403」同口径） |
| `src/main/java/com/finagent/chat/ChatService.java` | 修改 | 构造器加 `LongTermMemoryService memories`；`finishTurn` 里追加「命令识别 → 写入」，失败只加 `memory_write_failed` 警告 |

### 12.3 五条实现口径

1. **主体固定为 USER**：`subject_type='USER'`、`subject_id = TenantContext.require().userId()`。ORG 留字段不启用（D6）。
2. **embedding 尽力而为**：写入时调 `EmbeddingService.embedAll(List.of(content))` 存 1024 维 + model + version；失败只记 log，**记忆照样落库**（精确 key 读取不受影响，只是这条暂时进不了向量召回）。
3. **冲突版本化**：同 `(tenant, subject, type, key)` 已有 ACTIVE → `SELECT … FOR UPDATE` → 内容相同则**幂等返回旧行、不写事件**；内容不同则旧行 `SUPERSEDED` + 新行 `version = old.version + 1` + `memory_event(action='SUPERSEDE', old_value, new_value)`。顺序必须是「先降级旧行、再插新行」，否则撞 `uq_memory_active` 部分唯一索引。
4. **删除是逻辑删除**：`status='DELETED'` + `memory_event(action='DELETE')`；行保留（「用户什么时候撤回的偏好」不可重算）。
5. **过期不在写入时处理**：`expires_at` 由调用方给（可空）；「过期不召回」在 Task 8 的查询条件里生效（`expires_at IS NULL OR expires_at > now()`）。本次只保证能写进去、能读出来。

### 12.4 命令规则表（`MemoryCommandParser`）

| 触发 | memoryType | memoryKey | 例子 |
| --- | --- | --- | --- |
| 「以后…都…」/「以后回答…」+ 结论/先给结论 | INSTRUCTION | `answer.style` | 「以后回答先给结论」 |
| 同上 + 中文/英文/语言 | INSTRUCTION | `answer.language` | 「以后都用中文回答」 |
| 同上 + 引用/出处/来源 | INSTRUCTION | `analysis.must_cite` | 「以后每个数字都要给出处」 |
| 「记住：…」/「记住，…」 | INSTRUCTION | `instruction.general` | 「记住：我更关心经营性现金流」 |
| 「以后不要…」/「别再…」 | INSTRUCTION | `instruction.negative` | 「以后不要再用表格」 |
| 「关注…」+ 6 位股票代码 | WATCHLIST | `focus.company.<code>` | 「以后重点关注 300059」 |
| 以上都不命中 | — | — | 返回空（**不写**） |

正则只在句首或标点后匹配（避免「我记得…」这类误判）；解析出的 content 会去掉命令词前缀（「以后」「记住：」），避免召回时读出一句带指令语气的怪句子。

### 12.5 怎么验收

| 用例 | 断言 |
| --- | --- |
| 命令识别 6 例 | 上表六类各命中一条；「我记得东方财富去年营收」不命中 |
| 冲突版本化 | 同 key 写两次不同内容 → 1 条 ACTIVE(version=2) + 1 条 SUPERSEDED(version=1) + 1 条 `SUPERSEDE` 事件 |
| 幂等 | 同 key 写两次**相同**内容 → 仍 1 行、version 不变、事件数不变 |
| 删除 | `DELETE /api/memories/{id}` → 204；行变 DELETED；`GET` 看不到；`DELETE` 他人的 → 404 |
| API 写 | `POST /api/memories` → 201，返回 id/version/status；缺 key 或 content → 400 |
| 会话里说命令 | 带 `conversationId` 问「记住：我更关心经营性现金流」 → 答案照常返回，且落库一条 INSTRUCTION |
| 写失败不影响回答 | 仓库抛异常 → 答案照常，响应多一条 `memory_write_failed` |


### 12.6 执行结果与偏差（2026-10-27 21:35）

新增/修改的文件与 §12.2 完全一致，另加 4 个测试类。执行中定下 4 处细节：

| # | 计划 / 原文 | 实际做法 | 为什么 |
| --- | --- | --- | --- |
| 1 | 「以后不要再用表格」去掉命令词后剩「再用表格」 | 内容补回「不要」→「不要再用表格」 | 提醒句去掉否定词会变成反向指令，召回时读起来像「继续用表格」 |
| 2 | API 的 type 只认三个规范值 | 同时接受别名 `PREFERENCE` / `CONSTRAINT` 并归一到 `INSTRUCTION` | 计划最早用的就是这两个名字，调用方照旧能跑；但库里只存三种 |
| 3 | `expiresAt` 未定义传输格式 | 请求体用 ISO-8601 字符串（`2027-01-01T00:00:00Z`），解析失败 → 400 | 让 Jackson 直接绑 `OffsetDateTime` 会在缺时区时报 400 且文案不可控 |
| 4 | 写入时「向量化」没说失败怎么办 | 失败只记 warn，记忆照写（`embedding` 为 NULL） | 精确 key 读取不依赖向量；宁可少一条可召回，也不要因为外部 API 抖动丢用户指令 |

测试结果：

| 测试类 | 用例数 | 覆盖 |
| --- | --- | --- |
| `MemoryCommandParserTest` | 8 | 六类命令各一 + 「我记得…」不误判 + 第二句里的命令 |
| `LongTermMemoryServiceDbTest` | 8 | CREATE/幂等/SUPERSEDE/逻辑删除/越权 404/向量化失败/命令写入/无身份跳过 |
| `MemoryControllerTest` | 5 | 201、缺 content 400、列表、204 与 404、expiresAt 非法 400 |
| `ChatServiceTest`（新增 2 例） | 14 | 命令在收尾时写入且不改答案；写入失败只加 `memory_write_failed` |


## 13. 执行前修订（2026-10-27，Task 8 Retrieval Gate + LTM 召回）

### 13.1 与 §4 Task 8 的六处不同（以本节为准）

| # | 计划原文 | 实际做法 | 为什么 |
| --- | --- | --- | --- |
| 1 | 「复用 Task 5 那次 QU 调用的 `needLongTermMemory`」 | QU 里根本没有这个字段 → **新增**（canonical 12 参数 + 保留 11/10/9 参兼容构造器），`LlmRouter.RouterDto` 加 `Boolean needLongTermMemory`，规则路径恒 false | 不复用那次调用就要再调一次模型，白花 3 秒；字段不存在是计划的疏漏，不是「换个字段名」 |
| 2 | 第 1 步「已知 `memoryKey` → 结构化精确读取（不走向量）」 | 没有显式 key 参数可用 → 实现为「**规则命中**」：候选行的 key 末段（style/language/must_cite）或内容关键词出现在问题里，就直接采用，不调 embedding；没命中才走向量 | 「精确读取」的前提是知道 key；用户不会说「按 answer.style 回答」，只会说「按我以前的格式」。规则命中就是「知道 key」的判定 |
| 3 | 验收「不相关问题 embedding 调用次数 = 0」 | 两道闸：Gate 不通过 → 连库都不查；Gate 通过但候选行为空 → 直接返回空，**不起 embedding** | 只有第一道闸的话，「记忆库里一条都没有」仍然会因为向量检索白调一次 API |
| 4 | D10「INSTRUCTION 只合并压缩不丢弃」 | 超 600 token **照样注入** + 警告 `instruction_overflow`（不截断、不丢弃）；CONTEXTUAL/WATCHLIST 走 5% 配额、最多 3 条 | 「合并压缩」需要模型参与，而这里只能做确定性处理；宁可多花 token 也不能静默违反用户指令（这正是 D10 要防的） |
| 5 | 「按 importance 排序后进 `MemoryContext`」 | 排序键 = importance 降序 → 相似度降序 → id 降序 | importance 是显式声明的优先级，相似度只说明「这句话相关」，两者都要 |
| 6 | 未写 LTM 怎么进 Prompt | `MemoryAssembler` 新增 `withLongTerm(base, items)`（它持有渲染器），`MemoryContext` 加第 4 组件 `sections`（可空）以便重渲染；`ChatService` 在 QU 之后调用 | 召回必须等 QU（要 `needLongTermMemory`），装配却发生在 QU 之前；只加「重渲染」这一个动作，Prompt 结构不变 |

### 13.2 改哪些文件

| 文件 | 动作 | 干什么 |
| --- | --- | --- |
| `src/main/java/com/finagent/understanding/QueryUnderstanding.java` | 修改 | 新增 `boolean needLongTermMemory`（canonical 12 参；保留 11/10/9 参兼容） |
| `src/main/java/com/finagent/understanding/LlmRouter.java` | 修改 | `RouterDto` 加 `Boolean needLongTermMemory`，Prompt 里加一行要求；`toUnderstanding` 透传 |
| `src/main/java/com/finagent/memory/MemoryProperties.java` | 修改 | 新增 `longTermMinScore=0.6`、`longTermCandidates=8`、`gateEnabled=true` |
| `src/main/java/com/finagent/memory/LongTermMemoryRepository.java` | 修改 | 新增 `searchByVector(...)`（`ORDER BY embedding <=> ?::vector LIMIT ?`）与 `listCandidates(...)` |
| `src/main/java/com/finagent/memory/MemoryGate.java` | 新增 | 纯函数：词典快路径 / QU 字段 / 默认 false |
| `src/main/java/com/finagent/memory/LongTermMemoryRecaller.java` | 新增 | 六步召回（规则命中 → SQL 候选 → 向量 → 阈值 → 去重 → 取前 N → 记访问） |
| `src/main/java/com/finagent/memory/MemoryContext.java` | 修改 | 加第 4 组件 `MemorySections sections` + `withSections(...)` |
| `src/main/java/com/finagent/memory/MemoryAssembler.java` | 修改 | `assemble` 带上 `sections`；新增 `withLongTerm(base, items)`（含 INSTRUCTION / 其他两类两套预算） |
| `src/main/java/com/finagent/chat/ChatService.java` | 修改 | QU 之后：Gate → 召回 → `withLongTerm`；失败只加 `memory_recall_failed` |

### 13.3 召回 SQL 与阈值

- 候选行：`WHERE tenant_id=? AND subject_id=? AND status='ACTIVE' AND (expires_at IS NULL OR expires_at > now()) AND memory_type = ANY(?) LIMIT 100`；
- 向量：同一条件 + `embedding IS NOT NULL` + `ORDER BY embedding <=> ?::vector LIMIT `longTermCandidates`(8)`；
- 阈值：**相似度 ≥ 0.6 ⇔ 余弦距离 ≤ 0.4**（pgvector 的 `<=>` 返回距离，不是相似度，比较方向写反就会「越不相关越入选」）；
- 去重：同 `memory_key` 只留分数最高的一条（同一 key 有多行 ACTIVE 在库层面不可能，但历史行 + 阈值边界会让同 key 出现两次）；
- 记访问：`touchAccessed` 放在最后，异常吞掉（「回答成功但统计没记上」可接受，反之不行）。

### 13.4 Gate 词典

命中即召回：「以后 / 以前 / 之前说 / 上次 / 照旧 / 还是按 / 格式 / 偏好 / 习惯 / 记住 / 一贯 / 历来 / 按我的 / 照我」。
不召回：纯事实问答（`EXACT_FACT` 且无上述词）、确定性计算、上下文已完整的追问（有公司 + 年份 + 指标）、Gate 关闭。

### 13.5 怎么验收

| 用例 | 断言 |
| --- | --- |
| Gate 8 例 | 词典命中 / QU 字段命中 / 默认跳过 / 事实问答跳过 / 上下文充分跳过 / 空问题 / null / 开关关闭 |
| 规则命中不调 embedding | 问题里出现 `answer.style` 的关键词 → `embedAll` 从未被调用，只走 SQL |
| 零调用（无记忆） | 库里没有 ACTIVE 记忆 → `embedAll` 从未被调用 |
| 阈值过滤 | 相似度 0.55 的记忆不入选 |
| 去重 / 最多 3 条 / importance 优先 | 5 条候选里同 key 出现两次 → 结果 ≤ 3 且 key 不重复 |
| 过期 / 他人 | `expires_at` 已过、属于别人的行都不出现 |
| 记访问 | 召回后 `retrieval_count + 1`、`last_accessed_at` 非空 |
| Prompt | 召回结果进「背景信息 · 不可引用」段；INSTRUCTION 超 600 token 仍注入并带 `instruction_overflow` |

### 13.6 执行结果（2026-10-27 收尾）

| 项 | 结果 |
| --- | --- |
| 新增代码 | `MemoryGate`（词典 14 词 + QU 字段）、`LongTermMemoryRecaller`（六步召回）、`ChatTrace` 新增记忆段 `MemoryTrace`（D7） |
| 修改代码 | `QueryUnderstanding`（+needLongTermMemory）、`LlmRouter`、`MemoryProperties`（+3 项）、`LongTermMemoryRepository`（+listCandidates/searchByVector）、`MemoryContext`（+sections）、`MemoryAssembler`（+withLongTerm）、`ChatService`（Gate → 召回 → 重装配） |
| 新增测试 | `MemoryGateTest` 9、`LongTermMemoryRecallerDbTest` 8（真库，合成用户 9001/9002） |
| 扩充测试 | `MemoryAssemblerTest` 5 → 9、`ChatServiceTest` 14 → 18 |
| 全量非 e2e | **478 通过 / 0 失败 / 1 跳过（DoclingParserAdapterTest）BUILD SUCCESS**（Task 7 结束 453，+25） |
| 修掉的一个真 bug | `withLongTerm` 的预算判断写成 `if (budget > 0 && used + t > budget)` → `longTermRatio=0` 时反而「不限量」；改为「预算 <= 0 = 这一层关掉」，与 `structured` / `summaryText` 的既有语义对齐（偏差 #25） |
| 未在本任务做 | 真模型的多轮记忆 e2e（「以后回答先给结论」→ 下一轮验证注入），与 §10 的 4 个多轮场景一起放到 Task 9 |

**为什么记忆进 trace 而不是进证据块**：引用编号 `[E1..En]` 的序号是数组下标（`CitationValidator`），记忆没有 documentId / page / bbox，塞进去会让编号整体错位，还会产出「看起来有出处却点不开」的引用。记忆只在 `ChatTrace.memory` 里留统计（短期条数 / 丢弃数 / 摘要字符 / 长期条数 / 闸门是否命中），供评测与排障。
## 14. 执行前修订（2026-10-28，Task 9 收尾）

### 14.1 交付清单

| 产出 | 文件 | 内容 |
| --- | --- | --- |
| 契约 C12 | `docs/关键契约.md` | 四层记忆的真相源与行基数、三条硬规则（不可引用 / 只整条丢 / 越权 404）、会话作用域、Token 配额、Gate 判定、冲突版本化 |
| 术语 | `docs/术语表.md` | 新增「会话与记忆」小节（原 §10 速查表 → §11、原 §11 指路 → §12） |
| README | `README.md` | 阶段 9 一节：建会话 / 追问 / 写偏好的三条 curl + 开关表 + 记忆不产生引用 |
| 报告 | `docs/阶段9-Memory与会话报告.md` | 骨架同阶段 7/8（怎么读 → 结论 → 为什么 → 交付物 → 契约 → 实测 → 踩坑 → 限制 → 验收 → 下一步） |
| 路线图 | `docs/superpowers/plans/2026-10-05-demo-roadmap.md` | 阶段 9 标完成 + 计划索引 |
| e2e | `src/test/java/com/finagent/memory/ConversationE2ETest.java` | 6 个用例：4 个多轮消解 + 1 个偏好跨会话 + 1 个「记忆不得冒充证据」 |

### 14.2 e2e 六个用例（真模型，`@Tag("e2e")`）

| # | 场景 | 输入 | 断言 |
| --- | --- | --- | --- |
| 1 | 指标追问 | 会话 A：「东方财富2025年营业总收入是多少」→「那净利润呢？」 | 第 2 轮 `sufficient=true`、`warnings` 含 `query_inferred_from_context`、引用落在 document 7、答案含 `12,084,594,947` 或 `120.85` |
| 2 | 跨年对比 | 同会话：「比去年增长多少？」 | 答案同时出现 2024 与 2025 的金额（`44,587,872,891` 与 `16,067,741,039` 两个年度之一 + 另一年），引用 ≥ 2 |
| 3 | 换指标不换公司 | 同会话：「它现在的现金流怎么样？」 | `sufficient=true` 且引用只来自 document 7 |
| 4 | 指标不在语料 | 同会话：「那毛利率呢？」 | 200 且（`sufficient=true` 带引用）或（`sufficient=false` + `note` 非空）——不允许 500 |
| 5 | 偏好跨会话 | 会话 B：「以后回答先给结论」→ 新会话 C：「按我的格式，东方财富2025年营业总收入是多少」 | C 轮 `trace.memory.gateHit=true` 且 `longTermItems>=1`，答案仍 `sufficient=true` |
| 6 | 记忆不冒充证据 | 复用 C 轮的响应 | 所有 `citations[].documentId > 0` 且 `pageStart > 0`；记忆条数不参与 `citations.length` |

### 14.3 与计划的三处不同（以本节为准）

| 计划原文 | 实际做法 | 原因 |
| --- | --- | --- |
| e2e 只写「4 个多轮 + 1 偏好 + 1 负样本」 | 负样本落成「记忆不产生引用」的结构断言（用例 6），而不是「模型不许说错数字」的语义断言 | 模型是否复述记忆里的错误数字取决于 Prompt 遵循度，断言它等于断言模型今天的心情；但「引用里不能出现记忆」是代码不变量，永远可判 |
| 未写 e2e 需要哪些开关 | `properties = {memory.enabled=true, tenant.auth-enabled=true, tenant.acl-enabled=true}` + `@ActiveProfiles("test")` + `dev-analyst-token` | 记忆默认关（回归门限要它关）；ACL 打开后 analyst 只剩 300059 的权限，跨公司用例才有意义；不带 token 直接 401 |
| 未写清理策略 | `@AfterEach` 用 `JdbcTemplate` 硬删本轮造的 `conversation*` 行与 `long_term_memory`（按 id 列表），并在无 `TenantContext` 的主线程执行 | 测试线程不设上下文 = owner 连接，RLS 不拦；否则每跑一次 e2e，库里就多一批「新会话」与一条 `answer.e2e_*` 偏好 |

### 14.4 报告要收的实测数字

| 数字 | 来源 |
| --- | --- |
| 六个 e2e 的耗时与逐条结论 | surefire 输出 + `CONV-E2E\|case=...\|` 打印行 |
| 四层记忆在全量回归里被关掉时的零影响 | 全量非 e2e 用例数（基线 478）+ 1 跳过 |
| V10 六张表的行数与 RLS 策略数 | `SELECT count(*)` 与 `pg_policies` |
| 召回闸门的真实开销 | `trace.memory.gateHit` 与是否发生 embedding 调用（日志） |

## 15. 执行前修订（2026-10-28，QU 公司代码的「文本落地校验」）

> **⚠ 本节方案已被 §16 取代并删除代码**（`ChatService.groundCompanyFromQuestion` 已移除）。
> 保留原文只为记录「为什么第一版是补丁、第二版才是改分工」。要看最终口径请直接跳到 §16 与 C13。

### 15.1 现象

e2e 第三轮（整类 6 个用例）5 通过 / 1 失败，失败的是「偏好跨会话」：

- 问题原文 27 字：**按我的要求标注出处：东方财富2025年营业总收入是多少**（不含任何股票代码）；
- 日志：`作用域外公司，拒答：[600519]`，耗时 3.34 s（没检索、没调答案模型）；
- 因此 `trace.memory.gateHit` 必然为 false —— 拒答分支在 Gate 召回**之前**返回。

### 15.2 根因（两层，都要看清）

1. **LLM QU 偶发编造公司代码**：`LlmRouter` 的提示词写的是「companyIds：证券代码（6 位数字），不确定就留空数组」，但模型这一次给了 600519（贵州茅台，中文财经语境里最常见的示例代码）。600519 在本仓库 `src/main/java` 里只出现在两处注释（`RuleBasedRouter.java:13`、`RouterService.java:86`），**不在任何 Prompt 模板**里，所以不是「抄了示例」，是模型自己编的。
2. **编造值没有任何落地校验**：`RouterService.validate` 自阶段 6 起「只校验 6 位数字格式、保留未知代码」（那次修的正是「未知代码被静默丢弃 → 问五粮液答东方财富」），所以编造码原样通过 → `ChatService.companiesOutsideScope` 判定「用户问的是 600519」→ 拒答。安全（没答错主体）但误伤正常问题。

### 15.3 三个候选与取舍

| 候选 | 做法 | 结论 |
| --- | --- | --- |
| ① 文本落地校验（**采用**） | 只有当**问题原文点名了作用域内的公司**、且唯一时，才把这个「无依据的代码」改锚到它，并留 warning | 只在「有正面文本证据」时动手，不动阶段 6 的保护 |
| ② 丢弃所有问题里无依据的代码 | 简单，但「五粮液2024年营业收入」也会被丢 → 系统拿东方财富的数字回答，主体错 —— 正是阶段 6 修过的 bug | **否决** |
| ③ e2e 加重试 | 一次失败重跑直到绿 | **否决**：掩盖真实的产品脆弱点，报告里也就写不出这条 |

### 15.4 规则的精确形状（实现必须逐条对应）

1. 前置条件：`qu.companyIds()` 恰好 1 个，且该代码**不在**作用域文档的 `companyCode` 集合里；否则原样返回。
2. 在 `scope.documents()` 里找 `companyName` 出现在**问题原文**中的公司，按 code 去重后必须**恰好 1 个**；否则原样返回（0 个 = 用户没点名，2 个 = 说不清问哪家）。
3. 命中则：`companyIds` 改成该 code；`normalizedQuery` 若既不含该公司名也不含该代码，则加前缀 `名称（代码） `（与 `RouterService.fillFromContext` 的补全格式一致，检索才能命中）。
   实测补充：前置条件要求公司名出现在问题原文，所以 `normalizedQuery`（由模型归一化、多数情况等于原文）**通常已含公司名**，前缀分支很少触发；真正决定 Sparse 精确模式能否命中该公司的，是改过的 `companyIds`（它被拼进检索词），因此验收断言要看 `companyIds` 而不是看 query 文本。
4. 留 warning `query_company_reanchored`（**不带代码**，避免把「用户问过 600519」写进响应正文；细节只进日志）。

### 15.5 不变量（写完必须仍然成立）

- 问题里没有任何作用域公司名（`ChatServiceTest.refusesOutsideCompany` 的「五粮液…」）→ **完全不动**，仍拒答并带 `company_out_of_scope:000858`；
- 拒答分支的位置不变（仍在 Gate 召回之前）—— 安全边界不因为这次改动前移或后移；
- 无会话（单轮）与有会话走同一段校验，不做特例。

### 15.6 改哪些文件

| 文件 | 动作 | 干什么 |
| --- | --- | --- |
| `src/main/java/com/finagent/understanding/QueryUnderstanding.java` | 修改 | 新增 `withCompanyGrounding(List<String> companyIds, String normalizedQuery)`（record 没有 setter） |
| `src/main/java/com/finagent/chat/ChatService.java` | 修改 | QU 之后、`companiesOutsideScope` 之前插一步 `groundCompanyFromQuestion(...)`；warning 单独一组进 `mergeWarnings` |
| `src/test/java/com/finagent/chat/ChatServiceTest.java` | 扩充 | +2 例：编造代码被改锚（不拒答 + `query_company_reanchored` + 检索用的 query 含 300059）；问题里点名两家作用域公司时不改锚 |

### 15.7 验收

| 用例 | 断言 |
| --- | --- |
| 编造代码 + 问题点名唯一作用域公司 | 不拒答；`warnings` 含 `query_company_reanchored`；传给检索器的 `QueryUnderstanding.companyIds()` 恰为 `["300059"]` |
| 问题里点名两家作用域公司 | 不改锚（走原拒答或原逻辑） |
| 问题没点名任何作用域公司 | 仍拒答，`company_out_of_scope:000858`（既有用例） |
| e2e 偏好跨会话 | `gateHit=true`、`longTermItems>=1`、`sufficient=true` |



## 16. 执行前修订（2026-10-28，公司名→代码的解析搬到程序里）

### 16.1 为什么改

用户指出（2026-10-28）：「提示词好像就有问题啊，他怎么可能解析出来证券代码呢？用户一般都是按照公司名称来提问的」。判断成立，根因是**分工错了**：

| 现状 | 问题 |
| --- | --- |
| 提示词要求模型输出 `companyIds：证券代码（6 位数字）` | 用户只给公司名，模型必须凭训练记忆把「东方财富」翻译成「300059」——没有权威源可查，出错是概率问题 |
| 模型给错的代码能通过校验 | `RouterService.validate` 只校验「6 位数字」格式（src/main/java/com/finagent/understanding/RouterService.java:132-140），600519 完美通过 |
| 错代码导致整轮拒答 | `ChatService` 判「问的公司不在作用域」→ 拒答，用户莫名其妙（e2e 实测 3.34 秒、零检索） |

通用原则：**抽取（extraction）交给模型，实体链接（entity linking）交给数据库**。模型只负责「问题里出现了哪个词」，把词对到 `company` 行必须有权威表参与。

### 16.2 命名（用户硬要求：ids 与 codes 必须切开）

| 名字 | 类型 | 含义 | 由谁产生 |
| --- | --- | --- | --- |
| `QueryUnderstanding.companyCodes` | `List<String>` | **证券代码**（6 位数字，如 300059） | 原文写了代码时由正则/模型抄；否则由 CompanyResolver 查库得到 |
| `QueryUnderstanding.companyMentions` | `List<String>` | **原文里的公司名字面**（必须是问题原文子串，如「东方财富」） | 模型抽取 + 子串校验 |
| `ResolvedScope.companyIds()` | `Set<Long>` | **数据库 company.id 主键**（如 7） | 作用域解析（保持原名不变，它确实是 id） |

全仓把 `companyIds` 改名为 `companyCodes`，**唯一例外**是 `ResolvedScope.companyIds()`。

### 16.3 契约（新增 C13，随后写进 docs/关键契约.md）

1. 模型输出的 `companyMentions` 必须是问题原文的**逐字子串**；不是子串的一律丢弃（模型编不出来）。
2. 模型输出的 `companyCodes` 必须在问题原文里**字面出现**；没出现的一律丢弃 —— 这是根治 600519 类编造的那一刀。
3. `companyCodes` 为空时，由 `CompanyResolver` 用 mentions 在**当前作用域**内匹配，匹配到才得到代码。
4. 匹配到 1 家或多家的 → 全部写进 `companyCodes`（多家用于对比题）。
5. mentions 里**没有任何一家**能匹配上，且该 mention「像公司名」→ **拒答**（文案点名用户原话），记 `company_unresolved`；不允许退化成「无公司约束检索」（那是阶段 6「问五粮液答东方财富」的根因）。
6. 问题里根本没提公司（如「行业趋势怎么样」）→ 不加公司约束，正常检索。

### 16.4 CompanyResolver 规则

| 步骤 | 规则 |
| --- | --- |
| 代码优先 | 逐个 code 与 `scope.documents()` 的 `companyCode` 精确比对，命中即 match |
| 名称匹配 | 名称先归一化：去空白、去「股份有限公司 / 有限公司 / 股份 / 集团 / 控股 / 信息 / 科技」，再双向 `contains`（「东方财富信息」也能命中「东方财富」） |
| 去重 | 按 code 去重，保留首次命中的顺序 |
| 「像公司名」判定 | 长度 ≥ 3 视为实体；或含后缀词（公司/股份/集团/银行/证券/保险/控股/实业/科技/药业/能源/汽车/地产/电力/传媒/基金/信托/航空/钢铁/煤炭/化工/医药/食品/家电） |
| 黑名单（代词，不触发拒答） | 该公司、上述公司、本公司、母公司、子公司、公司、企业、行业、市场、这些公司、上市公司 |

### 16.5 文件表

| 文件 | 改动 |
| --- | --- |
| src/main/java/com/finagent/understanding/QueryUnderstanding.java | `companyIds` → `companyCodes`；**末尾**新增 `companyMentions`（放末尾是为了让 33 处既有 12 参调用点继续编译，且两个 `List<String>` 相邻会导致静默错位）；保留 12/11/10/9 参兼容构造器（mentions 传空）；`withCompanyGrounding` → `withCompanyCodes` |
| src/main/java/com/finagent/understanding/LlmRouter.java | 提示词拆成两个字段（mentions 逐字抄、codes 仅在原文有 6 位数字时填）；`RouterDto` 同步改名 + 加 `companyMentions`；`toUnderstanding` 传 13 参 |
| src/main/java/com/finagent/understanding/RouterService.java | `validate` 加两道子串过滤（mentions 必须是原文子串、codes 必须在原文出现）；`fillFromContext` / `hasEntities` 等改名 |
| **新增** src/main/java/com/finagent/understanding/CompanyResolver.java | 纯函数 + 两个 record（`Match(code, name, companyId)`、`Resolution(matches, unresolved, warnings)`）；无 Spring 依赖，单测不需要上下文 |
| src/main/java/com/finagent/chat/ChatService.java | 删 `groundCompanyFromQuestion`；改成 `CompanyResolver.resolve(...)` + `qu.withCompanyCodes(...)`；拒答分支改由「未匹配 mentions」驱动 |
| src/main/java/com/finagent/retrieval/HybridRetriever.java | `qu.companyIds()` → `qu.companyCodes()`（Sparse 词表用解析后的代码） |
| src/main/java/com/finagent/memory/ConversationContextService.java | 同上改名（写会话槽位时用的仍是代码） |
| src/test/java/... | 新增 `CompanyResolverTest` 8 例；`RouterServiceTest` +3（编造的 mentions/codes 被丢弃）；`ChatServiceTest` 两条改写成「mention 解析成功」与「mention 无法解析 → 拒答」 |

### 16.6 删掉的东西

| 删除项 | 为什么 |
| --- | --- |
| `ChatService.groundCompanyFromQuestion`（§15 的改锚补丁） | 它修的是「模型给了错代码」这个症状；现在代码只能来自原文或数据库，症状的成因不存在了 |
| `QueryUnderstanding.withCompanyGrounding` | 被 `withCompanyCodes` 取代 |
| `ChatService.companiesOutsideScope` + `refuseOutsideCompanies` | 解析结果只可能来自作用域内文档；范围外的情况统一由「未匹配 mentions」拒答承担 |

### 16.7 验收

| # | 用例 | 期望 |
| --- | --- | --- |
| 1 | 问题「东方财富2025年营业总收入是多少」，模型只给 mention | 解析出 300059，正常作答 |
| 2 | 同上，模型**编造** `companyCodes=[600519]` | validate 丢弃（原文无 600519），答案正常 |
| 3 | 模型编造 mention「贵州茅台」（原文没有） | 子串校验丢弃，不影响作答 |
| 4 | 「五粮液2024年营业收入」，范围只有东方财富 | 拒答，文案含「五粮液」，警告 `company_unresolved` |
| 5 | 「东方财富和同花顺2024年营收对比」，两家都在范围 | 两家代码都进 `companyCodes`，不拒答 |
| 6 | 「行业趋势怎么样」 | 无公司约束，正常检索 |
| 7 | 别名「东方财富信息股份有限公司」 | 归一后命中「东方财富」 |
| 8 | 多轮追问「那净利润呢」 | 仍走 `fillFromContext` 补公司，行为与阶段 9 一致 |
| 9 | 32 题 golden set + 6 个多轮 e2e | 全部不退化（不允许出现新的误拒答） |

### 16.8 风险

| 风险 | 缓解 |
| --- | --- |
| 模型抽出泛称（「公司」「行业」）被当成实体 → 误拒答 | 黑名单 + 「像公司名」判定；32 题回归兜底 |
| 简称（「东财」）匹配不上 | 归一化 + 双向 contains 能覆盖「东方财富信息」；真简称留给阶段 10 的 `company_alias` 表 |
| 匹配到多家但不是对比题 | 只记 debug 日志、不拒答（宁可多带约束，也不误伤） |

### 16.9 执行结果（2026-10-28）

| 项 | 结果 |
| --- | --- |
| 命名 | `QueryUnderstanding.companyCodes`（证券代码）/ `companyMentions`（原文字面）/ `ResolvedScope.companyIds()`（数据库主键）三者在代码、Javadoc、契约 C13 里均显式区分；`companyIds` 这个名字只保留在 `ResolvedScope` 上 |
| 新字段位置 | `companyMentions` 放在 record **末尾**：它与 `companyCodes` 同为 `List<String>`，相邻会让 33 处既有 12 参调用点按位置错配且编译器不报错 |
| 提示词 | 拆成两字段：mentions「逐字抄原文、不输出代码」/ codes「只有原文里字面出现 6 位数字时才填」；EXACT_FACT 的前置条件改成「mentions 或 codes 至少一个非空」 |
| 校验 | `RouterService.validate` 加两道子串过滤（mentions 必须是原文子串、codes 必须字面出现）；实测原有「保留未知代码」的语义保留 —— 写字原文里的 `999999` 仍会被保留，交给作用域拒答 |
| ChatService | 删 `groundCompanyFromQuestion` / `companiesOutsideScope` / `refuseOutsideCompanies`，改为一次 `CompanyResolver.resolve(...)` + `qu.withCompanyCodes(...)`；拒答文案回显用户原话（如「五粮液」），警告只放稳定码 `company_unresolved` |
| 新增测试 | `CompanyResolverTest` 10 例（代码精确命中 / 名字命中 / 全称归一 / 两家对比 / 范围外拒答 / 代词忽略 / 代码与名字去重 / 空输入 / 短泛称只留警告 / 空作用域）；`RouterServiceTest` 新增 4 例（代码必须出现在原文、原文里的代码保留、mention 必须子串、mention 命中保留）；`ChatServiceTest` 改写 4 例（名字解析出代码 / 范围外公司名拒答 / 两家都在范围 / 代码不在范围拒答） |
| 全量非 e2e | **494 通过 / 0 失败 / 1 跳过**（改前 480；+10 resolver、+4 router，−2 被改写的旧用例合并进新用例） |
| 遗留 | 两字简称（「茅台」「招行」）不满足「长度 ≥ 3 或含后缀词」，不会被判成实体 → 可能不加约束（记 warning）。语料只有两家公司时影响面小；接多公司语料时建议补 `company_alias` 表（阶段 10） |

## 17. 执行前修订（2026-10-28，e2e 第 4 轮抓到的两处）

### 17.1 问题一：问题里没有公司名时，Sparse 丢掉了公司约束

§16 落地后，`RetrievalEvalTest` 的 27 条全部变成 `qu=SEMANTIC_EXPLANATION/DEGRADED:weak_evidence`：评测问题形如「2024年营业总收入是多少？」
（公司由作用域隐含），新规则不许模型凭空补代码 → `EXACT_FACT` 三要素不全 → 降级。召回与 MRR 没变（1.000 / 0.782），但 Sparse 精确模式少了「300059」这个词，命中面被放大。

**修法（本条即 §16.3 的第 7 条规则）**：`ChatService` 在 `CompanyResolver.resolve(...)` 之后补一步 —— 若 `companyCodes` 仍为空且**作用域里只有一家公司**，用它的代码兜底，并加警告 `company_from_scope`。

| 情形 | 行为 | 为什么 |
| --- | --- | --- |
| 问题点名公司且能在作用域内解析 | 用解析结果 | 用户说的优先 |
| 问题没点名公司，作用域里只有一家 | 用那一家兜底 + `company_from_scope` | 作用域本身就是用户设定的约束，这是确定性推断，不是模型猜 |
| 问题没点名公司，作用域里有两家以上 | 不兜底 | 替用户选主体 = 主体错配（阶段 6 的 000858 事故） |
| 问题点名公司但作用域里没有 | 仍然拒答（§16.3 第 5 条） | 安全边界不动 |

### 17.2 问题二：模型判「证据不足」时，answer 与 note 可能都是空串

`ConversationE2ETest` 第 4 例（「那毛利率呢」）实测 `confidence=insufficient`、`citations=0`，而 `answer` 与 `note` 都是空串 —— 客户端拿到空文本，既没法展示也没法报警。
根因：`AnswerService` 的 `!dto.sufficient()` 分支直接透传 `dto.answer()` / `dto.note()`，且**一条 warning 都不加**。

**修法**：该分支补 `evidence_insufficient` 警告；`answer` / `note` 为空时给固定文案（「证据不足：检索到的内容里没有能支撑这个问题的信息。」/「模型判定现有证据不足以回答，且未给出原因。」）。

### 17.3 改哪些文件

| 文件 | 动作 | 干什么 |
| --- | --- | --- |
| `src/main/java/com/finagent/chat/ChatService.java` | 修改 | `CompanyResolver` 之后加「单公司作用域兜底」+ `scopeCompanyCodes(...)` |
| `src/main/java/com/finagent/answer/AnswerResult.java` | 修改 | 新增常量 `WARNING_EVIDENCE_INSUFFICIENT = "evidence_insufficient"` |
| `src/main/java/com/finagent/answer/AnswerService.java` | 修改 | `!dto.sufficient()` 分支补警告与空文案兜底 |
| `src/test/java/com/finagent/chat/ChatServiceTest.java` | 扩充 | +2：单公司兜底（断言检索用的 `companyCodes=[300059]`）；两家公司不兜底 |
| `src/test/java/com/finagent/memory/ConversationE2ETest.java` | 修改 | 第 4 例 insufficient 分支改断言 `note` 非空 + `citations` 为空 + 警告含 `evidence_insufficient` |

### 17.4 验收

| 用例 | 断言 |
| --- | --- |
| 单公司作用域 + 无公司名的问题 | 检索用的 QU `companyCodes()` 恰为 `["300059"]`；响应警告含 `company_from_scope` |
| 两家公司作用域 + 无公司名的问题 | 不兜底；无 `company_from_scope` |
| 模型判 insufficient 且不给文案 | `answer` 与 `note` 均非空；警告含 `evidence_insufficient` |
| 全量非 e2e | 494 + 2 = 496 通过 / 1 跳过 |
| `ConversationE2ETest` | 6/6 绿（第 4 例走 insufficient 分支也必须绿） |
