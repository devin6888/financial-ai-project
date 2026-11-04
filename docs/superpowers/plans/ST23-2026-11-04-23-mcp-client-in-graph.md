# 阶段 23：把行情 MCP Server 接进 Agent 图（设计稿）

> 状态：**待评审**（2026-11-04）。评审通过后再动代码；本文只描述要做什么、为什么这么做、怎么验收。
> 关联：`mcp-market-server/`（Python 参考实现）、`../financial-mcp-server4j/`（Spring AI 版 MCP Server，与主项目平级）、`docs/关键契约.md`、`docs/superpowers/plans/ST11-2026-10-28-11-agent-workflow.md`（图与状态契约 C17）。

---

## 0. 一句话结论

在 Agent 图里新增一条与「年报检索」并列的支路，并在 `dispatch` 处分流、在 `merge` 处合流：行情问题只走 MCP（`market → answer_market → END`），年报问题只走检索，两者都要时**并行取数、合流后写一份答案**。行情不查库、不产证据、不参与引用编号，只把带时间戳的快照交给答案层。

| 维度 | 本阶段做 | 本阶段不做 |
| --- | --- | --- |
| 问题类型 | 单公司实时行情：最新价、涨跌幅、市值、PE/PB、换手、成交额、近 N 日走势 | 板块/指数/基金/港股美股；盘口、分时、资金流 |
| 与年报的关系 | 三条路径：只行情 / 只检索 / **两者并行后合流写一份答案** | 多标的对比；需要计算（市值 ÷ 净利润）的混合留到阶段 24 |
| 调用方式 | **模型只选工具、填参数**（自建不带 `ToolCallingAdvisor` 的 `ChatClient`），执行与数字渲染都在我们这侧；参数非法或模型没调则回落到规则调用 | 让模型转述数字、让模型决定「要不要查行情」、让框架自动执行工具 |
| 数据落库 | 不落库，只把时间戳写进答案 | 行情快照表、历史行情缓存 |
| 前端 | `final` 载荷里的 `warnings` 标注「实时行情，非年报口径」 | 专门的行情卡片与走势图 |

---

## 1. 为什么必须新开一条支路

### 1.1 现有链路是「证据驱动」的，三处硬闸门都假设数据来自 PDF

```
understand → plan → approve → retrieve → verify ─┬─ 够证据 ─▶ compute → answer → cite_check → END
                                                 └─ 不够 ──▶ rewrite / respond_insufficient
```

| 闸门 | 位置 | 它假设什么 | 行情问题会发生什么 |
| --- | --- | --- | --- |
| 证据数量 | `RealAnalysisNodes.verify()` 第 460 行 `hits.size() < props.minEvidence()` | 答案必须由若干 chunk 支撑 | 行情问题检索不到年报证据 → 判 `insufficient` |
| 引用编号 | `answer` 节点的提示词只允许 `[E1]` 形状 | 每个数字都能指向一块证据 | 行情数字无出处可指 → 模型要么编编号、要么不给数字 |
| 引用校验 | `RealAnalysisNodes.citeCheck()` 第 702-722 行 | 答案里的编号必须落在本轮证据包内 | 无编号 → 写 `no_citation_marker` → 回边重写一次，仍失败 |

### 1.2 为什么不能把行情塞进 `hits`

`hits` 的元素是 `RetrievedChunk` / `ContextBlock` 的形状：`evidenceNo / chunkId / parentId / documentId / docTitle / sectionPath / pageStart / pageEnd / chunkType / caption / unit / headersJson / bboxJson / score / chunkIndex / tokenCount / role`（见 `src/main/java/com/finagent/context/ContextBlock.java:14-35`）。行情没有 `chunkId`、没有页码、没有父块，塞进去要么全填假值（后续任何按 `chunkId` 回库的代码都会拿到空正文，实测会在 `ContextBuilder` 与 `metricHit` 两处静默失效），要么给 `ContextBlock` 加一批 `null` 分支 —— 后者会污染所有已有的证据路径。

### 1.3 三条路的取舍

| 方案 | 做法 | 为什么不用 |
| --- | --- | --- |
| A. 塞进证据链 | 把行情包装成 chunk 形状的 `hit` | 语义造假：证据的定义是「PDF 原文的一页」，行情是外部接口的一次快照，混在一起后 `[E]` 到底指什么就说不清了 |
| B. 绕过 verify | 在 `verify` 里加一个「有行情就放行」的分支 | `verify` 要判的是「证据够不够」，让它关心行情就得读 `MARKET_*` 键，两个关注点绞在一起；且 `answer` 仍会要求 `[E]`，问题只是往后挪了一步 |
| **C. 新开支路（采用）** | `plan` 三分流，新节点自己取数、自己渲染、直接到 `END` | 代价是图多两个节点、两处标签 switch；换来的是「证据链不被污染」与「行情失败不影响年报问答」 |

---

## 2. 三个关键决策

### 2.1 决策一：意图判定用规则，不用模型

| 维度 | LLM 判定 | 规则判定（采用） |
| --- | --- | --- |
| 延迟 | 每轮多一次模型调用（本项目 QU 实测约 4 秒，2026-10-30 本机，qwen-plus） | 微秒级 |
| 可解释 | 只能看模型输出 | 命中哪个词、为什么没命中，可打印、可单测 |
| 误判代价 | 判错就多走一条完全不同的链路，且难复现 | 词表可审计，误判可定向修 |
| 覆盖 | 能理解「东财现在多少钱」这类口语 | 需要维护词表 |

判定送进 `understand` 节点（QU 之后、作用域解析之前），理由是：**行情问题通常没有入库文档**，如果先解析作用域，`ScopeEmptyException` 会在 `RealAnalysisNodes.understand()` 第 186-196 行提前 return，行情永远进不去。

### 2.2 决策二：模型选工具，我们执行，模板渲染数字

先纠正一个 2.0 的架构事实：**工具不是模型执行的，是 `ChatClient` 的 advisor 执行的**。`ChatClientAutoConfiguration` 注册了 `ToolCallingAdvisor.Builder` 这个 Bean，`chatClientBuilder(...)` 把它装进每个 `ChatClient`（开关 `spring.ai.chat.client.tool-calling.enabled`，javap 实测默认 **true**）；`ToolCallingAdvisor.adviseCall` 里是那个循环：调模型 → 取 `AssistantMessage.toolCalls()` → `toolCallingManager.executeToolCalls(prompt, response)` → 把 `ToolResponseMessage` 追加进对话 → 再调模型，直到模型不再要工具（`ToolCallLimits` + `ToolCallLimitBehavior.THROW / RETURN_ERROR_RESPONSE` 防死循环）。

