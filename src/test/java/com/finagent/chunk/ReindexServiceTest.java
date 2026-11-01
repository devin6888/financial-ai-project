package com.finagent.chunk;

import com.finagent.chunk.model.Chunk;
import com.finagent.chunk.model.ParentGroup;
import com.finagent.document.DocumentModel;
import com.finagent.document.DocumentRepository;
import com.finagent.document.NormalizedDocumentLoader;
import com.finagent.ingest.IngestFlow;
import com.finagent.ingest.IngestStep;
import com.finagent.ingest.IngestTracker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
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
    private ChunkRepository chunks;
    private IngestTracker tracker;
    private ReindexService service;

    @BeforeEach
    void setUp() {
        documents = mock(DocumentRepository.class);
        loader = mock(NormalizedDocumentLoader.class);
        writer = mock(ChunkWriter.class);
        chunks = mock(ChunkRepository.class);
        tracker = mock(IngestTracker.class);
        service = new ReindexService(documents, loader, writer, chunks, tracker);
    }

    @Test
    void fullReparseStillFailsLoudly() {
        // 空方法假装支持是最坏的 —— 调用方会以为生效了
        assertThatThrownBy(() -> service.reindex(1L, ReindexMode.FULL_REPARSE))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("尚未实现");
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
        // 校验不过就不开 run —— 否则每个被拒的请求都留一条 FAILED 执行记录
        verify(tracker, never()).start(anyLong(), any());
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
        // 重切才有「切块中」这一步
        verify(tracker).start(7L, IngestFlow.REINDEX);
        verify(tracker).begin(7L, IngestStep.CHUNKING);
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

    // ---------- RETRIEVAL_ONLY / REEMBED ----------

    @Test
    void retrievalOnlyRefusesDocumentWithoutAnyChunkVersion() {
        when(documents.findParseState(7L)).thenReturn(
                new DocumentRepository.ParseState(7L, "DONE", "parsed/x.json",
                        "docling", "2.138.0", 181, null, "某年报"));
        when(chunks.activeVersion(7L)).thenReturn(0);

        assertThatThrownBy(() -> service.reindex(7L, ReindexMode.RETRIEVAL_ONLY))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("还没有任何切块版本");
        verify(chunks, never()).findVersionChunks(anyLong(), anyInt());
    }

    @Test
    void retrievalOnlyRebuildsFromStoredChunksWithoutRechunking() {
        when(documents.findParseState(7L)).thenReturn(
                new DocumentRepository.ParseState(7L, "DONE", "parsed/x.json",
                        "docling", "2.138.0", 181, null, "某年报"));
        when(chunks.activeVersion(7L)).thenReturn(11);
        when(chunks.findVersionChunks(7L, 11)).thenReturn(List.of(
                parent(0, "第一节 § 营业收入", "总述"),
                child(0, 0, "第一节 § 营业收入", "营业收入 100 元"),
                child(1, 0, "第一节 § 营业收入", "营业成本 60 元")));
        when(writer.writeVersion(anyLong(), anyString(), any(), any(), anyString(), any(), anyBoolean()))
                .thenReturn(12);

        int version = service.reindex(7L, ReindexMode.RETRIEVAL_ONLY);

        assertThat(version).isEqualTo(12);
        // 关键：没有从 COS 重新读解析产物
        verify(loader, never()).load(anyString());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ParentGroup>> parents = ArgumentCaptor.forClass(List.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<List<Chunk>>> children = ArgumentCaptor.forClass(List.class);
        verify(writer).writeVersion(eq(7L), eq("某年报"), parents.capture(), children.capture(),
                eq("2.138.0"), eq(IngestStep.REUSED), eq(false));

        assertThat(parents.getValue()).hasSize(1);
        assertThat(children.getValue()).hasSize(1);
        // section_path 存的是 " § "，必须精确拆回两段 —— 拆错则 embedding_text 变、复用全失效
        assertThat(parents.getValue().get(0).parent().sectionPath())
                .containsExactly("第一节", "营业收入");
        assertThat(children.getValue().get(0)).hasSize(2);
        assertThat(children.getValue().get(0).get(1).content()).isEqualTo("营业成本 60 元");
        // 只重建检索数据：没有「切块中」这一步
        verify(tracker).start(7L, IngestFlow.RETRIEVAL);
        verify(tracker, never()).begin(7L, IngestStep.CHUNKING);
    }

    @Test
    void reembedForcesReembeddingWithSameChunks() {
        when(documents.findParseState(7L)).thenReturn(
                new DocumentRepository.ParseState(7L, "DONE", "parsed/x.json",
                        "docling", "2.138.0", 181, null, "某年报"));
        when(chunks.activeVersion(7L)).thenReturn(5);
        when(chunks.findVersionChunks(7L, 5)).thenReturn(List.of(
                parent(0, "第一节", "总述"),
                child(0, 0, "第一节", "营业收入 100 元")));
        when(writer.writeVersion(anyLong(), anyString(), any(), any(), anyString(), any(), anyBoolean()))
                .thenReturn(6);

        service.reindex(7L, ReindexMode.REEMBED);

        verify(writer).writeVersion(eq(7L), eq("某年报"), any(), any(), anyString(),
                eq(IngestStep.REUSED), eq(true));
    }

    @Test
    void orphanChildFailsInsteadOfSilentlyDropping() {
        when(documents.findParseState(7L)).thenReturn(
                new DocumentRepository.ParseState(7L, "DONE", "parsed/x.json",
                        "docling", "2.138.0", 181, null, "某年报"));
        when(chunks.activeVersion(7L)).thenReturn(3);
        // 子块的 parent_index=9，但只有 index=0 的父块 → 关系不完整
        when(chunks.findVersionChunks(7L, 3)).thenReturn(List.of(
                parent(0, "第一节", "总述"),
                child(0, 9, "第一节", "孤儿块")));

        assertThatThrownBy(() -> service.reindex(7L, ReindexMode.RETRIEVAL_ONLY))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("找不到所属 PARENT");

        verify(tracker).fail(eq(7L), anyString());
        verify(writer, never()).writeVersion(anyLong(), anyString(), any(), any(), anyString(),
                any(), anyBoolean());
    }

    private static ChunkRepository.StoredChunk parent(int chunkIndex, String path, String content) {
        return row("PARENT", chunkIndex, -1, path, content);
    }

    private static ChunkRepository.StoredChunk child(int chunkIndex, int parentIndex,
                                                     String path, String content) {
        return row("CHILD", chunkIndex, parentIndex, path, content);
    }

    private static ChunkRepository.StoredChunk row(String level, int chunkIndex, int parentIndex,
                                                   String path, String content) {
        return new ChunkRepository.StoredChunk(level, "TEXT", path, "营业收入", content,
                1, 1, "[\"b0\"]", "{\"x\":0,\"y\":0,\"w\":1,\"h\":1}", 20, chunkIndex, parentIndex,
                null, "元", "CNY", null, null, "2.138.0");
    }
}
