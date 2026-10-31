# 阶段 14：统一图引擎（所有问答走 LangGraph4j）

> 执行日期：2026-10-29 起
> 前置：阶段 11（Agent 工作流）、阶段 13（首屏反馈）已完成
> 目标（用户原话 m10200）：「最终的目标是所有问答全都走 langgraph4j」

## 0. 为什么做这一阶段

### 0.1 现在有两条链路，它们迟早会分叉

| 链路 | 入口 | 谁在用 | 有记忆 | 写会话 | 有 trace | 有 facts |
| --- | --- | --- | --- | --- | --- | --- |
| 直链 | `POST /api/chat`、`POST /api/chat/stream` | 默认（90% 的问题） | 有 | 有 | 有 | 有 |
| 图 | `POST /api/agent/runs/stream` + `/{id}/resume` | 用户手动开「深度分析」 | **没有**（传空串） | **没有** | 没有 | 没有 |

同一套检索、同一套答案服务、同一套引用校验，但「一轮结束之后要做什么」各写一遍，
于是图跑出来的问答在会话历史里不存在（幽灵对话），记忆偏好也不生效。
继续这样加功能，每加一项都要写两遍、测两遍。

### 0.2 为什么不是「删掉图，只留直链」

用户原话（m10187）：「正常用户使用的时候，肯定不会自己去判断当前问题是否需要深度检索」。
图提供的三件事直链给不了：**回退**（证据不足改写重试）、**人批**（歧义时先确认理解）、
**恢复**（检查点落库，刷新/重启能续）。这三件事都应该由程序按问题自动触发，而不是靠一个开关。

### 0.3 关键取舍

- 保留 URL `/api/chat/stream`：前端、e2e、README 全在用，换的是实现不是协议。
- 分四个阶段（P1~P4）落地，每阶段独立可验收、可回滚；不做一次性大爆炸改造。
- 每搬一条兜底逻辑（`company_from_scope` / `scope_acl_hidden` / `evidence_insufficient` /
  `renumberCitations`）就补一条用例 —— 这些是阶段 6~9 用真 bug 换来的。

## 1. 范围

### 1.1 做

1. P1 会话与记忆搬进图：抽 `TurnRecorder`；图跑完写会话消息、上下文、记忆命令、摘要。
2. P2 答案契约补齐：`facts` / `citations` / `trace` 进 `final`；后端合成 `ChatResponse`。
3. P3 歧义审批：运行时选 `fastGraph` / `hitlGraph`；只有歧义才弹「理解确认」。
4. P4 切换与删除：`/api/chat/stream` 内部改为图；删 `ChatService.ask*`；前端删「深度分析」开关。

### 1.2 不做

- 不做多轮图内重入（多轮靠会话表 + 记忆装配，图本身仍是一次运行）。
- 不做并行子问题检索（顺序循环，理由见 ST11 §2.2.2：引用编号要可复现）。
- 不引 `langgraph4j-studio`。
- 不改 C15/C16 事件名与含义（只给 `final` 增字段，旧前端忽略未知字段即可）。

## 2. 契约 C18 草案（统一入口）

1. **唯一入口**：`POST /api/chat/stream` 内部由 `AgentStreamService` 执行；`POST /api/chat`（非流式）复用同一条图，最后一次性返回。
2. **唯一收尾**：一轮结束必须经过 `TurnRecorder.record(...)`（会话消息 → 记忆命令 → 摘要），两条路径不再各写一遍。
3. **记忆先行**：记忆在进图之前装配成 `memoryText` 字符串写进状态；节点内不查记忆表（C17 §17.1）。
4. **审批只给歧义**：默认 `fastGraph`；命中歧义信号（公司多候选/库里没有、年份缺失、多文档未指定、QU 降级）才走 `hitlGraph`。
5. **契约不退化**：`final` 必须携带 `sufficient/answer/facts/citations/confidence/note/scope/warnings/trace/conversationId/messageId`，与 `ChatResponse` 同形。

## 3. P1：会话与记忆搬进图（本次）

### 3.1 改哪些文件

