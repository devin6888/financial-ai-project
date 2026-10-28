package com.finagent.task;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 异步任务（阶段 7）配置。
 *
 * <p>值可经环境变量注入（Spring Boot 宽松绑定）：
 * FINAGENT_TASK_PARSE_TOPIC / FINAGENT_TASK_REINDEX_TOPIC /
 * FINAGENT_TASK_DLQ_SUFFIX / FINAGENT_TASK_MAX_RETRIES / FINAGENT_TASK_BACKOFF_MS。
 *
 * <p>紧凑构造器给全部字段兜底默认值 —— 配置缺失时不该在运行期炸出 NPE
 * （与 {@code ParserProperties} 同一套约定）。
 */
@ConfigurationProperties(prefix = "finagent.task")
public record TaskProperties(
        String parseTopic,
        String reindexTopic,
        String dlqSuffix,
        int maxRetries,
        long backoffMs) {

    public TaskProperties {
        if (parseTopic == null || parseTopic.isBlank()) {
            parseTopic = "document.parse";
        }
        if (reindexTopic == null || reindexTopic.isBlank()) {
            reindexTopic = "document.reindex";
        }
        if (dlqSuffix == null || dlqSuffix.isBlank()) {
            dlqSuffix = ".dlt";
        }
        if (maxRetries <= 0) {
            maxRetries = 3;
        }
        if (backoffMs <= 0) {
            backoffMs = 1000;
        }
    }
}
