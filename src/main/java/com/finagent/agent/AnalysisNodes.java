package com.finagent.agent;

import java.util.Map;

/**
 * 八个节点的实现契约 —— 图只负责「怎么连」，节点负责「做什么」。
 *
 * <p>拆成接口的目的：Task 3 可以用假实现把拓扑与条件边跑通（不碰模型、不碰数据库），
 * Task 4 再注入真实现（RouterService / HybridRetriever / AnswerService …）。
 *
 * <p>每个方法返回「状态补丁」（只含本节点负责的键），由 LangGraph4j 按 {@link AnalysisState#SCHEMA}
 * 里的通道语义合并。返回空 Map 表示这一步没有状态变化。
 */
public interface AnalysisNodes {

    Map<String, Object> understand(AnalysisState state);

    Map<String, Object> plan(AnalysisState state);

    Map<String, Object> retrieve(AnalysisState state);

    Map<String, Object> verify(AnalysisState state);

    Map<String, Object> rewrite(AnalysisState state);

    /**
     * 事实层（ST16 Task 2）：把证据表格里的数抽成【已报事实】，再算出同比/占比/贡献度。
     *
     * <p>放在 approve 之后、answer 之前 —— 人批之前的节点只负责「找证据」，被拒的 run 不该白算一遍。
     * 只写 {@link AnalysisState#FACTS} 与警告，不改 hits / answer。
     */
    Map<String, Object> compute(AnalysisState state);

    Map<String, Object> answer(AnalysisState state);

    Map<String, Object> citeCheck(AnalysisState state);

    /** 证据确实不够时的终态：给可读答案 + 明确原因（契约 C6 第 5 条）。 */
    Map<String, Object> respondInsufficient(AnalysisState state);

    /**
     * 行情取数（阶段 23）：走 MCP 子进程拿实时快照/历史，写 MARKET_* 与 MARKET_FACTS。
     *
     * <p>失败不抛异常：写 MARKET_ERROR 后继续，由 {@code answer_market} 出降级文案 ——
     * 行情是可选的增强，不该让整轮问答 500。
     */
    Map<String, Object> market(AnalysisState state);

    /** 纯行情问题的答案：模板渲染数字与数据时间，不经模型（数字必须逐字准确）。 */
    Map<String, Object> answerMarket(AnalysisState state);
}