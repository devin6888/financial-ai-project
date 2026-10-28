package com.finagent.chunk;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 增量重嵌的分类与回填（契约 C10），纯函数，不连库。
 *
 * <p>复用三条件：hash 相同 + embedding_model 相同 + embedding_version 相同。
 * 任一不满足都必须重嵌 —— 漏掉一条就会把不同空间的向量混进同一个索引。
 */
class EmbeddingReuseServiceTest {

    private final EmbeddingReuseService service = new EmbeddingReuseService();

    private static ChunkRepository.ChildEmbedding old(String hash, String model, String version,
                                                      float... v) {
        return new ChunkRepository.ChildEmbedding(hash, model, version, v);
    }

    @Test
    @DisplayName("同 hash 且同 model/version → 复用，不需要重嵌")
    void sameHashSameModelReuses() {
        var result = service.plan(
                List.of(old("h1", "m", "v", 1, 2, 3)),
                List.of("text-a"), List.of("h1"), "m", "v");

        assertThat(result.toEmbedTexts()).isEmpty();
        assertThat(result.reusableByHash()).containsKey("h1");
        assertThat(result.reusableByHash().get("h1")).containsExactly(1f, 2f, 3f);
    }

    @Test
    @DisplayName("hash 不同 → 归入待嵌入")
    void differentHashNeedsEmbed() {
        var result = service.plan(
                List.of(old("h1", "m", "v", 1, 2, 3)),
                List.of("text-b"), List.of("h2"), "m", "v");

        assertThat(result.toEmbedTexts()).containsExactly("text-b");
        assertThat(result.reusableByHash()).doesNotContainKey("h2");
    }

    @Test
    @DisplayName("同 hash 但模型不同 → 不复用（换模型必须全量重嵌）")
    void differentModelDisablesReuse() {
        var result = service.plan(
                List.of(old("h1", "old-model", "v", 1, 2, 3)),
                List.of("text-a"), List.of("h1"), "new-model", "v");

        assertThat(result.toEmbedTexts()).containsExactly("text-a");
        assertThat(result.reusableByHash()).isEmpty();
    }

    @Test
    @DisplayName("同 hash 但版本不同 → 不复用")
    void differentVersionDisablesReuse() {
        var result = service.plan(
                List.of(old("h1", "m", "v1", 1, 2, 3)),
                List.of("text-a"), List.of("h1"), "m", "v2");

        assertThat(result.toEmbedTexts()).containsExactly("text-a");
        assertThat(result.reusableByHash()).isEmpty();
    }

    @Test
    @DisplayName("assemble：按原顺序回填，复用位用旧向量、其余用新向量")
    void assembleKeepsOrder() {
        var result = new EmbeddingReuseService.ReuseResult(
                Map.of("h1", new float[]{1, 2, 3}), List.of("text-b"));

        var vectors = service.assemble(result, List.of("h1", "h2", "h1"),
                List.of(new float[]{9, 9}));

        assertThat(vectors).hasSize(3);
        assertThat(vectors.get(0)).containsExactly(1f, 2f, 3f);
        assertThat(vectors.get(1)).containsExactly(9f, 9f);
        assertThat(vectors.get(2)).containsExactly(1f, 2f, 3f);
    }

    @Test
    @DisplayName("首次索引（无旧块）→ 全部待嵌入")
    void noOldChildrenEmbedsAll() {
        var result = service.plan(List.of(), List.of("a", "b"), List.of("h1", "h2"), "m", "v");

        assertThat(result.toEmbedTexts()).containsExactly("a", "b");
        assertThat(result.reusableByHash()).isEmpty();
    }
}
