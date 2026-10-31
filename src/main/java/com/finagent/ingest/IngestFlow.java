package com.finagent.ingest;

import java.util.List;

/**
 * 入库流程模板 —— <b>总步数的唯一来源</b>（阶段 19）。
 *
 * <p>为什么总步数必须由模板给：如果运行时「数已执行的行数」，重试、恢复、补跑都会改变它，
 * 「第 3/8 步」就会漂移。模板是常量，重试只在同一步上累加 retry。
 *
 * <p>注意<b>步数不等于百分比</b>：解析占 95% 的时间，8 步等权算出第 3 步是 37.5%，
 * 与真实时间严重不符。进度条继续用 C28 的时间加权检查点，这里只提供位置信息。
 */
public enum IngestFlow {

    /** 上传新文档：解析 + 切块 + 嵌入，8 步。 */
    PARSE(List.of(
            IngestStep.RECEIVED,
            IngestStep.SUBMITTED,
            IngestStep.PARSING,
            IngestStep.PARSED,
            IngestStep.CHUNKING,
            IngestStep.CHUNKED,
            IngestStep.EMBEDDING,
            IngestStep.SWITCHED)),

    /** 只重切 + 重嵌（document.reindex / CLI）：不碰解析，4 步。 */
    REINDEX(List.of(
            IngestStep.CHUNKING,
            IngestStep.CHUNKED,
            IngestStep.EMBEDDING,
            IngestStep.SWITCHED)),

    /** 断点恢复：沿用 PARSE 的模板（总步数不变，前面已完成的步骤保留）。 */
    RESUME(List.of(
            IngestStep.RECEIVED,
            IngestStep.SUBMITTED,
            IngestStep.PARSING,
            IngestStep.PARSED,
            IngestStep.CHUNKING,
            IngestStep.CHUNKED,
            IngestStep.EMBEDDING,
            IngestStep.SWITCHED));

    private final List<IngestStep> steps;

    IngestFlow(List<IngestStep> steps) {
        this.steps = steps;
    }

    public List<IngestStep> steps() {
        return steps;
    }

    public int totalSteps() {
        return steps.size();
    }

    /**
     * 步骤在本流程里的序号（1 起）；不在本流程里返回 -1（调用方应忽略，而不是抛异常打断入库）。
     *
     * <p>{@code indexOf} 未命中是 -1，直接 +1 会得到 0 这个「合法但不存在」的序号 ——
     * 必须显式判掉，否则会往库里写 seq=0 的行。
     */
    public int seqOf(IngestStep step) {
        int index = steps.indexOf(step);
        return index < 0 ? -1 : index + 1;
    }

    public IngestStep first() {
        return steps.get(0);
    }
}
