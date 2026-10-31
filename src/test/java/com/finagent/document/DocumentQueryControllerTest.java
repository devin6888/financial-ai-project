package com.finagent.document;

import com.finagent.chunk.ChunkRepository;
import com.finagent.scope.ScopeAuthorizer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.OffsetDateTime;
import java.util.List;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 文档列表端点：路由 / 默认 limit / JSON 字段。SQL 本身在 DocumentListDbTest 里打真库验。 */
class DocumentQueryControllerTest {

    private MockMvc mvc;
    private DocumentRepository documents;
    private ChunkRepository chunks;
    private ScopeAuthorizer authorizer;

    @BeforeEach
    void setUp() {
        documents = mock(DocumentRepository.class);
        chunks = mock(ChunkRepository.class);
        authorizer = mock(ScopeAuthorizer.class);
        when(authorizer.filterReadable(org.mockito.ArgumentMatchers.anyList()))
                .thenAnswer(inv -> inv.getArgument(0));
        mvc = MockMvcBuilders
                .standaloneSetup(new DocumentQueryController(documents, chunks, authorizer))
                .build();
    }

    @Test
    @DisplayName("列表 → 200，包含 FAILED 文档与四个状态字段")
    void listIncludesFailedDocuments() throws Exception {
        when(documents.listDocuments(50)).thenReturn(List.of(
                new DocumentRepository.DocumentRow(7L, "东方财富2025年年度报告", "300059", "东方财富",
                        2025, "ANNUAL", "DONE", 181, null, 7, 654,
                        OffsetDateTime.parse("2026-10-20T10:00:00+08:00"), 100, "已完成"),
                new DocumentRepository.DocumentRow(4L, "恢复测试文档", "600519", "贵州茅台",
                        2024, "ANNUAL", "FAILED", 0, "PDF 解析失败", null, 0,
                        OffsetDateTime.parse("2026-10-21T10:00:00+08:00"), 12, "失败")));

        mvc.perform(get("/api/documents"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].id").value(7))
                .andExpect(jsonPath("$[0].parseStatus").value("DONE"))
                .andExpect(jsonPath("$[0].chunkCount").value(654))
                .andExpect(jsonPath("$[0].activeChunkVersion").value(7))
                // FAILED 必须出现在列表里：否则用户看到的是「我的文档不见了」
                .andExpect(jsonPath("$[1].parseStatus").value("FAILED"))
                .andExpect(jsonPath("$[1].parseError").value("PDF 解析失败"));
    }

    @Test
    @DisplayName("列表过 ACL：不可读的文档不出现（枚举泄漏是同一种越权）")
    void listIsFilteredByAcl() throws Exception {
        when(documents.listDocuments(50)).thenReturn(List.of(
                row(7L, "东方财富2025年年度报告", "DONE"),
                row(9L, "别人的私有文档", "DONE")));
        when(authorizer.filterReadable(List.of(7L, 9L))).thenReturn(List.of(7L));

        mvc.perform(get("/api/documents"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(7));
    }

    @Test
    @DisplayName("切块列表：默认取 ACTIVE 版本，limit/offset 透传")
    void chunksUseActiveVersionAndPaging() throws Exception {
        when(chunks.activeVersion(7L)).thenReturn(9);
        when(chunks.listActiveChunks(7L, 9, 800, 0)).thenReturn(List.of(
                new ChunkRepository.ChunkBrief(500L, null, "PARENT", "TEXT",
                        "第二节 > 六、主要会计数据", "六、主要会计数据", 7, 7, 49, 1,
                        "{\"top\":0.1,\"left\":0.1,\"right\":0.9,\"bottom\":0.9}",
                        "单位：元 上述财务指标…", 52),
                new ChunkRepository.ChunkBrief(7216L, 500L, "CHILD", "TABLE",
                        "第二节 > 六、主要会计数据", "六、主要会计数据", 7, 7, 267, 2,
                        null, "| 指标 | 2025 |", 446)));

        mvc.perform(get("/api/documents/7/chunks"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].chunkLevel").value("PARENT"))
                .andExpect(jsonPath("$[1].parentId").value(500))
                .andExpect(jsonPath("$[1].chunkType").value("TABLE"))
                .andExpect(jsonPath("$[1].contentLength").value(446));
    }

    @Test
    @DisplayName("未入库的文档（没有 ACTIVE 版本）：返回空表，不查切块")
    void chunksOfUnindexedDocumentAreEmpty() throws Exception {
        when(chunks.activeVersion(9L)).thenReturn(0);

        mvc.perform(get("/api/documents/9/chunks"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));

        verify(chunks, org.mockito.Mockito.never()).listActiveChunks(9L, 0, 800, 0);
    }

    @Test
    @DisplayName("不可读文档的切块：空表（不 404，也不泄漏块数）")
    void chunksOfUnreadableDocumentAreEmpty() throws Exception {
        when(authorizer.filterReadable(List.of(9L))).thenReturn(List.of());

        mvc.perform(get("/api/documents/9/chunks"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));

        verify(authorizer).filterReadable(List.of(9L));
    }

    @Test
    @DisplayName("单块正文 → 200，带定位信息与完整 content（引用悬浮卡的数据来源）")
    void chunkDetailReturnsContent() throws Exception {
        when(chunks.findViewById(7216L)).thenReturn(java.util.Optional.of(chunkView(7216L, 7L)));

        mvc.perform(get("/api/chunks/7216"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.documentId").value(7))
                .andExpect(jsonPath("$.docTitle").value("东方财富2025年年度报告"))
                .andExpect(jsonPath("$.pageStart").value(7))
                .andExpect(jsonPath("$.chunkType").value("TABLE"))
                .andExpect(jsonPath("$.content").value("| 营业总收入（元） | 16,067,741,039.51 |"));
    }

    @Test
    @DisplayName("单块不存在 → 404 CHUNK_NOT_FOUND（不能 200 空体：前端分不清「没这块」和「取失败」）")
    void chunkDetailNotFound() throws Exception {
        when(chunks.findViewById(999L)).thenReturn(java.util.Optional.empty());

        mvc.perform(get("/api/chunks/999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("CHUNK_NOT_FOUND"));
    }

    @Test
    @DisplayName("单块所属文档不可读 → 404（不用 403：403 等于告诉对方「这个块存在」）")
    void chunkDetailOfUnreadableDocumentIs404() throws Exception {
        when(chunks.findViewById(7216L)).thenReturn(java.util.Optional.of(chunkView(7216L, 7L)));
        when(authorizer.filterReadable(List.of(7L))).thenReturn(List.of());

        mvc.perform(get("/api/chunks/7216"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("CHUNK_NOT_FOUND"));
    }

    private static ChunkRepository.ChunkView chunkView(long id, long documentId) {
        return new ChunkRepository.ChunkView(id, documentId, null, "CHILD", "TABLE",
                "第二节 公司简介和主要财务指标 § 五、主要会计数据和财务指标", "五、主要会计数据和财务指标",
                "| 营业总收入（元） | 16,067,741,039.51 |", 7, 7, 469, 2, null, null, null, null,
                "东方财富2025年年度报告");
    }

    private static DocumentRepository.DocumentRow row(long id, String title, String status) {
        return new DocumentRepository.DocumentRow(id, title, "300059", "东方财富",
                2025, "ANNUAL", status, 181, null, 9, 654,
                OffsetDateTime.parse("2026-10-20T10:00:00+08:00"), 100, "已完成");
    }

    @Test
    @DisplayName("limit 透传到仓储（夹紧逻辑在仓储里，不在控制器）")
    void limitIsPassedThrough() throws Exception {
        when(documents.listDocuments(200)).thenReturn(List.of());

        mvc.perform(get("/api/documents").param("limit", "200"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));

        verify(documents).listDocuments(200);
    }
}
