package com.finagent.memory;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** 记忆相关配置的装配点（风格同 ScopeAuthorizerConfig：集中开属性绑定）。 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(MemoryProperties.class)
public class MemoryConfig {
}
