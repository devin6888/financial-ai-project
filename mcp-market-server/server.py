"""FinAgent 行情 MCP Server（stdio 传输）。

三个工具，全部走东方财富公开行情接口（不需要 API Key）：

- `search_stock(keyword)`  —— 名称/代码/拼音 → 候选股票（带 secid）
- `get_quote(code)`         —— 实时快照：最新价、涨跌幅、总市值、市盈率 TTM、市净率、换手率
- `get_history(code, days)` —— 最近 N 个交易日的前复权日线

为什么单独做成一个进程（而不是在 Java 里直接调 HTTP）：
这正是 MCP 的用途 —— 数据源不归主服务所有、且是否调用由模型判断。
主服务只认「工具」这个接口，换数据源（同花顺/新浪/付费行情）时不用改 Java 一行。

启动方式（由 Java 侧 MCP client 以 stdio 子进程拉起）：
    python server.py

单独调试（MCP Inspector 或 mcp 自带 client）：
    npx @modelcontextprotocol/inspector python server.py
"""

from __future__ import annotations

import os
from datetime import datetime, timedelta, timezone
from typing import Any

import httpx
from mcp.server.mcpserver import MCPServer

CN_TZ = timezone(timedelta(hours=8))
UA = (
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
    "(KHTML, like Gecko) Chrome/124.0 Safari/537.36"
)
QUOTE_URL = "https://push2.eastmoney.com/api/qt/stock/get"
KLINE_URL = "https://push2his.eastmoney.com/api/qt/stock/kline/get"
SEARCH_URL = "https://searchapi.eastmoney.com/api/suggest/get"
TIMEOUT = float(os.getenv("MARKET_HTTP_TIMEOUT", "10"))

mcp = MCPServer(
    name="finagent-market",
    version="0.1.0",
    instructions=(
        "提供 A 股实时行情（东方财富公开接口）。"
        "问「现在/今天/最新」的股价、市值、市盈率、市净率、换手率时用 get_quote；"
        "问多久涨了多少、近期走势时用 get_history；"
        "只知道公司名不知道代码时先 search_stock。"
        "这些数据不是年报口径，回答时必须标注数据时间。"
    ),
)


def _now() -> str:
    return datetime.now(CN_TZ).isoformat(timespec="seconds")


def _secid(code: str) -> str:
    """6 位代码 → 东财 secid。1=沪市，0=深市/北交所。"""
    digits = "".join(ch for ch in code if ch.isdigit())
    if len(digits) != 6:
        raise ValueError(f"股票代码应为 6 位数字，收到：{code!r}")
    return ("1." if digits[0] == "6" else "0.") + digits


def _scaled(value: Any, digits: int = 2) -> float | None:
    """东财整数价格字段按 10^digits 放大，null/- 表示无值。"""
    if value is None or value == "-" or value == "":
        return None
    try:
        return round(float(value) / (10**digits), digits)
    except (TypeError, ValueError):
        return None


def _failure(exc: Exception) -> dict[str, Any]:
    """工具级错误：协议层仍是成功响应，模型能读到原因并自行决定下一步。

    为什么不用抛异常：MCP 会把未识别异常压成「Error executing tool xxx」，
    真正有用的那句话（代码不合法 / 接口超时）就丢了。
    """
    message = str(exc).strip() or exc.__class__.__name__
    return {"ok": False, "error": message, "errorType": exc.__class__.__name__, "asOf": _now()}


async def _get_json(url: str, params: dict[str, Any]) -> dict[str, Any]:
    async with httpx.AsyncClient(timeout=TIMEOUT, headers={"User-Agent": UA}) as client:
        resp = await client.get(url, params=params)
        resp.raise_for_status()
        return resp.json()


@mcp.tool(
    description=(
        "按关键词查 A 股股票，返回候选（代码、名称、市场、secid）。"
        "用户只说了公司名（如「东方财富」）而没给 6 位代码时先调这个。"
    )
)
async def search_stock(keyword: str) -> dict[str, Any]:
    try:
        return await _search_stock(keyword)
    except Exception as exc:  # noqa: BLE001 - 统一转成工具级错误载荷
        return _failure(exc)


