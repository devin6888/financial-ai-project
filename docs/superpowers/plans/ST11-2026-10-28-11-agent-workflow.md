# 阶段 11 · Agent 工作流（LangGraph4j + HITL）

> 状态：**计划草案（待确认）**　|　依赖：阶段 9（会话/Memory）、阶段 10（前端）、阶段 13（stage 事件协议）
> 目标：把现在这条「写死的流水线」升级成**显式状态图**，支持中间决策、人工介入与断点续跑；同时保持阶段 13 的首屏体验。

---

## 0. 为什么做这一步

### 0.1 现在的链路是一条直线，而不是一张图

`ChatService.ask()` 现在是固定的四步：

```
prepare()  →  检索 + 装配上下文  →  AnswerService 生成  →  compose() 打包响应
```

四个环节之间没有任何**判断**。这带来三个具体问题：

| 问题 | 现在的表现 | 想要的能力 |
| --- | --- | --- |
| 复杂问题拆不开 | 「比较 2024 与 2025 的毛利率并解释原因」一次检索、一次生成，模型只能拿一段证据硬答 | 拆成两个子问题分别检索，再合并 |
| 证据不足时不会自救 | 检索不到就拒答（`evidence_insufficient`），不会改写查询重试 | 改写一次（换同义词/放宽年份），仍不行才拒答 |
| 高风险结论没人把关 | 模型直接出答案，用户只能事后判断 | 复杂分析先给「计划 + 草稿要点」，用户确认后再生成 |

这三件事的共同点：**它们都是「下一步做什么」的决策**，不是「怎么算」的实现。决策需要状态、需要跳转、需要暂停 —— 直线代码表达不了。

### 0.2 为什么用 LangGraph4j，而不是自己写个状态机

自己写并不难（一个 `Map<String,Object>` + `while` 循环），但有两件事很难白送：

1. **Checkpoint**：每步状态落库，进程崩了能从断点续跑；同一线程可以回看历史、可以改状态再继续。
2. **interrupt / resume**：`interruptsBefore("approve")` 一行配置就能让图停在某节点前，把控制权交回调用方；恢复时用 `GraphInput.resume(...)` 带回人工输入。HITL 的全部难点（状态冻结、恢复点定位、重复执行防护）都在库里。

这两条正好对应设计文档 §14 的「Agent 执行可追溯 + HITL」。

### 0.3 库的实测事实（2026-10-28，Maven Central + javap）

| 项 | 实测结果 |
| --- | --- |
| 最新版本 | **1.9.7**（`org.bsc.langgraph4j`，2026-10 仍活跃） |
| 与我们的版本对齐 | `langgraph4j-parent:1.9.7` 里 `spring-ai.version = **2.0.1**`，与本项目完全一致 |
| Java 基线 | `maven.compiler.source=17`，我们跑 21，兼容 |
| 要引的坐标 | `langgraph4j-core`（319 KB）、`langgraph4j-spring-ai`（71 KB）、`langgraph4j-postgres-saver`（36 KB） |
| 传递依赖 | `org.bsc.async:async-generator`、`jspecify`、`slf4j-api`；**Jackson 2 与 Gson 都是 `provided`**，不引也能跑 |
| 默认序列化 | `StateGraph(AgentStateFactory)` 构造器内部用的是 **`ObjectStreamStateSerializer`（JDK 序列化）** —— 所以状态里只能放 JDK 可序列化类型 |
| 图构建 API | `StateGraph.addNode(name, AsyncNodeAction)` / `addEdge` / `addConditionalEdges(name, AsyncEdgeAction, Map)` / `compile(CompileConfig)` |
| 运行 API | `CompiledGraph.stream(Map, RunnableConfig)` → `AsyncGenerator<NodeOutput>`；`invoke`；`getState` / `updateState` / `getStateHistory` |
| 中断 API | `CompileConfig.builder().interruptsBefore(Set<String>)` / `interruptsAfter`；中断时抛 `GraphInterruptException`，带 `InterruptionMetadata`（`nodeId()` / `state()` / `reason()`） |
| 恢复 API | **`GraphInput.resume()`** / `GraphInput.resume(Map)`：同一 `threadId` 再调一次 `stream(...)` 即从断点继续 |
| 持久化 | `PostgresSaver.builder().datasource(DataSource).createTables(true).build()`；自带 `LG4JThread` / `LG4JCheckpoint` 两张表与 v1.0→v2.1 迁移脚本 |
| Spring AI 集成 | `org.bsc.langgraph4j.spring.ai` 提供 `StreamingChatGenerator`、`SpringAIToolService`（工具调用）、`SpringAIStateSerializer`（消息序列化） |
| 不引的 | `langgraph4j-studio-*`（可视化调试台）本期不引，说明见 D9 |

