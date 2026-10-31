package com.finagent.document;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * 进度上报的纯单测：只验两件容易在真链路上「悄悄错」的事。
 *
 * <p>① 向量化插值必须落在 55~95 且按<b>条数</b>而不是批次（最后一批常不满 10 条）；
 * <p>② 进度写库失败绝不能冒泡 —— 文档已经入库了，进度只是观测。
 */
class DocumentProgressTest {

    private DocumentRepository documents;
    private DocumentProgress progress;

    @BeforeEach
    void setUp() {
        documents = mock(DocumentRepository.class);
        progress = new DocumentProgress(documents);
    }

    @Test
    @DisplayName("step 原样透传百分比与阶段文案")
    void stepPassesThrough() {
        progress.step(7L, DocumentProgress.PARSING, DocumentProgress.STAGE_PARSING);

        verify(documents).updateProgress(7L, DocumentProgress.PARSING, DocumentProgress.STAGE_PARSING);
    }

    @Test
    @DisplayName("进度写库抛异常时只记日志，不把主流程拖失败")
    void stepSwallowsRepositoryFailure() {
        doThrow(new IllegalStateException("boom")).when(documents)
                .updateProgress(anyLong(), anyInt(), org.mockito.ArgumentMatchers.anyString());

        assertThatCode(() -> progress.step(7L, 50, "切块中")).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("向量化每批按累计条数插值：10/654 → 56%，654/654 → 95%")
    void embeddingStepInterpolatesByCount() {
        List<Integer> seen = new ArrayList<>();
        DocumentRepository repo = mock(DocumentRepository.class);
        DocumentProgress p = new DocumentProgress(repo);
        org.mockito.Mockito.doAnswer(inv -> {
            seen.add(inv.getArgument(1));
            return null;
        }).when(repo).updateProgress(anyLong(), anyInt(), org.mockito.ArgumentMatchers.anyString());

        p.embeddingStep(7L, 10, 654);
        p.embeddingStep(7L, 654, 654);

        assertThat(seen).containsExactly(56, DocumentProgress.VECTORIZED);
    }

    @Test
    @DisplayName("总数为 0 时不报进度（避免 0/0 出 NaN 再被强转成 0）")
    void embeddingStepIgnoresEmptyBatch() {
        progress.embeddingStep(7L, 0, 0);

        verify(documents, org.mockito.Mockito.never()).updateProgress(anyLong(), anyInt(),
                org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    @DisplayName("阶段文案带「向量化 x/y」，前端据此显示到第几条")
    void embeddingStepCarriesCounts() {
        List<String> stages = new ArrayList<>();
        DocumentRepository repo = mock(DocumentRepository.class);
        DocumentProgress p = new DocumentProgress(repo);
        org.mockito.Mockito.doAnswer(inv -> {
            stages.add(inv.getArgument(2));
            return null;
        }).when(repo).updateProgress(anyLong(), anyInt(), org.mockito.ArgumentMatchers.anyString());

        p.embeddingStep(7L, 120, 654);

        assertThat(stages).containsExactly("向量化 120/654");
    }
}
