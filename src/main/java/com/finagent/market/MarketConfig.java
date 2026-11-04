package com.finagent.market;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import org.springframework.ai.mcp.customizer.McpClientCustomizer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.List;

/**
 * 只暴露一个 {@link MarketGateway} Bean。
 *
 * <p><b>为什么是单工厂方法而不是成对的 {@code @ConditionalOnProperty}</b>：不带 {@code havingValue}
 * 的条件在属性为空串时也算「存在」，两个实现会同时命中，启动直接
 * {@code NoUniqueBeanDefinitionException}。阶段 4 的 rerank 装配踩过同一个坑（ST4 偏差登记 #9）。
 */
@Configuration
@EnableConfigurationProperties(MarketProperties.class)
public class MarketConfig {

    @Bean
    MarketGateway marketGateway(MarketProperties props,
                                ObjectProvider<List<McpSyncClient>> clientsProvider,
                                ObjectMapper mapper) {
        if (!props.isEnabled()) {
            return new UnavailableMarketGateway("行情能力未启用");
        }
        // 注意类型：starter 注册的是**一个 List<McpSyncClient> Bean**，不是每个连接一个 McpSyncClient Bean
        // （McpClientAutoConfiguration#mcpSyncClients 返回 List）。写成 ObjectProvider<McpSyncClient>
        // 会永远拿不到值 —— 阶段 23 冒烟时正是因此报「MCP client 未装配」。
        List<McpSyncClient> clients = clientsProvider.getIfAvailable();
        McpSyncClient client = (clients == null || clients.isEmpty()) ? null : clients.getFirst();
        if (client == null) {
            return new UnavailableMarketGateway("MCP client 未装配（检查 spring.ai.mcp.client.* 与 jar 路径）");
        }
        return new McpMarketGateway(client, props, mapper);
    }

    /**
     * MCP Java 客户端的握手超时单独放宽。
     *
     * <p>为什么需要：{@code requestTimeout} 管的是「单次请求」，但握手要等子进程把 JVM 与 Spring
     * 上下文都起起来（实测冷启动 3.7 s，机器忙时 9 s）。沿用 8 s 会让**第一次**行情调用稳定超时
     * （2026-11-04 冒烟：{@code initializationTimeout} 在 8000ms 触发），之后再被 60 s 冷却挡住。
     */
    @Bean
    McpClientCustomizer<McpClient.SyncSpec> marketClientCustomizer(MarketProperties props) {
        return (name, spec) -> spec
                .initializationTimeout(Duration.ofSeconds(30))
                .requestTimeout(props.getRequestTimeout());
    }
}
