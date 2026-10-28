package com.finagent.task;

import java.util.Optional;

import com.finagent.document.DocumentParseService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * document.parse 消费者单测：不连 broker，验证「接消息后的状态机 + 编排调用」。
 * 真正的解析编排（parseDocumentAsync）由 DocumentParseService 负责，这里只 mock。
 */
class DocumentParseListenerTest {

    private DocumentParseService parseService;
    private TaskMessageRepository taskMessages;
    private DocumentParseListener listener;

    @BeforeEach
    void setUp() {
        parseService = mock(DocumentParseService.class);
        taskMessages = mock(TaskMessageRepository.class);
        listener = new DocumentParseListener(parseService, taskMessages, JsonMapper.builder().build());
    }

    @Test
    void processCallsParseDocumentAsyncAndMarksSuccess() {
        DocumentTaskEvent event = new DocumentTaskEvent(
                "evt-1", 7L, null, "2026-10-26T22:00:00+08:00");
        when(taskMessages.upsert(eq("evt-1"), eq("document.parse"), eq("7"), eq(7L)))
                .thenReturn(99L);

        listener.process(event);

        InOrder inOrder = inOrder(taskMessages, parseService);
        inOrder.verify(taskMessages).upsert("evt-1", "document.parse", "7", 7L);
        inOrder.verify(taskMessages).markStatus(99L, "PROCESSING");
        inOrder.verify(parseService).parseDocumentAsync(7L);
        inOrder.verify(taskMessages).markStatus(99L, "SUCCESS");
    }

    @Test
    void processMarksFailedAndRethrowsOnError() {
        DocumentTaskEvent event = new DocumentTaskEvent(
                "evt-2", 7L, null, "2026-10-26T22:00:00+08:00");
        when(taskMessages.upsert(eq("evt-2"), eq("document.parse"), eq("7"), eq(7L)))
                .thenReturn(99L);
        doThrow(new RuntimeException("解析服务不可用"))
                .when(parseService).parseDocumentAsync(7L);

        assertThatThrownBy(() -> listener.process(event))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("解析服务不可用");

        verify(taskMessages).markStatus(99L, "PROCESSING");
        verify(taskMessages).markStatus(99L, "FAILED");
    }

    @Test
    void skipsWhenAlreadySuccessful() {
        DocumentTaskEvent event = new DocumentTaskEvent(
                "evt-1", 7L, null, "2026-10-26T22:00:00+08:00");
        when(taskMessages.findByEventId("evt-1")).thenReturn(Optional.of(
                new TaskMessage(99L, "evt-1", "document.parse", "7", 7L,
                        "SUCCESS", 0, null, null, null)));

        listener.process(event);

        verify(parseService, never()).parseDocumentAsync(anyLong());
        verify(taskMessages, never()).upsert(any(), any(), any(), anyLong());
    }
}