也就是说：1.0 里「模型自己执行工具」的开关（`ToolCallingChatOptions#internalToolExecutionEnabled`）在 2.0.1 确实没了，但执行被搬到了 advisor —— **不装 advisor，模型返回的 `toolCalls` 就原样留着**，由我们执行。三种落法：

| 维度 | A 默认（advisor 自动执行，模型写答案） | B 默认 + `ToolCallback` 录制 | C 关 advisor，自己执行（采用） |
| --- | --- | --- | --- |
| 谁执行工具 | 框架 advisor | 框架 advisor | **我们**（`McpSyncClient.callTool`） |
| 模型往返 | 2 次（选工具 + 写答案） | 2 次 | **1 次**（只选工具） |
| 数字来源 | 模型转述（`297457357650` → 「约 2975 亿」） | 录制里的原始返回 | 原始返回 |
| 代码量 | 最少 | +40 行装饰器 | +60 行（解析 `tool_calls` 的 JSON 参数、处理「没调工具」） |
| 排障 | 看不出调了什么 | 能看 | 能看，且是结构化的 `toolCalls` |

选 C 的理由：行情答案本来就要模板渲染（§9），让模型再写一遍纯属多一次 2~4 秒的往返；而且 `ChatResponse.getResult().getOutput().getToolCalls()` 里直接就是「工具名 + 参数 JSON」，比套一层录制更直白。

实现要点：

- **按实例关**：`ChatClient.builder(chatModel).defaultAdvisors(...)` 自建一个只给 `market` 用的 client，不改全局属性 —— 检索支路的答案生成不该被工具循环影响；
- **只认一个 call**：`parallel_tool_calls` 打开时 `toolCalls` 可能多于一个，取第一个名字在允许集合内、参数校验通过的，其余忽略；
- **`toolCalls` 为空就当失败**：模型没调工具（直接开始编）时立刻回落规则调用，不重试、不追问；
- 仍保留 `MockMcpToolCallback` 之外的 `MarketGateway` 兜底（§8），模型这条路是增强不是依赖。

### 2.3 决策三：用 starter 自动装配，但懒初始化

| 维度 | 手写 `McpClient.sync(transport)` | starter 自动装配（采用） |
| --- | --- | --- |
| 代码量 | 多约 40 行（建 transport、建 client、注册关闭钩子） | 一个 yml 块 |
| 生命周期 | 自己管 | `CloseableMcpSyncClients` 在容器关闭时关子进程 |
| 可测性 | 可以注入任意 client | 可以，但拿不到「按连接名索引」的 client（见 §7.5） |

自动装配的默认值是 `initialized=true`（`McpClientCommonProperties` 实测默认 `true`），含义是**启动时就拉起子进程并握手**。本设计改成 `false`，在首次真正要用时再 `initialize()`：

- 冷启动实测 **3666 ms**（2026-11-04 本机，`java -jar financial-mcp-server4j-0.1.0.jar`，从 spawn 到 `initialize()` 返回，含 JVM 启动 + Spring 上下文 + JSON-RPC 握手）。把它放进应用启动路径，等于每次 `mvn spring-boot:run` 都多等 3.7 秒；
- 更糟的是硬依赖：jar 没打包、路径写错、JVM 不在 PATH，应用直接起不来。**行情是可选能力，不该有这个权力**。

---

## 3. 图拓扑

三条支路，行情与检索在 `dispatch` 处分流、在 `merge` 处合流：

```
START → understand → plan ─┬ REFUSE ─────────────────────────────────▶ respond_insufficient → END
                           ├ review ─▶ approve ─┐
                           └ direct ────────────┤
                                                ▼
                                            dispatch ─┬ MARKET_ONLY ────────────▶ market ─┐
                                                      ├ BOTH ─▶ fanout ─┬ market ────────┤
                                                      │                 └ retrieve ─┐    │
                                                      └ RETRIEVAL ──────▶ retrieve ─┐│    │
                                                                       (cond: refuse) ││    │
                                                                          ▼           ▼▼    ▼
                                                                   respond_insufficient  merge
                                                                                           │
                                            merge (cond) ─┬ 只有行情 ─▶ answer_market ─▶ END
                                                          └ 有证据 ──▶ verify ─┬ 够 ─▶ compute ─▶ answer ─▶ cite_check ─▶ END
                                                                               ├ 重试 ─▶ rewrite ─▶ retrieve
                                                                               └ 不足 ─▶ respond_insufficient
```

### 3.1 节点与边的增量

| 位置 | 现在 | 改成 |
| --- | --- | --- |
| `AnalysisGraph` 常量 | 10 个节点常量 | 新增 `NODE_DISPATCH = "dispatch"`、`NODE_FANOUT = "fanout"`、`NODE_MARKET = "market"`、`NODE_MERGE = "merge"`、`NODE_ANSWER_MARKET = "answer_market"` |
| `plan` 条件边 | `refuse` / `review` 两路 | 三路：`refuse` / `review` / `direct`，`review` 与 `direct` 都进 `NODE_DISPATCH` |
| `dispatch` 条件边（新） | — | `MARKET_ONLY → market` / `BOTH → fanout` / `RETRIEVAL_ONLY → retrieve` |
| `fanout`（新） | — | 空节点，只用于分叉：`fanout → market`、`fanout → retrieve` 两条普通边 |
| `retrieve` 条件边 | 出口 `verify` | 出口改成 `merge`（`refuseReason` 非空仍去 `respond_insufficient`） |
| `merge`（新） | — | 入边 `market → merge`、`retrieve → merge`；条件边 `market_only → answer_market` / `has_evidence → verify` |
| `AnalysisNodes` 接口 | 9 个方法 | 新增 `dispatch` / `market` / `merge` / `answerMarket` 四个方法 |

`dispatch` 与 `fanout` 都是空节点（返回 `Map.of()`），存在的理由不同：`dispatch` 是**决策点**（拿 `MARKET_INTENT` 与「是否需要检索」算路），`fanout` 是**拓扑点**（LangGraph4j 的一条条件边只能指向一个目标，而 `BOTH` 要同时进两个节点，只能经过一个空节点再发散出两条普通边）。

### 3.2 库语义实测：join 会等，但并行不会自己来

这套拓扑依赖 LangGraph4j **1.9.7** 的两个语义，我写了一次性探针（`GraphFanoutProbeTest`，跑完即删）实测，结论不是推测：

