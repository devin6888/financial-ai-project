# MCP 与 Function Calling 学习笔记

> 面向 Java / Spring AI / LangGraph4j 开发者  
> 整理范围：MCP 的定位、Function Calling 的关系、工具发现与执行、Stdio / Streamable HTTP、Coding Agent 内置工具、Spring AI 传输配置、Tool 动态更新与通知、连接恢复、架构选型及面试问题。  
> 整理日期：2026-11-04

## 0. 五句话建立正确认知

1. **Function Calling / Tool Calling**：模型输出“要调用哪个工具、传什么参数”的结构化意图；真正执行工具的是 AI 应用（Harness / Agent Runtime）。
2. **MCP（Model Context Protocol）**：标准化 AI 应用与外部能力提供方之间的交互，包括工具发现、工具调用、结果返回，以及 Resources、Prompts 等能力。
3. **两者不在同一个层面**：Function Calling 属于模型与 Agent 之间的工具调用机制；MCP 属于 Agent 与外部工具/上下文服务之间的协议。MCP **不是对模型厂商 Function Calling API 的标准化**。
4. **MCP 的价值不是“能远程调用”**：普通 REST API 一样能远程调用。MCP 的核心收益是跨语言/框架统一的工具描述、发现、调用和复用。
5. **Stdio 不等于 MCP**：Stdio 只是进程间数据传输方式；只有双方传的是符合 MCP 规范的消息，才是 MCP Stdio。

## 1. Function Calling 到底是什么？

假设用户问：`查询贵州茅台 2025 年营业收入`。

### 1.1 不使用 MCP：直接注册本地工具

```text
用户问题
  ↓
Agent 把用户问题 + 可用 Tool Schema 发送给 LLM
  ↓
LLM 返回 tool call：getFinancialData({stockCode:"600519",year:2025})
  ↓
Agent / Spring AI 根据工具名找到 ToolCallback
  ↓
调用 Java 方法 FinancialTools.getFinancialData(...)
  ↓
FinancialService → 数据库 / 外部 API
  ↓
Tool Result 返回 Agent，再交给 LLM 生成最终回答
```

Spring AI 风格示意：

```java
@Component
public class FinancialTools {
    private final FinancialService financialService;

    public FinancialTools(FinancialService financialService) {
        this.financialService = financialService;
    }

    @Tool(description = "查询上市公司指定年份的财务数据")
    public FinancialData getFinancialData(
            @ToolParam(description = "股票代码") String stockCode,
            @ToolParam(description = "财报年份") Integer year) {
        return financialService.query(stockCode, year);
    }
}
```

模型没有执行 Java，也不知道数据库连接方式；它只是返回调用意图。`@Tool` 和 ToolCallback 用来让 Agent 认识和执行这个方法。实际注解及注册 API 取决于 Spring AI 版本。

### 1.2 Function Calling 不代表只能调用本地方法

`ToolCallback` 内部可以调用本地 Java Service、REST API、消息队列、数据库，或者 MCP Client。比如：

```text
LLM → Function Call → Java ToolCallback → HTTP REST API → 独立金融服务
```

所以，“Function Calling 必须把工具逻辑冗余写在 Agent 项目里”**不成立**。你可以用公共 Jar、REST API、RPC 等方式复用能力；只是标准化的工具发现与适配需要另行设计。

## 2. MCP 解决了什么问题？

当一个工具要给 Spring AI（Java）、LangGraph（Python）、Claude Code、Codex 等多个客户端复用时，逐一写适配器可能造成重复劳动。

MCP 在 AI 应用和工具提供方之间约定了通用规范：

| 标准能力 | 典型 MCP 方法 | 含义 |
|---|---|---|
| 工具发现 | `tools/list` | 返回工具名称、描述、输入参数 Schema |
| 工具执行 | `tools/call` | 按名称和参数调用工具 |
| 资源读取 | `resources/read` | 向客户端提供资源内容 |
| 提示模板获取 | `prompts/get` | 提供可复用 Prompt 模板 |

