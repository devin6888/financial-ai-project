package com.finagent.task;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Kafka topic 装配（阶段 7）。
 *
 * <p>两个业务 topic + 两个死信 topic 各自一个 {@link NewTopic} bean，由 Spring Boot 的 KafkaAdmin 在启动时
 * 自动创建（docker-compose 里已把 auto.create.topics.enable 关掉，topic 只能从这里来，
 * 保证命名与分区数唯一来源）。
 *
 * <p>分区数 / 副本数写死 1：本机单节点 KRaft 集群，副本数只能是 1（副本数 > 节点数会
 * 一直 UnderReplicated）；分区数 1 保证单分区内消息有序（同一 document 的重复投递按序消费）。
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(TaskProperties.class)
public class KafkaTopicConfig {

    @Bean
    public NewTopic documentParseTopic(TaskProperties props) {
        return new NewTopic(props.parseTopic(), 1, (short) 1);
    }

    @Bean
    public NewTopic documentReindexTopic(TaskProperties props) {
        return new NewTopic(props.reindexTopic(), 1, (short) 1);
    }

    /**
     * 死信 topic：失败消息的归宿（{@link KafkaErrorHandlerConfig} 里 DefaultErrorHandler
     * 的 DeadLetterPublishingRecoverer 会投到这里）。同样 1 分区 1 副本，DLQ 内消息有序。
     */
    @Bean
    public NewTopic documentParseDlqTopic(TaskProperties props) {
        return new NewTopic(props.parseTopic() + props.dlqSuffix(), 1, (short) 1);
    }

    @Bean
    public NewTopic documentReindexDlqTopic(TaskProperties props) {
        return new NewTopic(props.reindexTopic() + props.dlqSuffix(), 1, (short) 1);
    }
}
