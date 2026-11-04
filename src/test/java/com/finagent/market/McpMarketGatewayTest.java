package com.finagent.market;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 行情网关的分流规则：传输失败 = 不可用（技术细节不外泄），业务失败 = 把服务端原话给用户。
 *
 * <p>这两类混在一起，用户会看到「java.io.IOException: pipe closed」这种话；
 * 反过来把「代码不合法」压成「服务不可用」，模型与用户都没法自救。
 */
@ExtendWith(MockitoExtension.class)
class McpMarketGatewayTest {

    @Mock private McpSyncClient client;

    private McpMarketGateway gateway;

    @BeforeEach
    void setUp() {
        gateway = new McpMarketGateway(client, new MarketProperties(), new ObjectMapper());
        // 多数用例不会走到 ensureReady（未知工具、参数校验），用 lenient 避免 UnnecessaryStubbing
        org.mockito.Mockito.lenient().when(client.isInitialized()).thenReturn(true);
    }

    private static McpSchema.CallToolResult result(Map<String, Object> structured) {
        return new McpSchema.CallToolResult(List.of(), false, structured, Map.of());
    }

    private static Map<String, Object> quote() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("ok", true);
        data.put("code", "300059");
        data.put("name", "东方财富");
        data.put("price", 18.63);
        data.put("changePct", -1.22);
        data.put("totalMarketCap", 294767583671L);
        data.put("tradedAt", "2026-11-04T15:00:00+08:00");
        return data;
    }

    @Test
    @DisplayName("快照：structuredContent 原样返回，数据时间取 tradedAt")
    void quoteSuccess() {
        when(client.callTool(any())).thenReturn(result(quote()));

        MarketGateway.Snapshot snapshot = gateway.call("get_quote", "300059", 0);

        assertThat(snapshot.data()).containsEntry("price", 18.63);
        assertThat(snapshot.asOf()).isEqualTo("2026-11-04T15:00:00+08:00");
    }

    @Test
    @DisplayName("工具名与参数按约定组装：get_quote 只有 code，get_history 带 days")
    void arguments() {
        when(client.callTool(any())).thenReturn(result(quote()));

        gateway.call("get_quote", "300059", 0);
        gateway.call("get_history", "300059", 5);

        ArgumentCaptor<McpSchema.CallToolRequest> captor =
                ArgumentCaptor.forClass(McpSchema.CallToolRequest.class);
        verify(client, times(2)).callTool(captor.capture());
        assertThat(captor.getAllValues().get(0).name()).isEqualTo("get_quote");
        assertThat(captor.getAllValues().get(0).arguments()).containsOnlyKeys("code");
        assertThat(captor.getAllValues().get(1).arguments()).containsEntry("days", 5);
    }

    @Test
    @DisplayName("没有 structuredContent 时回落解析 TextContent 里的 JSON")
    void textFallback() {
        String json = "{\"ok\":true,\"code\":\"300059\",\"price\":18.63,"
                + "\"tradedAt\":\"2026-11-04T15:00:00+08:00\"}";
        when(client.callTool(any())).thenReturn(new McpSchema.CallToolResult(
                List.of(new McpSchema.TextContent(json)), false, null, Map.of()));

        MarketGateway.Snapshot snapshot = gateway.call("get_quote", "300059", 0);

        assertThat(snapshot.data()).containsEntry("price", 18.63);
    }

    @Test
    @DisplayName("历史数据时间取最后一根 K 线的日期（收盘 15:00）")
    void historyAsOfFromLastBar() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("ok", true);
        data.put("bars", List.of(
                Map.of("date", "2026-11-02", "close", 18.71),
                Map.of("date", "2026-11-03", "close", 18.86)));
        when(client.callTool(any())).thenReturn(result(data));

        assertThat(gateway.call("get_history", "300059", 2).asOf())
                .isEqualTo("2026-11-03T15:00:00+08:00");
    }

    @Test
    @DisplayName("业务失败（ok:false）→ MarketApiException，message 就是服务端原话")
    void businessFailure() {
        when(client.callTool(any())).thenReturn(result(Map.of(
                "ok", false, "error", "股票代码应为 6 位数字，收到：abc", "errorType", "MarketApiException")));

        assertThatThrownBy(() -> gateway.call("get_quote", "abc", 0))
                .isInstanceOf(MarketApiException.class)
                .hasMessage("股票代码应为 6 位数字，收到：abc");
    }

    @Test
    @DisplayName("isError=true → MarketUnavailableException（协议层失败，细节不给用户看）")
    void protocolFailure() {
        when(client.callTool(any())).thenReturn(new McpSchema.CallToolResult(
                List.of(new McpSchema.TextContent("boom")), true, null, Map.of()));

        assertThatThrownBy(() -> gateway.call("get_quote", "300059", 0))
                .isInstanceOf(MarketUnavailableException.class);
    }

    @Test
    @DisplayName("传输层抛异常 → 不可用 + 冷却：第二次不再打子进程")
    void transportFailureCooldown() {
        when(client.callTool(any())).thenThrow(new IllegalStateException("pipe closed"));

        assertThatThrownBy(() -> gateway.call("get_quote", "300059", 0))
                .isInstanceOf(MarketUnavailableException.class);
        assertThatThrownBy(() -> gateway.call("get_quote", "300059", 0))
                .isInstanceOf(MarketUnavailableException.class)
                .hasMessageContaining("冷却");

        // 冷却期内不能再去 spawn 一次：否则每次请求都拉起一个 JVM
        verify(client, times(1)).callTool(any());
    }

    @Test
    @DisplayName("公司名搜索：解析 items、按 searchLimit 截断；查不到返回空列表不抛")
    void search() {
        when(client.callTool(any())).thenReturn(result(Map.of(
                "ok", true,
                "items", List.of(
                        Map.of("code", "300059", "name", "东方财富"),
                        Map.of("code", "600519", "name", "贵州茅台")))));

        List<MarketGateway.Hit> hits = gateway.search("东方财富");

        assertThat(hits).hasSize(2);
        assertThat(hits.get(0).code()).isEqualTo("300059");
        assertThat(hits.get(0).name()).isEqualTo("东方财富");
    }

    @Test
    @DisplayName("搜索失败不冒泡：公司名解析的兜底路径失败就是没找到")
    void searchFailureIsEmpty() {
        when(client.callTool(any())).thenThrow(new IllegalStateException("pipe closed"));

        assertThat(gateway.search("东方财富")).isEmpty();
    }

    @Test
    @DisplayName("不支持的工具名直接拒绝，不发给服务端")
    void unknownTool() {
        assertThatThrownBy(() -> gateway.call("drop_table", "300059", 0))
                .isInstanceOf(MarketApiException.class)
                .hasMessageContaining("不支持的行情工具");
        verify(client, times(0)).callTool(any());
    }

    @Test
    @DisplayName("days 写死上限 120、缺省 20")
    void clampDays() {
        assertThat(McpMarketGateway.clampDays(0)).isEqualTo(20);
        assertThat(McpMarketGateway.clampDays(999)).isEqualTo(120);
    }

    private static McpSchema.Tool tool(String name, String description) {
        return McpSchema.Tool.builder()
                .name(name)
                .description(description)
                .inputSchema(Map.of(
                        "type", "object",
                        "properties", Map.of("code", Map.of("type", "string")),
                        "required", List.of("code")))
                .build();
    }

    @Test
    @DisplayName("工具清单来自服务端，search_stock 被滤掉（它是公司解析的内部步骤）")
    void toolsComeFromServer() {
        when(client.listTools()).thenReturn(new McpSchema.ListToolsResult(
                List.of(tool("get_quote", "快照"), tool("search_stock", "搜索"), tool("get_history", "日线")), null));

        List<MarketToolSpec> specs = gateway.tools();

        assertThat(specs).extracting(MarketToolSpec::name).containsExactly("get_quote", "get_history");
        assertThat(specs.get(0).description()).isEqualTo("快照");
        assertThat(specs.get(0).inputSchema()).contains("\"code\"");
    }

    @Test
    @DisplayName("工具清单按 TTL 缓存：连续两次只往子进程拉一次")
    void toolsAreCached() {
        when(client.listTools()).thenReturn(new McpSchema.ListToolsResult(List.of(tool("get_quote", "快照")), null));

        gateway.tools();
        gateway.tools();

        verify(client, times(1)).listTools();
    }

    @Test
    @DisplayName("拉清单失败 → 空列表且不抛，调用方回落内置定义")
    void toolsFailureIsEmpty() {
        when(client.listTools()).thenThrow(new IllegalStateException("pipe closed"));

        assertThat(gateway.tools()).isEmpty();
    }
}