工具定义样例（来自 `tools/list` 返回列表中的单个工具，非完整响应）：

```json
{
  "name": "get_financial_data",
  "description": "查询上市公司财务数据",
  "inputSchema": {
    "type": "object",
    "properties": {
      "stockCode": {"type":"string","description":"股票代码"},
      "year": {"type":"integer","description":"年份"}
    },
    "required": ["stockCode","year"]
  }
}
```

此 Schema 与编写工具时所用的 Java/Python/Go 无关。支持相应 MCP 规范的客户端可以读取、注册并调用此工具。

> MCP 可提供 Tools、Resources、Prompts；不能把 MCP 简化成“远程 Function Calling”。

## 3. 模型返回 Tool Call 后，究竟由谁执行 MCP Tool？

这是整个机制最重要的一环：**先发现并注册映射，后根据映射执行调用**。

### 3.1 发现与注册阶段

```text
Financial MCP Server
       │   tools/list
       ▼
Financial MCP Client 获得工具定义
       │
       ▼
MCP Tool Adapter（例如 Spring AI SyncMcpToolCallback）
       │ 保存工具名称/Schema + 负责连接的 MCP Client
       ▼
Agent Tool Registry / ToolCallbackProvider
       │
       ▼
模型请求中的 Tool Schema
```

这意味着 Agent 通常**不是**在 LLM 每次返回工具名之后才扫描全部 MCP Server。客户端已发现工具并注册对应执行器。

### 3.2 实际执行阶段

```text
LLM 返回：get_financial_data({stockCode:"600519",year:2025})
  ↓
Spring AI Tool Calling 管理层匹配已注册的 ToolCallback
  ↓
SyncMcpToolCallback（知道工具定义及所属 MCP Client）
  ↓
MCP Client.callTool(name, arguments)
  ↓
JSON-RPC：method = tools/call
  ↓
Financial MCP Server 分发到本地业务方法
  ↓
返回 CallToolResult
  ↓
Agent 将结果纳入上下文，LLM 继续生成回答
```

典型 `tools/call` 请求：

```json
{
  "jsonrpc":"2.0",
  "id":2,
  "method":"tools/call",
  "params":{
    "name":"get_financial_data",
    "arguments":{"stockCode":"600519","year":2025}
  }
}
```

请分清 `method:"tools/call"`（MCP 协议方法）与 `name:"get_financial_data"`（业务工具名称）。

### 3.3 多个 Server 的同名工具怎么办？

例如两个 Server 都提供 `search`，模型侧应采用唯一的工具名称，内部保存映射：

| 模型侧工具名 | Client | 服务端工具名 |
|---|---|---|
| `financial_search` | FinancialClient | `search` |
| `report_search` | ReportClient | `search` |

工具映射/前缀、工具白名单、权限检查属于 Agent/框架的责任。MCP 只提供协议标准，不保证所有工具自动有权调用，也不保证工具刚发布就能被模型立即使用。

## 4. JSON-RPC：MCP 消息的结构

MCP 使用 JSON-RPC 2.0 编码消息（不同协议修订的具体元信息、生命周期要求可能有所不同）。

```json
{
  "jsonrpc":"2.0",
  "id":1001,
  "method":"tools/list"
}
```

字段含义：

- `jsonrpc`：消息使用 JSON-RPC 2.0。
- `id`：标识请求，使响应能与请求对应。
- `method`：调用的 MCP 方法，例如 `tools/list`、`tools/call`。
- `params`：该方法的参数。
- 普通 Request 要求响应；Notification 通常没有 `id`，不要求响应。

**JSON-RPC 负责“消息长什么样”，不等于某种网络传输方式。** 同一条 MCP 消息可以通过 Stdio 或 Streamable HTTP 传输。

## 5. Stdio：为什么不需要端口？

