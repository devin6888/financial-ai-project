# MCP Client → MCP Server 调用链路（阶段 23 实况）

> 只讲一件事：一句「东方财富股价」是怎么从 HTTP 请求走到东方财富接口、再变成答案里的数字的。
> 代码行号以当前版本为准（会漂），搜类名更稳。相关契约见 `docs/关键契约.md` C40。

## 0. 一句话结论

主应用里有一个 **MCP Client（库，不是进程）**，它用 stdio 把另一个 JVM（行情 MCP Server）**当子进程拉起来**，
两者之间只走 JSON-RPC：Client 往子进程 stdin 写请求、从 stdout 读响应。
**模型只负责「选工具、填参数」，数字由主应用自己渲染**，不经过模型转述。

## 1. 四个角色

| 角色 | 是什么 | 在哪 |
| --- | --- | --- |
| 主应用 | Spring Boot 单体，端口 8081 | `financial-ai-project` |
| MCP Client | 进程内的库：`io.modelcontextprotocol.client.McpSyncClient` | 主应用 JVM 内 |
| MCP Server | **独立 JVM 子进程**，stdio 传输，不监听端口 | `financial-mcp-server4j` 打出的 jar |
| 行情上游 | 东方财富公开 HTTP 接口 | `push2.eastmoney.com` / `push2his.eastmoney.com` / `searchapi.eastmoney.com` |

最容易误解的一点：**Client 不是进程**。它只做三件事——拼 JSON-RPC、读写子进程的 stdin/stdout、管理生命周期。

## 2. 启动阶段：三个 Bean 是怎么来的

~~~
application.yml:60-77                  Spring AI 自动装配                        我们的代码
─────────────────────                  ─────────────────────                    ────────────────────
spring.ai.mcp.client.stdio.             StdioTransportAutoConfiguration
  connections.market.command: java  ──►  (按 connections 建 transport)
  args: [-jar, .../xxx.jar]                        │
                                                   ▼
                                        McpClientAutoConfiguration
                                        #mcpSyncClients(...)
                                                   │  产出「一个 List<McpSyncClient> Bean」
                                                   ▼
                                        MarketConfig#marketGateway(props,
                                                  ObjectProvider<List<McpSyncClient>>, mapper)
                                                   │  取 List 的第一个
                                                   ▼
                                             McpMarketGateway
~~~

三个必须记住的点：

1. **注入类型是 `List`**：`McpClientAutoConfiguration#mcpSyncClients` 返回的是一个 `List<McpSyncClient>` Bean，
   不是「每个连接一个 Bean」。写成 `ObjectProvider<McpSyncClient>` 永远拿不到值（冒烟时就是报「MCP client 未装配」）。
2. **握手超时单独放宽**：`MarketConfig#marketClientCustomizer`（`McpClientCustomizer<McpClient.SyncSpec>`）设了
   `initializationTimeout(30s)` + `requestTimeout(8s)`。沿用 8 s 会让第一次行情调用稳定超时。
3. **不把工具注册进所有 ChatClient**：`spring.ai.mcp.client.toolcallback.enabled=false`。
   否则年报问答的模型也能看到 `get_quote`，可能绕过模板去查股价。

## 3. 预热：谁先握手

~~~
ApplicationReadyEvent
      │
      ▼
McpMarketGateway#warmUp()            ← 独立 daemon 线程 "market-mcp-warmup"
      │
      ▼
ensureReady()                        ← 双检 + 60 s 冷却
      │  client.isInitialized() == false
      ▼
client.initialize()                  ← 真正 spawn 子进程的地方
      │
      ├─ ProcessBuilder: java -jar financial-mcp-server4j-0.1.0.jar
      ├─ 子进程 Spring 起来（WebApplicationType.NONE，spring.ai.mcp.server.stdio=true）
      └─ JSON-RPC: initialize → result → notifications/initialized
      ▼
日志：行情 MCP 子进程握手完成：finagent-market
~~~

实测：冷启动 **3.7 s**（机器忙时 6~9 s），预热放在启动后异步线程，用户第一个请求不再等它。

## 4. 一次请求的完整时序

~~~
① POST /api/chat {"question":"东方财富股价"}
      │
② AgentRunService.execute → AnalysisGraph（图是唯一执行引擎）
      │
③ understand：QU（一次模型调用，实测 4~9 s）
      │
④ MarketIntentDetector.detect(question)        ← 纯规则，微秒级
      │  matched=true, history=false
      ▼
⑤ MarketCompanyResolver.resolve(question, qu.codes, qu.mentions)
      │  ① 问题里的 6 位代码 ② QU codes ③ 本租户 company 表 ④ MCP search_stock 唯一命中
      ▼  300059 / 东方财富（来源 company）
⑥ 写状态：MARKET_INTENT=true、MARKET_PLAN={tool,code,name,days}、MARKET_ONLY=true
      │  （MARKET_ONLY = 去掉行情口径指标后没有财报指标、也没有年份）
      ▼