| 问题 | 实测结果 | 对设计的影响 |
| --- | --- | --- |
| 一个节点发两条边，汇合节点跑几次？ | **1 次**，且状态里两支的写入都在（`mergeRuns=1`、`dense=[D] market=[M]`） | 有真正的 join 屏障，不需要自己计数 |
| 汇合节点看到的线程 | `ForkJoinPool.commonPool-worker-1` 同一个线程跑完两支 | 库只是「都跑完再跑 merge」，不等于并行 |
| 两个各 `sleep(400)` 的普通节点（`node_async(lambda)`）总耗时 | **812 ms**（≈ 400+400） | **串行**。现在 `AnalysisGraph.node(...)` 全是这种写法 |
| 同样两个节点改成返回 `CompletableFuture.supplyAsync(...)` | **549 ms**，两支在 t=542 ms 同一时刻开始，分别在 worker-2 / worker-3 | 真并行：**节点必须自己把阻塞工作扔到别的线程**，否则画边也没用 |
| 条件边跳过 `market` 时，`merge` 会不会卡死或睡等？ | 不卡，仍跑 **1 次**，`market=[]` | 可以放心用条件边裁剪支路 |

两条由此导出的硬约束：

1. **并发要靠节点动作返回真实 future**，而且线程池不能是 `commonPool`（LLM 与 JDBC 都是阻塞调用，会把公共池占满）。新增一个 `finagent.agent.parallel-pool` 的 `ThreadPoolTaskExecutor`（core 4 / max 8 / 队列有界），两个支路节点各自 `supplyAsync(..., parallelPool)`。
2. **`TenantContext` 不跟着线程走**。`AnalysisGraph.node(...)` 的写法是 `set(user)` → 建 future → `finally clear()`（`AnalysisGraph.java:145-157`），一旦节点返回未完成的 future，`clear()` 会在业务真正执行前就发生，worker 线程里 `TenantContext.require()` 直接抛 `UnauthorizedException(AUTH_REQUIRED)`（`TenantContext.java:34-40`）。所以异步节点必须自己捕获 `CurrentUser`，在 worker 内 `set` / `finally clear`。这一条如果漏了，表现为「行情查询偶发 401」，很难查。

### 3.3 为什么 `merge` 合并的是「输入」，不是「两路答案」

rerank 分数是**候选内相对值**（同一条 chunk 在不同候选集里分数会变），行情快照根本没有可比的 score；两者不存在可融合的排序。若各写一份答案再拼，会出现三个问题：同一个问题两个结论、引用编号体系对不上（行情没有 `[E]`）、以及「哪份答案该被裁掉」没有判据。

所以 `merge` 只做一件事：把 `ContextPackage`（PDF 证据）与 `MarketSnapshot`（行情快照 + `asOf`）放进同一个状态，交给 `answer` **一次**写。答案里 PDF 部分带 `[E1]`，行情部分带时间戳、不带编号。

### 3.4 为什么拆 `market` / `answer_market` 两个节点

和 `compute` / `answer` 的拆法一致：**取数与措辞分开**。

- `market` 只负责「拿到一份快照或一个错误」，任何异常都转成 `MARKET_ERROR`，它不写字；
- `answer_market` 只负责「把快照或错误渲染成人话」，它不联网、不重试。

好处是失败路径不需要第三个节点：`market` 失败 → `merge` → `answer_market` 渲染道歉文案 → `END`，图的拓扑不变。

### 3.5 为什么 `answer_market` 直接到 `END`，不接 `cite_check`

`citeCheck()` 会对「答案里一条编号都没有」写 `no_citation_marker`，而 `cite_check` 的条件边看到非空问题列表就会回 `answer` 重写一次 —— 行情答案永远不会有编号，等于每轮白跑一遍模型，还可能把模板答案改成自由发挥。行情不参与引用体系，就不该进校验节点。

### 3.6 可以不做图并行的理由（保留的退路）

检索支路实测 **388~623 ms**（`docs/阶段4-检索报告.md`：dense 154~235、sparse 1~10、fuse 0~1、rerank 208~403），行情热调用 **38~42 ms**。并行最多省 40 ms，却引入线程池、线程上下文传递与 trace 时间线交错三处复杂度。

真正省时间的不是并行，是**分流**：纯行情问题不走检索，省掉 600 ms 与一次 embedding + rerank 计费。所以如果并行实现拖慢交付，可以退化成「`dispatch` 仍然分流，`fanout` 只连检索、`market` 在 `retrieve` 之后串行执行」——拓扑与状态契约不变，只换两条边。

---

## 4. 状态契约（新增键）

`AnalysisState` 的硬规则不变：**只放 JDK 可序列化类型**（默认序列化器是 `ObjectStreamStateSerializer`，放 record 或 `float[]` 会在写检查点时炸）。因此行情不存 `CallToolResult`，只存剥出来的 `Map` / `String`。

| 键 | 类型 | 通道 | 写入者 | 读者 | 说明 |
| --- | --- | --- | --- | --- | --- |
| `MARKET_INTENT` | Boolean | base(false) | understand | plan | 是否走行情支路 |
| `MARKET_PLAN` | `Map<String,Object>` | base(Map.of()) | understand | market / answer_market | 见下表 |
| `MARKET_AS_OF` | String | base("") | market | answer_market | 数据时间（ISO-8601 带 +08:00），渲染时必须出现 |
| `MARKET_DATA` | `Map<String,Object>` | base(Map.of()) | market | answer_market | 已剥成基本类型的快照 |
| `MARKET_ERROR` | String | base("") | market | answer_market | 空 = 成功；非空 = 失败原因（人类可读） |
| `MARKET_TOOL` | String | base("") | market | trace / 排障 | 这次实际用的工具名；配合 `codeSource` 判断「模型选的还是兜底的」 |

`MARKET_PLAN` 的字段：

| 字段 | 例 | 谁写 | 说明 |
| --- | --- | --- | --- |
| `tool` | `get_quote` / `get_history` | understand | 由意图词决定（「走势/最近」→ history，否则 quote） |
| `code` | `300059` | understand | 6 位 A 股代码 |
| `name` | `东方财富` | understand | 用于文案；可能为空（用户只给代码时） |
| `days` | `20` | understand | 仅 history 用，默认 20，范围 1~120 |
| `codeSource` | `QUESTION` / `COMPANY_TABLE` / `MCP_SEARCH` | understand | 代码从哪来的，排障与评测要用 |

这些键全部写进 `AnalysisState.SCHEMA`；`Map.ofEntries` 目前 41 个条目，加 6 个仍在可读范围。

---

## 5. 意图判定 `MarketIntentDetector`

位置：`src/main/java/com/finagent/agent/MarketIntentDetector.java`（纯函数，无 Spring 依赖，风格与 `OffTopicDetector` 一致）。

### 5.1 判定规则

```java
/** 返回空表示不是行情问题；非空时顺便给出工具与天数。 */
static MarketPlan detect(String question, List<String> metrics, int[] yearRange)
```

