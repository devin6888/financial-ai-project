package com.finagent.retrieval.rerank;

import java.util.List;

/**
 * 重排模型抽象 —— <b>Spring AI 至今没有这个接口</b>（GitHub issues #4343 / #5776
 * 仍是 feature request），所以自定义一层。
 *
 * <p>为什么要抽象而不是直接调 HTTP：Rerank 是「可选增强」，换模型、加缓存、做降级
 * 都必须能在<b>调用方不知情</b>的前提下替换实现。这一层就是守住这个边界。
 *
 * <p><b>契约</b>（实现方必须守住，调用方可以依赖）：
 * <ol>
 *   <li>返回条数不超过 {@code topN}，按相关性<b>降序</b>；</li>
 *   <li>{@link RerankResult#index()} 是输入 {@code documents} 的下标，<b>不是名次</b>；</li>
 *   <li>分数只在<b>本轮内部</b>可比，跨模型不可比 —— 它不是概率，是模型自己的打分；</li>
 *   <li>失败一律抛 {@link RerankException}，<b>本层不降级</b>。降级放在编排层，
 *       因为只有编排层知道降级后 trace 该怎么标（§9.7 要求 degraded=true）。</li>
 * </ol>
 */
public interface RerankModel {

    /**
     * @param query     用户问题
     * @param documents 候选文本（已拼好上下文），顺序即输入顺序
     * @param topN      最多返回几条
     * @return 按相关性降序；候选不足时可能少于 topN
     * @throws RerankException 调用失败、响应无法解析、下标越界
     */
    List<RerankResult> rerank(String query, List<String> documents, int topN);

    /** 模型名，写进 trace；未启用精排时返回 {@link NoopRerankModel#NAME}。 */
    String modelName();
}