⑦ plan：marketOnly → 只写三条计划文本，不拆子问题
      │
⑧ 条件边 plan ─(marketOnly)→ market
      ▼
⑨ market 节点 → MarketToolSelector.pick(question, code)
      │  给模型两条 ToolDefinition（get_quote / get_history），temperature=0
      │  模型返回 tool_calls（arguments 是 JSON 字符串）
      ▼  我们只读 name + arguments，不执行
⑩ market.execute(tool, args, days)
      │  ensureReady()（已预热则直接返回）
      │  client.callTool(new CallToolRequest(tool, args))
      ▼
⑪ stdio：主应用 → 子进程 stdin（JSON-RPC tools/call）
      ▼
⑫ 子进程：MarketTools#getQuote → EastmoneyClient（HTTP）→ 东财接口
      │  组装 CallToolResult{ content:[TextContent(JSON)], structuredContent:{...}, isError:false }
      ▼
⑬ 主应用：payload() 先读 structuredContent，没有才解析 TextContent；ok:false → MarketApiException
      │  asOf()：get_history 取最后一根 bar 的日期+15:00；否则 tradedAt；都没有才用当前时间
      ▼
⑭ factsOf() → MARKET_FACTS（no 从 900 起，source=MARKET，evidence="实时行情 <asOf>"）
      │
⑮ answer_market：模板拼「19.20 元 / +1.80% / 市值 3037.86 亿 / PE 19.13」+ 数据时间
      │
⑯ bridge.marketTrace(...) → ChatTrace.market → 前端角标「实时行情 · 11-04 15:34」
~~~

每一步的落点：

| 步骤 | 代码 | 备注 |
| --- | --- | --- |
| ③④⑤⑥ | `src/main/java/com/finagent/agent/RealAnalysisNodes.java:274-302` | `understand()` 尾部，写状态 |
| ⑦ | `RealAnalysisNodes.java:318-331` | `plan()` 的 marketOnly 分支 |
| ⑧ | `src/main/java/com/finagent/agent/AnalysisGraph.java:206-221` | plan 的三值条件边 |
| ⑨ | `src/main/java/com/finagent/market/MarketToolSelector.java:73-105` | 直连 `ChatModel`，不经 `ChatClient` |
| ⑩ | `RealAnalysisNodes.java:798-820` | 失败在这里被分流成三类 |
| ⑪⑫⑬ | `src/main/java/com/finagent/market/McpMarketGateway.java:60-74, 152-171, 182-206` | execute / ensureReady / payload |
| ⑭ | `RealAnalysisNodes.java:829-876` | 事实行与 compute 同构 |
| ⑮ | `RealAnalysisNodes.java:878-938` | 纯模板，不调模型 |
| ⑯ | `src/main/java/com/finagent/chat/MarketTrace.java`、`ChatTrace` 第 13 参 | 排障用 |

## 5. 真实报文（截断）

主应用 → 子进程（stdin，一行一个 JSON）：

~~~json
{"jsonrpc":"2.0","id":3,"method":"tools/call",
 "params":{"name":"get_quote","arguments":{"code":"300059"}}}
~~~

子进程 → 主应用（stdout）：

~~~json
{"jsonrpc":"2.0","id":3,"result":{
  "content":[{"type":"text","text":"{\"ok\":true,\"price\":19.2,...}"}],
  "structuredContent":{"ok":true,"code":"300059","name":"东方财富","price":19.2,
                       "changePct":1.8,"totalMarketCap":303786000000,
                       "peTtm":19.13,"pb":2.97,"tradedAt":"2026-11-04T15:34:06+08:00"},
  "isError":false}}
~~~

两个载体都带同样的 JSON，是因为：`structuredContent` 只有声明了 outputSchema 的客户端才保证能读，
`content` 里的文本是给「只认文本」的老客户端兜底的。`McpMarketGateway#payload` 两路都认。

## 6. 数据结构对照

| 阶段 | 类型 | 关键字段 |
| --- | --- | --- |
| 模型返回 | `AssistantMessage.ToolCall` | `name`、`arguments`（JSON **字符串**） |
| 我们发出 | `McpSchema.CallToolRequest` | `name`、`arguments`（`Map`） |
| 我们收到 | `McpSchema.CallToolResult` | `content`、`structuredContent`、`isError` |
| 状态 | `MARKET_DATA` | `Map`：price / changePct / totalMarketCap / peTtm / pb / tradedAt |
| 事实行 | `MARKET_FACTS` 里的一行 | no / metric / period / value / unit / scope / evidence / source=MARKET |
| 答案 | `String` | 模板文本 + 「数据时间：…，来源：东方财富公开行情。」 |

## 7. 失败矩阵