| 条件 | 结果 |
| --- | --- |
| 空问题 | 不进入（交给原有的拒答路径） |
| 命中「年报/季报/半年报/报告/披露/第 N 页」等文档词 | 不进入（「年报里披露的市值」是检索问题） |
| 指定了年份（`yearRange` 任一 > 0） | 不进入（历史数据问的是报表，不是实时行情） |
| 命中走势词：`走势 / 最近 / 近 \d+ 天 / 涨了 / 跌了 / K 线` | `get_history`，`days` 取问题里的数字，否则 20 |
| 命中快照词：`股价 / 价格 / 多少钱 / 市值 / 市盈率 / PE / PB / 换手 / 成交额 / 涨跌幅 / 现在 / 今天 / 实时 / 盘` | `get_quote` |
| 以上都不命中 | 不进入 |

### 5.2 两个防误判设计

1. **文档词优先于行情词**：「2024 年报里写的市盈率是多少」同时命中 `市盈率` 与 `年报`，必须走检索 —— 这条规则让两者冲突时偏保守。
2. **有年份就不走行情**：`2024 年股价` 是一个不存在的东西（要历史行情），当前只支持快照与近 120 日，宁可拒答也不给错数。

### 5.3 单测（表驱动，至少 20 例）

| 输入 | 期望 | 断言点 |
| --- | --- | --- |
| `东方财富现在股价多少` | 进入，`get_quote` | 工具选择；`code` 留空，交给 §6 解析 |
| `300059 的市值` | 进入，`get_quote` | code 直接来自问题 |
| `东方财富最近 10 天走势` | 进入，`get_history`，days=10 | 天数解析 |
| `2024 年东方财富的市盈率` | 不进入 | 年份优先 |
| `年报里披露的股价` | 不进入 | 文档词优先 |
| `帮我总结一下营收增长的原因` | 不进入 | 无行情词 |
| `今天天气怎么样` | 不进入 | 无金融词（同时会被 `OffTopicDetector` 拦） |

---

## 6. 公司解析 `MarketCompanyResolver`

位置：`src/main/java/com/finagent/agent/MarketCompanyResolver.java`（需要查库，所以是 Spring 组件，但判定逻辑是纯函数）。

**不能复用 `CompanyResolver`**：它只在本轮作用域的 `ResolvedScope.documents()` 里找公司（`CompanyResolver.resolve(codes, mentions, scope)`），而行情问题的公司往往根本没入库 —— 复用会让「东方财富现在多少钱」在只上传了茅台的租户里被判成「公司不在范围内」。

### 6.1 三级解析顺序

| 顺序 | 来源 | 命中条件 | `codeSource` | 风险 |
| --- | --- | --- | --- | --- |
| ① | 问题原文里的 6 位数字 | 正则 `(?<!\d)(60\d{4}|68\d{4}|00\d{4}|30\d{4})(?!\d)` | `QUESTION` | 无（公开行情，不需要租户边界） |
| ② | 本租户 `company` 表 | 名称归一化后双向包含（复用 `CompanyResolver.normalize`） | `COMPANY_TABLE` | 只在已入库公司里找得到 |
| ③ | MCP `search_stock` | 返回**恰好 1** 条 A 股候选 | `MCP_SEARCH` | 东财搜索会返回近似名，所以只接受唯一命中 |

### 6.2 失败与歧义

| 情况 | 处理 | `MARKET_ERROR` 文案 |
| --- | --- | --- |
| 三级都没命中 | `answer_market` 出提示 | `没听出是哪家公司的股票，请给出 6 位代码（如 300059）` |
| ③ 返回 ≥2 条候选 | 不猜，列候选 | `找到多个可能的股票：东方财富(300059)、东方财富证券(…)` |
| 问题里有代码但格式不对（如 5 位） | 不进入行情支路，退回检索 | — |

**为什么第 ③ 级只接受唯一命中**：行情数字错了，用户从答案里看不出来。宁可多问一句「你说的是哪只」，也不要给出另一家公司的股价。

---

## 7. MCP client 装配

### 7.1 依赖

```xml
<dependency>
  <groupId>org.springframework.ai</groupId>
  <artifactId>spring-ai-starter-mcp-client</artifactId>
</dependency>
```

版本由已有的 `spring-ai-bom:2.0.1` 管理；传递依赖是 MCP Java SDK 2.0.0（含 `mcp-json-jackson3`，与 Boot 4 的 Jackson 3 对齐）。

### 7.2 配置（`application.yml`）

```yaml
spring:
  ai:
    mcp:
      client:
        enabled: true
        name: finagent-agent
        version: 1.0.0
        # 关键：不在启动时拉起子进程（默认 true）
        initialized: false
        request-timeout: 8s
        toolcallback:
          enabled: false          # 本阶段不让任何 ChatClient 拿到行情工具
        stdio:
          connections:
            finagent-market:
              command: java
              args:
                - -jar
                - ${FINAGENT_MARKET_JAR:../financial-mcp-server4j/target/financial-mcp-server4j-0.1.0.jar}
              env:
                JAVA_TOOL_OPTIONS: -Dfile.encoding=UTF-8
```

| 配置项 | 值 | 为什么 |
| --- | --- | --- |
| `initialized` | `false` | 见 §2.3；改为首次调用时 `initialize()` |
| `request-timeout` | `8s` | 必须大于 server 端单次 HTTP 超时（见 §7.4 的预算表） |
| `toolcallback.enabled` | `false` | 防「模型自主选工具」误入 |
| `args` 里的 jar 路径 | 环境变量可覆盖 | 相对路径依赖工作目录（`mvn spring-boot:run` 与 IDE 不同），必须可覆盖 |

### 7.3 实测数字（2026-11-04 本机，Windows，Python 3.12 官方 SDK 拉起 jar）

| 指标 | 值 | 口径 |
| --- | --- | --- |
| 冷启动（spawn + `initialize()`） | 3666 ms | 1 次，`java -jar financial-mcp-server4j-0.1.0.jar`，含 JVM + Spring 上下文 + 握手 |
| `get_quote` 首次 | 700 ms | 1 次，含 TLS 握手与 JIT |
| `get_quote` 热调用 | 42 / 38 ms | 连续 2 次 |
| `get_history(20)` | 161 ms | 1 次 |

结论：**冷启动必须移出请求路径，也不能放在应用启动路径上**。做法是 `ApplicationReadyEvent` 之后异步预热（一个后台线程调 `initialize()`），失败只记日志，不影响主流程。

### 7.4 超时预算

| 层 | 建议值 | 现状 |
| --- | --- | --- |
| 东财 HTTP（MCP Server 内） | 5 s | 现在默认 10 s（`finagent.market.http-timeout-seconds`）→ **要改小** |
| MCP client 请求 | 8 s | 默认 20 s → 要改 |
| 图节点整体 | 由 client 超时兜住，不额外加 | — |

现在的 10 s / 20 s 组合会让「东财卡住」时用户等 20 秒，且 client 超时晚于 server 超时，日志里只会看到 client 侧超时、看不到真实原因。

