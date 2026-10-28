package com.finagent.task;

import org.apache.kafka.clients.admin.NewTopic;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * topic 装配验证 —— 用 ApplicationContextRunner 只加载 {@link KafkaTopicConfig}，
 * 不需要 broker（NewTopic 只是描述对象，真正建 topic 是 KafkaAdmin 启动时做的事）。
 */
class KafkaTopicConfigTest {

    private final ApplicationContextRunner runner =
            new ApplicationContextRunner().withUserConfiguration(KafkaTopicConfig.class);

    @Test
    void registersBusinessAndDlqTopics() {
        runner.run(ctx -> {
            Map<String, NewTopic> topics = ctx.getBeansOfType(NewTopic.class);
            assertThat(topics).containsOnlyKeys(
                    "documentParseTopic", "documentReindexTopic",
                    "documentParseDlqTopic", "documentReindexDlqTopic");

            assertThat(topics.get("documentParseTopic").name()).isEqualTo("document.parse");
            assertThat(topics.get("documentReindexTopic").name()).isEqualTo("document.reindex");
            assertThat(topics.get("documentParseDlqTopic").name()).isEqualTo("document.parse.dlt");
            assertThat(topics.get("documentReindexDlqTopic").name()).isEqualTo("document.reindex.dlt");

            topics.values().forEach(topic -> {
                assertThat(topic.numPartitions()).isEqualTo(1);
                assertThat(topic.replicationFactor()).isEqualTo((short) 1);
            });
        });
    }

    @Test
    void topicNamesAreOverridable() {
        runner.withPropertyValues(
                        "finagent.task.parse-topic=my.parse",
                        "finagent.task.reindex-topic=my.reindex")
                .run(ctx -> {
                    assertThat(ctx.getBean("documentParseTopic", NewTopic.class).name())
                            .isEqualTo("my.parse");
                    assertThat(ctx.getBean("documentReindexTopic", NewTopic.class).name())
                            .isEqualTo("my.reindex");
                    assertThat(ctx.getBean("documentParseDlqTopic", NewTopic.class).name())
                            .isEqualTo("my.parse.dlt");
                    assertThat(ctx.getBean("documentReindexDlqTopic", NewTopic.class).name())
                            .isEqualTo("my.reindex.dlt");
                });
    }

    @Test
    void defaultsKickInWhenUnset() {
        runner.run(ctx -> {
            TaskProperties props = ctx.getBean(TaskProperties.class);
            assertThat(props.parseTopic()).isEqualTo("document.parse");
            assertThat(props.reindexTopic()).isEqualTo("document.reindex");
            assertThat(props.dlqSuffix()).isEqualTo(".dlt");
            assertThat(props.maxRetries()).isEqualTo(3);
            assertThat(props.backoffMs()).isEqualTo(1000);
        });
    }
}
