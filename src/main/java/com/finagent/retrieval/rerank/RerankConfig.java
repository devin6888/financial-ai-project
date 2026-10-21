package com.finagent.retrieval.rerank;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Rerank 的装配：<b>无论有没有 key，都保证恰好一个 {@link RerankModel} Bean</b>。
 *
 * <p>为什么不用「成对的 @ConditionalOnProperty」：计划初版写的是
 * <pre>
 *   DashScope: @ConditionalOnProperty(name = "finagent.rerank.api-key")
 *   Noop:      @ConditionalOnProperty(name = "finagent.rerank.api-key", havingValue = "")
 * </pre>
 * 这个组合在 <b>api-key 写成空串</b>时会<b>同时命中两个</b> —— 因为 {@code ConditionalOnProperty}
 * 不带 havingValue 时的语义是「存在且不等于 false」，空串也算存在。
 * 结果是注入点找不到唯一 Bean，应用启动失败。
 * 而「api-key 先留空占位」在 yml 里太常见了，不能靠「大家都记得别这么写」来避免。
 *
 * <p>所以条件判断放在 Java 里做：一个工厂方法，返回两种实现之一，
 * <b>不可能出现 0 个或 2 个 Bean</b>，也不依赖注解求值顺序。
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(RerankProperties.class)
public class RerankConfig {

    private static final Logger log = LoggerFactory.getLogger(RerankConfig.class);

    @Bean
    public RerankModel rerankModel(RerankProperties props) {
        if (!props.isConfigured()) {
            log.info("未配置 finagent.rerank.api-key —— 装配 NoopRerankModel，检索结果不做精排");
            return new NoopRerankModel();
        }
        log.info("Rerank 已启用：model={}，base-url={}", props.getModel(), props.getBaseUrl());
        return new DashScopeRerankModel(props);
    }
}