### 7.5 已知限制：拿不到「按连接名索引」的 client

`McpClientAutoConfiguration.mcpSyncClients(...)` 返回的是 `List<McpSyncClient>`，没有名字映射。只有一个连接时无所谓（取第一个并断言 `size()==1`），接第二个 MCP Server 时就必须改成手写 `McpClient.sync(transport)` 或按 `initialize()` 返回的 `serverInfo().name()` 匹配。这条写进未决问题。

### 7.6 降级

`MarketGateway` 是一个接口、两个实现（`@ConditionalOnMissingBean` 保证兜底只有一个）：

| 实现 | 生效条件 | 行为 |
| --- | --- | --- |
| `McpMarketGateway` | `spring.ai.mcp.client.enabled=true` 且至少有一个 client | 懒初始化 + 调用 + 超时 + 错误映射 |
| `UnavailableMarketGateway` | 其余情况 | 直接返回 `MARKET_ERROR`，文案「行情服务未启用」 |

加上 `finagent.market.enabled`（默认 `true`）作为业务开关：关掉时 `MarketIntentDetector` 不进入行情支路，问题回到原来的检索路径。

---

## 8. `market` 节点

两段，模型在前、规则兜底在后：**模型只输出「调哪个工具、什么参数」，我们自己执行，数字从原始返回渲染**。

```java
@Override
public Map<String, Object> market(AnalysisState state) {
    MarketPlan plan = state.marketPlan();          // 规则先算一份，永远有兜底
    ToolCall picked = pickByModel(state, plan);     // 见下：一次模型往返，只拿 toolCalls
    MarketGateway.Snapshot snap;
    boolean byModel = picked != null;
    try {
        snap = byModel ? gateway.execute(picked)   // 我们执行：McpSyncClient.callTool
                        : gateway.call(plan.tool(), plan.code(), plan.days());
    } catch (MarketUnavailableException e) {
        return Map.of(AnalysisState.MARKET_ERROR, "行情服务暂时不可用，请稍后再试");
    } catch (MarketApiException e) {
        return Map.of(AnalysisState.MARKET_ERROR, e.getMessage());
    }
    Map<String, Object> patch = new LinkedHashMap<>();
    patch.put(AnalysisState.MARKET_DATA, snap.data());
    patch.put(AnalysisState.MARKET_AS_OF, snap.asOf());
    patch.put(AnalysisState.MARKET_TOOL, byModel ? picked.name() : plan.tool());
    patch.put(AnalysisState.WARNINGS, List.of(WARNING_MARKET_REALTIME));
    return patch;
}

/** 只让模型挑工具：不装 ToolCallingAdvisor，所以工具不会被自动执行。 */
private ToolCall pickByModel(AnalysisState state, MarketPlan plan) {
    try {
        ChatResponse resp = marketClient.prompt()
                .system(MARKET_SYSTEM_PROMPT)      // 「必须先调工具取数，不要自己回答」
                .user(marketPrompt(state, plan))   // 问题 + 候选代码 + 当天日期
                .toolCallbacks(mcpToolCallbacks)   // 只是把工具定义发给模型
                .call().chatResponse();
        List<ToolCall> calls = resp.getResult().getOutput().getToolCalls();
        for (ToolCall call : calls) {
            if (allowed(call) && validArgs(call, plan)) {
                return call;
            }
        }
        return null;                               // 没调 / 调错 → 兜底
    } catch (RuntimeException e) {
        log.warn("行情 tool 选择失败，回退规则调用：{}", e.toString());
        return null;
    }
}
```

关键在于 `marketClient` 是**自建**的：`ChatClient.builder(chatModel).defaultAdvisors(...)`，不经过自动装配，因此不带 `ToolCallingAdvisor`（§2.2）。工具定义照常发给模型，模型照常回 `tool_calls`，只是没人替它执行。

四个刻意的取舍：

1. **只要 `toolCalls`，不要正文**。这次调用的唯一目的是让模型挑工具、填参数；让它顺便写答案就等于把数字交给它转述（`297457357650` 会被写成「约 2975 亿」）。答案由 §9 的模板渲染。
2. **`validArgs` 校验参数**：`code` 必须是 6 位数字且等于 `plan.code()`（或落在候选集内），`days` ∈ [1,120]，工具名 ∈ {`get_quote`,`get_history`,`search_stock`}。不合法不放行 —— 模型把 300059 说成 600519 时，输出看起来毫无异常。
3. **规则兜底永远在**。模型这条路是「增强」不是「依赖」：没调工具、超时、返回乱码，都退到 `MarketGateway` 的确定性调用，用户感知不到差别。
4. **`MARKET_TOOL` 落状态**，trace 里能看出这次是模型选的还是兜底的 —— 线上判断「模型路由到底有没有在工作」全靠它。

`MarketUnavailableException` 对应「子进程没起来 / 握手失败」，不把技术细节甩给用户；`MarketApiException` 对应工具内的业务错误（代码不合法 / 未返回数据），原文已经是人话。
```

四个刻意的取舍：

1. **模型正文丢掉**。这次调用唯一目的是让模型挑工具、填参数；让它顺便写答案就等于把数字交给它转述（`297457357650` 会被写成「约 2975 亿」）。答案由 §9 的模板渲染。
2. **`isValid` 校验参数**：`code` 必须是 6 位数字且等于 `plan.code()`（或落在候选集内），`days` ∈ [1,120]，`tool` ∈ {`get_quote`,`get_history`,`search_stock`}。不合法不放行 —— 模型把 300059 说成 600519 时，输出看起来毫无异常。
3. **规则兜底永远在**。模型这条路是「增强」不是「依赖」：模型没调工具、超时、返回乱码，都退到 `MarketGateway` 的确定性调用，用户感知不到差别。
4. **`MARKET_TOOL` 落状态**，trace 里能看出这次是模型选的还是兜底的 —— 线上判断「模型路由到底有没有在工作」全靠它。

`MarketUnavailableException` 对应「子进程没起来 / 握手失败」，不把技术细节甩给用户；`MarketApiException` 对应工具内的业务错误（代码不合法 / 未返回数据），原文已经是人话。

### 8.1 结果解析

`CallToolResult` 里 JSON 可能出现在两处，按优先级取：

| 顺序 | 位置 | 说明 |
| --- | --- | --- |
| ① | `structuredContent()` | 服务端显式返回结构化结果时优先用它 |
| ② | `content()` 里第一条 `TextContent.text()` | Java/Python 两个实现都走这条；用 Jackson 3 `JsonMapper` 解析成 `Map` |
| ③ | `isError() == true` | 协议层失败，抛 `MarketUnavailableException` |

解析出 `Map` 后检查 `ok`：`false` 时把 `error` 原样抛成 `MarketApiException`（两个服务实现的错误契约一致）。

### 8.2 `MarketGateway` 接口

```java
public interface MarketGateway {
    record Snapshot(Map<String, Object> data, String asOf) { }
    Snapshot call(String tool, String code, int days);          // 规则兜底
    Snapshot execute(ToolCall call);                              // 执行模型选出来的调用
}
```

`McpMarketGateway` 现在只承担**兜底路径**（模型没选工具或参数非法时），接口不变，因此它也天然是集成测试里最好用的桩：不用模型就能验证整条取数链路。

`McpMarketGateway` 的三条内部规则：

1. **懒初始化**：`if (!client.isInitialized()) client.initialize();`，成功后置 `ready=true`；失败后 60 秒内直接抛 `MarketUnavailableException`，避免每个请求都等 8 秒；
2. **只传非空参数**：`get_quote` 只传 `code`，`get_history` 传 `code` + `days`；参数越少越不容易踩到两个实现之间的差异；
3. **asOf 兜底**：优先用返回体里的 `tradedAt`；缺失时用当前时间并打 WARN，因为「没有时间戳的行情」比「没有行情」更危险。

---

## 9. `answer_market` 节点

答案用模板渲染，**不调模型**。行情答案里每个数字都必须能对上接口返回值，自由生成只增加出错概率。

### 9.1 成功时的结构

```
东方财富（300059）的实时行情

