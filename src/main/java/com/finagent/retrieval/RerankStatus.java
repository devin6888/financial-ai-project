package com.finagent.retrieval;

/**
 * 一次检索里 Rerank 的实际结局。
 *
 * <p><b>为什么不是 boolean</b>：计划原文用单个 degraded 表示，但同一段代码把
 * useRerank=false 也算成「降级」—— 那是「没启用」，不是「降级」。
 * 两者对排查的结论正好相反：FAILED 说明外部服务挂了（要告警），
 * SKIPPED 说明这次请求故意没开（评测对比时就是它）。用一个布尔存两件事，
 * trace 就再也回答不了「为什么这次没精排」。
 */
public enum RerankStatus {
    /** 精排成功。 */
    APPLIED,
    /** 没精排：请求关闭、或没有候选。 */
    SKIPPED,
    /** 想精排但失败了，已回退到 RRF 结果。 */
    FAILED
}
