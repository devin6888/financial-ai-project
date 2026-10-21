package com.finagent.retrieval.rerank;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class NoopRerankModelTest {

    private final NoopRerankModel model = new NoopRerankModel();

    @Test
    void keepsInputOrderAndMarksScoresUnknown() {
        List<RerankResult> results = model.rerank("营业收入", List.of("a", "b", "c"), 3);

        assertThat(results).extracting(RerankResult::index).containsExactly(0, 1, 2);
        // NaN 而不是 0.0 —— 0.0 会被读成「模型认为完全不相关」
        assertThat(results).allSatisfy(r -> assertThat(r.relevanceScore()).isNaN());
    }

    @Test
    void truncatesToTopN() {
        assertThat(model.rerank("q", List.of("a", "b", "c"), 2))
                .extracting(RerankResult::index)
                .containsExactly(0, 1);
    }

    @Test
    void topNLargerThanInputReturnsEverything() {
        assertThat(model.rerank("q", List.of("a", "b"), 99)).hasSize(2);
    }

    @Test
    void emptyInputOrNonPositiveTopNReturnsEmpty() {
        assertThat(model.rerank("q", List.of(), 5)).isEmpty();
        assertThat(model.rerank("q", null, 5)).isEmpty();
        assertThat(model.rerank("q", List.of("a"), 0)).isEmpty();
        assertThat(model.rerank("q", List.of("a"), -1)).isEmpty();
    }

    @Test
    void reportsNoneAsModelName() {
        assertThat(model.modelName()).isEqualTo("none");
        assertThat(NoopRerankModel.NAME).isEqualTo("none");
    }
}
