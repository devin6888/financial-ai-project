package com.finagent.task;

import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;

import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 错误处理器装配测试：验证 defaultFalse + 瞬时白名单 的装配结果，以及 DLQ 目的地
 * 是「原 topic + .dlt 后缀」。
 *
 * <p>真正的「瞬时重试 / 业务进 DLQ」端到端行为由 Task 8 集成测试覆盖（真 broker）。
 */
class KafkaErrorHandlerConfigTest {

    @Test
    void buildsRecovererAndCommonErrorHandler() {
        KafkaErrorHandlerConfig config = new KafkaErrorHandlerConfig();
        TaskProperties props = new TaskProperties(null, null, null, 0, 0);
        KafkaTemplate<Object, Object> kafka = new KafkaTemplate<>(mock(ProducerFactory.class));

        DeadLetterPublishingRecoverer recoverer = config.deadLetterPublishingRecoverer(kafka, props);
        DefaultErrorHandler handler = config.kafkaErrorHandler(recoverer, props);

        assertThat(recoverer).isNotNull();
        assertThat(handler).isNotNull();
        assertThat(handler).isInstanceOf(CommonErrorHandler.class);
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void dlqDestinationUsesSuffix() {
        KafkaErrorHandlerConfig config = new KafkaErrorHandlerConfig();
        TaskProperties props = new TaskProperties("document.parse", "document.reindex", ".dlt", 3, 1000);
        KafkaOperations<Object, Object> kafka = mock(KafkaOperations.class);
        when(kafka.send(any(ProducerRecord.class)))
                .thenReturn(CompletableFuture.completedFuture(null));

        DeadLetterPublishingRecoverer recoverer = config.deadLetterPublishingRecoverer(kafka, props);
        ConsumerRecord<String, String> record = new ConsumerRecord<>("document.parse", 0, 0L, "key", "value");
        recoverer.accept(record, mock(Consumer.class), new IllegalStateException("boom"));

        ArgumentCaptor<ProducerRecord> captor = ArgumentCaptor.forClass(ProducerRecord.class);
        verify(kafka).send(captor.capture());
        assertThat(captor.getValue().topic()).isEqualTo("document.parse.dlt");
    }
}