> 一个必须记住的坑：`provided` 意味着 **Jackson 2 不在 classpath 上**。本项目 Boot 4 用的是 Jackson 3（`tools.jackson`），所以绝不能用 `JacksonStateSerializer` / `SpringAIJacksonStateSerializer`，否则运行时 `NoClassDefFoundError`。

---

## 1. 范围

### 1.1 本阶段要做

1. 引入 LangGraph4j 三件套，并跑通一个最小图（spike，先验证 Boot 4 下可用）。
2. 定义 `AnalysisState`（显式 state schema，只放 JDK 可序列化类型）。
3. 六个节点：`understand`（理解/补全）→ `plan`（决定走哪条路）→ `retrieve` → `verify`（证据够不够）→ `answer` → `cite_check`。
4. 条件边：`plan` 按问题类型分流；`verify` 不足时回到 `retrieve`（最多一次改写）；`cite_check` 不过回 `answer`（最多一次）。
5. HITL：复杂分析在 `answer` 前暂停，等人 `APPROVE` / `EDIT` / `REJECT`。
6. Checkpoint：每步落库（`PostgresSaver`），进程重启后能恢复；线程 = 一次 run。
7. 业务表 `agent_run` / `agent_run_step`（Flyway V11，带 `tenant_id` + RLS + 注释），记录归属、状态、每一步摘要。
8. REST：`POST /api/agent/runs`、`GET /api/agent/runs/{id}`、`POST /api/agent/runs/{id}/resume`、`GET /api/agent/runs/{id}/stream`（SSE，复用 C15/C16 协议）。
9. 前端：问答页新增「分析计划 + 步骤时间线 + 审批框」，审批后继续流式出答案。

### 1.2 本阶段不做（以及为什么）

| 不做 | 理由 |
| --- | --- |
| 自由 ReAct（让模型自己决定调哪个工具、无限循环） | 演示场景固定（财务问答 + 对比 + 趋势），固定图更可控、可测、可解释；自由 ReAct 的失败模式没法写进验收 |
| 多 Agent 协作（Planner/Writer/Critic 三个 Agent） | 单 Agent 六节点已经够讲；多 Agent 需要消息总线与投票机制，成本翻倍、收益在演示里看不出来 |
| 报告生成（Markdown/Word 研报） | 设计文档把它放在 V3，且依赖图表能力，见 `docs/竞品对比与技术债.md` 的 P2 |
| 工具调用白名单之外的能力（联网搜索、代码执行） | 安全边界：本机演示不引入任意代码执行 |
| LangGraph4j Studio 可视化 | 它是一个独立 web 应用（jetty/springboot 两种），本期用前端自己的步骤时间线展示即可 |
| Redis 做 checkpoint | 现有 Postgres 已够用，多一个容器多一份运维（沿用 ST9 D1 的判断） |
| 跨会话的 Agent 记忆 | 复用阶段 9 的四层 Memory，不为 Agent 另建一套 |
| 自动重试整个 run（超过改写一次） | 会放大模型成本；失败态落到 `agent_run.status=FAILED` 让人决定 |

---

## 2. 契约 C17（草案，落 `docs/关键契约.md`）

### 2.1 状态：`AnalysisState`

状态是整张图唯一的真相源，**必须可 JDK 序列化**（默认序列化器是 `ObjectStreamStateSerializer`）。

