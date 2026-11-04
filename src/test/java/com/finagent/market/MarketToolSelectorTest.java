package com.finagent.market;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * 模型选工具：只验「怎么解析 tool_calls」与「解析不出来时回落」。
 *
 * <p>不去断言 Prompt 文案 —— 提示词会改，断言它只会让测试变脆；
 * 真正要守住的是「永远不抛异常」与「参数校验在客户端再做一遍」。
 */
@ExtendWith(MockitoExtension.class)
class MarketToolSelectorTest {

    @Mock private ChatModel chatModel;

    private MarketToolSelector selector;

    @BeforeEach
    void setUp() {
        selector = new MarketToolSelector(chatModel, new ObjectMapper());
    }

    private void reply(AssistantMessage.ToolCall... calls) {
        when(chatModel.call(any(org.springframework.ai.chat.prompt.Prompt.class)))
                .thenReturn(new ChatResponse(List.of(new Generation(AssistantMessage.builder()
                        .content("")
                        .toolCalls(List.of(calls))
                        .build()))));
    }

    @Test
    @DisplayName("get_quote：解析 code，参数原样交给网关")
    void quote() {
        reply(new AssistantMessage.ToolCall("call-1", "function", "get_quote", "{\"code\":\"300059\"}"));

        Optional<MarketToolSelector.Picked> picked = selector.pick("东方财富现在多少钱", null);

        assertThat(picked).isPresent();
        assertThat(picked.get().tool()).isEqualTo("get_quote");
        assertThat(picked.get().arguments()).containsEntry("code", "300059");
        assertThat(picked.get().arguments()).doesNotContainKey("days");
    }

    @Test
    @DisplayName("get_history：days 超过 120 在客户端就截断，不指望服务端兜")
    void historyDaysClamped() {
        reply(new AssistantMessage.ToolCall("call-1", "function", "get_history",
                "{\"code\":\"300059\",\"days\":999}"));

        MarketToolSelector.Picked picked = selector.pick("东方财富近三年走势", null).orElseThrow();

        assertThat(picked.tool()).isEqualTo("get_history");
        assertThat(picked.arguments()).containsEntry("days", 120);
    }

    @Test
    @DisplayName("模型没调工具（普通文本回复）→ 空，调用方回落规则")
    void noToolCall() {
        reply();

        assertThat(selector.pick("东方财富现在多少钱", null)).isEmpty();
    }

    @Test
    @DisplayName("代码不合法（abc）→ 空：宁可回落规则，也不要拿坏参数去调服务")
    void invalidCode() {
        reply(new AssistantMessage.ToolCall("call-1", "function", "get_quote", "{\"code\":\"abc\"}"));

        assertThat(selector.pick("东方财富现在多少钱", null)).isEmpty();
    }

    @Test
    @DisplayName("未知工具名 → 空")
    void unknownTool() {
        reply(new AssistantMessage.ToolCall("call-1", "function", "get_quote_v2", "{\"code\":\"300059\"}"));

        assertThat(selector.pick("东方财富现在多少钱", null)).isEmpty();
    }

    @Test
    @DisplayName("参数不是合法 JSON → 空，不抛")
    void malformedArguments() {
        reply(new AssistantMessage.ToolCall("call-1", "function", "get_quote", "{not json"));

        assertThat(selector.pick("东方财富现在多少钱", null)).isEmpty();
    }

    @Test
    @DisplayName("模型调用本身抛异常 → 空（行情支路仍可用规则跑）")
    void modelThrows() {
        when(chatModel.call(any(org.springframework.ai.chat.prompt.Prompt.class)))
                .thenThrow(new IllegalStateException("rate limited"));

        assertThat(selector.pick("东方财富现在多少钱", null)).isEmpty();
    }

    @Test
    @DisplayName("多个 tool_call 只取第一个合法的")
    void firstValidCallWins() {
        reply(new AssistantMessage.ToolCall("call-1", "function", "get_quote", "{\"code\":\"bad\"}"),
                new AssistantMessage.ToolCall("call-2", "function", "get_history", "{\"code\":\"600519\"}"));

        MarketToolSelector.Picked picked = selector.pick("茅台最近走势", null).orElseThrow();

        assertThat(picked.tool()).isEqualTo("get_history");
        assertThat(picked.arguments()).containsEntry("code", "600519");
    }

    @Test
    @DisplayName("服务端清单直接采用：描述与 schema 都用服务端那份")
    void usesServerSpecs() {
        List<ToolCallback> callbacks = MarketToolSelector.definitions(List.of(
                new MarketToolSpec("get_quote", "服务端版说明",
                        "{\"type\":\"object\",\"properties\":{\"code\":{\"type\":\"string\"}}}")));

        assertThat(callbacks).hasSize(1);
        ToolDefinition definition = callbacks.get(0).getToolDefinition();
        assertThat(definition.name()).isEqualTo("get_quote");
        assertThat(definition.description()).isEqualTo("服务端版说明");
        assertThat(definition.inputSchema()).contains("\"code\"");
    }

    @Test
    @DisplayName("search_stock 不进候选：那是公司解析的内部工具")
    void searchStockNotSelectable() {
        List<ToolCallback> callbacks = MarketToolSelector.definitions(List.of(
                new MarketToolSpec("search_stock", "搜索", "{\"type\":\"object\"}"),
                new MarketToolSpec("get_history", "日线", "{\"type\":\"object\"}")));

        assertThat(callbacks).extracting(c -> c.getToolDefinition().name()).containsExactly("get_history");
    }

    @Test
    @DisplayName("服务端没给 schema → 回落内置定义，不能让模型面对空 schema")
    void blankSchemaFallsBack() {
        List<ToolCallback> callbacks = MarketToolSelector.definitions(List.of(
                new MarketToolSpec("get_quote", "服务端版说明", "  ")));

        assertThat(callbacks).hasSize(2);
        assertThat(callbacks.get(0).getToolDefinition().description()).contains("实时快照");
    }

    @Test
    @DisplayName("服务端没给描述 → 用内置描述补上")
    void blankDescriptionUsesBuiltin() {
        List<ToolCallback> callbacks = MarketToolSelector.definitions(List.of(
                new MarketToolSpec("get_history", "", "{\"type\":\"object\"}")));

        assertThat(callbacks.get(0).getToolDefinition().description()).contains("前复权");
    }

    @Test
    @DisplayName("pick 把服务端清单交给模型（而不是内置那份）")
    void pickUsesServerSpecs() {
        reply(new AssistantMessage.ToolCall("call-1", "function", "get_quote", "{\"code\":\"300059\"}"));

        Optional<MarketToolSelector.Picked> picked = selector.pick("东方财富现在多少钱", null,
                List.of(new MarketToolSpec("get_quote", "服务端版说明", "{\"type\":\"object\"}")));

        assertThat(picked).isPresent();
        org.mockito.ArgumentCaptor<org.springframework.ai.chat.prompt.Prompt> captor =
                org.mockito.ArgumentCaptor.forClass(org.springframework.ai.chat.prompt.Prompt.class);
        org.mockito.Mockito.verify(chatModel).call(captor.capture());
        ToolCallingChatOptions options = (ToolCallingChatOptions) captor.getValue().getOptions();
        assertThat(options.getToolCallbacks())
                .extracting(c -> c.getToolDefinition().description())
                .containsExactly("服务端版说明");
    }
}
