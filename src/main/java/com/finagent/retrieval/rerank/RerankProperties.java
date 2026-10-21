package com.finagent.retrieval.rerank;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Rerank 配置。
 *
 * <p>值可经环境变量注入（Spring Boot 宽松绑定）：
 * FINAGENT_RERANK_API_KEY / FINAGENT_RERANK_BASE_URL / FINAGENT_RERANK_MODEL /
 * FINAGENT_RERANK_CONNECT_TIMEOUT / FINAGENT_RERANK_READ_TIMEOUT。
 *
 * <p>api-key 缺失或为空时装配 {@link NoopRerankModel}（见 {@link RerankConfig}）——
 * Rerank 只是可选增强，不该成为启动的硬依赖。
 */
@ConfigurationProperties(prefix = "finagent.rerank")
public class RerankProperties {

    /** 百炼服务地址，不含具体接口路径 */
    private String baseUrl = "https://dashscope.aliyuncs.com";

    /** API Key。为空表示不启用精排。 */
    private String apiKey;

    /** 模型名。注意 gte-rerank 已于 2026-05-30 下线，不要再用。 */
    private String model = "qwen3-rerank";

    /** 连接超时。Rerank 可降级，宁可快速失败也不要拖慢整条检索。 */
    private Duration connectTimeout = Duration.ofSeconds(2);

    /** 读超时。实测 3 条候选约 0.6 s，20~50 条在 2 s 内；给 15 s 留足余量。 */
    private Duration readTimeout = Duration.ofSeconds(15);

    /**
     * 是否真的配置了可用凭据。
     *
     * <p><b>必须判空串</b>：yml 里写成 api-key: "" 这种占位很常见，
     * 而配置项的「存在」和「可用」是两件事 —— 空 key 能装配成功，
     * 但要到第一次调用才 401，那时已经在线上了。
     */
    public boolean isConfigured() {
        return apiKey != null && !apiKey.isBlank();
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public Duration getConnectTimeout() {
        return connectTimeout;
    }

    public void setConnectTimeout(Duration connectTimeout) {
        this.connectTimeout = connectTimeout;
    }

    public Duration getReadTimeout() {
        return readTimeout;
    }

    public void setReadTimeout(Duration readTimeout) {
        this.readTimeout = readTimeout;
    }
}