| 键 | 类型 | 写入者 | 说明 |
| --- | --- | --- | --- |
| `runId` | String | 入口 | UUID，也是 checkpoint 的 `threadId` |
| `question` | String | 入口 | 用户原话，**永不改写**（改写只写 `rewrittenQuery`） |
| `rewrittenQuery` | String | plan | 检索用的查询（可为空 = 用原问） |
| `queryType` | String | understand | `EXACT_FACT` / `COMPARISON` / `COMPLEX_ANALYSIS` …（沿用 `QueryType` 的枚举名，存字符串） |
| `companyCodes` / `yearFrom` / `yearTo` | List<String> / Integer | understand | 与 `QueryUnderstanding` 对齐 |
| `plan` | List<String> | plan | 人可读的步骤（每项一句话），给前端画计划卡片 |
| `subQuestions` | List<String> | plan | 复杂问题拆出来的子问题 |
| `hits` | List<Map<String,Object>> | retrieve | 每条形如 `{chunkId, documentId, page, sectionPath, type, score}` |
| `evidenceCount` / `insufficient` | Integer / Boolean | verify | 证据是否够（规则判定 + 可选模型判定） |
| `retryCount` | Integer | verify | 改写重试次数，上限 1 |
| `answer` | String | answer | 最终答案（含 `[E1]` 编号） |
| `citationIssues` | List<String> | cite_check | 引用校验问题列表 |
| `decision` | String | resume | `APPROVE` / `EDIT` / `REJECT` |
| `decisionNote` | String | resume | 人工备注（EDIT 时是要点补充） |
| `status` | String | 各节点 | `RUNNING` / `WAITING_APPROVAL` / `DONE` / `FAILED` / `REJECTED` |

三条硬规则：

1. **状态里只放 JDK 类型**（String / List / Map / Integer / Boolean）—— 不放 record、不放 `ChatMessage`、不放 `float[]`。要传结构化对象就转成 `Map`。
2. **节点只能写自己负责的键**（上表「写入者」列），别顺手改别人的字段。
3. **`question` 永不被改写**：改写只写 `rewrittenQuery`，这样「用户到底问了什么」在任何时候都能追溯。

### 2.2 图（本节给了两份视图：原计划形态 + 带改动标记的视图）

```
                ┌──────────────┐
   START ──────▶│ understand   │  规则/LLM 补全，确定性
                └──────┬───────┘
                       ▼
                ┌──────────────┐
                │ plan         │  决定：直答 / 拆子问题 / 改写
                └──┬────────┬──┘
         直答/对比  │        │ 复杂分析
                   ▼        ▼
            ┌───────────┐  ┌─────────────────┐
            │ retrieve  │◀─│ retrieve ×N     │（按 subQuestions 逐个检索）
            └─────┬─────┘  └────────┬────────┘
                  ▼                 ▼
            ┌───────────┐◀──────────┘
            │ verify    │  不足且 retryCount<1 → 改写后回 retrieve
            └─────┬─────┘  仍不足 → respond_insufficient
                  ▼
            ┌───────────┐   ← interruptsBefore("answer")，仅复杂分析
            │ answer    │◀─ 人工 APPROVE/EDIT 后继续；REJECT 直接结束
            └─────┬─────┘
                  ▼
            ┌───────────┐  校验不过且未重试 → 回 answer
            │ cite_check│
            └─────┬─────┘
                  ▼
                 END
```

#### 2.2.1 同一张图，标出「本版本动哪里」

> 库本身的概念（状态 / 通道 / 检查点 / 中断恢复 / 序列化坑）见 `docs/LangGraph4j-工作机制.md`，本节只讲「我们的图长什么样、这版改哪里」。

图例（三种标记，全文统一）：

| 标记 | 含义 | 判断标准 |
| --- | --- | --- |
| `[新]` | 本阶段从零写的代码 | 现在仓库里不存在这段逻辑 |
| `[改]` | 已有逻辑，本阶段只在外面包一层适配（逻辑本身一行不动） | 既有类能直接调，只是要读写 state |
| `[用]` | 直接调用，既不包也不改 | 纯依赖，失败只会影响该节点 |

```text
START
  │ [新] 初始化：校验请求、生成 runId、把 question/tenantId/scope 塞进 AnalysisState
  ▼
[改] understand ────────── 包 RouterService.understand(query, TurnContext)
  │                        出参 qu 拆成 queryType / companyCodes / yearFrom / yearTo / metrics 写进 state
  │ [新]
  ▼
[新] plan ──────────────── 读 qu.queryType 决定路线，不调模型（纯规则）
  │ [新] 条件边：route = DIRECT | DECOMPOSE | REFUSE
  ├── DIRECT（EXACT_FACT / SEMANTIC_EXPLANATION / COMPARISON）──▶ [改] retrieve
  └── DECOMPOSE（COMPLEX_ANALYSIS 且有 subQuestions）──────────▶ [新] retrieve ×N 循环
                                                                        │
                                                                        ▼
                                                                  [改] retrieve（每个子问题各调一次）
  ▼
[新] verify ───────────── 读 AnswerResult.confidence 与 blocks 数，判三条出口
  │ [新] 条件边：verdict = ENOUGH | RETRY | INSUFFICIENT
  ├── RETRY（不足 && retryCount < 1）──▶ [改] rewrite（只写 rewrittenQuery）──▶ 回 retrieve
  ├── INSUFFICIENT ────────────────────▶ [改] respond_insufficient（复用现有拒答文案 + C6 第 5 条）
  └── ENOUGH ──────────────────────────▶ [新] 条件边：只有 DECOMPOSE 路线进 answer 且带中断
  ▼
[新] answer（仅复杂路线）── 前置 interruptsBefore(Set.of("answer"))
  │                          节点内复用 AnswerService.answerStreaming(...) 的 delta 回调
  │ [新]
  ▼
[改] cite_check ────────── 复用 CitationValidator + AnswerService 的编号重排与 ground
  │ [新] 条件边：不过且未重试 → 回 answer
  ▼
END
```

