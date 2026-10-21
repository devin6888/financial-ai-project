package com.finagent.chunk;

/**
 * 切块参数与策略版本 —— 参数的**唯一事实源**。
 *
 * <p>改任何一个参数都必须同时升 {@link #VERSION}：chunk_strategy_version 是判断
 * "要不要重切"的依据（§8.6）。只改参数不升版本，会让新旧 chunk 混在同一版本号下。
 */
public final class ChunkStrategy {

    /**
     * 切块策略版本。
     *
     * <p>格式 v&lt;主&gt;.&lt;次&gt;：主版本变更意味着**必须 RECHUNK**（边界变了）；
     * 次版本意味着可 RETRIEVAL_ONLY（只是实现细节调整）。
     */
    public static final String VERSION = "v1.0";

    /** Child 目标 token 数（§8.2：300~700） */
    public static final int CHILD_MIN_TOKENS = 300;
    public static final int CHILD_MAX_TOKENS = 700;

    /**
     * Parent 锚在 section_path 的第几层（1 起）。**本阶段最重要的一个参数。**
     *
     * <p>评审用真实 181 页数据实测的 Child 入区间（300~700 token）天花板：
     * <ul>
     *   <li>depth 1 → 9 个 Parent，95.9%（但单个 Parent 远超 3000 token）</li>
     *   <li><b>depth 2 → 98 个 Parent，64.7%（默认，语义与粒度最平衡）</b></li>
     *   <li>depth 3 → 296 个 Parent，32.0%</li>
     *   <li>最深层（初版做法）→ 479 个 Parent，中位仅 48 token，17.0%</li>
     * </ul>
     *
     * <p>阶段 6 若发现上下文扩展不够用，降到 1 即可 —— 改这一个常量。
     */
    public static final int PARENT_DEPTH = 2;

    /**
     * TEXT Child 的语义 overlap（§8.5：小量即可；TABLE 不用机械 overlap）。
     *
     * <p>实现为**块级重叠**（把上一片最后一块重复到下一片开头），而不是 token 级截断 ——
     * token 级截断会把句子劈成两半。
     */
    public static final int TEXT_OVERLAP_TOKENS = 60;

    private ChunkStrategy() {
    }
}