async def _search_stock(keyword: str) -> dict[str, Any]:
    payload = await _get_json(
        SEARCH_URL,
        {
            "input": keyword,
            "type": "14",
            "token": "D43BF722C8E33BDC906FB84D85E326E8",
            "count": "8",
        },
    )
    rows = ((payload or {}).get("QuotationCodeTable") or {}).get("Data") or []
    items = [
        {
            "code": row.get("Code"),
            "name": row.get("Name"),
            "market": row.get("SecurityTypeName"),
            "secid": row.get("QuoteID"),
        }
        for row in rows
        if row.get("Classify") == "AStock"
    ][:5]
    return {"ok": True, "keyword": keyword, "count": len(items), "items": items, "asOf": _now()}


@mcp.tool(
    description=(
        "查一只 A 股的实时快照：最新价、涨跌额/涨跌幅、今开/昨收/最高/最低、"
        "总市值与流通市值（元）、市盈率 TTM、市净率、换手率、成交额。"
        "code 传 6 位数字代码，如 300059。"
    )
)
async def get_quote(code: str) -> dict[str, Any]:
    try:
        return await _get_quote(code)
    except Exception as exc:  # noqa: BLE001
        return _failure(exc)


async def _get_quote(code: str) -> dict[str, Any]:
    secid = _secid(code)
    payload = await _get_json(
        QUOTE_URL,
        {
            "secid": secid,
            "fields": "f43,f44,f45,f46,f47,f48,f57,f58,f60,f86,f116,f117,f162,f167,f168,f169,f170",
        },
    )
    data = (payload or {}).get("data")
    if not data:
        raise ValueError(f"东财未返回 {code} 的行情（代码可能不存在或已退市）")
    traded_at = data.get("f86")
    return {
        "ok": True,
        "code": data.get("f57") or code,
        "name": data.get("f58"),
        "price": _scaled(data.get("f43")),
        "change": _scaled(data.get("f169")),
        "changePct": _scaled(data.get("f170")),
        "open": _scaled(data.get("f46")),
        "prevClose": _scaled(data.get("f60")),
        "high": _scaled(data.get("f44")),
        "low": _scaled(data.get("f45")),
        "volume": data.get("f47"),
        "amount": data.get("f48"),
        "totalMarketCap": data.get("f116"),
        "floatMarketCap": data.get("f117"),
        "peTtm": _scaled(data.get("f162")),
        "pb": _scaled(data.get("f167")),
        "turnoverPct": _scaled(data.get("f168")),
        "currency": "CNY",
        "source": "eastmoney/push2",
        "tradedAt": (
            datetime.fromtimestamp(int(traded_at), CN_TZ).isoformat(timespec="seconds")
            if traded_at
            else None
        ),
        "asOf": _now(),
    }


@mcp.tool(
    description=(
        "查一只 A 股最近 N 个交易日的前复权日线（默认 20 天，最多 120 天）："
        "日期、开高低收、成交量、成交额、涨跌幅、换手率。"
    )
)
async def get_history(code: str, days: int = 20) -> dict[str, Any]:
    try:
        return await _get_history(code, days)
    except Exception as exc:  # noqa: BLE001
        return _failure(exc)


async def _get_history(code: str, days: int) -> dict[str, Any]:
    if not 1 <= days <= 120:
        raise ValueError("days 必须在 1~120 之间")
    payload = await _get_json(
        KLINE_URL,
        {
            "secid": _secid(code),
            "fields1": "f1,f2,f3,f4",
            "fields2": "f51,f52,f53,f54,f55,f56,f57,f58,f59,f60,f61",
            "klt": "101",
            "fqt": "1",
            "end": "20500101",
            "lmt": str(days),
        },
    )
    data = (payload or {}).get("data")
    if not data or not data.get("klines"):
        raise ValueError(f"东财未返回 {code} 的日线数据")
    bars = []
    for line in data["klines"]:
        parts = line.split(",")
        bars.append(
            {
                "date": parts[0],
                "open": float(parts[1]),
                "close": float(parts[2]),
                "high": float(parts[3]),
                "low": float(parts[4]),
                "volume": float(parts[5]),
                "amount": float(parts[6]),
                "amplitudePct": float(parts[7]),
                "changePct": float(parts[8]),
                "change": float(parts[9]),
                "turnoverPct": float(parts[10]),
            }
        )
    return {
        "ok": True,
        "code": data.get("code"),
        "name": data.get("name"),
        "days": len(bars),
        "bars": bars,
        "source": "eastmoney/push2his",
        "asOf": _now(),
    }


if __name__ == "__main__":
    mcp.run(transport="stdio")
