package com.finagent.chunk;

import com.finagent.chunk.model.ParentGroup;
import com.finagent.document.DocumentModel;
import com.finagent.document.DocumentRepository;
import com.finagent.document.NormalizedDocumentLoader;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ReindexServiceTest {

    private DocumentRepository documents;
    private NormalizedDocumentLoader loader;
    private ChunkWriter writer;
    private ReindexService service;

    @BeforeEach
    void setUp() {
        documents = mock(DocumentRepository.class);
        loader = mock(NormalizedDocumentLoader.class);
        writer = mock(ChunkWriter.class);
        service = new ReindexService(documents, loader, writer);
    }

    @Test
    void unimplementedModesFailLoudly() {
        // 空方法假装支持是最坏的 —— 调用方会以为生效了
        for (ReindexMode mode : List.of(ReindexMode.RETRIEVAL_ONLY,
                ReindexMode.REEMBED, ReindexMode.FULL_REPARSE)) {
            assertThatThrownBy(() -> service.reindex(1L, mode))
                    .isInstanceOf(UnsupportedOperationException.class)
                    .hasMessageContaining("尚未实现");
        }
        // 且完全没有触库
        verify(documents, never()).findParseState(anyLong());
    }

    @Test
    void rechunkRefusesUnparsedDocument() {
        when(documents.findParseState(1L)).thenReturn(
                new DocumentRepository.ParseState(1L, "FAILED", null, null, null, null, "boom", "t"));

        assertThatThrownBy(() -> service.reindex(1L, ReindexMode.RECHUNK))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("尚未解析完成");

        verify(loader, never()).load(anyString());
    }

    @Test
    void rechunkReadsCosAndWritesNewVersion() {
        when(documents.findParseState(7L)).thenReturn(
                new DocumentRepository.ParseState(7L, "DONE", "parsed/x.json",
                        "docling", "2.138.0", 181, null, "某年报"));

        var block = new DocumentModel.Block("b0", "text", "财".repeat(400),
                new DocumentModel.Provenance(1, null), List.of("第一节"));
        var model = new DocumentModel(
                new DocumentModel.Meta(1, "x.pdf", "2.138.0"), List.of(block), List.of());
        when(loader.load("parsed/x.json")).thenReturn(model);
        when(writer.writeVersion(anyLong(), anyString(), any(), any(), anyString())).thenReturn(3);

        int version = service.reindex(7L, ReindexMode.RECHUNK);

        assertThat(version).isEqualTo(3);
        // 用的是产物里的 parser_version，而不是数据库那条（两者应一致，但产物才是事实源）
        verify(writer).writeVersion(eq(7L), eq("某年报"), any(), any(), eq("2.138.0"));
    }

    @Test
    void rechunkDoesNotTouchParserService() {
        // 评审验收 #9：RECHUNK 全程不应调用解析服务 —— 本服务没有任何 ParserAdapter 依赖
        when(documents.findParseState(7L)).thenReturn(
                new DocumentRepository.ParseState(7L, "DONE", "parsed/x.json",
                        "docling", "2.138.0", 181, null, "t"));
        when(loader.load(anyString())).thenReturn(
                new DocumentModel(null, List.of(), List.of()));
        when(writer.writeVersion(anyLong(), anyString(), any(), any(), any())).thenReturn(1);

        service.reindex(7L, ReindexMode.RECHUNK);

        // 这条断言由构造器保证：ReindexService 根本没有 ParserAdapter 字段
        assertThat(service.getClass().getDeclaredFields())
                .noneMatch(f -> f.getType().getSimpleName().contains("Parser"));
    }
}