| 失败点 | 抛什么 | 用户看到 | 图怎么走 |
| --- | --- | --- | --- |
| 子进程起不来 / 管道断 / 请求超时 | `MarketUnavailableException` | 「行情服务暂时不可用」 | 纯行情：`answer_market` 出降级文案；混合：`verify` 照常判年报证据 |
| 工具返回 `ok:false`（如代码非法） | `MarketApiException` | 服务端原话 | 同上 |
| 模型选工具失败 | 内部 catch，回落规则 | 无感（日志 `by=rule`） | 正常 |
| 连续失败 | 60 s 冷却 | 同上 | 冷却期内不再 spawn |

降级方向很重要：**行情挂了只丢行情，年报照答**；反过来「年报证据不足但有行情」也不会整题拒答
（图里 `verify → answer_market` 的 `market_fallback` 边，`AnalysisGraph.java:241-252`）。

## 8. 日志锚点（照着 grep 就知道卡在哪）

| 日志 | 含义 |
| --- | --- |
| `行情意图：matched=true history=false days=0` | 规则命中（DEBUG） |
| `行情意图命中：… → 300059/东方财富(company)` | 公司解析成功（DEBUG） |
| `行情口径判定：metrics=[股价] reportMetrics=[] reportLike=false` | 判成纯行情（DEBUG） |
| `MCP server starting.` / `MCP server started` | 子进程被拉起 |
| `行情 MCP 子进程握手完成：finagent-market` | `initialize` 成功 |
| `行情工具清单已刷新：[get_history, get_quote]` | `listTools()` 成功，模型看到的定义换成服务端那份（DEBUG） |
| `拉取行情工具清单失败，沿用…` | `listTools()` 挂了，回落上次结果/内置定义（DEBUG，不影响取数） |
| `行情取数成功：tool=get_quote code=300059 by=model asOf=…` | 工具执行成功 |
| `模型选工具失败，回落规则调用：…` | 多半是 options 类型不对（见 §9） |
| `行情服务不可用：code=… reason=…` | 进降级分支，看 reason |

## 9. 五个常见误解（都踩过）

1. **「MCP Client 是进程」** —— 不是，它是库；起进程的是它。子进程是那个 22 MB 的 jar。
2. **「注入 `McpSyncClient` 就行」** —— Bean 是 `List<McpSyncClient>`，按单实例注入永远是空。
3. **「工具会自己执行」** —— 只有经过 `ChatClient` 的 `ToolCallingAdvisor` 才会自动执行；
   直接 `chatModel.call(...)` 拿到的 `toolCalls` 是**没执行**的原始请求（我们就是这样做的）。
4. **「服务端只返回文本」** —— `structuredContent` 是首选载体；文本只是兜底。
5. **「description 写好，模型就不会乱调」** —— description 管语义选择，管不住「这个调用点在不在证据链里」。
   隔离要靠装配（给不给这个 ChatClient 挂工具），不是靠提示词。

## 10. 工具清单从哪来（以及一个仍然保留的取舍）

1. **工具清单来自服务端**，不是手写的。链路：`McpMarketGateway.tools()` → 懒握手 → `client.listTools()`
   → `McpSchema.Tool.inputSchema()`（`Map`）序列化成 JSON 字符串 → `MarketToolSpec`
   → `MarketToolSelector.definitions()` → `ToolDefinition`。两条过滤：只留 `get_quote`/`get_history`
   （`search_stock` 是 `MarketCompanyResolver` 的内部步骤，模型不该看到），schema 为空的丢掉。
2. **缓存与回落**：成功拉到的清单缓存 5 分钟（`TOOLS_TTL_MS`）；拉失败或服务端没声明可用工具时缓存 60 秒
   （`TOOLS_RETRY_MS`）并沿用上次结果，从未拉到过就用内置定义（`MarketToolSelector.BUILTIN`）。
   所以服务端挂了也不会出现「模型面前一个工具都没有」。
3. **仍然保留的取舍**：模型选工具与执行工具分属两段代码 —— `MarketToolSelector` 只读 `tool_calls`，
   执行在 `McpMarketGateway`。好处是数字能逐字渲染、能补 `asOf`、能进事实表；
   代价是「选了但没执行」这种状态得自己保证，参数校验（`code` 六位、`days` 1~120）也要在客户端再做一遍。

## 11. 复现

~~~powershell
# 1) 子进程 Server（独立项目，与主项目平级）
cd D:\Project\myproject\financial-mcp-server4j
mvn -DskipTests package        # 产物：target/financial-mcp-server4j-0.1.0.jar

# 2) 主应用
cd D:\Project\myproject\financial-ai-project
. .\scripts\load-env.ps1
java -jar target\financial-agent-0.1.0-SNAPSHOT.jar --server.port=8081
# 起来 20 秒内日志会有一行「行情 MCP 子进程握手完成」

# 3) 问一句（PowerShell 5.1 直接拼中文会变乱码，用文件传 body）
"{""question"":""东方财富股价""}" | Out-File -Encoding utf8 target/q1.json
curl.exe -X POST http://127.0.0.1:8081/api/chat ^
  -H "Authorization: Bearer <token>" -H "Content-Type: application/json; charset=utf-8" ^
  --data-binary "@target/q1.json"
~~~