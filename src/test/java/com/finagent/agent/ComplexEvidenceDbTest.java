package com.finagent.agent;

import com.finagent.context.ContextPackage;
import com.finagent.scope.ChatScope;
import com.finagent.scope.ResolvedScope;
import com.finagent.scope.ScopeResolver;
import com.finagent.understanding.QueryUnderstanding;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 真库回归：复杂问题拆解后，「2025 年主要会计数据」那张主表必须留在上下文里（ST15 §4.2）。
 *
 * <p><b>这条用例在防什么</b>：拆出来的碎片子问题（「并结合净利润」）会召回财务报表附注页，
 * 附注的 rerank 分数（实测 0.602）高于主表（0.523），于是 6000 token 的上下文预算被附注吃掉、
 * 主表被挤掉 —— 模型只能在「2025 | 2024」两列并排的表里挑，答成 2024 年的数。
 * 修法是让每个片段自包含（补主体与年份），主表分数升到 0.572 并回到上下文。
 *
 * <p>跑法：{@code mvn -o test "-Dtest=ComplexEvidenceDbTest" "-Dgroups=db" "-Dsurefire.excludedGroups="}
 * （真检索 + 真 rerank，约 20 秒，会产生百炼调用费用）
 */
@SpringBootTest
@ActiveProfiles("test")
@Tag("db")
class ComplexEvidenceDbTest {

    private static final String QUESTION =
            "分析东方财富2025年营业收入增长的主要原因，并结合净利润、毛利率的变化说明盈利能力";

    /**
     * document 7 第 7 页「五、主要会计数据和财务指标」，2025 与 2024 两列并排的那张表。
     *
     * <p>不写死 chunk id：文档重切过几次（现在 active 版本是 11），写死的 id（曾经的 7216）会变成
     * SUPERSEDED 行 —— 而检索只读 active 版本的 ACTIVE 行（C4），断言必然红。
     * 改成按「第 7 页 + TABLE + 含 2025 年营业总收入」定位，与版本号无关。
     */
    private static final int MAIN_TABLE_PAGE = 7;

    /** 2025 年营业总收入（主表第一列）。 */
    private static final String REVENUE_2025 = "16,067,741,039.51";

    @Autowired
    private RealAnalysisNodes nodes;

    @Autowired
    private ScopeResolver scopes;

    @Test
    @DisplayName("复杂问题：碎片补上主体与年份后，2025 主表仍在上下文里")
    void fragmentedSubQuestionsStillKeepMainTable() {
        AnalysisState state = initial();

        Map<String, Object> planPatch = nodes.plan(state);
        state = merged(state, planPatch);
        assertThat(state.subQuestions()).as("拆出的子问题").hasSize(3);
        assertThat(state.subQuestions().get(1)).as("第二个片段必须自包含").contains("东方财富").contains("2025年");

        state = merged(state, nodes.retrieve(state));
        assertThat(state.hits()).as("多路合并后的候选").isNotEmpty();

        ResolvedScope scope = scopes.resolve(ScopeMapper.toScope(state));
        QueryUnderstanding qu = ScopeMapper.toQuery(state);
        ContextPackage pkg = nodes.packageOf(state, qu, scope);

        assertThat(pkg.blocks()).as("上下文块").isNotEmpty();
        assertThat(pkg.blocks()).as("必须含 2025 年主要会计数据那张表（第 7 页 TABLE，且带 2025 年营业总收入）")
                .anyMatch(b -> "TABLE".equals(b.chunkType())
                        && b.pageStart() != null && b.pageStart() == MAIN_TABLE_PAGE
                        && b.content() != null && b.content().contains(REVENUE_2025));
    }

    /** 直接给结构化字段，跳过 understand（不调模型，结果可复现）。 */
    private static AnalysisState initial() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put(AnalysisState.QUESTION, QUESTION);
        data.put(AnalysisState.RUN_ID, "db-complex-evidence");
        data.putAll(ScopeMapper.initialScope(ChatScope.corpus()));
        data.put(AnalysisState.QUERY_TYPE, "COMPLEX_ANALYSIS");
        data.put(AnalysisState.COMPANY_CODES, List.of("300059"));
        data.put(AnalysisState.COMPANY_MENTIONS, List.of("东方财富"));
        data.put(AnalysisState.YEAR_FROM, 2025);
        data.put(AnalysisState.YEAR_TO, 2025);
        data.put(AnalysisState.METRICS, List.of("营业收入", "净利润", "毛利率"));
        return new AnalysisState(data);
    }

    private static AnalysisState merged(AnalysisState state, Map<String, Object> patch) {
        Map<String, Object> data = new LinkedHashMap<>(state.data());
        data.putAll(patch);
        return new AnalysisState(data);
    }
}