逐项落点（每行都能对应到具体文件，写代码时按这张表检查）：

| # | 图上元素 | 标记 | 复用的既有代码 | 本版本要写的部分 |
| --- | --- | --- | --- | --- |
| 1 | START 初始化 | `[新]` | — | `AnalysisState` 初始 Map（question / tenantId / userId / runId / scopeKind） |
| 2 | `understand` | `[改]` | `RouterService.understand(String, TurnContext)`、`CompanyResolver.resolve(...)` | 节点壳 + 把 `qu` 拆进 state |
| 3 | `understand → plan` 边 | `[新]` | — | 一行 `addEdge` |
| 4 | `plan` | `[新]` | `QueryUnderstanding.queryType()` / `needDecompose()` | 三值分流规则（不调模型） |
| 5 | `plan` 条件边 | `[新]` | — | `addConditionalEdges` + 三个目标声明 |
| 6 | `retrieve` | `[改]` | `HybridRetriever.retrieve(req, qu, scope)`、`ContextBuilder.build(...)` | 节点壳 + 把 `ContextPackage` 累加进 state 的 `blocks` |
| 7 | `retrieve ×N` | `[新]` | 同上 | 顺序 for 循环（**不用 `ParallelNode`**，理由见下方 2.2.2） |
| 8 | `retrieve → verify` 边 | `[新]` | — | 一行 `addEdge` |
| 9 | `verify` | `[新]` | `AnswerResult.confidence`（阶段 5 已有）、`ContextPackage.blocks().size()` | 三条出口的判定阈值 |
| 10 | `rewrite` 回边 | `[改]` | `RouterService` 的降级改写 + `QueryUnderstanding.normalizedQuery()` | 节点只写 `rewrittenQuery`，`retryCount + 1` |
| 11 | `respond_insufficient` | `[改]` | 现有拒答文案 + 契约 C6 第 5 条（answer / note / `evidence_insufficient` 三者必齐） | 组一个 `REJECTED`/`insufficient` 的终态 state |
| 12 | `answer` | `[改]` | `AnswerService.answerStreaming(question, qu, pkg, memory, onDelta)` | 节点壳 + `onDelta` 转成 C15 的 `delta` 事件 |
| 13 | `interruptsBefore(answer)` | `[新]` | — | `CompileConfig` + 审批 REST + `GraphInput.resume(...)` |
| 14 | `cite_check` | `[改]` | `CitationValidator`、`AnswerService` 内的 `renumberCitations` 与 `ground` | 校验失败的回边（最多回一次） |
| 15 | SSE 阶段映射 | `[改]` | 契约 C16 的 `stage` 事件（`ChatStreamEvent.stage(phase,label,detail)`） | `phase` 取值从四个固定值扩成节点 id + 新增 `approval` 事件 |
| 16 | Checkpoint | `[新]` | — | `PostgresSaver` + `agent_run`/`agent_run_step` 两表（V11） |

#### 2.2.2 本版本明确不动的部分（防止范围蔓延）

| 不动的东西 | 为什么不动 | 如果非要动会怎样 |
| --- | --- | --- |
| `HybridRetriever` 内部（Dense/Sparse/RRF/Rerank 权重） | 阶段 4~13 已经调平，评测门槛 `Recall@10=1.000 / MRR=0.782` 是照着现状打的 | 一改就动门槛，需要重跑 27 题评测才能证明没退化 |
| `AnswerService` 的四层校验（格式 / 引用 / 数值落地 / 时间） | 阶段 5~6 修过三次真实事故（编 5,560、编号跳号、无引用） | 校验逻辑散进节点后，非 Agent 路径（`/api/chat`）就与 Agent 路径不一致 |
| C15 的事件 JSON 形状（`meta` / `delta` / `final` / `error`） | 前端 `web/src/utils/sse.ts` 已按它写好解析 | 改形状要同步改两个仓库，且旧客户端直接崩 |
| 前端三栏布局与侧栏 | 阶段 12 刚验证完（拖宽、持久化、窄屏回退） | 这期前端只加「时间线 + 审批框」两块，不改骨架 |
| 检索 Query 的构造（`normalizedQuery`） | `understand` 节点产出后由 `retrieve` 消费，职责已经清晰 | 若在 `retrieve` 里再改写，`verify` 的重试就与首次检索口径不一致 |

