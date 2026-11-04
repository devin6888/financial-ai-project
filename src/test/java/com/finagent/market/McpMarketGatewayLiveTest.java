package com.finagent.market;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.ServerParameters;
import io.modelcontextprotocol.client.transport.StdioClientTransport;
import io.modelcontextprotocol.json.McpJsonDefaults;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 真起子进程的真行情用例：拉起 financial-mcp-server4j 的 jar，走 stdio 调一次东方财富。
 *
 * <p>默认不跑（需要网络、需要先 mvn package 那个独立项目），显式打开：
 * <pre>
 * $env:FINAGENT_MARKET_LIVE="true"; mvn -Dtest=McpMarketGatewayLiveTest test
 * </pre>
 * 这是唯一能证明「串起来的这条链路」真的通的用例 —— 单测里的 McpSyncClient 全是 mock。
 */
@EnabledIfEnvironmentVariable(named = "FINAGENT_MARKET_LIVE", matches = "true")
class McpMarketGatewayLiveTest {

    private static McpSyncClient client;
    private static McpMarketGateway gateway;

    @BeforeAll
    static void start() {
        Path jar = Path.of(System.getenv().getOrDefault("FINAGENT_MCP_JAR",
                "../financial-mcp-server4j/target/financial-mcp-server4j-0.1.0.jar"));
        Assumptions.assumeTrue(Files.exists(jar), "缺行情 Server 的 jar：" + jar.toAbsolutePath()
                + "（先在 financial-mcp-server4j 里 mvn -DskipTests package）");
        ServerParameters params = ServerParameters.builder("java")
                .args("-jar", jar.toAbsolutePath().toString())
                .build();
        client = McpClient.sync(new StdioClientTransport(params, McpJsonDefaults.getMapper()))
                .requestTimeout(Duration.ofSeconds(30))
                .initializationTimeout(Duration.ofSeconds(60))
                .build();
        client.initialize();
        MarketProperties props = new MarketProperties();
        props.setDays(20);
        props.setSearchLimit(5);
        props.setWarnMissingAsOf(true);
        gateway = new McpMarketGateway(client, props, new ObjectMapper());
    }

    @AfterAll
    static void stop() {
        if (client != null) {
            client.closeGracefully();
        }
    }

    @Test
    @DisplayName("真子进程：get_quote 返回正的价格与可解析的 asOf")
    void quote() {
        MarketGateway.Snapshot snapshot = gateway.call("get_quote", "300059", 0);

        assertThat(snapshot.data()).containsKeys("price", "name", "tradedAt");
        double price = ((Number) snapshot.data().get("price")).doubleValue();
        assertThat(price).isGreaterThan(0);
        assertThat(snapshot.asOf()).matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\+08:00");
    }

    @Test
    @DisplayName("真子进程：get_history 返回按日期升序的 bar")
    void history() {
        MarketGateway.Snapshot snapshot = gateway.call("get_history", "300059", 5);

        assertThat((java.util.List<?>) snapshot.data().get("bars")).hasSize(5);
        assertThat(snapshot.asOf()).contains("T15:00:00+08:00");
    }

    @Test
    @DisplayName("真子进程：坏代码是业务错误（可给用户看），不是连接故障")
    void badCodeIsBusinessError() {
        assertThatThrownBy(() -> gateway.call("get_quote", "abc", 0))
                .isInstanceOf(MarketApiException.class)
                .hasMessageContaining("6 位数字");
    }

    @Test
    @DisplayName("真子进程：工具清单来自服务端，只暴露 get_quote/get_history")
    void toolsDeclaredByServer() {
        java.util.List<MarketToolSpec> specs = gateway.tools();

        assertThat(specs).extracting(MarketToolSpec::name)
                .containsExactlyInAnyOrder("get_quote", "get_history");
        assertThat(specs).allSatisfy(spec -> assertThat(spec.inputSchema()).contains("code"));
        assertThat(gateway.tools()).isSameAs(specs);
    }

    @Test
    @DisplayName("真子进程：search_stock 能按名字找到 300059")
    void search() {
        assertThat(gateway.search("东方财富"))
                .anySatisfy(hit -> assertThat(hit.code()).isEqualTo("300059"));
    }
}