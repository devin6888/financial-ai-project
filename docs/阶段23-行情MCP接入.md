# 阶段 23 · 实时行情 MCP 接入（串行版）

> 目标：让 Agent 能回答「现在股价多少」这类问题，同时不污染「答案必须来自年报证据」这条底线。
> 计划：`docs/superpowers/plans/ST23-2026-11-04-23-mcp-client-in-graph.md`；口径：`docs/关键契约.md` **C40**。

## 1. 做成了什么

| 能力 | 落地位置 |
| --- | --- |
| 行情意图识别（纯规则） | `src/main/java/com/finagent/agent/MarketIntentDetector.java` |
| 公司解析（代码 / 本租户 company 表 / MCP 搜索兜底） | `src/main/java/com/finagent/agent/MarketCompanyResolver.java` |
| 模型选工具（直连 ChatModel，不经 advisor） | `src/main/java/com/finagent/market/MarketToolSelector.java` |
| stdio MCP 调用（懒握手、冷却、错误分流） | `src/main/java/com/finagent/market/McpMarketGateway.java` |
| 图上的两条支路 | `src/main/java/com/finagent/agent/AnalysisGraph.java`（`market` / `answer_market`） |
| 事实行 + 模板答案 + trace | `RealAnalysisNodes.market/answerMarket`、`com.finagent.chat.MarketTrace` |

### 1.1 图拓扑

```
START → understand → plan ─┬─(refuse)──────────────────────────────→ respond_insufficient → END
                           ├─(marketOnly)──→ market → answer_market → END
                           └─(review)──→ approve → retrieve ─┬─(refuse)→ respond_insufficient
                                                              └─(marketIntent)→ market → verify ─┬─(enough)─────────→ compute → answer → cite_check → END
                                                                                                 ├─(还缺证据且无行情)→ respond_insufficient
                                                                                                 └─(还缺证据但有行情)→ answer_market → END
```

串行而非并行是**用户拍的**（m25292：「确定不用并行调用mcp」）。实测过 LangGraph4j 的并行语义：
节点必须返回真实 `CompletableFuture` 才并行，且 `node()` 包装会在 `finally` 里清 `TenantContext`，
worker 线程拿不到租户会抛 `AUTH_REQUIRED`。省下的只有几十毫秒（热调用 40 ms 级），不值得为此改租户传递。

## 2. 关键取舍

1. **行情不进证据链**：`verify` 判证据、`answer` 只认 `[E]`、`cite_check` 校验编号，三道闸门都不认识行情；
   塞进 `hits` 会被判死，且 `ContextBlock` 的字段全是切块形状。
2. **行情不落库**：只在内存状态里活一轮；落库的只有答案文本 + `ChatTrace.market`。
3. **答案用模板拼，不调模型**：价格、市值、时间戳必须逐字准确，模型转述会编。
4. **模型只选工具，不执行工具**：Spring AI 2.0 的工具执行在 `ToolCallingAdvisor`，
   走 `ChatClient` 会把工具调完并让模型转述结果；`MarketToolSelector` 直连 `ChatModel` 才拿得到原始 `toolCalls`。
5. **失败必须降级到「只答年报」**：任何行情异常都只写 `MARKET_ERROR`，不抛到图外。

## 3. 实测

| 项 | 结果 | 环境/说明 |
| --- | --- | --- |
| 冷启动（spawn + initialize） | **3666 ms** | JVM + Spring 上下文 + 握手，故 `initialized:false` + 首次问行情才握手 |
| 热调用 `get_quote` | **42 ms / 38 ms** | 同一子进程第二次起 |
| `get_history(20)` | **161 ms** | 含 20 根 K 线 |
| 全量测试 | **949 个用例 / 0 失败 / 6 跳过**（129 个测试类） | `mvn test`；跳过的是需要解析服务的 `DoclingParserAdapterTest`、需要真 key/真行情的 4 个 live 用例 |
| 真子进程行情用例 | **4/4 通过（9.4 s）** | `FINAGENT_MARKET_LIVE=true mvn -Dtest=McpMarketGatewayLiveTest test` |
| 前端 | `vue-tsc` 0 错误、vitest **85 通过** | 新增行情角标与 4 个 warning 文案 |
| 端到端「东方财富股价」 | **10.3 s**，答案含价格/涨跌幅/市值/PE/PB + 数据时间 | 2026-11-04 16:18；trace 里 `quMs=4287`，行情热调用 < 1 s，QU 是主要成本 |
| 端到端「东方财富2025年净利润是多少」 | **20.0 s**，`sufficient=true`，不触发行情 | 回归用例：行情支路不影响原有年报链路 |
| 端到端「东方财富2025年净利润和现在股价」 | **19.3 s**，净利润与实时行情各自带引用 | 混合问题答案口径偏保守（见 §6）；**阶段 24 已修**，见 `docs/阶段24-部分答案与混合问题.md` 与契约 C41 |
| 应用内首次握手 | 启动后异步预热，**6.7 s 完成** | `ApplicationReadyEvent` → daemon 线程，用户第一个请求不再等冷启动 |
| 工具清单动态化后的端到端 | 「东方财富股价」**6.1 s**，`by=model`，日志 `行情工具清单已刷新：[get_history, get_quote]` | 2026-11-04 18:41；握手已预热，故比上午的 10.3 s 快（那次含冷启动） |