报价时间：2026-11-04 12:07:06（北京时间）
最新价 18.80 元，较昨收 -0.32%；总市值 2974.57 亿元，市盈率（TTM）18.74 倍。

| 指标 | 数值 |
| --- | --- |
| 今开 / 昨收 | 18.62 / 18.86 元 |
| 最高 / 最低 | 18.95 / 18.55 元 |
| 成交额 / 换手率 | 12.34 亿元 / 1.23% |
| 市净率 | 2.11 倍 |

以上为公开行情接口的快照，非年报口径；若当前非交易时段，显示的是最近一次收盘价。
```

| 规则 | 为什么 |
| --- | --- |
| 报价时间必须在正文第一段出现，且不能用「今天」代替 | 非交易时段的数据会停在上一个交易日 15:00 |
| 金额统一换算：≥1 亿用「亿元」保留两位，否则用「万元」 | 接口给的是元（如 297457357650），直接展示没人读 |
| 涨跌幅带符号，跌用 `-`，平用 `0.00%` | 金融场景里符号是信息 |
| 缺字段就不渲染该行 | 避免把「接口没给」显示成「数值为零」 |

### 9.2 同步产出的结构化数据

`answer_market` 调 `bridge.result(runId, AnswerResult)`，让 `final` 载荷与历史消息拿得到同一份：

| 字段 | 值 |
| --- | --- |
| `sufficient` | 成功 `true`；`MARKET_ERROR` 非空时 `false` |
| `facts` | 每指标一条 `Fact`：`metric=最新价`、`period=tradedAt`、`value=18.80`、`unit=元`、`scope=实时行情`、`evidence=null`、`source="MARKET"`、`origin=REPORTED` |
| `citations` | 空列表（行情不产引用） |
| `confidence` | `high`（数据直取）；失败时 `insufficient` |
| `note` | `实时行情，非年报口径；数据时间 2026-11-04T12:07:06+08:00` |
| `warnings` | 成功 `market_realtime_not_annual_report`；失败 `market_unavailable` / `market_company_ambiguous` |
| `citationsDropped` | 0 |

`AnswerResult.Fact` 的 `source` 目前只有 `SOURCE_PDF`，需要新增 `SOURCE_MARKET = "MARKET"`；前端据此把这一行标成「行情」而不是「PDF」。

### 9.3 失败时的文案

| `MARKET_ERROR` 来源 | 文案 | `status` |
| --- | --- | --- |
| 服务不可用 | `行情服务暂时不可用：<原因>。年报类问题不受影响，可以继续问。` | `REJECTED` |
| 公司没解析出来 | `没听出是哪家公司的股票，请给出 6 位代码（如 300059）。` | `REJECTED` |
| 多个候选 | `找到多个可能的股票：…，请指定代码。` | `REJECTED` |
| 工具业务错误 | `<接口原文>，可以稍后重试或换一只股票。` | `REJECTED` |

---

## 10. 与现有链路的接缝

| 位置 | 改动 | 为什么 |
| --- | --- | --- |
| `AnalysisState` | 加 6 个键（§4） | 节点之间传数据 |
| `AnalysisNodes` | 加 `market` / `answerMarket` | 图只连拓扑 |
| `AnalysisGraph` | 5 个节点（`dispatch` / `fanout` / `market` / `merge` / `answer_market`）、2 条条件边、5 条普通边 | §3.1 |
| `RealAnalysisNodes.understand()` | 在 QU 之后、作用域解析之前插入意图判定 | 行情问题可能没有入库文档，先解析作用域会被 `scope_empty` 提前拦掉 |
| `RealAnalysisNodes.plan()` | `MARKET_INTENT` 为真时只写一条计划步骤并返回 | 不做子问题拆解、不判形态 |
| `ChatTrace` | 加 `MarketTrace market`（13 参，补兼容构造） | 排障要看工具、代码、耗时、asOf |
| `ChatTrace.market` | 字段加 `byModel`（boolean） | 分清「模型选的工具」与「规则兜底」，否则模型路由坏掉也看不出来 |
| `AgentStreamService` | 节点标签 switch（`AgentStreamService.java:348-397`，含 `stepMs` 计时）加 `market` / `answer_market`；开跑前发一条 stage | 前端进度条与阶段文案 |
| `AgentRunService` | 时间线文案 switch `summary(node, state)`（`AgentRunService.java:496-510`）加两个 case | run 详情页 |
| `AnswerResult` | 加 `SOURCE_MARKET` 常量 | §9.2 |
| `AnswerService.ground()` | `allowedValues` 追加行情快照里的数字（价格 / 涨跌幅 / 市值 / PE / 成交额 / 日期） | 该方法把答案里每个数字拿去和**被引用 chunk 正文**比对，对不上记 `ungrounded`，`ungrounded == total` 判「不是从证据里读出来的」（`AnswerService.java:573-628`）。行情数字永远不在 chunk 正文里，不放进白名单，混合答案必然被判不落地 → 拒答 |
| `RealAnalysisNodes.answer()` | 构造 prompt 时注入「行情快照」段（带 `asOf`），并把它写进 `FactPack` 之外的第二份上下文 | 一份答案同时讲年报数字与实时价格 |
| `AnalysisGraph.node(...)` | 新增 `asyncNode(...)`：捕获 `CurrentUser`，在 worker 线程 `TenantContext.set/clear`，返回真实 future | 现有包装器在 `finally` 里 `clear()`（`AnalysisGraph.java:145-157`），节点一旦返回未完成 future，worker 里 `TenantContext.require()` 必抛 `AUTH_REQUIRED`（§3.2） |
| 前端 | 最小改动：`warnings` 里的 `market_realtime_not_annual_report` 渲染成提示条 | 阶段 23 不做行情卡片 |

`ChatTrace` 的注释里写过「为什么组合 `RetrievalTrace` 而不是往里加字段」—— 行情不是检索，所以加的是并列的 `market` 段，不是往 `RetrievalTrace` 里塞字段。没有行情时该字段为 `null`。

---

## 11. 文件清单

| 类型 | 路径 | 职责 |
| --- | --- | --- |
| 新增 | `src/main/java/com/finagent/agent/MarketIntentDetector.java` | 纯规则：问题 → 工具 + 天数 |
| 新增 | `src/main/java/com/finagent/agent/MarketCompanyResolver.java` | 三级公司解析 |
| 新增 | `src/main/java/com/finagent/market/MarketGateway.java` | 取数接口 + `Snapshot` record |
| 新增 | `src/main/java/com/finagent/market/McpMarketGateway.java` | 懒初始化、`callTool`、解析、错误映射 |
| 新增 | `src/main/java/com/finagent/market/UnavailableMarketGateway.java` | 未启用时的兜底实现 |
| 新增 | `src/main/java/com/finagent/market/MarketProperties.java` | `finagent.market.*` |
| 新增 | `src/main/java/com/finagent/market/MarketToolSelector.java` | 一次模型调用只取 `toolCalls`；`allowed` / `validArgs` 校验；失败返回 `null` |
| 新增 | `src/main/java/com/finagent/chat/MarketTrace.java` | tool / code / name / elapsedMs / asOf / error |
| 修改 | `pom.xml` | 加 `spring-ai-starter-mcp-client` |
| 修改 | `src/main/resources/application.yml` | `spring.ai.mcp.client.*` + `finagent.market.*` |
| 修改 | `src/main/java/com/finagent/agent/AnalysisState.java` | 6 个键 + SCHEMA |
| 修改 | `src/main/java/com/finagent/agent/AnalysisNodes.java` | 4 个方法（`dispatch` / `market` / `merge` / `answerMarket`） |
| 修改 | `src/main/java/com/finagent/agent/AnalysisGraph.java` | 节点 + 边 |
| 修改 | `src/main/java/com/finagent/agent/RealAnalysisNodes.java` | understand 插入判定 / plan 分流 / 两个新节点实现 |
| 修改 | `src/main/java/com/finagent/agent/AgentStreamService.java` | 标签 switch + stage |
| 修改 | `src/main/java/com/finagent/agent/AgentRunService.java` | 时间线文案 switch（`:496-510`） |
| 修改 | `src/main/java/com/finagent/chat/ChatTrace.java` | 13 参 + 兼容构造 |
| 修改 | `src/main/java/com/finagent/answer/AnswerService.java` | `allowedValues` 合并行情数字 |
| 修改 | `src/main/java/com/finagent/answer/AnswerResult.java` | `SOURCE_MARKET` |
| 修改 | `docs/关键契约.md` | 新增 C41 |
| 新增 | `docs/阶段23-行情MCP接入报告.md` | 执行后的实测报告 |

---

## 12. 任务拆分与验收

| 任务 | 内容 | 验收标准（可执行、可判定） |
| --- | --- | --- |
| T1 | `MarketIntentDetector` + 表驱动单测 | 单测 ≥20 例全绿；`mvn -Dtest=MarketIntentDetectorTest test` 通过 |
| T2 | `MarketCompanyResolver` + 单测（含歧义/未命中） | 三级来源各 ≥2 例；不依赖数据库的用例用假 repo |
| T3 | `pom.xml` + `application.yml` + `MarketProperties` | `ApplicationContextRunner`：开启时存在 `McpSyncClient`；`spring.ai.mcp.client.enabled=false` 时装配 `UnavailableMarketGateway` |
| T4 | `MarketGateway` 接口 + 两个实现 + 单测 | 假 `McpSyncClient` 断言：① 未初始化时先 `initialize()`；② `ok=false` → `MarketApiException`；③ 超时 → `MarketUnavailableException`；④ `asOf` 兜底 |
| T5 | `MarketTrace` + `ChatTrace` 13 参 + 单测 | 12 参老构造仍可编译；`market=null` 时 `totalMs()` 不变 |
| T6 | 状态键 + 图节点 + 边 + 图单测 | 假 gateway 跑图：行情问题节点序列 = `understand,plan,market,answer_market`，`hits` 空、`citations` 空、`status=DONE`；年报问题序列不含 `market` |
| T7 | 两处标签 switch + SSE stage | 流式跑一遍，`stage` 事件出现 `market` 与 `answer_market` 两条，无异常日志 |
| T8 | 集成测试（真子进程） | `@Tag("mcp")`：`get_quote 300059` 返回非空 `asOf`；答案含 `tradedAt` 的分钟串；子进程不存在时 9 秒内返回 `market_unavailable` 且 HTTP 200 |
| T9 | 文档：契约 C41 + 阶段 23 报告 + 术语表补 MCP 条 | 报告含实测数字（延迟、用例数）；术语表 MCP 条目有「是什么 / 为什么需要 / 我们项目里长什么样」 |
| T10 | `MarketToolSelector`（自建无 advisor 的 `ChatClient`）+ 校验 + 兜底 | 假 `ChatClient`：① 返回 `get_quote(300059)` 的 `toolCall` → `byModel=true` 且 `gateway.execute` 收到该调用；② 返回 `get_quote(600519)` → 校验失败、走规则兜底，`byModel=false`；③ 无 `toolCalls` → 走兜底；④ 抛异常 → 走兜底且不把异常抛给用户；⑤ 两个 `toolCalls`（并行调用）→ 取第一个合法的 |
| T11 | 并行：`dispatch` / `fanout` / `merge` + 线程池 + `asyncNode` | 单测断言 `fanout` 的两支在**不同线程**完成（比较 `Thread.currentThread().getName()`）；`BOTH` 路径 `merge` 只执行一次且两支数据都在；`TenantContext` 在 worker 内可见（假 repo 记录 `require()` 未抛） |

### 12.1 回归底线

- 全量 `mvn test` 现有 127 个用例保持全绿（1 个 skip 是 `DoclingParserAdapterTest`，需要解析服务）；
- `../financial-mcp-server4j/target/*.jar` 不存在时：**应用必须能起来**，日志一条 WARN，行情问题得到「服务不可用」文案，年报问题不受影响。

---

## 13. 测试计划

| 用例 | 断言 | 需要子进程 |
| --- | --- | --- |
| 意图：20 例表驱动 | 工具 / 天数 / 是否进入 | 否 |
| 公司解析：三级 + 歧义 + 未命中 | `code` / `codeSource` / 错误码 | 否（假 repo、假 gateway） |
| `McpMarketGateway`：4 条 | 异常类型与消息 | 否（假 client） |
| 图：行情路径 / 年报路径 / 行情失败路径 | 节点序列、`hits`、`citations`、`status`、`warnings` | 否（假 gateway） |
| 集成：真 jar | `asOf` 非空、答案含时间 | 是 |
| 集成：jar 缺失 | 9 秒内失败、年报路径正常 | 否（`args` 指向不存在的路径） |
| 回归：全量 | 127 例全绿 | 部分 |
| tool 选择：5 例 | 正常选中 / 参数非法 / 未调工具 / 抛异常 / 并行多调用 | 否（假 `ChatClient`） |
| 并行：3 例 | 线程不同、`merge` 一次、跳过支路不挂 | 否 |

---

## 14. 风险与未决问题

| 编号 | 问题 | 现状 / 计划 |
| --- | --- | --- |
| R1 | 行情问题仍要跑一次 QU（实测约 4 秒，2026-10-30 本机 qwen-plus），而 QU 的四类 `queryType` 与行情无关 | 本阶段接受；优化方向是 QU 之前粗筛短路，代价是丢掉 `metrics/yearRange`（它们用于防误判） |
| R2 | 单个 `McpSyncClient` 被多线程并发调用是否安全 | SDK 声称 sync 客户端线程安全，未实测；T8 加 10 线程 × 5 次并发用例 |
| R3 | 停牌、退市、新股：`price` 可能为 0 或缺失 | 缺字段不渲染；`price=0` 判异常走失败文案 |
| R4 | 东财接口无 SLA，偶发 502 | 失败即降级；备用源（腾讯/新浪）不在本阶段 |
| R5 | 「今天」与交易日不一致（周末、节假日） | 统一用 `tradedAt` 表述，不写「今天」 |
| R6 | 拿不到按连接名索引的 `McpSyncClient`（§7.5） | 单连接假设；接第二个 server 时改手写 client |
| R7 | 混合问题（年报数字 vs 实时市值） | 本阶段支持**基础形态**：`BOTH` 分支把一份行情快照与年报证据同时交给 `answer`；多标的对比、需要「市值 ÷ 净利润」这类计算的混合仍留阶段 24 |

---

## 15. 偏差登记（执行时填）

| 编号 | 计划写的 | 实际做的 | 原因 |
| --- | --- | --- | --- |
| D1 | 决策二：工具由代码调，不让模型选 | 改成「模型选工具 + 我们执行 + 规则兜底」 | 用户要求模型自主 function calling；2.0.1 的执行点在 `ToolCallingAdvisor`，自建不带该 advisor 的 `ChatClient` 即可拿到未执行的 `toolCalls` |
| D2 | 图拓扑：plan 直接三分支（行情 / 检索 / 拒答） | 改成 `dispatch` 三分流 + `fanout` 并行 + `merge` 合流 | 用户要求行情与检索并发、rerank 后合并；单条条件边只能指一个目标，`BOTH` 必须经空节点 `fanout` 发散 |
| D3 | 新增 5 个状态键 | 6 个（多 `MARKET_TOOL`） | 需要区分模型选的工具与兜底 |
| D4 | 未提并行执行 | 补 §3.2 实测：普通节点串行 812 ms、future 节点 549 ms；补 `asyncNode` 与 `TenantContext` 传递 | 探针实测，原设计假设「画边即并行」是错的 |
| D5 | 图拓扑：`dispatch → fanout → {market, retrieve} → merge` 并行 | **串行**：plan 三分流（refuse / market / review），混合问题在 retrieve 之后接 market | 用户定：「确定不用并行调用 mcp」（m25292）；并行收益只有几十毫秒，但要改租户传递 |
| D6 | 「注入 ObjectProvider<McpSyncClient>」 | ObjectProvider<List<McpSyncClient>> 取第一个 | starter 注册的是一个 List Bean（McpClientAutoConfiguration#mcpSyncClients），按单实例注入永远为空 → 冒烟报「MCP client 未装配」 |
| D7 | 「用 ToolCallingChatOptions.builder() 传工具」 | 从 chatModel.getDefaultOptions().mutate() 派生 | OpenAI 客户端把 options 强转 OpenAiChatOptions，通用实现直接 ClassCastException → 模型选工具永远回落规则 |
| D8 | 「首次调用时懒握手」 | 懒握手 + ApplicationReadyEvent 异步预热 + 握手超时 30 s | 冷启动 3.7~9 s 超过 8 s 请求超时，第一次问行情稳定失败并触发 60 s 冷却 |
| D9 | 「有指标就算混合问题」 | 先剔除行情口径指标（股价/市值/PE/PB/换手率…）再判 | QU 把「股价」也抽成指标，纯行情问题被送去检索年报 → 证据不足拒答 |
| D10 | 「verify 不足 → 拒答」 | 有行情事实时改走 answer_market（前缀说明「年报没找到证据」） | 整题拒答会把已取回的实时价格一起丢掉 |
| D11 | 工具定义手写在 MarketToolSelector（definitionsOnly 两条） | `McpMarketGateway#tools()` 拉 `client.listTools()` → `MarketToolSpec`，过滤 `search_stock`，TTL 5 分钟，失败/空清单回落内置定义 | 手写 schema 会漂移：服务端加字段、改必填，客户端无感知；服务端才是工具协议的单一事实来源 |

---

## 16. 复现

```powershell
# 1. 打包并验证 MCP Server（与主项目平级）
Set-Location D:\Project\myproject\financial-mcp-server4j
mvn -DskipTests package
& ../financial-ai-project/mcp-market-server/.venv/Scripts/python.exe test_client.py

# 2. 主项目：指定 jar 路径（相对路径取决于工作目录，建议绝对路径）
Set-Location D:\Project\myproject\financial-ai-project
$env:FINAGENT_MARKET_JAR = "D:/Project/myproject/financial-mcp-server4j/target/financial-mcp-server4j-0.1.0.jar"

# 3. 单测
mvn -Dtest=MarketIntentDetectorTest,MarketCompanyResolverTest,McpMarketGatewayTest,MarketGraphTest test

# 4. 集成（真子进程）
mvn -Dtest=MarketMcpIntegrationTest "-Dgroups=mcp" test

# 5. 手工验收：起服务后问「东方财富现在股价多少」
mvn spring-boot:run
```

预期的答案里必须同时出现：股票代码、`tradedAt` 的完整时间、至少三个指标、口径说明。
