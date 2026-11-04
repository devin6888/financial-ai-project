# finagent-market MCP Server

把「A 股实时行情」做成一个独立的 MCP Server（stdio 传输），供 FinAgent 的 Agent 作为 MCP Client 调用。

## 为什么单独一个进程

行情数据不归本服务所有，是否查询由问题决定 —— 这正是 MCP 的适用场景：
主服务只认「工具」接口，将来换数据源（新浪 / 同花顺 / 付费行情）不用改 Java 一行。

## 三个工具

| 工具 | 参数 | 返回 |
| --- | --- | --- |
| `search_stock` | `keyword` | 候选股票（代码、名称、市场、secid） |
| `get_quote` | `code`（6 位） | 最新价、涨跌额/幅、今开昨收、最高最低、总市值、流通市值、市盈率 TTM、市净率、换手率、成交额、行情时间 |
| `get_history` | `code`, `days`(1~120) | 最近 N 个交易日的前复权日线 |

数据源：东方财富公开接口（`push2` / `push2his` / `searchapi`），无需 API Key。

## 运行

```bash
python -m venv .venv
.venv/Scripts/pip install -r requirements.txt
.venv/Scripts/python test_client.py      # 协议层冒烟：initialize → tools/list → tools/call
```

Java 侧由 Spring AI 的 MCP client 以 stdio 子进程拉起，配置见 `src/main/resources/application.yml` 的
`spring.ai.mcp.client.stdio.connections.market`。

## 错误约定

工具**不抛异常**，统一返回 `{"ok": false, "error": "...", "errorType": "..."}`。
原因：MCP 会把未识别异常压成「Error executing tool xxx」，真正有用的那句话会丢，模型也无法自救。

## 已知边界

- 只支持 A 股（沪/深/北），不支持港股、美股、基金、债券；
- 行情是快照，不做盘中逐笔；
- 交易时段外返回的是最近一次收盘快照，`tradedAt` 会明确给出时间，回答时必须带上它。
