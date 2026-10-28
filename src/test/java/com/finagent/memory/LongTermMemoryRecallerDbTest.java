package com.finagent.memory;

import com.finagent.embedding.EmbeddingService;
import com.finagent.security.CurrentUser;
import com.finagent.security.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 长期记忆召回的真库测试（规则命中 / 向量阈值 / 去重 / 截断 / 过期 / 越权 / 访问计数）。
 *
 * <p>为什么用 @MockitoBean 换掉 EmbeddingService：这里要验的是「什么时候<b>不该</b>调 embedding」
 * 与「距离阈值方向有没有写反」，真调 1024 维模型既慢又不可控。向量内容由测试直接给定，
 * 所以「相似度」是可算的：记忆向量 [1,0,…] 与问题向量 [1,0.2,0,…] 的余弦距离约 0.02。
 */
@SpringBootTest(properties = "finagent.memory.enabled=true")
@ActiveProfiles("test")
class LongTermMemoryRecallerDbTest {

    private static final long TENANT = 1L;
    /** 刻意用库里不存在的合成用户：真实 analyst(id=1) 身上有手工验证留下的记忆行，会污染「空库」用例。 */
    private static final long ANALYST = 9001L;
    private static final long OTHER_USER = 9002L;
    private static final String MODEL = "text-embedding-v4";
    private static final String VERSION = "v1";

    @Autowired
    private LongTermMemoryRecaller recaller;

    @Autowired
    private LongTermMemoryRepository repository;

    @Autowired
    private JdbcTemplate jdbc;

    @MockitoBean
    private EmbeddingService embedding;

    @BeforeEach
    void setUp() {
        asUser(ANALYST);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
        jdbc.update("DELETE FROM long_term_memory WHERE tenant_id = ? AND subject_id IN (?, ?)",
                TENANT, ANALYST, OTHER_USER);
    }

    @Test
    @DisplayName("库里一条记忆都没有 → 召回为空，且一次 embedding 都不调")
    void noMemoriesMeansNoEmbedding() {
        assertThat(recaller.recall("我以前说过的格式是什么")).isEmpty();
        verify(embedding, never()).embedAll(any());
    }

    @Test
    @DisplayName("规则命中占满名额（3 条）→ 完全不调 embedding")
    void ruleHitsFillQuotaSoNoEmbedding() {
        insert("answer.style", "回答先给结论", vector(1.0f, 0f, 0f), 3);
        insert("answer.language", "用中文回答", vector(1.0f, 0f, 0f), 3);
        insert("analysis.must_cite", "每个数字都给引用", vector(1.0f, 0f, 0f), 3);

        List<LongTermMemory> got = recaller.recall("按以前的格式和语言回答，记得给引用");

        assertThat(got).extracting(LongTermMemory::memoryKey)
                .containsExactlyInAnyOrder("answer.style", "answer.language", "analysis.must_cite");
        verify(embedding, never()).embedAll(any());
    }

    @Test
    @DisplayName("向量命中：距离 0.02 ＜ 0.4 阈值 → 召回")
    void vectorHitAboveThreshold() {
        insert("contextual.quarter", "用户习惯先看单季度再看累计", vector(1.0f, 0f, 0f), 2);
        when(embedding.embedAll(any())).thenReturn(List.of(vector(1.0f, 0.2f, 0f)));

        List<LongTermMemory> got = recaller.recall("帮我看看这家公司的情况");

        assertThat(got).extracting(LongTermMemory::memoryKey).containsExactly("contextual.quarter");
        verify(embedding, times(1)).embedAll(any());
    }

    @Test
    @DisplayName("向量过滤：正交向量（距离 1.0 ＞ 0.4）→ 不召回")
    void belowThresholdExcluded() {
        insert("contextual.quarter", "用户习惯先看单季度再看累计", vector(1.0f, 0f, 0f), 2);
        when(embedding.embedAll(any())).thenReturn(List.of(vector(0f, 1f, 0f)));

        assertThat(recaller.recall("帮我看看这家公司的情况")).isEmpty();
    }