Stdio = Standard Input/Output。它不是 TCP，也不是 HTTP，而是客户端启动子进程后，借助操作系统的**匿名管道（Pipe）**传递数据。

```text
Agent 进程（MCP Client）       MCP Server 子进程
        │                         │
        ├──写入───────────────→ stdin
        │   JSON-RPC 请求          │
        │                         │ 执行工具
        │   JSON-RPC 响应          │
        └──读取←──────────────── stdout

        stderr：服务端日志（与协议输出分开）
```

### 5.1 与 Java I/O 的关系

- `System.in`：子进程从中读取 MCP 消息。
- `System.out`：子进程将 MCP 响应写回父进程。
- `System.err`：用于日志输出，避免污染协议消息。
- 从父进程视角，`process.getOutputStream()` 用于**写入子进程 stdin**；`process.getInputStream()` 用于**读取子进程 stdout**。

```java
Process process = new ProcessBuilder("java", "-jar", "financial-mcp-server.jar")
        .start();

OutputStream toChildStdin = process.getOutputStream();
InputStream fromChildStdout = process.getInputStream();
```

这只是展示 Java 进程管道，并非完整 MCP SDK 实现。MCP Stdio 还需要正确处理协议消息、行分隔、响应关联等。按规范，stdout 不允许写入任意普通日志。

### 5.2 Stdio 为什么存在？

本地代码搜索、文件读取、开发辅助功能没有必要为了一个工具再开启 HTTP 监听端口。Stdio 可以保持独立进程和语言无关的 MCP 接口，而无须启动 Web Server。

**注意两点：**

1. Shell 工具执行 `rg`/`git` 时，也可能通过 stdout 收集文本，但这不代表它调用了 MCP。那只是普通子进程 I/O。
2. MCP Stdio Server 通常是每个 Host/Client 分别启动的本地子进程，不是一个可直接供网络上多个客户端共享的监听服务。

## 6. Streamable HTTP：远程共享的 MCP Server

```text
Spring AI Agent / MCP Client
       │ HTTPS POST JSON-RPC
       ▼
https://financial.example.com/mcp
       │
Financial MCP Server
       │
FinancialService / PostgreSQL / 第三方金融 API
```

- 适合独立部署、跨机器访问、多个 Agent 复用。
- 基于 HTTP 传递 MCP 消息；依协议与请求情况，可返回普通 JSON 或 SSE 流。
- **Streamable HTTP 不表示所有请求都必须使用 SSE。**
- 网络端口、域名、HTTPS、鉴权、网关等由部署环境负责。

对比：

| 项目 | Stdio | Streamable HTTP |
|---|---|---|
| 服务形态 | 客户端启动本地子进程 | 独立网络服务 |
| 通信 | stdin/stdout 管道 | HTTP POST/响应，适用时 SSE |
| 是否需要 TCP 监听端口 | 不需要 | 通常需要 |
| 典型场景 | IDE、本地文件系统 | 企业金融 API、跨服务复用 |
| 安全关注点 | 本地进程权限、路径、环境变量 | 认证、授权、网络暴露、租户隔离 |

## 7. 传输方式是 Server 代码决定，还是配置决定？

**准确结论：工具的业务代码通常与传输无关；但 Server 必须具有对应的传输实现，才能通过配置启用。**

这需要同时满足三项：

1. Server 项目引入了所需传输实现（SDK / Starter）。
2. Server 运行时启用了该传输入口（启动命令、配置、Web 容器等）。
3. Client 配置了与 Server 兼容的传输方式。

不能认为“随便写一个 Server，只要改 YAML 就能从 Stdio 变 HTTP”。如果程序根本没有 HTTP 传输实现，配置也无能为力。

### 7.1 Spring AI Server（示例）

**Stdio：** 使用 `spring-ai-starter-mcp-server`，配置：

```yaml
spring:
  ai:
    mcp:
      server:
        stdio: true
```

**Streamable HTTP（WebMVC）：** 使用 `spring-ai-starter-mcp-server-webmvc`，配置：

