package com.finagent.agent;

import com.finagent.scope.ChatScope;
import com.finagent.scope.ResolvedScope;
import com.finagent.scope.ScopeResolver;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 真库回归：跨年份问题必须把每一年的证据都拉进来（ST16 Task 3 §3.5）。
 *
 * <p><b>这条用例在防什么</b>：2024 年报与 2025 年报是两份文档，同一条问题只检索一次时，
 * 分数高的那一份会把另一份挤掉（实测跨年问句里 18 条证据全来自 doc 7）。
 * 修法是按年分车道：year:2025 / year:2024 各检索一次，带 docYear 过滤。
 *
 * <p>跑法：{@code mvn -o test "-Dtest=CrossYearCoverageDbTest" "-Dgroups=db" "-Dsurefire.excludedGroups="}
 * （真检索 + 真 rerank，约 20 秒，会产生百炼调用费用）
 */
@SpringBootTest
@ActiveProfiles("test")
@Tag("db")
class CrossYearCoverageDbTest {

    private static final String QUESTION =
            "分析东方财富2024年和2025年营业总收入的变化，并说明是什么原因导致的";

    /** 2025 年报。 */
    private static final long DOC_2025 = 7L;

    /** 2024 年报。 */
    private static final long DOC_2024 = 38L;

    @Autowired
    private RealAnalysisNodes nodes;

    @Autowired
    private ScopeResolver scopes;

    @Test
    @DisplayName("跨年问题：两年各一路检索车道，两份年报都有证据落进候选")
    void crossYearQuestionCoversBothAnnualReports() {
        AnalysisState state = initial();

        state = merged(state, nodes.plan(state));
        state = merged(state, nodes.retrieve(state));

        assertThat(state.lanes()).as("检索车道").contains("full", "year:2025", "year:2024");

        Set<Long> docs = new LinkedHashSet<>();
        state.hits().forEach(h -> docs.add(((Number) h.get("documentId")).longValue()));
        assertThat(docs).as("候选证据覆盖的文档").contains(DOC_2025, DOC_2024);

        assertThat(state.warnings()).as("不应有年份覆盖告警").doesNotContain("year_coverage_missing:2024",
                "year_coverage_missing:2025");
        assertThat(state.refuseReason()).as("不拒答").isBlank();
    }

    /** 直接给结构化字段，跳过 understand（不调模型，结果可复现）。 */
    private static AnalysisState initial() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put(AnalysisState.QUESTION, QUESTION);
        data.put(AnalysisState.RUN_ID, "db-cross-year");
        data.putAll(ScopeMapper.initialScope(ChatScope.corpus()));
        data.put(AnalysisState.QUERY_TYPE, "COMPLEX_ANALYSIS");
        data.put(AnalysisState.COMPANY_CODES, List.of("300059"));
        data.put(AnalysisState.COMPANY_MENTIONS, List.of("东方财富"));
        data.put(AnalysisState.YEAR_FROM, 2024);
        data.put(AnalysisState.YEAR_TO, 2025);
        data.put(AnalysisState.METRICS, List.of("营业总收入"));
        return new AnalysisState(data);
    }

    private static AnalysisState merged(AnalysisState state, Map<String, Object> patch) {
        Map<String, Object> data = new LinkedHashMap<>(state.data());
        data.putAll(patch);
        return new AnalysisState(data);
    }
}
