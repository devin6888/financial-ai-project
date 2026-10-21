package com.finagent.embedding;

import com.finagent.chunk.model.Chunk;
import com.finagent.chunk.model.ChunkLevel;
import com.finagent.chunk.model.ChunkType;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EmbeddingServiceTest {

    /**
     * 假模型：记录每次收到的批量大小，并回等长的假向量。
     *
     * <p><b>⚠️ 必须实现 call(EmbeddingRequest) 与 embed(Document) 两个抽象方法</b> ——
     * javap 实测这两个是 abstract，embed(List) 只是 default。
     * 初版计划漏了它们，导致编译失败（评审 B3）。
     */
    static class FakeEmbeddingModel implements EmbeddingModel {
        final List<Integer> batches = new ArrayList<>();
        int dimension = 1024;
        int returnCount = -1;     // >=0 时强制只返回这么多条，用于注入失败

        @Override
        public List<float[]> embed(List<String> texts) {
            batches.add(texts.size());
            int n = returnCount >= 0 ? returnCount : texts.size();
            List<float[]> out = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                out.add(new float[dimension]);
            }
            return out;
        }

        @Override
        public EmbeddingResponse call(EmbeddingRequest request) {
            return null;
        }

        @Override
        public float[] embed(Document document) {
            return new float[dimension];
        }
    }

    @Test
    void splitsIntoBatchesOfTen() {
        var model = new FakeEmbeddingModel();
        var service = new EmbeddingService(model);

        List<String> texts = new ArrayList<>();
        for (int i = 0; i < 23; i++) {
            texts.add("文本" + i);
        }
        List<float[]> vectors = service.embedAll(texts);

        assertThat(vectors).hasSize(23);
        // 23 条应切成 10 + 10 + 3 —— 百炼单请求上限 10 行
        assertThat(model.batches).containsExactly(10, 10, 3);
    }

    @Test
    void emptyInputSkipsModelEntirely() {
        var model = new FakeEmbeddingModel();
        assertThat(new EmbeddingService(model).embedAll(List.of())).isEmpty();
        assertThat(new EmbeddingService(model).embedAll(null)).isEmpty();
        assertThat(model.batches).isEmpty();
    }

    @Test
    void mismatchedVectorCountFailsLoudly() {
        // 宁可失败也不要错位入库 —— 向量与 chunk 错位是灾难性的静默错误
        var model = new FakeEmbeddingModel();
        model.returnCount = 1;
        assertThatThrownBy(() -> new EmbeddingService(model).embedAll(List.of("a", "b")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("向量数量与输入不符");
    }

    @Test
    void modelAndVersionAreSemanticallySeparated() {
        // 评审 C9：初版把 embedding_model 存成类名、version 硬编码成模型名，语义反了
        var service = new EmbeddingService(new FakeEmbeddingModel());
        assertThat(service.modelName()).isEqualTo("text-embedding-v4");
        assertThat(service.modelVersion()).isEqualTo("v1");
    }

    @Test
    void embeddingTextPrependsTitleAndSectionPathButKeepsContent() {
        Chunk c = new Chunk(ChunkLevel.CHILD, ChunkType.TEXT,
                List.of("第一节", "一、小节"), "一、小节", "原始内容",
                1, 1, "b0", null, 4, 0,
                "单位：元", "元", "CNY", null, null, null);

        String text = EmbeddingService.embeddingText("某年报", c);

        assertThat(text).contains("某年报");
        assertThat(text).contains("第一节 > 一、小节");
        assertThat(text).contains("单位：元");
        assertThat(text).endsWith("原始内容");
    }

    @Test
    void embeddingTextEqualsContentWhenNoContext() {
        Chunk c = new Chunk(ChunkLevel.CHILD, ChunkType.TEXT,
                List.of(), null, "只有正文",
                1, 1, "b0", null, 4, 0,
                null, null, null, null, null, null);
        assertThat(EmbeddingService.embeddingText(null, c)).isEqualTo("只有正文");
    }
}