```yaml
spring:
  ai:
    mcp:
      server:
        protocol: STREAMABLE
```

业务工具 `getFinancialData(...)` 可以保持不变，但 Starter、运行环境和配置需要调整。不要在 Stdio 模式往 stdout 输出 Spring Boot 启动日志。

### 7.2 Spring AI Client（示例）

**Stdio 客户端：**

```yaml
spring:
  ai:
    mcp:
      client:
        stdio:
          connections:
            financial:
              command: java
              args:
                - -jar
                - /opt/financial-mcp-server.jar
```

**Streamable HTTP 客户端：**

```yaml
spring:
  ai:
    mcp:
      client:
        streamable-http:
          connections:
            financial:
              url: http://localhost:8080
              endpoint: /mcp
```

注意 `url` 是基本地址、`endpoint` 是路径；不要在采用此配置格式时把 `/mcp` 同时写两次。示例对应当前 Spring AI 文档，实际属性、注解与依赖以项目所用版本为准。

### 7.3 Client 和 Server 不匹配怎么办？

- 只有 Stdio 的 Server，不能直接被 HTTP Client 访问。
- 只有 HTTP 入口的 Server，不能直接被 Stdio Client 作为管道程序调用。
- 可以编写同时支持两种传输的部署方式，或设置**桥接/代理**；但协议不会自动转换。
- 传输匹配还不够：协议版本、认证、权限等也要兼容。

## 8. 经典 MCP 生命周期与版本注意事项

以 **2025-11-25 经典协议** 为例：

```text
Client ── initialize ──────→ Server
Client ←─ initialize result ─ Server
Client ── notifications/initialized → Server
Client ── tools/list ──────→ Server
Client ←─ 工具列表 ───────── Server
Client ── tools/call ──────→ Server
Client ←─ 调用结果 ───────── Server
```

工具列表可缓存；**通常无须每次 Tool Call 前重新调用 `tools/list`**。

**版本警示：** MCP 持续演进；2026-07-28 规范修订对初始化与请求元信息的处理有变化，不可把经典 `initialize` 时序当成适用于所有新版本的永久规则。实际应先确认 Client SDK、Server SDK 兼容的协议修订，并以双方实现的版本为准。

## 9. Claude Code / Codex 等 Coding Agent 的内置工具，与 MCP 有什么关系？

这些 Agent 通常有统一的工具注册/分发层，但执行后端可能完全不同：

```text
LLM → Tool Call → Agent Tool Dispatcher
                         ├── 内置文件工具 → 本地代码/文件 API
                         ├── Shell 工具 → 启动 rg/git/bash 子进程
                         └── MCP 工具 → MCP Client → MCP Server
```

- `read_file`：可能直接用文件系统 API。
- `grep` / `shell`：可能启动命令行子进程，收集退出码和 stdout/stderr。
- MCP 工具：由注册的 MCP Client 发送标准 MCP 请求。

**不是每个内置工具都走 MCP；也不是调用了子进程就等于使用 MCP Stdio。**

为什么不用 MCP 统一包装所有本地工具？因为直接调用本地函数更简单，避免额外序列化、协议适配、进程管理和故障点。MCP 最值得用于跨应用、跨语言、可独立部署、可扩展的共享工具。

## 10. 金融 RAG Agent：推荐落地架构

```text
用户
 ↓
Spring Boot Financial Agent
 ├── LangGraph4j：Agent 编排
 ├── Spring AI：LLM / Function Calling
 ├── RAG Pipeline：解析、Embedding、混合检索、Rerank
 │      └── 内部 Java Service（不必 MCP 化）
 └── MCP Client
        └── Streamable HTTP
               └── Financial MCP Server
                      ├── 财务指标查询
                      ├── 外部金融数据源
                      └── 权限、审计、超时、租户隔离
```

### 建议的选型规则

