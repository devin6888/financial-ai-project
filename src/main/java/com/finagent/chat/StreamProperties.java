package com.finagent.chat;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 流式输出的配置（契约 C15）。
 *
 * @param timeoutMs 单条流的生命周期上限：模型最长也就几十秒，180 秒是「浏览器还在等」的兜底
 * @param pingMs    心跳间隔，必须小于常见反代/网关的 60 秒空闲超时
 */
@ConfigurationProperties(prefix = "finagent.stream")
public record StreamProperties(@DefaultValue("180000") long timeoutMs,
                               @DefaultValue("15000") long pingMs) { }