为什么 `retrieve ×N` 用顺序循环而不是 `ParallelNode`：

1. 合并顺序必须可复现 —— 并行分支的完成顺序不确定，`blocks` 的先后会抖动，引用编号 `[E1..En]` 跟着抖，评测无法重放。
2. 3 个子问题 × 约 1 s 的检索，顺序执行也只多 2 s，而模型生成要 7 s，收益不可见。
3. 并行的真正收益要等多文档、多公司大范围检索（阶段 14+ 再评估），现在引入只会增加租户上下文与连接池的复杂度。

### 2.3 Checkpoint 与线程

- `threadId` = `runId`（UUID，服务端生成，不可猜）。
- `CompileConfig.builder().checkpointSaver(postgresSaver).interruptsBefore(Set.of("answer")).recursionLimit(12)`。
- `PostgresSaver` 自建 `LG4JThread` / `LG4JCheckpoint` 两张表，**这是 Flyway 管 schema 规则的一个明确例外**：表结构归库自己的迁移脚本（`db/migration/v1.1__init.sql` → `v2.1`），我们不复制、不改写，只登记原因。
- `PostgresSaver` 用一个**专属的 owner 数据源**（不是 `TenantAwareDataSource`）：库给的 DDL 不会带 `tenant_id`，也不会给 `finagent_app` 授权；反过来，任何人通过 API 都拿不到这两张表（没有对应端点），租户边界由 `agent_run` 保证。
- 读到 checkpoint 之前必须先查 `agent_run` 并校验 `tenant_id` / `user_id`，否则等于绕过 RLS。

### 2.4 HITL 语义

| 决定 | 行为 | 状态迁移 |
| --- | --- | --- |
| `APPROVE` | 按当前 plan 与草稿继续生成 | `WAITING_APPROVAL` → `RUNNING` → `DONE` |
| `EDIT` | 把 `decisionNote` 追加到 answer 的提示词（如「补充毛利率口径」），再继续 | 同上 |
| `REJECT` | 不生成，直接落 `REJECTED` + 原因 | `WAITING_APPROVAL` → `REJECTED` |

四条硬规则：

1. **只有 `WAITING_APPROVAL` 能 resume**；其它状态 resume → 409 `RUN_NOT_WAITABLE`。
2. **resume 幂等**：同一 `(runId, decisionSeq)` 重复提交只生效一次（`agent_run_step` 里记 `decision` 行，重复则返回现有结果）。
3. **暂停期间状态冻结**：`WAITING_APPROVAL` 的 run 不允许新问题插入（同一会话再问会新建 run）。
4. **超时不自动通过**：等待超过 `finagent.agent.approval-timeout`（默认 30 分钟）→ `FAILED` + `approval_timeout`，绝不「默认同意」。

### 2.5 与 C15 / C16 的关系

- 流式**沿用同一个 SSE 协议**（`meta` / `delta` / `final` / `error`），只是 `stage` 事件的 `phase` 取值从四个固定值扩展成**节点 id**（`understand` / `plan` / `retrieve` / `verify` / `answer` / `cite_check`）。前端 `StageLine` 不需要改渲染逻辑，只多认几个 label。
- `POST /api/agent/runs/{id}/stream` 与 `POST /api/chat/stream` 共用 `ChatStreamEvent`；差异只有：多了 `plan` 事件（`stage` 的 `detail` 里带 `plan` 数组）与 `approval` 事件。
- HITL 暂停时**不发 `final`**，发 `approval` 事件后关闭流；resume 时重新开一条流（同一个 runId）。

### 2.6 租户与权限

- `agent_run` 带 `tenant_id` + `user_id`，RLS 策略与阶段 8 一致（`p_agent_run_tenant`）。
- 跨租户 / 他人的 run → **404**（不是 403），与 C11 一致。
- `agent_run_step.summary` 只存人可读摘要，**不存证据正文**（正文在 checkpoint 里，且只能经 runId 访问）。