| 能力 | 更适合的方案 | 理由 |
|---|---|---|
| RAG 内部切分/检索/重排 | Java Service / Pipeline Node | 内部高频流程，直接调用更简单 |
| 单 Agent 内部少量业务工具 | Spring AI `@Tool` | 直接开发、低适配成本 |
| 多 Java 服务共用领域代码 | 公共模块 / REST | 不一定需要 MCP |
| 本地跨客户端代码分析工具 | MCP Stdio | 同一工具可供不同 Host 配置使用 |
| 共享金融数据查询能力 | MCP Streamable HTTP | 标准发现/调用，支持独立部署 |

生产环境特别注意：HTTP MCP Server 的**鉴权、授权、敏感数据权限与审计**必须单独落实。Spring AI 的 Web MCP Starter 默认不会自动给所有 MCP 工具提供业务级访问控制；不应未经保护就暴露公网。

## 11. 高频面试题（速记）

**Q1：MCP 和 Function Calling 有什么区别？**  
A：Function Calling 是模型表达工具调用意图的机制；MCP 是 AI 客户端与外部能力服务之间的标准协议。二者配合而不是互斥。

**Q2：Function Calling 是不是只能调用本地函数？**  
A：不是。工具实现可以调用 REST、RPC 或数据库。MCP 解决的是标准化发现和接入，不是“第一次能远程调用”。

**Q3：LLM 返回工具名后，Agent 怎么知道调用哪个 MCP Server？**  
A：连接阶段通过 `tools/list` 发现工具，并注册工具名/Schema/所属 Client 的映射；执行时 Tool Dispatcher 找到对应 Callback，MCP Client 发送 `tools/call`。注意同名工具的命名空间和映射冲突。

**Q4：Stdio 为什么不需要端口？**  
A：它基于父子进程的标准输入输出管道，属于操作系统进程间通信，不使用 TCP 监听端口。

**Q5：执行 `rg` 命令用了 stdout，是 MCP Stdio 吗？**  
A：不一定。stdout 是普通进程输出机制；必须按 MCP JSON-RPC 规范进行交互，才算 MCP Stdio。

**Q6：MCP Server 的传输方式能只靠配置切换吗？**  
A：前提是相关传输组件已在代码或框架中实现。Spring AI 下通常通过 Starter + 配置切换，Tool 业务代码可以不改。

**Q7：为什么本地工具还要用 MCP Stdio？**  
A：重点是工具与 Host 解耦、跨语言和客户端复用，以及进程边界，而不是本地调用性能。

**Q8：所有工具都应该 MCP 化吗？**  
A：不应该。只在当前 Agent 内部运行的高频功能通常直接调用；需要跨应用共享的能力再考虑 MCP。

**Q9：MCP Server 新增 Tool，LLM 就立刻能用吗？**  
A：未必。客户端需发现或刷新工具，Agent 需将工具注册给模型并做权限控制，模型才可能调用。

**Q10：Stdio 与 Streamable HTTP 如何选？**  
A：本地子进程工具选 Stdio；共享远程服务选 Streamable HTTP。还需考虑安全、进程隔离、认证、资源管理。

## 12. 建议练习

1. 用 Spring AI 写一个 `get_financial_data` MCP Server（先用 Stdio）。
2. 在 Spring AI Client 中配置 `stdio.connections`，观察工具发现、LLM Tool Call、工具结果。
3. 将相同业务工具改为 WebMVC + `protocol: STREAMABLE`，客户端改为 `streamable-http.connections`。
4. 比较两种传输下工具 Schema / Tool Result 是否保持一致，记录 HTTP/进程日志差异。
5. 为两个 MCP Server 都注册 `search`，验证工具名冲突解决和工具权限控制。

## 13. 官方参考资料

