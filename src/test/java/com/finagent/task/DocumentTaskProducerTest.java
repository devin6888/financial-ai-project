package com.finagent.task;

import com.finagent.chunk.ReindexMode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.kafka.core.KafkaTemplate;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * 生产者单元测试：不连 broker，只验证「投到哪个 topic、key 是什么、消息体字段对不对」。
 */
class DocumentTaskProducerTest {

    private KafkaTemplate<String, String> kafka;
    private DocumentTaskProducer producer;

    @BeforeEach
    void setUp() {
        kafka = mock(KafkaTemplate.class);
        TaskProperties props = new TaskProperties("document.parse", "document.reindex", ".dlt", 3, 1000L);
        producer = new DocumentTaskProducer(kafka, JsonMapper.builder().build(), props);
    }

    @Test
    void sendParseUsesParseTopicWithDocumentKeyAndBody() throws Exception {
        producer.sendParse(7L);

        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(kafka).send(eq("document.parse"), eq("7"), body.capture());

        DocumentTaskEvent e = JsonMapper.builder().build()
                .readValue(body.getValue(), DocumentTaskEvent.class);
        assertThat(e.documentId()).isEqualTo(7L);
        assertThat(e.eventId()).isNotBlank();
        assertThat(e.mode()).isNull();
        assertThat(e.occurredAt()).isNotBlank();
    }

    @Test
    void sendReindexUsesReindexTopicWithMode() throws Exception {
        producer.sendReindex(38L, ReindexMode.RECHUNK);

        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(kafka).send(eq("document.reindex"), eq("38"), body.capture());

        DocumentTaskEvent e = JsonMapper.builder().build()
                .readValue(body.getValue(), DocumentTaskEvent.class);
        assertThat(e.documentId()).isEqualTo(38L);
        assertThat(e.mode()).isEqualTo("RECHUNK");
    }
}