---

## 3. 文件结构

### 3.1 新增

| 文件 | 作用 |
| --- | --- |
| `src/main/java/com/finagent/agent/AnalysisState.java` | 状态工厂 + 键常量 + `AgentState` 子类（只读 JDK 类型） |
| `src/main/java/com/finagent/agent/AnalysisGraph.java` | 组装图：六个节点 + 条件边 + `compile(CompileConfig)` |
| `src/main/java/com/finagent/agent/AgentNodes.java` | 六个节点实现（`AsyncNodeAction`），注入现有 `RouterService` / `HybridRetriever` / `AnswerService` |
| `src/main/java/com/finagent/agent/AgentProperties.java` | `finagent.agent.*`：enabled、approval-timeout、recursion-limit、max-rewrite |
| `src/main/java/com/finagent/agent/AgentCheckpointConfig.java` | `PostgresSaver`（专属 owner 数据源）+ `CompiledGraph` 的 Bean |
| `src/main/java/com/finagent/agent/AgentRun.java` / `AgentRunStep.java` | 两个 record |
| `src/main/java/com/finagent/agent/AgentRunRepository.java` | `JdbcTemplate`：建 run / 更新状态 / 追加 step / 按租户查 |
| `src/main/java/com/finagent/agent/AgentRunService.java` | 启动 run、resume、状态校验、幂等 |
| `src/main/java/com/finagent/agent/AgentController.java` | 四个端点 |
| `src/main/resources/db/migration/V11__agent_run.sql` | 两张业务表 + 索引 + RLS 策略 + 全套 COMMENT |
| `src/test/java/com/finagent/agent/*` | 图单测（`MemorySaver` + 假 ChatModel）、HITL 集成、恢复测试 |

前端（仓库 `financial-ai-web`）：

| 文件 | 作用 |
| --- | --- |
| `src/components/PlanCard.vue` | 计划卡片（`plan` 数组） |
| `src/components/StepTimeline.vue` | 步骤时间线（节点 id + 状态 + 耗时） |
| `src/components/ApprovalCard.vue` | 审批框（通过 / 修改要点 / 拒绝） |
| `src/api/agent.ts` | 四个端点封装 |
| `src/views/ChatView.vue` | 复杂问题走 agent 路径；流式渲染复用现有逻辑 |

### 3.2 修改

| 文件 | 改动 |
| --- | --- |
| `pom.xml` | 加 `langgraph4j-core` / `-spring-ai` / `-postgres-saver` 1.9.7 |
| `src/main/java/com/finagent/chat/ChatService.java` | 复杂问题（`COMPLEX_ANALYSIS` 或需要拆子问题）转 `AgentRunService.start()`；其余仍走原链路 |
| `src/main/java/com/finagent/chat/ChatStreamEvent.java` | 新增 `APPROVAL` 事件类型与 `plan` 载荷 |
| `src/main/resources/application.yml` | 新增 `finagent.agent` 配置块 |

---

## 4. 任务（Task 1~9）

### Task 1　依赖与 spike（先证伪再动工）

**做**：pom 加三个依赖 → 写一个 `AgentSpikeTest`：`StateGraph` 两节点（`a → b`）+ `MemorySaver` + `invoke`，断言状态合并正确；再写一个 `interruptsBefore` 用例，断言第一次 `stream` 抛 `GraphInterruptException`、`GraphInput.resume(Map)` 后能继续。

**验收**：`mvn -B test -Dtest=AgentSpikeTest` 绿；确认**没有** `NoClassDefFoundError: com/fasterxml/jackson/databind/*`。

**如果失败**（Java 序列化与 Spring 代理冲突 / Boot 4 类加载冲突）：改 `langgraph4j-core` + `GsonStateSerializer`（加 gson 依赖，1 MB 以内），并在 §7 偏差登记。**不要**退回 Jackson 2 —— 那会和 Boot 4 的 Jackson 3 抢注解。

### Task 2　V11 迁移

**做**：`agent_run`（`id UUID PK` / `tenant_id` / `user_id` / `conversation_id` / `question` / `status` / `current_node` / `plan_json` / `decision` / `decision_note` / `error` / `created_at` / `updated_at`）+ `agent_run_step`（`run_id` / `seq` / `node` / `status` / `summary` / `duration_ms` / `created_at`，`UK(run_id, seq)`）+ `p_agent_run_tenant` / `p_agent_run_step_tenant` 两条 RLS 策略 + 全套 COMMENT。

