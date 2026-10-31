package com.finagent.agent;

import com.finagent.answer.AnswerResult;
import com.finagent.scope.ChatScope;
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
 * 真库回归：事实层在真实上下文上抽得出数、算得出同比（ST16 Task 2.7）。
 *
 * <p><b>这条用例在防什么</b>：事实层全是纯函数，单测用的是手写表格片段。真实报告的表格是
 * 「合并表头 + 分组行 + 子表头」的形态（7246 的表头里 2025 年出现三次），
 * 只有跑真库才能确认 {@code TableRowParser.regroup} 没有把 2024 的金额错配到 2025 列。
 *
 * <p>跑法：{@code mvn -o test "-Dtest=FactLayerDbTest" "-Dgroups=db" "-Dsurefire.excludedGroups="}
 * （真检索 + 真 rerank，约 20 秒，会产生百炼调用费用）
 */
@SpringBootTest
@ActiveProfiles("test")
@Tag("db")
class FactLayerDbTest {

    private static final String QUESTION = "东方财富2025年营业总收入同比增长多少";

    /** 2025 年营业总收入（主表第一列）。状态里的 value 是去掉千分位的纯数字，display 才是「160.68 亿元」。 */
    private static final String REVENUE_2025 = "16067741039.51";

    /** 披露的同比（主表「本年比上年增减」列）。 */
    private static final String YOY_2025 = "38.46";

    @Autowired
    private RealAnalysisNodes nodes;

    @Test
    @DisplayName("事实层：真上下文里抽出营业总收入，并算出同比 38.46%")
    void extractsAndComputesFromRealContext() {
        AnalysisState state = initial();

        state = merged(state, nodes.retrieve(state));
        assertThat(state.hits()).as("多路合并后的候选").isNotEmpty();

        Map<String, Object> patch = nodes.compute(state);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> facts = (List<Map<String, Object>>) patch.get(AnalysisState.FACTS);

        assertThat(facts).as("事实清单").isNotEmpty();
        assertThat(facts).as("必须抽到 2025 年营业总收入")
                .anyMatch(f -> "160.68 亿元".equals(f.get("display"))
                        && REVENUE_2025.equals(String.valueOf(f.get("value")))
                        && AnswerResult.ORIGIN_REPORTED.equals(f.get("origin")));
        assertThat(facts).as("必须算出同比增长率 38.46%，并标明是系统计算")
                .anyMatch(f -> String.valueOf(f.get("metric")).contains("同比增长率")
                        && YOY_2025.equals(String.valueOf(f.get("value")))
                        && AnswerResult.ORIGIN_COMPUTED.equals(f.get("origin"))
                        && String.valueOf(f.get("formula")).contains("16,067,741,039.51"));

        Object warnings = patch.get(AnalysisState.WARNINGS);
        List<?> list = warnings instanceof List<?> l ? l : List.of();
        assertThat(list).as("与披露值一致时不应报冲突").noneMatch(w -> String.valueOf(w).startsWith("compute_conflict"));
    }

    /** 直接给结构化字段，跳过 understand（不调模型，结果可复现）。 */
    private static AnalysisState initial() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put(AnalysisState.QUESTION, QUESTION);
        data.put(AnalysisState.RUN_ID, "db-fact-layer");
        data.putAll(ScopeMapper.initialScope(ChatScope.corpus()));
        data.put(AnalysisState.QUERY_TYPE, "COMPARISON");
        data.put(AnalysisState.COMPANY_CODES, List.of("300059"));
        data.put(AnalysisState.COMPANY_MENTIONS, List.of("东方财富"));
        data.put(AnalysisState.YEAR_FROM, 2025);
        data.put(AnalysisState.YEAR_TO, 2025);
        data.put(AnalysisState.METRICS, List.of("营业总收入"));
        data.put(AnalysisState.ANSWER_SHAPES, List.of("COMPARISON"));
        return new AnalysisState(data);
    }

    private static AnalysisState merged(AnalysisState state, Map<String, Object> patch) {
        Map<String, Object> data = new LinkedHashMap<>(state.data());
        data.putAll(patch);
        return new AnalysisState(data);
    }
}