| 文件 | 动作 | 说明 |
| --- | --- | --- |
| `src/main/java/com/finagent/chat/TurnRecorder.java` | 新增 | 从 `ChatService` 抽出：`updateContext` / `record`（落库 → 记忆命令 → 摘要），失败只加警告 |
| `src/main/java/com/finagent/chat/ChatService.java` | 改 | 构造器去掉 `ConversationContextService` / `ConversationSummarizer` / `LongTermMemoryService`，改为一个 `TurnRecorder`；`finishTurn` 委托 |
| `src/main/java/com/finagent/agent/AgentStreamService.java` | 改 | 装配会话 + 记忆；`memoryText` 进图；跑完合成 `ChatResponse` 交给 `TurnRecorder`；`final` 带 `conversationId`/`messageId` |
| `src/main/java/com/finagent/agent/AgentRunService.java` | 改 | 新增 `conversationIdOf(runId)` / `questionOf(runId)`（resume 时要用） |
| 测试 | 改 | `ChatServiceTest` / `ChatServiceStageTest` 用真 `TurnRecorder` 包住原 mock；`AgentStreamServiceTest` 加三个 mock；`AgentStreamE2ETest` 加「图跑完写两条消息」 |

### 3.2 三条硬口径

1. 收尾顺序不能反：先落消息，再摘要（摘要要含这一轮）。
2. 收尾失败不改变答案：只加 `conversation_persist_failed` / `memory_write_failed` / `summary_failed` 警告。
3. `TurnRecorder` 不知道「怎么检索、怎么生成」，只吃「问了什么、答了什么、用了什么范围」—— 否则它就成了第二个 ChatService。

### 3.3 验收

| # | 断言 |
| --- | --- |
| 1 | 图跑一轮（含 resume）后 `conversation_message` 恰多 2 行，USER 是原问、ASSISTANT 含答案 |
| 2 | 无 `conversationId` 时行为不变（不写会话、不经 `requireOwned`） |
| 3 | 图这一轮也能落 `conversation_context`（追问能继承公司/年份） |
| 4 | 直链 628 基线不回归 |

## 4. P2：答案契约补齐

- `AnalysisState` 增 `FACTS` / `CITATIONS` / `TRACE`（JDK 类型）。
- `answer` 节点改用 `AnswerService` 的完整结果（`AnswerResult` 已含 facts/citations/trace）。
- `final` 载荷直接嵌 `ChatResponse`；前端删 `toChatResponse` 的临时映射。
- 验收：同问题两条链路（P4 后只剩一条）返回的 `citations` 逐字段相等。

## 5. P3：歧义审批

- `AgentRunService.run` 按歧义信号选图（`AnalysisGraph.fastGraph()` / `hitlGraph()`）。
- 审批卡文案从「审批」改为「确认理解」：显示公司/年份/子问题，三个决定不变。
- 验收：四类歧义各弹一次；非歧义问题一次也不弹；`EDIT` 的修改意见进答案。

## 6. P4：切换与删除

- `/api/chat/stream` 与 `/api/chat` 内部改为图；`AgentController` 只留 `resume` 与 `GET`。
- 删 `ChatService.ask` / `askStreaming` / `prepare` / `compose`（它们的兜底逻辑已在 P1~P2 搬进节点）。
- 前端删「深度分析」开关与 `agent` 分支。
- 验收：全量测试绿；前端手测四页；`final` 与旧 `ChatResponse` 字段逐一对照。

## 7. 决策

| # | 决策 | 理由 |
| --- | --- | --- |
| D1 | 分 P1~P4 四步，不一次性切换 | 每步可验收可回滚；当前 628 条用例是安全网 |
| D2 | 保留 `/api/chat/stream` URL | 换实现不换协议，前端零改动 |
| D3 | `TurnRecorder` 放在 `com.finagent.chat` | 它服务于「一轮问答」，不属于 memory 领域 |
| D4 | P1 先不补 `facts/trace` | 会话历史只需要「问了什么、答了什么、引了哪些块」 |
| D5 | P1 的作用域快照用会话配置（`ResolvedScope` 传 null） | 图内每节点会重解析，精确 id 与 chunk 版本留给 P2 |
| D6 | 不做图内多轮重入 | 多轮靠会话表 + 记忆装配，图仍是一次运行 |

## 8. 偏差登记

| # | 计划 | 实际 | 原因 |
| --- | --- | --- | --- |
| 1 | P1 写「图跑完落上下文」 | 用 `conversation.scope()` 做快照，`chunkVersions` 为空 | 图在节点里重解析作用域，P1 不引入 `ScopeResolver` 依赖；P2 补精确版本 |

