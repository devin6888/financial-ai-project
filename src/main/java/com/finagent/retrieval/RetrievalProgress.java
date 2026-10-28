package com.finagent.retrieval;

/**
 * 检索内部各阶段的进度回调（阶段 13，契约 C16）。
 *
 * <p>为什么需要它：流式前端要显示「正在检索 / 正在精排」，而「精排」只有
 * {@link HybridRetriever} 自己知道 —— 它内部先双路召回、再融合、再截断、最后调 rerank。
 * 如果只在 ChatService 层埋点，就只能给出「检索中」，无法区分召回与精排；
 * 而精排恰恰是外部 HTTP 调用（430~1,220 ms），抖动最大、用户等待感最强。
 *
 * <p>为什么用字符串 phase 而不是枚举：这是「给用户看的进度」而不是状态机，
 * 加一个阶段不应该逼所有调用点重新编译。phase 的合法值见契约 C16：
 * {@code dense} / {@code sparse} / {@code fuse} / {@code rerank}。
 */
@FunctionalInterface
public interface RetrievalProgress {

    /** 空实现：既有调用点（评测、测试、非流式）不需要关心进度。 */
    RetrievalProgress NONE = (phase, count) -> { };

    /**
     * 即将进入某一阶段时调用（不是完成时）。
     *
     * @param phase 阶段名，见类注释
     * @param count 该阶段的候选数；没有意义时传 0
     */
    void onPhase(String phase, int count);

    /** rerank 阶段（前端显示「正在精排」）。 */
    String PHASE_RERANK = "rerank";
}
