package com.finagent.retrieval;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RetrievalRequestTest {

    @Test
    void zeroTopKBecomesDefaults() {
        // 传 0 不能变成「什么都不召回」—— 必须兜底成 §9.13 的配额
        var r = RetrievalRequest.of("净利润");
        assertThat(r.topDense()).isEqualTo(30);
        assertThat(r.topSparse()).isEqualTo(30);
        assertThat(r.fusionLimit()).isEqualTo(50);
        assertThat(r.topRerank()).isEqualTo(8);
    }

    @Test
    void negativeTopKAlsoBecomesDefaults() {
        var r = new RetrievalRequest("x", null, -5, -1, 0, -9, true);
        assertThat(r.topDense()).isEqualTo(30);
        assertThat(r.topSparse()).isEqualTo(30);
        assertThat(r.topRerank()).isEqualTo(8);
    }

    @Test
    void explicitTopKIsKept() {
        var r = new RetrievalRequest("x", null, 10, 20, 25, 5, false);
        assertThat(r.topDense()).isEqualTo(10);
        assertThat(r.topSparse()).isEqualTo(20);
        assertThat(r.fusionLimit()).isEqualTo(25);
        assertThat(r.topRerank()).isEqualTo(5);
        assertThat(r.useRerank()).isFalse();
    }

    @Test
    void blankQueryIsRejected() {
        assertThatThrownBy(() -> RetrievalRequest.of("   "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("query");
        assertThatThrownBy(() -> RetrievalRequest.of((String) null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void queryIsStripped() {
        assertThat(RetrievalRequest.of("  净利润 ").query()).isEqualTo("净利润");
    }

    @Test
    void nullFiltersBecomeNone() {
        assertThat(RetrievalRequest.of("x").filters()).isNotNull();
        assertThat(RetrievalRequest.of("x").filters().documentIds()).isEmpty();
    }

    @Test
    void retrievedChunkCopiesPreserveOtherFields() {
        var c = new RetrievedChunk(1L, 2L, 3L, "TEXT", "s", "t", "c", 5, 6, "b",
                1, null, 0.0, null);
        var scored = c.withRrfScore(0.5).withRerankScore(0.9);
        assertThat(scored.rrfScore()).isEqualTo(0.5);
        assertThat(scored.rerankScore()).isEqualTo(0.9);
        assertThat(scored.denseRank()).isEqualTo(1);
        assertThat(scored.sparseRank()).isNull();
        assertThat(scored.pageStart()).isEqualTo(5);
    }
}
