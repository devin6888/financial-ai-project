package com.finagent.market;

import java.util.List;
import java.util.Map;

/**
 * 行情取数入口。实现有两种：真调 MCP（{@link McpMarketGateway}）与「未启用」（{@link UnavailableMarketGateway}）。
 *
 * <p>为什么还要 {@code call(tool, code, days)} 这条确定性入口：模型选工具是增强路径，
 * 模型没调、参数非法、超时，都要能退回「按规则算出来的调用」。
 */
public interface MarketGateway {

    /** 一次快照：原始返回 + 数据时间。{@code asOf} 必须非空，没有时间戳的行情比没有行情更危险。 */
    record Snapshot(Map<String, Object> data, String asOf) {
    }

    /** 搜索结果里的一个候选。 */
    record Hit(String code, String name) {
    }

    /** 确定性调用（规则兜底）。 */
    Snapshot call(String tool, String code, int days);

    /** 执行模型选出来的调用：{@code arguments} 是 tool_call 里那段 JSON 解析出来的 Map。 */
    Snapshot execute(String tool, Map<String, Object> arguments, int days);

    /** 按关键词查代码；查不到返回空列表，不抛异常。 */
    List<Hit> search(String keyword);

    /**
     * 服务端声明的可选用工具（已过滤掉内部工具 {@code search_stock}）。
     *
     * <p>空列表表示「拿不到」——调用方应回落到内置定义，而不是不挂工具。
     */
    default List<MarketToolSpec> tools() {
        return List.of();
    }
}
