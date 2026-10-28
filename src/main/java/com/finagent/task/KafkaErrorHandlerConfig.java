package com.finagent.task;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.TopicPartition;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.BackOff;
import org.springframework.util.backoff.FixedBackOff;

import java.util.function.BiFunction;

/**
 * Kafka 消费者错误处理装配（阶段 7，设计文档 §13.1）。
 *
 * <p>策略：{@link DefaultErrorHandler} 默认「不重试」，只对瞬时错误白名单
 * （见 {@link RetryClassifier#transientTypes()}）做有限次固定间隔重试；
 * 重试耗尽或非瞬时错误直接把原始消息投到 DLQ topic（原 topic 名 + {@code .dlt} 后缀）。
 *
 * <p>为什么默认不重试：业务错误（对象键缺失、文档状态不对）和数据错误（消息体坏）
 * 重试永远失败，无限重试只会反复执行解析、刷 task_message 的 retry_count，并阻塞分区。
 * 只有瞬时错误才值得重试 —— 这正是 {@link RetryClassifier} 的三分类。
 *
 * <p>装配：返回的 {@link DefaultErrorHandler} 是 {@code CommonErrorHandler}，Spring Boot
 * 的 Kafka 自动配置会自动把它装到默认 {@code kafkaListenerContainerFactory} 上
 * （两个 listener 容器共用同一个错误处理器）。
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(TaskProperties.class)
public class KafkaErrorHandlerConfig {

    /**
     * 死信发布器：把失败消息投到「原 topic + {@code .dlt}」。
     * 分区沿用原消息分区（单分区 topic，永远是 0），保持 DLQ 内消息有序。
     */
    @Bean
    public DeadLetterPublishingRecoverer deadLetterPublishingRecoverer(
            KafkaOperations<?, ?> kafkaTemplate, TaskProperties props) {
        BiFunction<ConsumerRecord<?, ?>, Exception, TopicPartition> destination =
                (record, e) -> new TopicPartition(
                        record.topic() + props.dlqSuffix(), record.partition());
        return new DeadLetterPublishingRecoverer(kafkaTemplate, destination);
    }

    /**
     * 错误处理器：默认不重试，只重试瞬时白名单；退避固定 {@code backoff-ms}、
     * 最多 {@code max-retries} 次。
     */
    @Bean
    public DefaultErrorHandler kafkaErrorHandler(
            DeadLetterPublishingRecoverer recoverer, TaskProperties props) {
        BackOff backOff = new FixedBackOff(props.backoffMs(), props.maxRetries());
        DefaultErrorHandler handler = new DefaultErrorHandler(recoverer, backOff);
        // 默认不重试（业务/数据错误直接进 DLQ），只重试瞬时白名单
        handler.defaultFalse();
        for (Class<? extends Exception> type : RetryClassifier.transientTypes()) {
            handler.addRetryableExceptions(type);
        }
        return handler;
    }
}
