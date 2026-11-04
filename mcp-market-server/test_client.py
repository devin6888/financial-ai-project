"""用官方 SDK 以 stdio 拉起 server.py，走一遍 initialize → tools/list → tools/call。

用途：不启动 Java 就能验证 MCP server 本身是对的（协议层 smoke test）。

    .venv\\Scripts\\python.exe test_client.py
"""

from __future__ import annotations

import asyncio
import json
import sys
from pathlib import Path

from mcp import ClientSession
from mcp.client.stdio import StdioServerParameters, stdio_client

HERE = Path(__file__).resolve().parent


def _text(result) -> str:
    parts = []
    for item in getattr(result, "content", []) or []:
        text = getattr(item, "text", None)
        if text:
            parts.append(text)
    if not parts and getattr(result, "structuredContent", None):
        return json.dumps(result.structured_content, ensure_ascii=False)
    return "\n".join(parts)


async def main() -> int:
    params = StdioServerParameters(
        command=sys.executable,
        args=[str(HERE / "server.py")],
        env={"PYTHONIOENCODING": "utf-8", "MARKET_HTTP_TIMEOUT": "10"},
    )
    async with stdio_client(params) as (read, write):
        async with ClientSession(read, write) as session:
            info = await session.initialize()
            print("server:", info.server_info.name, info.server_info.version)
            print("protocol:", info.protocol_version)
            print("capabilities:", info.capabilities.model_dump(exclude_none=True))

            tools = await session.list_tools()
            print("\ntools:")
            for tool in tools.tools:
                required = (tool.input_schema or {}).get("required", [])
                print(f"  - {tool.name}({', '.join(required)}) : {(tool.description or '')[:40]}")

            print("\ncall search_stock:")
            found = await session.call_tool("search_stock", {"keyword": "东方财富"})
            print("  isError:", found.is_error)
            print("  " + _text(found)[:300].replace("\n", " "))

            print("\ncall get_quote:")
            quote = await session.call_tool("get_quote", {"code": "300059"})
            print("  isError:", quote.is_error)
            data = json.loads(_text(quote))
            print("  ", {k: data.get(k) for k in ("name", "price", "changePct", "totalMarketCap", "peTtm", "tradedAt")})

            print("\ncall get_history(days=3):")
            hist = await session.call_tool("get_history", {"code": "300059", "days": 3})
            print("  isError:", hist.is_error)
            bars = json.loads(_text(hist))["bars"]
            print("  ", [(b["date"], b["close"], b["changePct"]) for b in bars])

            print("\ncall bad code:")
            bad = await session.call_tool("get_quote", {"code": "abc"})
            print("  isError:", bad.is_error, "| " + _text(bad)[:120].replace("\n", " "))
    return 0


if __name__ == "__main__":
    raise SystemExit(asyncio.run(main()))