## 9. 执行结果（P1，2026-10-29 20:0x）

| 项 | 结果 |
| --- | --- |
| 全量非 e2e | **629 通过 / 0 失败 / 1 跳过**（P1 前 628；+1 = `AgentStreamE2ETest.graphTurnIsPersistedIntoConversation`） |
| 图落库 e2e | 建会话 → 流式跑到 approval → resume APPROVE → `conversation_message` 恰 2 行：USER = 原问全文，ASSISTANT 含 `fake-answer`（48.9 s，含 Spring 上下文） |
| 直链回归 | `ChatServiceTest` 25、`ChatServiceStageTest` 4 全绿（收尾断言仍打到同一批 mock，证明抽出前后行为一致） |
| 事件翻译 | `AgentStreamServiceTest` 3 全绿（新增三个 mock；`final` 在有会话时带 `conversationId`/`messageId`） |

### 9.1 实现细节与踩坑

1. `ChatService` 的构造器从 14 参降到 12 参（去掉 `ConversationContextService`/`ConversationSummarizer`/`LongTermMemoryService`，加 `TurnRecorder`）；`contextOf` 读路径也搬进 `TurnRecorder`，否则 ChatService 还要留一个只读依赖。
2. `MemoryContext.EMPTY.text()` 是 **null**（不是空串）→ 传给图的可变参数用 `anyString()` 桩时直接 `PotentialStubbingProblem`。修法：`memory.text() == null ? "" : memory.text()`。测试抓的，读代码看不出来。
3. resume 时拿不到会话：`AgentRun` 有 `conversationId`，但 `AgentStreamService` 只拿到 runId → 新增 `AgentRunService.conversationIdOf` / `questionOf`（内部复用 `requireOwned`，别人的 run 仍是 404）。
4. 落库必须在发 `final` 之前：前端要拿 `messageId` 才能把这一轮挂进历史；`approval` 分支不落库（这一轮还没结束）。
5. `ConversationController` 的建会话响应字段是 **`conversationId`**（不是 `id`）→ e2e 解析写成 `"id"` 直接 `NumberFormatException: For input string: ""`。


### 9.2 P1 未做（留给 P2）

- `facts` / `trace` 仍不进图与 final（历史行里 `citations_json` 有、`trace_id` 为 null）。
- 图这一轮的作用域快照 `chunkVersions` 为空（P1 传 null 的 `ResolvedScope`）。
- 图轮次不写 `ChatTrace` 文件，前端诊断面板在 agent 轮次没有分项耗时。

## 10. P2：答案契约补齐（2026-10-29 20:2x）

### 10.1 为什么需要「旁路」而不是把结果放进状态

图的检查点用 JDK 序列化写 `LG4JCheckpoint.state_data`（C17 §17.1），所以状态里只能放 JDK 类型。
`AnswerResult` / `Fact` / `Citation` 都是 record，**没有实现 `Serializable`**；
硬塞进 `AnalysisState` 的后果不是编译错，而是「跑到 approval 停下那一刻」序列化直接炸。

因此沿用 `delta` 已有的通道：`AnswerStreamBridge` 扩成三张表（sinks / results / traces），
生命周期都是「一次请求」，`clear(runId)` 一起清。

### 10.2 改哪些文件

| 文件 | 动作 | 说明 |
| --- | --- | --- |
| `src/main/java/com/finagent/agent/AnswerStreamBridge.java` | 改 | 新增 `result(runId, AnswerResult)` / `retrievalTrace(runId, RetrievalTrace)` 与读取方法 |
| `src/main/java/com/finagent/agent/RealAnalysisNodes.java` | 改 | `retrieve` 登记第一次检索轨迹；`answer` 登记完整 `AnswerResult` |
| `src/main/java/com/finagent/agent/AgentStreamService.java` | 改 | `synthesize` 优先用 bridge 里的结果；构造 `ChatTrace`（runId 当 traceId）并落盘；`final` 增加 `response` 字段 |
| `src/test/java/com/finagent/agent/AgentStreamServiceTest.java` | 改 | +1 用例：bridged 结果进 `final.response`（facts / citations / confidence / trace.citationsDropped），并 verify trace 落盘 |
| 前端 `src/api/agent.ts`、`src/views/ChatView.vue` | 改 | `AgentFinalView` 带 `response`；气泡优先用后端响应，`toChatResponse` 降级为兼容回落 |

