package com.finagent.agent;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Task 3 用的假节点：只改状态，不调模型、不碰数据库。
 *
 * <p>它存在的意义是「把拓扑与条件边先跑通」——真实现留到 Task 4 注入。
 * 三个开关覆盖三条需要被测的分支：第一次检索有没有证据、改写后有没有证据、引用校验前几次不过。
 */
final class FakeAnalysisNodes implements AnalysisNodes {

    private final boolean evidenceAtFirst;
    private final boolean evidenceAfterRewrite;
    private final int citeFailures;

    FakeAnalysisNodes(boolean evidenceAtFirst, boolean evidenceAfterRewrite, int citeFailures) {
        this.evidenceAtFirst = evidenceAtFirst;
        this.evidenceAfterRewrite = evidenceAfterRewrite;
        this.citeFailures = citeFailures;
    }

    static FakeAnalysisNodes happy() {
        return new FakeAnalysisNodes(true, true, 0);
    }

    @Override
    public Map<String, Object> understand(AnalysisState state) {
        boolean complex = state.question().contains("复杂");
        return Map.of(
                AnalysisState.QUERY_TYPE, complex ? "COMPLEX_ANALYSIS" : "EXACT_FACT",
                AnalysisState.COMPANY_CODES, List.of("300059"),
                AnalysisState.YEAR_FROM, 2025);
    }

    @Override
    public Map<String, Object> plan(AnalysisState state) {
        List<String> plan = new ArrayList<>();
        plan.add("理解问题：" + state.question());
        if (state.question().contains("复杂")) {
            plan.add("拆成 2 个子问题");
            plan.add("逐个检索证据");
            return Map.of(
                    AnalysisState.PLAN, plan,
                    AnalysisState.SUB_QUESTIONS, List.of("营业总收入是多少", "同比增速是多少"));
        }
        plan.add("检索证据");
        return Map.of(AnalysisState.PLAN, plan);
    }

    @Override
    public Map<String, Object> retrieve(AnalysisState state) {
        boolean hasEvidence = state.retryCount() > 0 ? evidenceAfterRewrite : evidenceAtFirst;
        int queries = Math.max(1, state.subQuestions().size());
        List<Map<String, Object>> hits = new ArrayList<>();
        if (hasEvidence) {
            for (int q = 1; q <= queries; q++) {
                for (int i = 1; i <= 3; i++) {
                    Map<String, Object> hit = new LinkedHashMap<>();
                    hit.put("chunkId", q * 100 + i);
                    hit.put("documentId", 7);
                    hit.put("page", 60 + i);
                    hit.put("sectionPath", "第三节 § 一、主营业务");
                    hit.put("type", "TEXT");
                    hit.put("score", 0.9 - i * 0.01);
                    hits.add(hit);
                }
            }
        }
        return Map.of(
                AnalysisState.HITS, hits,
                AnalysisState.EVIDENCE_COUNT, hits.size());
    }

    @Override
    public Map<String, Object> verify(AnalysisState state) {
        // 规则简单：一条证据都没有就是不够；有证据就直接进 answer（真实现的阈值在 Task 4）
        return Map.of(AnalysisState.INSUFFICIENT, state.hits().isEmpty());
    }

    @Override
    public Map<String, Object> rewrite(AnalysisState state) {
        return Map.of(
                AnalysisState.REWRITTEN_QUERY, state.question() + "（改写）",
                AnalysisState.RETRY_COUNT, 1,
                AnalysisState.PLAN, List.of("证据不足，改写检索式后重试"));
    }

    @Override
    public Map<String, Object> compute(AnalysisState state) {
        // 拓扑用例只关心「这一步有没有跑」，事实内容由 RealAnalysisNodes 的用例与真库回归覆盖
        return Map.of();
    }

    @Override
    public Map<String, Object> answer(AnalysisState state) {
        String suffix = state.decisionNote().isBlank() ? "" : "（补充：" + state.decisionNote() + "）";
        return Map.of(
                AnalysisState.ANSWER, "fake-answer:" + state.question() + suffix + " [E1]",
                AnalysisState.STATUS, AgentRun.STATUS_DONE);
    }

    @Override
    public Map<String, Object> citeCheck(AnalysisState state) {
        boolean fail = state.citeAttempts() < citeFailures;
        return Map.of(
                AnalysisState.CITATION_ISSUES, fail ? List.of("缺少引用编号") : List.of(),
                AnalysisState.CITE_ATTEMPTS, 1);
    }

    @Override
    public Map<String, Object> respondInsufficient(AnalysisState state) {
        return Map.of(
                AnalysisState.ANSWER, "证据不足：检索到的内容里没有能支撑这个问题的信息。",
                AnalysisState.STATUS, AgentRun.STATUS_REJECTED);
    }
}