真行情样例（2026-11-04 12:07，东方财富 300059）：`18.80 / -0.32% / PE 18.74`，`tradedAt=2026-11-04T12:07:06+08:00`。

## 4. 新增测试（60 个）

| 类 | 数量 | 覆盖 |
| --- | --- | --- |
| `MarketIntentDetectorTest` | 16 | 快照词/走势词/财报词压制、天数折算（周×7、月×30、上限 120）、代码优先 |
| `McpMarketGatewayTest` | 14 | 结构化内容优先、文本回落、业务错误 vs 传输错误、冷却、工具白名单、days 截断、**工具清单来自服务端 + 过滤 `search_stock` + TTL 缓存 + 拉取失败返回空** |
| `MarketToolSelectorTest` | 13 | 解析 tool_calls、非法参数回落、模型抛异常不炸、多 call 取第一个合法、**服务端清单替换内置定义、空 schema/空描述回落、`search_stock` 不可选** |
| `RealAnalysisNodesTest`（新增 7） | 7 | 规则回落、模型选工具、facts 形状与 `source=MARKET`、错误三级、模板答案 |
| `AnalysisGraphTest`（新增 3） | 3 | 纯行情短路（不含 retrieve/answer）、混合问题先检索后行情、改写后仍不足但有行情改走 `answer_market` |
| `MarketMetricFilterTest` | 2 | 行情口径指标（股价/市值/PE/PB/换手/成交/涨跌幅）不算「要看年报」 |
| `McpMarketGatewayLiveTest` | 5 | 真子进程 + 真网络（默认跳过），含「工具清单确实来自子进程」 |

## 5. 偏差登记

| # | 计划原文 | 实际做法 | 原因 |
| --- | --- | --- | --- |
| D1 | 「plan 之后检索与 MCP 并行，rerank 后合并」 | 串行 | 用户定：不引入图并行（m25292）；收益只有几十毫秒 |
| D2 | 成对 `@ConditionalOnProperty` 选 MCP / Unavailable | **单个 @Bean 工厂方法** | 空串时两个条件同时命中 → `NoUniqueBeanDefinitionException` |
| D3 | `answer_market` 读 `MARKET_PLAN.tool` | 读 `MARKET_TOOL` | 模型可能改工具；答案必须按**真正调过**的工具选模板 |
| D4 | 测试沿用主配置 | `application-test.yml` 关掉 MCP client 与行情 | 否则每个 Spring 上下文都拉起一个 JVM 子进程 |
| D5 | 「注入 `ObjectProvider<McpSyncClient>`」 | `ObjectProvider<List<McpSyncClient>>` 取第一个 | starter 注册的是**一个 List Bean**（`McpClientAutoConfiguration#mcpSyncClients`），按单实例注入永远为空 → 冒烟报「MCP client 未装配」 |
| D6 | 「用 `ToolCallingChatOptions.builder()` 传工具」 | 从 `chatModel.getDefaultOptions().mutate()` 派生 | OpenAI 客户端会把 options 强转 `OpenAiChatOptions`，通用实现直接 `ClassCastException`，模型选工具永远回落规则 |
| D7 | 「首次调用时懒握手」 | 懒握手 + `ApplicationReadyEvent` 异步预热 + 握手超时 30 s | 冷启动 3.7~9 s 超过 8 s 请求超时，第一次问行情稳定失败并触发 60 s 冷却 |
| D8 | 「有指标就算混合问题」 | 先剔除行情口径指标（股价/市值/PE/PB/换手率…）再判 | QU 把「股价」也抽成指标，导致纯行情问题被送去检索年报 → 证据不足拒答 |
| D9 | 「verify 不足 → 拒答」 | 有行情事实时改走 `answer_market`（前缀说明「年报没找到证据」） | 整题拒答会把已经取回的实时价格一起丢掉，用户看到的是「什么都不答」 |
| D10 | 工具定义手写在 `MarketToolSelector` | 改成 `McpMarketGateway#tools()` 拉 `listTools()`，过滤 `search_stock`，TTL 5 分钟 + 失败回落内置定义 | 手写 schema 会漂移（服务端加字段/改必填，客户端无感知），服务端才该是工具协议的单一事实来源 |

## 6. 没做（明确留给后续）

- 混合问题（「2025 净利润 vs 现在市值」）的**对比式**回答：现在只是两条都跑，答案各说各的；
  实测「2025 净利润和现在股价」时模型会把「现在」判成含糊时点，输出 `sufficient=false` 的保守答案
  （数字与引用都对，读起来像在辩解）—— 留给阶段 24 的对比式答案；
- 港股/美股、盘口分时、停牌复牌日历；
- 行情落库与「按历史日期回看当时股价」；
- 前端独立行情卡片（目前是角标 + 答案末尾时间戳）。

## 7. 复现

```powershell
# 1) 行情 Server（独立项目）
cd ..\financial-mcp-server4j; mvn -DskipTests package
# 2) 真行情用例
cd ..\financial-ai-project; . .\scripts\load-env.ps1
$env:FINAGENT_MARKET_LIVE="true"; mvn -Dtest=McpMarketGatewayLiveTest test
# 3) 起应用后直接问：东方财富现在股价多少
```
