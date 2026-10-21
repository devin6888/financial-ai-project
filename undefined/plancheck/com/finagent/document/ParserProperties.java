package com.finagent.document;
import org.springframework.boot.context.properties.ConfigurationProperties;
@ConfigurationProperties(prefix = "finagent.parser")
public record ParserProperties(String baseUrl, int readTimeoutMinutes) {}
