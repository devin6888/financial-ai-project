package com.finagent.memory;

import com.finagent.embedding.EmbeddingService;
import com.finagent.security.CurrentUser;
import com.finagent.security.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * 长期记忆写入流水线的真库测试（版本化 / 审计 / 逻辑删除 / 越权）。
 *
 * <p>用 @MockitoBean 替换 EmbeddingService：本测试关心的是「写进来什么」，不是「向量算得准不准」；
 * 真调 1024 维模型既慢又花钱。
 */
@Tag("db")
@SpringBootTest(properties = "finagent.memory.enabled=true")
@ActiveProfiles("test")
class LongTermMemoryServiceDbTest {

    private static final long TENANT = 1L;
    private static final long ANALYST = 1L;
    private static final long OUTSIDER = 2L;
    private static final String KEY = "test.style";

    @Autowired
    private LongTermMemoryService service;

    @Autowired
    private LongTermMemoryRepository repository;

    @Autowired
    private JdbcTemplate jdbc;

    @MockitoBean
    private EmbeddingService embedding;

    @BeforeEach
    void setUp() {
        asUser(ANALYST);
        when(embedding.modelName()).thenReturn("text-embedding-v4");
        when(embedding.modelVersion()).thenReturn("v1");
        float[] vector = new float[1024];
        vector[0] = 1.0f;
        when(embedding.embedAll(any())).thenReturn(List.of(vector));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
        jdbc.update("DELETE FROM memory_event WHERE tenant_id = ? AND memory_id IN "
                + "(SELECT id FROM long_term_memory WHERE memory_key LIKE 'test.%')", TENANT);
        jdbc.update("DELETE FROM long_term_memory WHERE tenant_id = ? AND memory_key LIKE 'test.%'", TENANT);
    }

    @Test
    @DisplayName("写入：ACTIVE / version=1 / 有向量 / 一条 CREATE 审计")
    void createsActiveMemoryWithAudit() {
        LongTermMemory saved = save("先给结论");

        assertThat(saved.status()).isEqualTo(LongTermMemory.STATUS_ACTIVE);
        assertThat(saved.version()).isEqualTo(1);
        assertThat(saved.memoryType()).isEqualTo(LongTermMemory.TYPE_INSTRUCTION);
        assertThat(saved.memoryKey()).isEqualTo(KEY);
        assertThat(saved.sourceType()).isEqualTo(LongTermMemory.SOURCE_API);
        assertThat(count("SELECT count(*) FROM long_term_memory WHERE embedding IS NOT NULL "
                + "AND memory_key = ?", KEY)).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM memory_event WHERE target_id = ? AND action = 'CREATE'",
                saved.id())).isEqualTo(1);
    }

    @Test
    @DisplayName("幂等：同 key 同内容写两次 → 仍一行、version 不变、审计不增加")
    void sameContentIsIdempotent() {
        LongTermMemory first = save("先给结论");
        LongTermMemory second = save("先给结论");

        assertThat(second.id()).isEqualTo(first.id());
        assertThat(second.version()).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM long_term_memory WHERE memory_key = ?", KEY)).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM memory_event WHERE target_id = ?", first.id())).isEqualTo(1);
    }

    @Test
    @DisplayName("版本化：同 key 换内容 → 旧行 SUPERSEDED、新行 version=2、审计记 SUPERSEDE")
    void changedContentSupersedesOldVersion() {
        LongTermMemory first = save("先给结论");
        LongTermMemory second = save("先给结论再给数据");

        assertThat(second.id()).isNotEqualTo(first.id());
        assertThat(second.version()).isEqualTo(2);
        assertThat(second.status()).isEqualTo(LongTermMemory.STATUS_ACTIVE);
        assertThat(statusOf(first.id())).isEqualTo(LongTermMemory.STATUS_SUPERSEDED);
        assertThat(activeCount()).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM memory_event WHERE target_id = ? AND action = 'SUPERSEDE'",
                second.id())).isEqualTo(1);
    }

    @Test
    @DisplayName("删除：逻辑删除（行还在、状态 DELETED），列表里看不到，审计记一条 DELETE")
    void deleteIsLogical() {
        LongTermMemory saved = save("先给结论");
        service.delete(saved.id());

        assertThat(statusOf(saved.id())).isEqualTo(LongTermMemory.STATUS_DELETED);
        assertThat(service.list(null)).noneMatch(m -> m.id() == saved.id());
        assertThat(count("SELECT count(*) FROM memory_event WHERE target_id = ? AND action = 'DELETE'",
                saved.id())).isEqualTo(1);
    }

    @Test
    @DisplayName("越权：删别人的记忆 → 404 语义（MemoryNotFoundException）")
    void cannotDeleteAnotherUsersMemory() {
        LongTermMemory mine = save("先给结论");

        asUser(OUTSIDER);
        assertThatThrownBy(() -> service.delete(mine.id())).isInstanceOf(MemoryNotFoundException.class);
        assertThat(statusOf(mine.id())).isEqualTo(LongTermMemory.STATUS_ACTIVE);
    }

    @Test
    @DisplayName("向量化失败不拦写入：记忆照样落库，只是没有向量")
    void embeddingFailureStillSaves() {
        when(embedding.embedAll(any())).thenThrow(new IllegalStateException("embedding api down"));

        LongTermMemory saved = save("先给结论");

        assertThat(saved.id()).isPositive();
        assertThat(count("SELECT count(*) FROM long_term_memory WHERE id = ? AND embedding IS NULL",
                saved.id())).isEqualTo(1);
    }

    @Test
    @DisplayName("会话里的显式命令会被写入（source_type=EXPLICIT_COMMAND，带原话）")
    void explicitCommandIsRecorded() {
        var saved = service.maybeRecordCommand("以后回答先给结论", "trace-1");

        assertThat(saved).isPresent();
        assertThat(saved.get().sourceType()).isEqualTo(LongTermMemory.SOURCE_COMMAND);
        assertThat(saved.get().sourceRef()).isEqualTo("以后回答先给结论");
        assertThat(saved.get().memoryKey()).isEqualTo("answer.style");
    }

    @Test
    @DisplayName("没有身份时命令一律不写（不抛异常，静默跳过）")
    void commandIgnoredWithoutIdentity() {
        TenantContext.clear();
        assertThat(service.maybeRecordCommand("以后回答先给结论", null)).isEmpty();
    }

    // ------------------------------------------------------------ helpers

    private void asUser(long userId) {
        TenantContext.set(new CurrentUser(userId, TENANT, "u" + userId, "用户" + userId, Set.of("ANALYST")));
    }

    private LongTermMemory save(String content) {
        return service.save(new LongTermMemoryService.SaveRequest(
                        LongTermMemory.TYPE_INSTRUCTION, KEY, content, 3, null),
                LongTermMemory.SOURCE_API, null, null);
    }

    private String statusOf(long id) {
        return jdbc.queryForObject("SELECT status FROM long_term_memory WHERE id = ?", String.class, id);
    }

    private int activeCount() {
        return count("SELECT count(*) FROM long_term_memory WHERE memory_key = ? AND status = 'ACTIVE'", KEY);
    }

    private int count(String sql, Object... args) {
        Integer n = jdbc.queryForObject(sql, Integer.class, args);
        return n == null ? 0 : n;
    }
}