- MCP 最新规范入口：https://modelcontextprotocol.io/specification
- MCP 2025-11-25 Transport：https://github.com/modelcontextprotocol/modelcontextprotocol/blob/main/docs/specification/2025-11-25/basic/transports.mdx
- MCP 2026-07-28 Transport：https://github.com/modelcontextprotocol/modelcontextprotocol/blob/main/docs/specification/2026-07-28/basic/transports/index.mdx
- Spring AI MCP Server Boot Starter：https://docs.spring.io/spring-ai/reference/api/mcp/mcp-server-boot-starter-docs.html
- Spring AI MCP Client Boot Starter：https://docs.spring.io/spring-ai/reference/api/mcp/mcp-client-boot-starter-docs.html

---

**一句话复盘：** LLM 用 Function Calling 提出工具调用意图；Agent 通过 ToolCallback / Tool Registry 执行它；需要调用外部 MCP Tool 时，MCP Client 通过 Stdio 或 Streamable HTTP 发送标准协议消息，MCP Server 执行业务后返回结果。传输怎么选由 Server 的传输实现与部署方式、Client 的连接配置共同决定。


## 14. MCP Server 更新 Tool 后，Client 如何同步？

> **核心结论：** MCP 提供了“工具列表已变更”的通知机制，但“收到通知 → 重新发现 → 更新 Agent Tool Registry → 让后续 LLM 请求使用新 Schema”是一个需要各层配合的完整流程，并不是自动魔法。

### 14.1 运行期间工具发生变化

假设 Financial MCP Server 最初提供：

```text
get_financial_data
get_stock_price
```

之后新增 `get_financial_report`，或者修改原有工具的参数 Schema。一个典型的刷新流程是：

```text
Server 更新工具列表
   ↓
Server 发送 notifications/tools/list_changed（若已启用/订阅该能力）
   ↓
Client 收到通知，重新调用 tools/list
   ↓
Client 获取最新的 Tool name / description / inputSchema
   ↓
Agent 刷新 ToolCallback / Tool Registry
   ↓
后续模型请求获得最新工具定义
```

经典 MCP 的变更通知是 **JSON-RPC Notification**，不包含请求 `id`：

```json
{"jsonrpc":"2.0","method":"notifications/tools/list_changed"}
```

Client 通常再通过以下请求重新获取完整工具列表（示意，省略版本相关元数据）：

```json
{"jsonrpc":"2.0","id":10,"method":"tools/list"}
```

**注意两个不同的“同步”：**

1. **MCP 层更新**：MCP Client 知道 Server 最新有哪些工具。
2. **Agent/LLM 层更新**：应用将新 Schema 注册到模型请求使用的工具集合。只有完成第二步，LLM 才可能选择新工具。

Spring AI 中能否自动完成第二步，取决于具体版本、ToolCallbackProvider 使用方式、是否缓存了启动时的工具列表等；不能笼统说“只要配置 MCP Starter 就一定热更新”。

### 14.2 Server 重新部署和动态修改工具不是一回事

- **不停机动态修改：** Server 可以通过已有通知通道发出 `list_changed`，Client 据此刷新。
- **重启后新版本：** 旧连接或子进程可能已经终止，变更通知可能根本没有机会送达。Client 恢复连接后，应该**主动重新执行 `tools/list`**，不要只依赖通知。

### 14.3 变更兼容性

| 变更 | 对已有 Client 的影响 | 建议 |
|---|---|---|
| 新增工具 | 一般不会破坏旧工具 | 刷新 Registry 后对新请求开放 |
| 修改 description | 模型的工具选择可能变化 | 在下一轮模型请求使用新描述 |
| 新增必填参数 | 旧模型请求/旧调用可能不兼容 | 尽量使用兼容演进或新工具名 |
| 删除工具/修改参数类型 | 可能使在途调用失败 | 版本化、灰度、明确错误处理 |

**并发调用建议：** 当前 Agent 运行中的某个工具调用可以继续使用已绑定的工具快照；新版本尽量从下一次模型请求生效，避免“模型根据旧 Schema 选工具，但执行器已换成新 Schema”的不一致。

### 14.4 版本差异提醒

