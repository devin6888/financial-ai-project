package com.finagent.document;

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

    @BeforeEach
    void setUp() {
        documents = mock(DocumentRepository.class);
        mvc = MockMvcBuilders.standaloneSetup(new DocumentQueryController(documents)).build();
    }

    @Test
    @DisplayName("列表 → 200，包含 FAILED 文档与四个状态字段")
    void listIncludesFailedDocuments() throws Exception {
        when(documents.listDocuments(50)).thenReturn(List.of(
                new DocumentRepository.DocumentRow(7L, "东方财富2025年年度报告", "300059", "东方财富",
                        2025, "ANNUAL", "DONE", 181, null, 7, 654,
                        OffsetDateTime.parse("2026-10-20T10:00:00+08:00")),
                new DocumentRepository.DocumentRow(4L, "恢复测试文档", "600519", "贵州茅台",
                        2024, "ANNUAL", "FAILED", 0, "PDF 解析失败", null, 0,
                        OffsetDateTime.parse("2026-10-21T10:00:00+08:00"))));

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
    @DisplayName("limit 透传到仓储（夹紧逻辑在仓储里，不在控制器）")
    void limitIsPassedThrough() throws Exception {
        when(documents.listDocuments(200)).thenReturn(List.of());

        mvc.perform(get("/api/documents").param("limit", "200"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));

        verify(documents).listDocuments(200);
    }
}
