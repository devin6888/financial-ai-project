package com.finagent.document;

import com.finagent.chunk.ReindexMode;
import com.finagent.chunk.ReindexService;
import com.finagent.storage.ObjectStorage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * parseDocumentAsync 编排单测：验证「从 COS 读回 PDF → 解析 → 切块 + 嵌入」这条链路。
 * 阶段 7 把解析移进 worker，本方法是 worker 的入口，值得单独测。
 */
class DocumentParseServiceTest {

    private ParserAdapter parser;
    private DocumentRepository documents;
    private ObjectStorage storage;
    private ReindexService reindex;
    private DocumentParseService service;
    private DocumentProgress progress;
    private com.finagent.ingest.IngestTracker tracker;

    @BeforeEach
    void setUp() {
        parser = mock(ParserAdapter.class);
        documents = mock(DocumentRepository.class);
        storage = mock(ObjectStorage.class);
        reindex = mock(ReindexService.class);
        progress = new DocumentProgress(documents);
        tracker = mock(com.finagent.ingest.IngestTracker.class);
        service = new DocumentParseService(parser, documents, storage, reindex, progress,
                tracker);
    }

    @Test
    void parseDocumentAsyncDownloadsParsesAndRechunks() {
        byte[] pdf = "%PDF-1.4 fake".getBytes(StandardCharsets.UTF_8);
        DocumentRepository.ParseInput input =
                new DocumentRepository.ParseInput(7L, "uploads/a.pdf", "h123", "东方财富2025年报");
        ParsedDocument parsed =
                new ParsedDocument("h123", "docling", "2.138.0", 181, 100, 407, 55, 0, "{}");

        when(documents.findParseInput(7L)).thenReturn(input);
        when(storage.get("uploads/a.pdf")).thenReturn(pdf);
        when(parser.submit(any(byte[].class), eq("东方财富2025年报.pdf"), isNull(), isNull()))
                .thenReturn("job-1");
        // 主链路走带轮询回调的重载（心跳）
        when(parser.fetchResult(eq("job-1"), any())).thenAnswer(inv -> {
            java.util.function.Consumer<ParserAdapter.ParseJobStatus> cb = inv.getArgument(1);
            cb.accept(new ParserAdapter.ParseJobStatus("running", 12.5));
            return parsed;
        });
        when(parser.fetchResult("job-1")).thenReturn(parsed);
        when(reindex.reindex(7L, ReindexMode.RECHUNK)).thenReturn(3);

        service.parseDocumentAsync(7L);

        InOrder inOrder = inOrder(storage, parser, documents, reindex);
        inOrder.verify(storage).get("uploads/a.pdf");
        inOrder.verify(parser).submit(pdf, "东方财富2025年报.pdf", null, null);
        inOrder.verify(documents).markRunning(7L, "job-1");
        inOrder.verify(parser).fetchResult(eq("job-1"), any());
        inOrder.verify(storage).put(eq("parsed/h123/docling-2.138.0/normalized.json"),
                any(byte[].class), eq("application/json"));
        inOrder.verify(documents).markDone(7L, "parsed/h123/docling-2.138.0/normalized.json",
                "docling", "2.138.0", 181);
        inOrder.verify(documents).backfillPeriod(7L);
        inOrder.verify(reindex).reindex(7L, ReindexMode.RECHUNK);
    }

    @Test
    void parseDocumentAsyncRejectsMissingObjectKey() {
        DocumentRepository.ParseInput input =
                new DocumentRepository.ParseInput(7L, null, "h123", "title");
        when(documents.findParseInput(7L)).thenReturn(input);

        assertThatThrownBy(() -> service.parseDocumentAsync(7L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("没有 PDF 对象键");
    }

    @Test
    void parseDocumentAsyncMarksFailedAndRethrowsOnParseError() {
        byte[] pdf = "%PDF".getBytes(StandardCharsets.UTF_8);
        DocumentRepository.ParseInput input =
                new DocumentRepository.ParseInput(7L, "uploads/a.pdf", "h123", "title");
        when(documents.findParseInput(7L)).thenReturn(input);
        when(storage.get("uploads/a.pdf")).thenReturn(pdf);
        when(parser.submit(any(byte[].class), any(String.class), isNull(), isNull()))
                .thenThrow(new RuntimeException("解析服务 500"));

        assertThatThrownBy(() -> service.parseDocumentAsync(7L))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("解析服务 500");

        verify(documents).markFailed(7L, "RuntimeException: 解析服务 500");
        // 解析失败后不得重切
        verify(reindex, never()).reindex(anyLong(), any(ReindexMode.class));
    }
}
