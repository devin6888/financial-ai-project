package com.finagent.market;

/**
 * 服务端（MCP）声明的一个可选工具，已归一化成 {@code ToolDefinition} 需要的三段。
 *
 * <p>为什么要从服务端拉：手写 schema 会漂移 —— 服务端加了字段、改了必填，主应用这边毫无感知，
 * 模型按旧 schema 填参数，客户端校验又拦不住，最后表现为「偶发参数错误」。
 * 服务端是工具协议的单一事实来源，这边只做过滤与兜底。
 *
 * @param name        工具名（只有 get_quote / get_history 会被拿去做选择，其余忽略）
 * @param description 给模型看的说明；服务端为空时用内置文案
 * @param inputSchema JSON Schema 字符串，直接塞进 {@code ToolDefinition.inputSchema}
 */
public record MarketToolSpec(String name, String description, String inputSchema) {

    public MarketToolSpec {
        description = description == null ? "" : description;
        inputSchema = inputSchema == null ? "" : inputSchema;
    }
}