经典版 MCP 中，Server 可在协商并支持变更通知的条件下向客户端发送 `notifications/tools/list_changed`。2026-07-28 版引入显式订阅模型，客户端需按新规范建立相关订阅后才能接收对应通知。**具体支持状况要以所使用 SDK 与协议版本为准。**

---

## 15. Server 怎么主动通知 Client？是否有 TCP 长连接？

### 15.1 Stdio：不需要 TCP，通过管道推送

```text
Agent / MCP Client                 MCP Server 子进程
        │                                │
        ├───────── stdin ──────────────►│  Client 写请求
        │                                │
        │◄──────── stdout ──────────────┤  Server 写响应或通知
```

Server 可以在其 stdout 输出符合 MCP 格式的 JSON-RPC Notification；Client 对 stdout 进行持续读取，从而收到主动通知。**这是操作系统管道 IPC，不需要 TCP socket、监听端口或 HTTP。**

这里的 stdout 是 MCP 协议通道，不应混入普通日志；日志通常写向 stderr。

### 15.2 Streamable HTTP：通知依赖可用的响应流/订阅，而不是普通 Keep-Alive

使用 HTTP/1.1 或 HTTP/2 时，底层通常通过 TCP 传输（HTTP/3 则可通过 QUIC/UDP）；但要区分：

- **TCP 连接**：底层传输连接。
- **HTTP Keep-Alive**：复用连接，**不等于**服务端可向已完成的请求随意推消息。
- **SSE（Server-Sent Events）**：保持一个 HTTP 响应流持续打开，让 Server 向 Client 发送事件。

经典 Streamable HTTP 可以使用支持的 SSE 流传递 Server→Client 消息（包括适用的工具变更通知）；新版 MCP 则采用明确的订阅机制，例如通过 `subscriptions/listen` 建立可接收相关通知的事件流。

```text
MCP Client                         MCP Server
    │                                  │
    ├── 建立受支持的 SSE 流/订阅 ───────►│
    │◄── 持续的事件响应流 ──────────────┤
    │                                  │
    │◄── tools/list_changed ───────────┤
    │                                  │
    ├── POST tools/list ───────────────►│
    │◄── 返回最新 Tool Schema ─────────┤
```

**判断题：** “HTTP 有 TCP 长连接，所以 Server 天然能够主动推送 MCP 通知”——错误。必须有符合该 MCP 版本的通知传递方式；普通已结束的 HTTP 响应不能追加消息。

---

## 16. MCP Server 重启后，谁来恢复连接？

**核心原则：** MCP 协议定义消息交互，但不对每一种 SDK 强制保证自动重连、进程重启、重试、恢复订阅和 Registry 刷新。一般由 **MCP Client / SDK / Agent 运行时**实现通信恢复，由部署环境或进程管理器维护服务端进程。

### 16.1 Stdio Server 崩溃或退出

一般情况下，Client 负责启动本地 MCP Server 子进程，因此也常由 Client 负责恢复：

```text
MCP Server 子进程退出
  ↓
Client 发现 EOF、管道异常、进程退出或请求超时
  ↓
清理旧 stdin/stdout 管道与进程资源
  ↓
按重试策略重新启动子进程
  ↓
重建 Stdio 通道
  ↓
按所选协议版本重新完成必要的初始化/交互
  ↓
重新 tools/list，刷新 Registry
```

**不要假设所有 MCP Client 都会自动执行上述流程**；需要核实 SDK 机制并做好测试。

### 16.2 HTTP Server 重新部署

```text
远程 MCP Server 重启
  ↓
旧 HTTP 连接/SSE 流中断，部分请求报错
  ↓
Docker / Kubernetes / systemd 等恢复 Server 实例
  ↓
MCP Client 重新发起请求、必要时重新建连接
  ↓
恢复需要的订阅，并主动 tools/list
  ↓
Agent 刷新工具列表，继续服务
```