### 10.3 口径

1. `final.response` 与 `POST /api/chat` 的响应**同形**（`ChatResponse`），字段名不做别名。
2. `traceId = runId`：同一个 id 在 SSE、`agent_run`、`logs/chat-trace.jsonl` 三处可对。
3. 分段耗时来自 `agent_run_step.duration_ms`：`quMs` = understand、`answerMs` = answer（cite_check 回边重算时累加）。
4. `contextBlocks` / `contextTokens` 暂时为 0：装配在 answer 节点内部完成，没有回传（要回传就得给状态加键）。
5. 假节点（e2e）没有 `AnswerResult` → 回落到「evidence → citations」，测试与演示仍能跑。

### 10.4 验收

| # | 断言 |
| --- | --- |
| 1 | 单测：bridged `AnswerResult` 的 facts/citations 出现在 `final.response`，且 `trace.citationsDropped` 一致 |
| 2 | 单测：`traceWriter.write` 被调用一次 |
| 3 | e2e 4 例不回归（假节点路径回落到 evidence 映射） |
| 4 | 全量非 e2e 不回归（629 基线） |

### 10.5 执行结果（2026-10-29 20:3x）

| 项 | 结果 |
| --- | --- |
| 全量非 e2e | **630 通过 / 0 失败 / 1 跳过 BUILD SUCCESS**（629 + 1 条 P2 单测） |
| 真链路（agent 端点，`finagent.agent.enabled=true`） | `FIRST events=[stage×4, approval]`；resume 后 `final.status=DONE`、`response.sufficient=true`、`facts=2`、`citations=3`、`confidence=high`、`trace.traceId=runId`、`trace.quMs=3932`、`trace.answerMs=6891`、`citationsDropped=0`、`conversationId=1665`、`messageId=4285` |
| 前端 | `npm run build` 通过（ChatView chunk 136.34 kB / gzip 45.88 kB）；`AgentFinalView.response` 优先，`toChatResponse` 降级为兼容回落 |

### 10.6 P2 未做（留给 P4）

- `trace.contextBlocks` / `contextTokens` 仍是 0（装配在节点内部，没有回传）。
- `scope` 仍来自会话配置，不是本轮解析出的 `ResolvedScope`（FILTER 模式下 `documentIds` 为空）。
- `facts` 已进历史行，但直链与图两条链路仍各写一份 `ChatResponse` —— P4 删掉直链后只剩一份。


## 11. P3：歧义审批（2026-10-29 21:0x）

### 11.1 为什么不能「每个问题都批一次」

阶段 11 把审批点放在 `approve` 节点，代价是**所有**问题都要点一下才出答案。
用户原话（m10194）：「走图不一定是全部要人批的，一般是不明确的问题才需要人去审核」。
所以真正要判的是「这个问题本身是否明确」，而不是「要不要走流程」。

### 11.2 为什么必须在开跑前判定

`CompileConfig.interruptsBefore` 是**编译期**配置（C17 §17.2），`fastGraph()` 与 `hitlGraph()` 是两张图。
想知道「问题歧义吗」本来要等 `understand` 节点（QU，约 3~4 秒）跑完，
但那时图已经选定了。所以歧义判定必须放在图**外面**、且只能用不调模型的确定性信号。

### 11.3 判定规则（`AmbiguityDetector`）

| 信号 | 触发条件 | 为什么 |
| --- | --- | --- |
| `company_not_specified` | 作用域里有 ≥2 家公司，且问题里既没有证券代码也没有公司名 | 模型只能自己挑一家 → 阶段 6 的「问五粮液答东方财富」就是这么来的 |
| `year_not_specified` | 作用域里有 ≥2 个年度，且问题里没有 `(19\|20)\d{2}` | 「净利润是多少」在 2024/2025 两份年报下没有唯一答案 |

- 只有「作用域里确实存在多种可能」才判歧义：单公司单年度**永不**弹确认。
- 不判「指标没说清」：那会让「经营情况怎么样」这类正常问题全部变成收费站，宁可少弹。
- 作用域为空不算歧义，`ScopeEmptyException` 照旧往上抛（404/409 语义不变）。

### 11.4 改哪些文件

