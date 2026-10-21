package com.finagent.document;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 解析服务配置。环境变量经宽松绑定注入：
 * FINAGENT_PARSER_BASE_URL / FINAGENT_PARSER_READ_TIMEOUT_MINUTES /
 * FINAGENT_PARSER_POLL_INTERVAL_SECONDS / FINAGENT_PARSER_JOB_TIMEOUT_MINUTES
 *
 * <p>紧凑构造器给全部字段兜底默认值 —— 配置缺失时不应该在运行期炸出 NPE
 * （评审 M9：初版没处理 baseUrl=null）。作业化之后单个请求都很短，
 * 所以读超时只需几分钟，不再需要 45 分钟。
 */
@ConfigurationProperties(prefix = "finagent.parser")
public record ParserProperties(
        String baseUrl,
        int readTimeoutMinutes,
        int pollIntervalSeconds,
        int jobTimeoutMinutes) {

    public ParserProperties {
        if (baseUrl == null || baseUrl.isBlank()) {
            baseUrl = "http://127.0.0.1:8000";
        }
        if (readTimeoutMinutes <= 0) {
            readTimeoutMinutes = 5;
        }
        if (pollIntervalSeconds <= 0) {
            pollIntervalSeconds = 10;
        }
        if (jobTimeoutMinutes <= 0) {
            jobTimeoutMinutes = 60;
        }
    }
}
