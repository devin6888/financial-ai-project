package com.finagent.chunk;

/**
 * 重索引模式（§8.6）。
 *
 * <p>本阶段<b>只实现 RECHUNK</b>，其余三个显式抛异常（评审 Y2）：
 * 空方法假装支持是最坏的情况 —— 调用方会以为重嵌入生效了。
 */
public enum ReindexMode {
    /** 只改检索参数：chunk 与向量都不动。依赖阶段 4 的检索代码，本阶段无实现。 */
    RETRIEVAL_ONLY,
    /**
     * 用已存的 NormalizedDocument 重新切块并重嵌入 —— <b>不重新解析 PDF</b>。
     *
     * <p>这正是 §8.2「原始 block 必须持久化」的兑现处。
     */
    RECHUNK,
    /** 保留 chunk 边界，只重算向量。依赖「换模型」场景，阶段 4 实现。 */
    REEMBED,
    /** 从解析开始重来：调 Python 解析服务。依赖跨服务调用编排，阶段 4 实现。 */
    FULL_REPARSE
}
