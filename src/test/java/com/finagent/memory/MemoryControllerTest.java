package com.finagent.memory;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.OffsetDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 记忆端点：路由 / 状态码 / JSON 形状。业务在 LongTermMemoryServiceDbTest 里测。 */
class MemoryControllerTest {

    private MockMvc mvc;
    private LongTermMemoryService memories;

    @BeforeEach
    void setUp() {
        memories = mock(LongTermMemoryService.class);
        mvc = MockMvcBuilders.standaloneSetup(new MemoryController(memories)).build();
    }

    @Test
    @DisplayName("POST /api/memories → 201 + id/version/status")
    void createReturns201() throws Exception {
        when(memories.save(any(), eq(LongTermMemory.SOURCE_API), eq(null), eq(null)))
                .thenReturn(memory(5L, "answer.style", 1));

        mvc.perform(post("/api/memories").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"INSTRUCTION\",\"key\":\"answer.style\",\"content\":\"先给结论\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(5))
                .andExpect(jsonPath("$.key").value("answer.style"))
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.status").value("ACTIVE"));
    }

    @Test
    @DisplayName("POST 缺 content → 400 BAD_REQUEST")
    void createWithoutContentIs400() throws Exception {
        when(memories.save(any(), any(), any(), any()))
                .thenThrow(new IllegalArgumentException("content 不能为空"));

        mvc.perform(post("/api/memories").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"key\":\"answer.style\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("BAD_REQUEST"));
    }

    @Test
    @DisplayName("GET /api/memories → 列表（含 key/content/version）")
    void listReturnsItems() throws Exception {
        when(memories.list(null)).thenReturn(List.of(memory(5L, "answer.style", 2)));

        mvc.perform(get("/api/memories"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(5))
                .andExpect(jsonPath("$[0].version").value(2))
                .andExpect(jsonPath("$[0].content").value("先给结论"));
    }

    @Test
    @DisplayName("DELETE 存在的 → 204；不存在（他人）→ 404 MEMORY_NOT_FOUND")
    void deleteIs204AndUnknownIs404() throws Exception {
        mvc.perform(delete("/api/memories/5")).andExpect(status().isNoContent());

        org.mockito.Mockito.doThrow(new MemoryNotFoundException(9L)).when(memories).delete(9L);
        mvc.perform(delete("/api/memories/9"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("MEMORY_NOT_FOUND"));
        verify(memories).delete(5L);
    }

    @Test
    @DisplayName("expiresAt 不是 ISO 时间 → 400（不做静默忽略）")
    void badExpiryIs400() throws Exception {
        mvc.perform(post("/api/memories").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"key\":\"answer.style\",\"content\":\"先给结论\",\"expiresAt\":\"下个月\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("BAD_REQUEST"));
    }

    private static LongTermMemory memory(long id, String key, int version) {
        return new LongTermMemory(id, 1L, LongTermMemory.SUBJECT_USER, 1L, LongTermMemory.TYPE_INSTRUCTION,
                key, "先给结论", null, LongTermMemory.SOURCE_API, null, 1.0, 3,
                LongTermMemory.STATUS_ACTIVE, version, null, null, 0, null, OffsetDateTime.now());
    }
}