    @Test
    @DisplayName("最多 3 条：5 条候选都相似也只回 3 条")
    void atMostThreeItems() {
        for (int i = 1; i <= 5; i++) {
            insert("contextual.topic" + i, "偏好" + i, vector(1.0f, 0f, 0f), i);
        }
        when(embedding.embedAll(any())).thenReturn(List.of(vector(1.0f, 0f, 0f)));

        assertThat(recaller.recall("帮我看看这家公司的情况")).hasSize(3);
    }

    @Test
    @DisplayName("过期记忆不召回（哪怕向量完全一致）")
    void expiredExcluded() {
        insert("contextual.expired", "这条已经过期了", vector(1.0f, 0f, 0f), 3,
                OffsetDateTime.now().minusDays(1));
        when(embedding.embedAll(any())).thenReturn(List.of(vector(1.0f, 0f, 0f)));

        assertThat(recaller.recall("帮我看看这家公司的情况")).isEmpty();
    }

    @Test
    @DisplayName("别人的记忆不可见（按 subject_id 隔离）")
    void otherUserInvisible() {
        long id = insertFor(OTHER_USER, "contextual.mine", "别人的偏好", vector(1.0f, 0f, 0f), 3);
        when(embedding.embedAll(any())).thenReturn(List.of(vector(1.0f, 0f, 0f)));

        assertThat(recaller.recall("帮我看看这家公司的情况")).isEmpty();
        TenantContext.clear();
        assertThat(jdbc.queryForObject("SELECT status FROM long_term_memory WHERE id = ?", String.class, id))
                .isEqualTo(LongTermMemory.STATUS_ACTIVE);
    }

    @Test
    @DisplayName("召回成功 → 访问计数 +1、last_accessed_at 非空")
    void touchIncrementsAccessCount() {
        long id = insert("answer.style", "回答先给结论", vector(1.0f, 0f, 0f), 3);

        recaller.recall("按以前的格式回答");

        TenantContext.clear();
        Integer count = jdbc.queryForObject(
                "SELECT retrieval_count FROM long_term_memory WHERE id = ?", Integer.class, id);
        OffsetDateTime touched = jdbc.queryForObject(
                "SELECT last_accessed_at FROM long_term_memory WHERE id = ?", OffsetDateTime.class, id);
        assertThat(count).isEqualTo(1);
        assertThat(touched).isNotNull();
    }

    // ------------------------------------------------------------ helpers

    private long insert(String key, String content, float[] vector, int importance) {
        return insert(key, content, vector, importance, null);
    }

    private long insert(String key, String content, float[] vector, int importance,
                        OffsetDateTime expiresAt) {
        return insertFor(ANALYST, key, content, vector, importance, expiresAt);
    }

    private long insertFor(long userId, String key, String content, float[] vector, int importance) {
        return insertFor(userId, key, content, vector, importance, null);
    }

    private long insertFor(long userId, String key, String content, float[] vector, int importance,
                           OffsetDateTime expiresAt) {
        return repository.insert(TENANT, LongTermMemory.SUBJECT_USER, userId,
                LongTermMemory.TYPE_CONTEXTUAL, key, content, null, vector, MODEL, VERSION,
                LongTermMemory.SOURCE_API, "test", 1.0, importance, 1, expiresAt);
    }

    private static float[] vector(float... head) {
        float[] vector = new float[1024];
        System.arraycopy(head, 0, vector, 0, head.length);
        return normalize(vector);
    }

    /** 单位化后余弦距离才等于「1 - 余弦相似度」，测试里的阈值推断才成立。 */
    private static float[] normalize(float[] vector) {
        double sum = 0;
        for (float v : vector) {
            sum += (double) v * v;
        }
        double norm = Math.sqrt(sum);
        if (norm == 0) {
            return vector;
        }
        float[] out = new float[vector.length];
        for (int i = 0; i < vector.length; i++) {
            out[i] = (float) (vector[i] / norm);
        }
        return out;
    }

    private void asUser(long userId) {
        TenantContext.set(new CurrentUser(userId, TENANT, "u" + userId, "用户" + userId, Set.of("ANALYST")));
    }
}