**验收**：Flyway 到 V11 success；手工三条探针（owner 看到 N 行 / `finagent_app` 无变量 0 行 / 设 tenant 后 N 行）。

### Task 3　状态与图骨架（不接真模型）

**做**：`AnalysisState` + `AnalysisGraph` 六节点全用**假实现**（节点只改状态、不调外部），`MemorySaver`，写 4 条单测：直答路径、复杂路径、`verify` 不足回 `retrieve`、`cite_check` 不过回 `answer`。

**验收**：4 条绿；`MermaidGenerator` 输出的图与 §2.2 一致（作为文档附件，不引 studio）。

### Task 4　节点实现（接真组件）

**做**：`understand` 复用 `RouterService.understand`；`plan` 用**规则 + 一次 LLM**（复杂问题才调）；`retrieve` 调 `HybridRetriever.retrieve(..., RetrievalProgress)`；`verify` 用规则（命中数 ≥ 阈值 + 命中是否含目标指标）；`answer` 调 `AnswerService.answerStreaming`；`cite_check` 复用 `CitationValidator`。

**验收**：单测覆盖「检索为空 → 不足 → 改写一次 → 仍空 → 拒答」；trace 里能看到节点耗时。

### Task 5　HITL

**做**：`interruptsBefore("answer")` 仅复杂问题启用（`CompileConfig` 是编译期配置，所以**编两张图**：`fastGraph` 与 `hitlGraph`，按问题类型选）；`AgentRunService.resume(runId, decision, note)`；三种决定 + 幂等 + 超时。

**验收**：集成测试跑三遍：APPROVE 出答案、EDIT 的 note 出现在答案里、REJECT 不生成且 `status=REJECTED`；重复 resume 只生效一次。

### Task 6　Checkpoint 持久化（PostgresSaver）

**做**：`AgentCheckpointConfig` 用专属 `HikariDataSource`（owner，不复用 `TenantAwareDataSource`）建 `PostgresSaver`；`createTables(true)` 首次建表；跑通「进程内建图 → 暂停 → 重启应用 → 用同 runId resume」。

**验收**：`LG4JThread` / `LG4JCheckpoint` 出现行；重启后 resume 成功；`docs/关键契约.md` C17 里登记「Flyway 例外」。

### Task 7　REST + SSE

**做**：`POST /api/agent/runs`（body：question/scope/conversationId）→ 202 + `{runId}`；`GET /api/agent/runs/{id}/stream`（SSE：`stage`(节点) → `meta`(计划 + 证据) → `delta`* → `approval` 或 `final`）；`POST /api/agent/runs/{id}/resume`；`GET /api/agent/runs/{id}`（状态 + 步骤）。

**验收**：`RestAuthContractTest` 口径补三条（无 token 401 / 他人 run 404 / 越权 resume 404）；SSE 事件顺序与 C16 表一致。

### Task 8　前端

**做**：复杂问题自动走 agent 路径（后端返回 `runId` 时前端切到「计划 + 时间线」模式）；`ApprovalCard` 三个按钮；resume 后接同一条流；失败态可读。

**验收**：headless 探针跑「复杂问题 → 计划出现 → 暂停 → 点通过 → 答案出现」四步，控制台 clean。

### Task 9　文档与报告

**做**：`docs/关键契约.md` C17 定稿；`docs/术语表.md` 加「Agent 状态图 / Checkpoint / HITL / interrupt-resume / 节点与边」；README 阶段 11 节；`docs/阶段11-Agent工作流报告.md`（骨架同阶段 12/13）；roadmap 标完成；ST11 §7 偏差登记。

---

## 5. 总验收

| # | 门槛 | 怎么验 |
| --- | --- | --- |
| 1 | 最小图在 Boot 4 下可跑 | `AgentSpikeTest` 绿，无 Jackson 2 相关异常 |
| 2 | 直答路径不回归 | 事实题走原链路或 fastGraph，`ChatStreamE2ETest` 仍绿 |
| 3 | 复杂路径能拆子问题 | 单测断言 `subQuestions.size() >= 2` 且检索被调用两次 |
| 4 | 证据不足会改写重试一次 | 单测断言 `retryCount==1` 且 `rewrittenQuery` 非空 |
| 5 | HITL 三种决定正确 | 集成测试三条 + 幂等一条 |
| 6 | 重启后可恢复 | 手工重启应用 + `GET /runs/{id}` 仍 `WAITING_APPROVAL`，resume 成功 |
| 7 | 跨租户 404 | e2e：outsider 访问 analyst 的 runId → 404 且正文无敏感字段 |
| 8 | 首屏不回归 | 首个 `stage` ≤ 300 ms，`meta` ≤ 5 s（沿用阶段 13 口径） |
| 9 | 后端全量绿 | `mvn -B test` 0 失败（基线 568 + 新增） |
| 10 | 前端构建 + 单测绿 | `npm run build` + `vitest` |