| 文件 | 动作 |
| --- | --- |
| `src/main/java/com/finagent/agent/AmbiguityDetector.java` | 新增（`@Component`，依赖 `ScopeResolver`） |
| `src/main/java/com/finagent/agent/AgentStreamService.java` | `startStream` 先 `detect` 再决定 `hitl`；歧义时打日志（原因码 + 问题） |
| 前端 `ApprovalCard.vue` / `labels.ts` | 文案从「人工审批」改成「确认理解」；提示改成「问题里有多个可能的主体或年份」 |
| `src/test/java/com/finagent/agent/AmbiguityDetectorTest.java` | 新增 7 例 |
| `AgentStreamE2ETest` | 新增 1 例：问题点了年份 → 不弹审批、直接 final |

### 11.5 验收与实测

| 项 | 结果 |
| --- | --- |
| 全量非 e2e | **638 通过 / 0 失败 / 1 跳过**（630 + 7 检测器 + 1 e2e） |
| 真链路 · 不歧义 | `复杂问题：2025年营业总收入和净利润分别是多少` → events = stage×4 → delta×11 → stage×2 → final，**无 approval**，`status=DONE`、citations=3、facts=2、25.5 s |
| 真链路 · 歧义 | `复杂问题：营业总收入和净利润分别是多少`（语料有 2024/2025 两份）→ stage×4 → **approval@7.6 s**；resume APPROVE → `status=DONE`、citations=3 |
| 前端 | `npm run build` 通过（ChatView 136.38 kB / gzip 45.91 kB） |

### 11.6 已知不完美

- 判定只看「有没有点主体/年份」，看不懂「这次问的主体是不是作用域里那一家」——那是 QU 的活，判定阶段还没有 QU 结果。
- 用户如果用昵称（「东财」）问，`mentionsCompany` 认不出 → 会被判成歧义（弹一次确认，成本可接受）。
- 弹确认时没有把「为什么问你」的原因码传给前端：`AgentRunService.RunView` 里没有字段，加字段要动 `AgentRun` 与多个测试；文案改用固定解释。

## 12. P4：切换与删除（2026-10-29 21:1x~22:1x）

### 12.1 做法：换实现不换协议

| 端点 | P4 之后 | 为什么 |
| --- | --- | --- |
| `POST /api/chat` | 内部调 `AgentStreamService.ask`（图，`hitl=false`） | URL 是前端 / e2e / README 的既有契约 |
| `POST /api/chat/stream` | 内部调 `AgentStreamService.startStream`（图，歧义时弹审批） | 事件协议仍是 C15 + C16，只是 `stage.phase` 从四个中文阶段名换成节点 id |
| `POST /api/agent/runs/{id}/resume` | 保留 | 聊天流弹审批后也要续跑，前端只有这一个续跑入口 |
| `GET /api/agent/runs/{id}` | 保留 | 刷新页面看进度；也是排障入口 |

`finagent.agent.enabled` 不再是入口闸门（`AgentRunService.create` 里的检查已删）：图是唯一引擎，默认值 `false` 只是历史遗留配置项。

### 12.2 为什么这一步不能只删代码

删 `ChatService` 之前必须先把四件「直链有、图没有」的事补上，否则用户看到的是功能回退：

| 能力 | 直链原本在哪 | P4 搬到哪 |
| --- | --- | --- |
| 会话落库 / 上下文 / 记忆命令 / 摘要 | `ChatService.finishTurn` | P1 的 `TurnRecorder`，由 `AgentStreamService` 在 final 之前调用 |
| facts / citations / confidence / note | `AnswerResult` 直接进 `ChatResponse` | P2 的 `AnswerStreamBridge.result`，`synthesize` 优先用它 |
| trace 落盘 | `ChatTraceWriter.write` | P2 的 `AnswerStreamBridge.retrievalTrace` + `AgentStreamService.trace` |
| 证据先行（meta） | 检索完就发 | 图的 `verify` 节点完成后由 `progress` 发 `metaEvidence` |

### 12.3 三处语义务必对齐