HTTP Client **通常不负责启动远端 Server**；它负责通信层恢复。Server 地址不变，也不意味着恢复前的协议会话一定仍有效：经典版的 Session 可能丢失，可能需要重新初始化。2026-07-28 版取消协议级 Session，但仍需要按新规范恢复订阅及工具状态。

### 16.3 重连不等于重试所有 Tool

Server 在执行工具途中重启时，Client 可能无法确认工具是否**已经执行成功**。因此：

- 查询财务数据等**只读操作**通常更适合在有限次数内重试。
- 创建订单、发邮件、支付等**有副作用的工具**不能盲目自动重试；必须考虑幂等键、操作状态查询和权限确认。
- 应设置超时、指数退避、最大重试次数和错误监控。
- 通知可能丢失，**恢复后主动 `tools/list`** 是重要的兜底机制。

### 16.4 推荐的金融 Agent 职责划分

| 模块 | 责任 |
|---|---|
| MCP Server | 暴露 Tool、按支持的协议发送变更通知、执行业务 |
| MCP Client / SDK | 通信、协议解析、识别连接异常、（依实现）恢复连接 |
| Agent 连接管理 | 重连策略、恢复订阅、重新发现工具 |
| Tool Registry / ToolCallbackProvider | 工具名与执行器映射、更新 Schema、防止同名冲突 |
| 模型调用层 | 后续 LLM 请求采用最新可用且获授权的工具定义 |
| Docker / Kubernetes / systemd | 远程 Server 进程存活、部署、健康检查和恢复 |

### 16.5 生产环境检查清单

- [ ] 启动/连接时能通过 `tools/list` 正确发现工具。
- [ ] Server 新增、删除、修改 Tool 后，通知或重新发现能同步更新 Registry。
- [ ] Server 重启或 SSE 断开，Client 可以按策略恢复。
- [ ] 恢复后能够重新订阅通知，而不只是恢复普通 HTTP 请求。
- [ ] 已有调用使用一致的 Schema；新调用不会引用已删除工具。
- [ ] 对有副作用的工具限制自动重试，落实幂等和权限校验。
- [ ] 有连接状态、重试次数、工具调用失败率的日志与指标。

---

## 17. 本轮补充：高频面试问答

**Q11：MCP Server 新增 Tool，Agent 是否立刻能调用？**  
A：不保证。Server 发工具变更通知（或 Client 主动轮询/重连后发现）→ Client `tools/list` → Agent 更新 ToolCallback/Registry → 后续 LLM 请求传入新 Schema，才有机会调用。

**Q12：MCP Server 如何主动通知 Client？他们有 TCP 连接吗？**  
A：Stdio 通过进程管道 stdout 推送，不需要 TCP；远程 HTTP 模式通常借助受支持的 SSE 事件流传送，HTTP/1.1/2 通常底层为 TCP，但 Keep-Alive 不能代替 SSE 订阅。

**Q13：Server 重启后谁负责重连？**  
A：一般由 Client/SDK/Agent 运行时负责通信恢复；Stdio 可能需要重启子进程，HTTP Server 进程通常由部署平台管理。具体是否自动恢复取决于实现，而不是 MCP 规范的强制保证。

**Q14：为什么恢复连接后还要 tools/list？**  
A：Server 重启期间工具列表可能变化，原有通知可能丢失，Client 需要重新确认当前能力，刷新 Registry。

**Q15：HTTP 已复用一个 TCP 连接，为什么不能直接推送变更？**  
A：TCP 连接复用只提供传输通道，MCP 消息仍须使用 HTTP 协议支持的 SSE 响应流或版本规定的订阅机制；不能向已结束的普通 HTTP 响应追加消息。

---

## 更新记录

- **2026-11-04（补充）**：新增工具动态更新、`notifications/tools/list_changed`、Client/Agent 的工具同步、Stdio 与 HTTP 主动通知、SSE 与 TCP 的区别、Server 重启后的重连与恢复、幂等重试及面试问答。
