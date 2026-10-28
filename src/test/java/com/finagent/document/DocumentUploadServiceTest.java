package com.finagent.document;

import com.finagent.security.CurrentUser;
import com.finagent.security.TenantContext;
import com.finagent.storage.ObjectStorage;
import com.finagent.task.DocumentTaskProducer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Optional;

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

/**
 * 上传链路：落 COS → 登记 PENDING → 投递 document.parse，以及幂等快路径。
 *
 * <p>纯 mock，不连真 COS / 真 Kafka —— 编排顺序才是本层要验的东西。
 */
class DocumentUploadServiceTest {

    private DocumentUploadService service;
    private DocumentRepository documents;
    private ObjectStorage storage;
    private DocumentTaskProducer producer;

    @BeforeEach
    void setUp() {
        documents = mock(DocumentRepository.class);
        storage = mock(ObjectStorage.class);
        producer = mock(DocumentTaskProducer.class);
        service = new DocumentUploadService(documents, storage, producer);
        // 上传是 HTTP 请求线程：没有身份就不该能上传（对应 TenantContext.require()）
        TenantContext.set(new CurrentUser(2L, 1L, "analyst", "演示分析师", java.util.Set.of("ANALYST")));
    }

    @org.junit.jupiter.api.AfterEach
    void clearContext() {
        TenantContext.clear();
    }

    @Test
    @DisplayName("新文档：落 COS → 登记 PENDING → 投递 parse → 返回 id + PENDING")
    void uploadNewDocument() {
        byte[] pdf = "%PDF-1.7 fake".getBytes(StandardCharsets.US_ASCII);
        when(documents.findCompletedIdByContentHash(anyString())).thenReturn(Optional.empty());
        when(documents.upsertCompany("300059", "东方财富", "SZ", 1L)).thenReturn(1L);
        when(documents.upsertDocument(anyLong(), anyString(), any(), anyString(), anyString(), anyString()))
                .thenReturn(42L);

        DocumentUploadService.UploadResult r = service.upload(
                "300059", "东方财富", "SZ", "ANNUAL", 2025, "东方财富2025年年度报告", pdf);

        assertThat(r.documentId()).isEqualTo(42L);
        assertThat(r.parseStatus()).isEqualTo("PENDING");

        verify(storage).put("samples/300059-2025-annual.pdf", pdf, "application/pdf");
        verify(documents).upsertCompany("300059", "东方财富", "SZ", 1L);
        verify(documents).upsertDocument(eq(1L), eq("ANNUAL"), eq(2025),
                eq("东方财富2025年年度报告"), eq("samples/300059-2025-annual.pdf"), anyString());
        verify(producer).sendParse(42L);
    }

    @Test
    @DisplayName("同一 PDF 已解析入库 → 幂等快路径：返回 DONE，不落 COS、不投递")
    void duplicateUploadReturnsExistingDone() {
        byte[] pdf = "%PDF-1.7 fake".getBytes(StandardCharsets.US_ASCII);
        when(documents.findCompletedIdByContentHash(anyString())).thenReturn(Optional.of(7L));

        DocumentUploadService.UploadResult r = service.upload(
                "300059", "东方财富", "SZ", "ANNUAL", 2025, "东方财富2025年年度报告", pdf);

        assertThat(r.documentId()).isEqualTo(7L);
        assertThat(r.parseStatus()).isEqualTo("DONE");

        verify(storage, never()).put(anyString(), any(), anyString());
        verify(documents, never()).upsertCompany(anyString(), anyString(), anyString(), anyLong());
        verify(documents, never()).upsertDocument(anyLong(), anyString(), any(), anyString(), anyString(), anyString());
        verify(producer, never()).sendParse(anyLong());
    }

    @Test
    @DisplayName("空 PDF → 拒绝")
    void emptyPdfRejected() {
        assertThatThrownBy(() -> service.upload("300059", "东方财富", "SZ", "ANNUAL", 2025,
                "东方财富2025年年度报告", new byte[0]))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("PDF");
    }

    @Test
    @DisplayName("缺必填字段（companyCode 空白）→ 拒绝")
    void blankCompanyCodeRejected() {
        assertThatThrownBy(() -> service.upload("  ", "东方财富", "SZ", "ANNUAL", 2025,
                "东方财富2025年年度报告", "%PDF".getBytes(StandardCharsets.US_ASCII)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("companyCode");
    }

    @Test
    @DisplayName("源对象键 = samples/{code}-{year}-{docType 小写}.pdf")
    void sourceKeyFormat() {
        assertThat(DocumentUploadService.sourceKey("300059", "ANNUAL", 2025))
                .isEqualTo("samples/300059-2025-annual.pdf");
        assertThat(DocumentUploadService.sourceKey("000001", "Q1", 2026))
                .isEqualTo("samples/000001-2026-q1.pdf");
    }
}