1. **空作用域仍然 409**：图的 `understand` 会捕获 `ScopeEmptyException` 并写成 `scope_empty` 警告、状态 REJECTED；同步接口必须把它翻回 HTTP 409（`AgentStreamService.ask` 里显式抛），否则客户端会把「没权限」当成「答不出来」。
2. **首屏不能等一次 LLM**：`understand` 节点本身要跑 QU（约 4 秒），所以 `startStream` 第一行就先发一条 `stage(understand)`，节点完成时不再重发。
3. **final 是运行视图**：`{runId,status,answer,warnings,plan,evidence,steps,currentNode,decision,decisionNote,error,response}`，标准 `ChatResponse` 嵌在 `response` 里（C18 第 5 条）。

### 12.4 删除清单

| 删除 | 理由 |
| --- | --- |
| `src/main/java/com/finagent/chat/ChatService.java` | 两条路径合并为一条；它的职责已拆到 `TurnRecorder`（落库）、`AgentStreamService`（装配）、图的节点（检索与生成） |
| `src/test/java/com/finagent/chat/ChatServiceTest.java`（25 例） | 断言的是直链编排；等价断言分布在 `AgentStreamServiceTest` / `AgentStreamE2ETest` / `AnalysisGraphTest` |
| `src/test/java/com/finagent/chat/ChatServiceStageTest.java`（4 例） | 同上；阶段顺序改由 `AnalysisGraphTest` 与 e2e 覆盖 |

### 12.5 前端改动

- 删「深度分析」开关（`deep` / `readDeep` / `toggleDeep` / localStorage 键 `finagent.deepAnalysis`）与 `askAgent` 分支：只有一条链路，开关没有意义。
- `askWithStream` 接管 `approval` 事件：把审批视图并进 `emptyAgent(question)` 后写进气泡，返回 `null`（调用方知道「停在确认」而不是失败）。
- `src/api/chat.ts`：新增 `onApproval`；`final` 事件取 `payload.response` 作为 `ChatResponse`（老后端没有 `response` 时按原样吃）。
- `decide` 不变：审批后续跑仍走 `POST /api/agent/runs/{id}/resume`。

### 12.6 验收与实测

| 项 | 结果 |
| --- | --- |
| 后端全量非 e2e | **610 通过 / 0 失败 / 1 跳过**（638 − 29 条已删的 `ChatService` 用例 + 1 条 resume 回归，其余零回归） |
| `ChatStreamE2ETest` | 见 §12.7；断言改为「图节点顺序 + meta 早于首字 + `response.citations` 非空」 |
| `ChatControllerTest` | 6/6（mock 从 `ChatService` 换成 `AgentStreamService`） |
| `RestAuthContractTest` | 6/6（空作用域仍 409，正文无数字） |
| 前端 | `vitest` 24/24、`eslint` 0 error、`npm run build` 通过（ChatView 135.40 kB / gzip 45.57 kB） |

### 12.7 已知取舍

- 同步接口 `/api/chat` 用 `hitl=false`：没有通道把审批交回去，所以歧义问题在同步接口里不会被拦（前端只用流式，不受影响）。
- `stage.phase` 从 C16 的四个中文阶段名变成节点 id（`understand` / `plan` / `retrieve` / `verify` / `answer` / `cite_check` / `respond_insufficient`），C16 的「四阶段」表述在 C18 里被替换。

### 12.8 真链路追加抓到的 bug：resume 还读 `finagent.agent.enabled`

**现象**：聊天流弹「确认理解」→ 点「通过，生成答案」→ `event:error {"code":"INTERNAL"}`，HTTP 500。

**原因**：P4 只删了 `AgentRunService.create` 里的 enabled 检查，`resume(...)` 里还留着同一条检查；默认配置 `finagent.agent.enabled=false` → 400/500。单元测试全部用 `enabled=true` 构造，整类 26 条断言都碰不到。

**修法**：删掉 resume 里的检查；补 `AgentHitlDbTest.resumeWorksWhileSwitchIsOff`（`enabled=false` 也要能停下来再续跑）。

**教训**：「入口闸门」往往是**多个入口**各写一次，删的时候要搜全仓，而不是只删第一个报错的那处。

**验证（curl 真链路）**：`营业总收入和净利润分别是多少` → `stage×4 → meta → approval`（runId `13320f07…`，WAITING_APPROVAL，8 条候选）→ resume APPROVE → `delta×15 → stage×2 → final`，`status=DONE`、`response.citations=3`、`response.facts=2`、答案「营业总收入为160.68亿元…净利润为120.85亿元…」。