---

## 6. 测试计划

| 层 | 类 | 覆盖 |
| --- | --- | --- |
| L1 单测 | `AnalysisStateTest` | 状态合并、键白名单、非法类型拒绝 |
| L1 单测 | `AgentNodesTest` | 六个节点的纯逻辑（假依赖） |
| L2 集成 | `AgentGraphDbTest`（`@Tag("db")`） | `MemorySaver` 跑全图；`PostgresSaver` 落库 + 恢复 |
| L2 集成 | `AgentHitlTest`（`@Tag("db")`） | 三种决定 + 幂等 + 超时 |
| L2 集成 | `AgentControllerTest` | 四个端点的状态码与错误形状 |
| L3 e2e | `AgentE2ETest`（`@Tag("e2e")`） | 真模型：复杂问题 → 暂停 → 通过 → 答案；首屏时延 |

---

## 7. 偏差登记

| # | 计划原文 | 实际 | 原因 |
| --- | --- | --- | --- |
| （执行时填） | | | |

---

## 8. 决策（待确认）

| # | 决策 | 结论 |
| --- | --- | --- |
| D1 | LangGraph4j 版本与坐标 | **1.9.7**，只引 `core` / `spring-ai` / `postgres-saver` |
| D2 | 状态用什么 | 自定义 `AnalysisState`（不用 `MessagesState`），只放 JDK 类型 |
| D3 | 序列化 | 默认 `ObjectStreamStateSerializer`；失败才退 `GsonStateSerializer` |
| D4 | Checkpoint 表归属 | `LG4J*` 由库自建（登记为 Flyway 例外），业务表 `agent_run*` 走 V11 |
| D5 | Checkpoint 数据源 | 专属 owner `HikariDataSource`，不复用 `TenantAwareDataSource` |
| D6 | HITL 触发条件 | 仅 `COMPLEX_ANALYSIS`（或模型判 `needDecompose=true`） |
| D7 | 人工决定 | `APPROVE` / `EDIT`（追加要点）/ `REJECT`，不自动通过 |
| D8 | 流式协议 | 复用 C15/C16；`stage.phase` 扩展为节点 id，新增 `approval` 事件 |
| D9 | 可视化 | 用前端自己的时间线，不引 `langgraph4j-studio` |
| D10 | 工具集 | 只做「检索 / 拆解 / 改写」，不做联网与代码执行 |

---

## 9. 执行顺序

```
Task 1 依赖与 spike ──┬─▶ Task 2 V11 ──▶ Task 3 图骨架 ──▶ Task 4 节点实现
                      │                                        │
                      └─▶ Task 6 Checkpoint（可与 3/4 并行）    ▼
                                                        Task 5 HITL
                                                             │
                                                             ▼
                                              Task 7 REST+SSE ──▶ Task 8 前端 ──▶ Task 9 文档
```

Task 1 是硬门槛：**spike 不过就改方案，不往上堆代码**。

---

## 10. 风险与回滚

| 风险 | 影响 | 兜底 |
| --- | --- | --- |
| LangGraph4j 与 Boot 4 类加载冲突 | Task 1 直接卡住 | 退 `GsonStateSerializer`；再不行只引 `core`（不用 spring-ai 模块），工具调用自己写 |
| Java 序列化与 Spring 代理对象冲突 | 状态里混进不可序列化对象 | 状态只放 JDK 类型（C17 硬规则 1）；`AnalysisStateTest` 里加「反射检查所有值为 Serializable」 |
| `PostgresSaver` 建表与 Flyway 抢 schema | 迁移顺序错乱 | 首次启动前手工 `createTables(true)` 一次，之后 `createTables(false)` |
| HITL 暂停把 SSE 连接挂死 | 前端转圈 | 暂停即关流（发 `approval` 后 complete），resume 另开一条 |
| 复杂路径慢（多一次 LLM 规划 + 多次检索） | 首屏变差 | 规划与检索并行；`plan` 用规则优先、模型兜底 |
