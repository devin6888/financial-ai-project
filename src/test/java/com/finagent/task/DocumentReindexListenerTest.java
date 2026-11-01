package com.finagent.task;

import java.util.Optional;

import com.finagent.chunk.ReindexMode;
import com.finagent.chunk.ReindexService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyLong;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * document.reindex 消费者单测：验证「接消息后调 reindex(RECHUNK) + 状态机」。
 */
class DocumentReindexListenerTest {

    private ReindexService reindexService;
    private TaskMessageRepository taskMessages;
    private DocumentReindexListener listener;

    @BeforeEach
    void setUp() {
        reindexService = mock(ReindexService.class);
        taskMessages = mock(TaskMessageRepository.class);
        listener = new DocumentReindexListener(reindexService, taskMessages,
                JsonMapper.builder().build());
    }

    @Test
    void processCallsReindexWithRechunkAndMarksSuccess() {
        DocumentTaskEvent event = new DocumentTaskEvent(
                "evt-1", 38L, "RECHUNK", "2026-10-26T22:00:00+08:00");
        when(taskMessages.upsert(eq("evt-1"), eq("document.reindex"), eq("38"), eq(38L)))
                .thenReturn(99L);
        when(reindexService.reindex(38L, ReindexMode.RECHUNK)).thenReturn(3);

        listener.process(event);

        InOrder inOrder = inOrder(taskMessages, reindexService);
        inOrder.verify(taskMessages).upsert("evt-1", "document.reindex", "38", 38L);
        inOrder.verify(taskMessages).markStatus(99L, "PROCESSING");
        inOrder.verify(reindexService).reindex(38L, ReindexMode.RECHUNK);
        inOrder.verify(taskMessages).markStatus(99L, "SUCCESS");
    }

    @Test
    void processMarksFailedAndRethrowsOnError() {
        DocumentTaskEvent event = new DocumentTaskEvent(
                "evt-2", 38L, "RECHUNK", "2026-10-26T22:00:00+08:00");
        when(taskMessages.upsert(eq("evt-2"), eq("document.reindex"), eq("38"), eq(38L)))
                .thenReturn(99L);
        when(reindexService.reindex(38L, ReindexMode.RECHUNK))
                .thenThrow(new IllegalStateException("文档尚未解析完成"));

        assertThatThrownBy(() -> listener.process(event))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("尚未解析完成");

        verify(taskMessages).markStatus(99L, "PROCESSING");
        verify(taskMessages).markStatus(99L, "FAILED");
    }

    @Test
    void skipsWhenAlreadySuccessful() {
        DocumentTaskEvent event = new DocumentTaskEvent(
                "evt-1", 38L, "RECHUNK", "2026-10-26T22:00:00+08:00");
        when(taskMessages.findByEventId("evt-1")).thenReturn(Optional.of(
                new TaskMessage(99L, "evt-1", "document.reindex", "38", 38L,
                        "SUCCESS", 0, null, null, null)));

        listener.process(event);

        verify(reindexService, never()).reindex(anyLong(), any());
        verify(taskMessages, never()).upsert(any(), any(), any(), anyLong());
    }
}
