package com.finagent.document;

import com.finagent.scope.ScopeAuthorizer;
import com.finagent.storage.ObjectStorage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 原文页图端点的安全语义：**任何不可读 / 不存在 / 未解析 / 页码越界都是 404**。
 *
 * <p>这四条用例的价值不在「返回 200 的那条」，而在另外三条：
 * 403 会确认「文档存在」，500 会暴露「对象键读不到」这类内部状态。
 */
class DocumentPageControllerTest {

    private DocumentRepository documents;
    private ObjectStorage storage;
    private PdfPageRenderer renderer;
    private ScopeAuthorizer authorizer;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        documents = mock(DocumentRepository.class);
        storage = mock(ObjectStorage.class);
        renderer = mock(PdfPageRenderer.class);
        authorizer = mock(ScopeAuthorizer.class);
        mvc = MockMvcBuilders
                .standaloneSetup(new DocumentPageController(documents, storage, renderer, authorizer))
                .build();
    }

    @Test
    @DisplayName("可读文档：返回 png 字节与页图宽高")
    void readableDocumentReturnsPng() throws Exception {
        when(authorizer.filterReadable(List.of(7L))).thenReturn(List.of(7L));
        when(documents.findParseInput(7L))
                .thenReturn(new DocumentRepository.ParseInput(7L, "samples/a.pdf", "hash-1", "年报"));
        when(storage.get("samples/a.pdf")).thenReturn(new byte[]{1, 2, 3});
        when(renderer.render(7L, "hash-1", new byte[]{1, 2, 3}, 12, 150))
                .thenReturn(new PdfPageRenderer.PageImage(new byte[]{(byte) 0x89, 0x50}, 1240, 1754));

        mvc.perform(get("/api/documents/7/pages/12"))
                .andExpect(status().isOk())
                .andExpect(content().contentType("image/png"))
                .andExpect(header().string("X-Page-Width", "1240"))
                .andExpect(header().string("X-Page-Height", "1754"));
    }

    @Test
    @DisplayName("无权限：404，且一次都不读对象存储")
    void unreadableIsNotFound() throws Exception {
        when(authorizer.filterReadable(List.of(7L))).thenReturn(List.of());

        mvc.perform(get("/api/documents/7/pages/12"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("PAGE_NOT_FOUND"));

        verify(storage, never()).get(anyString());
        verify(renderer, never()).render(anyLong(), anyString(), any(), anyInt(), anyInt());
    }

    @Test
    @DisplayName("文档没有对象键（未上传完成）：404")
    void missingObjectKeyIsNotFound() throws Exception {
        when(authorizer.filterReadable(List.of(7L))).thenReturn(List.of(7L));
        when(documents.findParseInput(7L))
                .thenReturn(new DocumentRepository.ParseInput(7L, null, "hash-1", "年报"));

        mvc.perform(get("/api/documents/7/pages/12")).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("文档不存在：404（不是 500）")
    void unknownDocumentIsNotFound() throws Exception {
        when(authorizer.filterReadable(List.of(9L))).thenReturn(List.of(9L));
        when(documents.findParseInput(9L)).thenThrow(new EmptyResultDataAccessException(1));

        mvc.perform(get("/api/documents/9/pages/1")).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("页码越界：404，不把「共 181 页」这种信息透出去")
    void pageOutOfRangeIsNotFound() throws Exception {
        when(authorizer.filterReadable(List.of(7L))).thenReturn(List.of(7L));
        when(documents.findParseInput(7L))
                .thenReturn(new DocumentRepository.ParseInput(7L, "samples/a.pdf", "hash-1", "年报"));
        when(storage.get("samples/a.pdf")).thenReturn(new byte[]{1});
        when(renderer.render(anyLong(), anyString(), any(), anyInt(), anyInt()))
                .thenThrow(new PdfPageRenderer.PageNotFoundException("第 999 页不存在（共 181 页）"));

        mvc.perform(get("/api/documents/7/pages/999")).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("dpi 被夹到 72~300：前端传 1000 不会让服务端渲一张巨型图")
    void dpiIsClamped() throws Exception {
        when(authorizer.filterReadable(List.of(7L))).thenReturn(List.of(7L));
        when(documents.findParseInput(7L))
                .thenReturn(new DocumentRepository.ParseInput(7L, "samples/a.pdf", "hash-1", "年报"));
        when(storage.get("samples/a.pdf")).thenReturn(new byte[]{1});
        when(renderer.render(7L, "hash-1", new byte[]{1}, 1, 300))
                .thenReturn(new PdfPageRenderer.PageImage(new byte[]{1}, 100, 100));

        mvc.perform(get("/api/documents/7/pages/1").param("dpi", "1000"))
                .andExpect(status().isOk());
    }
}
