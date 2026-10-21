package com.finagent.retrieval;

import org.assertj.core.data.Offset;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RrfFusionTest {

    private static RetrievedChunk chunk(long id, Integer dense, Integer sparse) {
        return new RetrievedChunk(id, 0, 1, "TEXT", "s", "t", "c", 1, 1, "b",
                dense, sparse, 0.0, null);
    }

    @Test
    void computesRrfWithK60() {
        // 两路都是第 1 名：1/(60+1) + 1/(60+1) = 2/61
        var fused = RrfFusion.fuse(List.of(chunk(1, 1, 1)), 60);
        assertThat(fused.get(0).rrfScore()).isCloseTo(2.0 / 61, Offset.offset(1e-9));
    }

    @Test
    void ranksByCombinedScoreNotSinglePath() {
        // A 只有 dense #1 = 1/61；B 有 dense #2 + sparse #2 = 2/62 > 1/61
        var fused = RrfFusion.fuse(List.of(chunk(1, 1, null), chunk(2, 2, 2)), 60);
        assertThat(fused).extracting(RetrievedChunk::chunkId).containsExactly(2L, 1L);
    }

    @Test
    void chunkMissingFromOnePathStillCounts() {
        var fused = RrfFusion.fuse(List.of(chunk(1, null, 1)), 60);
        assertThat(fused.get(0).rrfScore()).isCloseTo(1.0 / 61, Offset.offset(1e-9));
    }

    @Test
    void missingRankIsAbsentNotZero() {
        // 若把 null 当 0，1/(60+0)=1/60 会高于任何真实命中 —— 这是最容易犯的错
        var fused = RrfFusion.fuse(List.of(chunk(1, 1, null)), 60);
        assertThat(fused.get(0).rrfScore()).isLessThan(1.0 / 50);
    }

    @Test
    void tiesAreBrokenDeterministicallyByChunkId() {
        // 同分按 chunkId 升序，保证评测可复现
        var fused = RrfFusion.fuse(List.of(chunk(9, 1, null), chunk(3, 1, null)), 60);
        assertThat(fused).extracting(RetrievedChunk::chunkId).containsExactly(3L, 9L);
    }

    @Test
    void emptyInputYieldsEmptyOutput() {
        assertThat(RrfFusion.fuse(List.of(), 60)).isEmpty();
    }

    @Test
    void firstPathRankOneBeatsSecondPathRankOne() {
        // 名次越靠前分越高：rank1 > rank2
        var r1 = RrfFusion.fuse(List.of(chunk(1, 1, null)), 60).get(0).rrfScore();
        var r2 = RrfFusion.fuse(List.of(chunk(2, 2, null)), 60).get(0).rrfScore();
        assertThat(r1).isGreaterThan(r2);
    }

    @Test
    void differentKChangesScaleButNotOrder() {
        var a = RrfFusion.fuse(List.of(chunk(1, 1, null), chunk(2, 2, null)), 60);
        var b = RrfFusion.fuse(List.of(chunk(1, 1, null), chunk(2, 2, null)), 10);
        assertThat(a).extracting(RetrievedChunk::chunkId)
                .isEqualTo(b.stream().map(RetrievedChunk::chunkId).toList());
        // k 越小，分越高
        assertThat(b.get(0).rrfScore()).isGreaterThan(a.get(0).rrfScore());
    }
}
