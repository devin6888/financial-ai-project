package com.finagent.document;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.charset.StandardCharsets;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HTTP 层：路由 / 参数绑定 / 状态码。业务细节在 DocumentUploadServiceTest 里测。
 *
 * <p>用 standaloneSetup 而非 @WebMvcTest（本项目没引入 Boot 4 的 web 测试切片模块），
 * 与 ChatControllerTest 同一套约定。
 */
class DocumentControllerTest {

    private MockMvc mvc;
    private DocumentUploadService uploadService;
    private DocumentRepository documents;

    @BeforeEach
    void setUp() {
        uploadService = mock(DocumentUploadService.class);
        documents = mock(DocumentRepository.class);
        mvc = MockMvcBuilders.standaloneSetup(new DocumentController(uploadService, documents)).build();
    }

    @Test
    @DisplayName("上传 PDF → 200 + documentId + PENDING")
    void uploadReturnsDocumentIdAndPending() throws Exception {
        when(uploadService.upload(anyString(), anyString(), anyString(), anyString(), any(), anyString(), any()))
                .thenReturn(new DocumentUploadService.UploadResult(42L, "PENDING"));

        MockMultipartFile file = new MockMultipartFile(
                "file", "report.pdf", "application/pdf",
                "%PDF-1.7".getBytes(StandardCharsets.US_ASCII));

        mvc.perform(multipart("/api/documents")
                        .file(file)
                        .param("companyCode", "300059")
                        .param("companyName", "东方财富")
                        .param("market", "SZ")
                        .param("docType", "ANNUAL")
                        .param("docYear", "2025")
                        .param("title", "东方财富2025年年度报告"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.documentId").value(42))
                .andExpect(jsonPath("$.parseStatus").value("PENDING"));
    }

    @Test
    @DisplayName("查询状态 → 200 + 完整详情（含 activeChunkVersion）")
    void detailReturnsDocumentStatus() throws Exception {
        when(documents.findDetail(42L)).thenReturn(Optional.of(new DocumentRepository.DocumentDetail(
                42L, "DONE", 3, "东方财富2025年年度报告", 181, null,
                "parsed/abc/docling-2.138.0/normalized.json", "docling", "2.138.0",
                2025, "ANNUAL", "300059", "东方财富", 100, "已完成")));

        mvc.perform(get("/api/documents/42"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(42))
                .andExpect(jsonPath("$.parseStatus").value("DONE"))
                .andExpect(jsonPath("$.activeChunkVersion").value(3))
                .andExpect(jsonPath("$.pagesParsed").value(181))
                .andExpect(jsonPath("$.title").value("东方财富2025年年度报告"))
                .andExpect(jsonPath("$.companyCode").value("300059"));
    }

    @Test
    @DisplayName("同公司同年度同类型已有别的文档 → 409 + DOCUMENT_CONFLICT（不再漏成 500）")
    void duplicateIdentityReturns409() throws Exception {
        when(uploadService.upload(anyString(), anyString(), anyString(), anyString(), any(), anyString(), any()))
                .thenThrow(new org.springframework.dao.DuplicateKeyException(
                        "duplicate key value violates unique constraint \"uq_document_identity\""));

        MockMultipartFile file = new MockMultipartFile(
                "file", "report.pdf", "application/pdf",
                "%PDF-1.7".getBytes(StandardCharsets.US_ASCII));

        mvc.perform(multipart("/api/documents")
                        .file(file)
                        .param("companyCode", "300059")
                        .param("companyName", "东方财富")
                        .param("market", "SZ")
                        .param("docType", "Q1")
                        .param("docYear", "2027")
                        .param("title", "东方财富2027年一季度报告"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("DOCUMENT_CONFLICT"));
    }

    @Test
    @DisplayName("文档不存在 → 404 + NOT_FOUND")
    void detailNotFoundReturns404() throws Exception {
        when(documents.findDetail(99L)).thenReturn(Optional.empty());

        mvc.perform(get("/api/documents/99"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("NOT_FOUND"));
    }
}
