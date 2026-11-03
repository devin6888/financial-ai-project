package com.finagent.agent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 审批选项 → 状态补丁（阶段 22）。这一层必须能被单独验证：
 * 它决定了「点了 2024 年报」到底改没改检索条件，而不只是改了个界面状态。
 */
class ApprovalPatchTest {

    private static AnalysisState before(String rewritten) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put(AnalysisState.QUESTION, "营业总收入和净利润分别是多少");
        data.put(AnalysisState.REWRITTEN_QUERY, rewritten);
        data.put(AnalysisState.APPROVAL_REASONS, List.of("year_not_specified"));
        return new AnalysisState(data);
    }

    @Test
    @DisplayName("点了某一年：年份写进状态、计划留一行、歧义清空（恢复后不会再弹）")
    void yearChoicePatchesState() {
        Map<String, Object> patch = AgentRunService.approvalPatch(
                before(""), AgentRun.DECISION_APPROVE, null,
                new AgentRunService.ApprovalChoice(2024, 2024, List.of()));

        assertThat(patch).containsEntry(AnalysisState.YEAR_FROM, 2024);
        assertThat(patch).containsEntry(AnalysisState.YEAR_TO, 2024);
        assertThat(patch).containsEntry(AnalysisState.DECISION, AgentRun.DECISION_APPROVE);
        assertThat(patch.get(AnalysisState.APPROVAL_REASONS)).isEqualTo(List.of());
        assertThat(patch.get(AnalysisState.PLAN).toString()).contains("2024 年");
    }

    @Test
    @DisplayName("EDIT：补充文本拼进检索式，并从里面认出年份")
    void editMergesNoteAndExtractsYear() {
        Map<String, Object> patch = AgentRunService.approvalPatch(
                before("营业总收入和净利润分别是多少"), AgentRun.DECISION_EDIT, "只看2024年的总营收",
                AgentRunService.ApprovalChoice.none());

        assertThat(patch).containsEntry(AnalysisState.REWRITTEN_QUERY,
                "营业总收入和净利润分别是多少 只看2024年的总营收");
        assertThat(patch).containsEntry(AnalysisState.YEAR_FROM, 2024);
        assertThat(patch).containsEntry(AnalysisState.DECISION_NOTE, "只看2024年的总营收");
    }

    @Test
    @DisplayName("什么都没选：只写决定，不动检索条件（「两份都看」就是这条）")
    void plainApproveDoesNotTouchFilters() {
        Map<String, Object> patch = AgentRunService.approvalPatch(
                before(""), AgentRun.DECISION_APPROVE, null, AgentRunService.ApprovalChoice.none());

        assertThat(patch).containsEntry(AnalysisState.DECISION, AgentRun.DECISION_APPROVE);
        assertThat(patch).doesNotContainKeys(AnalysisState.YEAR_FROM, AnalysisState.YEAR_TO);
        assertThat(patch).doesNotContainKey(AnalysisState.SCOPE_KIND);
    }

    @Test
    @DisplayName("选公司：作用域收窄成 FILTER，年份/类型沿用当前值")
    void companyChoiceNarrowsScope() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put(AnalysisState.SCOPE_YEARS, List.of(2024, 2025));
        data.put(AnalysisState.SCOPE_DOC_TYPES, List.of("ANNUAL"));
        AnalysisState state = new AnalysisState(data);

        Map<String, Object> patch = AgentRunService.approvalPatch(
                state, AgentRun.DECISION_APPROVE, null,
                new AgentRunService.ApprovalChoice(null, null, List.of("300059")));

        assertThat(patch).containsEntry(AnalysisState.SCOPE_KIND, "FILTER");
        assertThat(patch.get(AnalysisState.SCOPE_COMPANY_CODES)).isEqualTo(List.of("300059"));
        assertThat(patch.get(AnalysisState.SCOPE_YEARS)).isEqualTo(List.of(2024, 2025));
    }
}
