package com.finagent.agent;

import com.finagent.retrieval.RetrievalRequest;
import com.finagent.scope.DocMeta;
import com.finagent.scope.ResolvedScope;
import com.finagent.scope.ScopeKind;
import com.finagent.understanding.AnswerShape;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 车道规划与年份覆盖（ST16 Task 3）。
 *
 * <p>这些都是纯函数：不查库、不调模型、不花一分钱。它们的正确性决定了两件事 ——
 * 「跨年问题会不会少一年的证据」和「一次答案要不要多花两三次 rerank」。
 */
class RetrievalLanesTest {

    private static final Set<Integer> BOTH_YEARS = Set.of(2024, 2025);

    private static List<String> ids(List<RetrievalLane> lanes) {
        return lanes.stream().map(RetrievalLane::id).toList();
    }

    @Test
    @DisplayName("单年问题：只有整句一路（QU 的年份过滤本来就生效，不需要按年分路）")
    void singleYearHasNoYearLane() {
        List<String> warnings = new ArrayList<>();

        List<RetrievalLane> lanes = RetrievalLanes.plan("东方财富2025年营业总收入",
                "东方财富2025年营业总收入", List.of(), Set.of(), new int[]{2025, 2025}, BOTH_YEARS, warnings);

        assertThat(ids(lanes)).containsExactly("full");
        assertThat(warnings).isEmpty();
    }

    @Test
    @DisplayName("跨年问题：每年一路，按年份倒序（新的一年在前面）")
    void crossYearAddsOneLanePerYear() {
        List<String> warnings = new ArrayList<>();

        List<RetrievalLane> lanes = RetrievalLanes.plan("2024年和2025年营业总收入",
                "2024年和2025年营业总收入", List.of(), Set.of(), new int[]{2024, 2025}, BOTH_YEARS, warnings);

        assertThat(ids(lanes)).containsExactly("full", "year:2025", "year:2024");
        assertThat(lanes.get(1).docYear()).isEqualTo(2025);
        assertThat(lanes.get(1).topK()).isEqualTo(RetrievalLanes.YEAR_TOP_K);
        assertThat(warnings).isEmpty();
    }

    @Test
    @DisplayName("三年区间：三路；四年以上：不加年份车道并记 year_range_too_wide（车道会爆炸）")
    void wideRangeFallsBack() {
        List<String> three = new ArrayList<>();
        assertThat(ids(RetrievalLanes.plan("2023到2025年", "2023到2025年", List.of(), Set.of(),
                new int[]{2023, 2025}, Set.of(2023, 2024, 2025), three)))
                .containsExactly("full", "year:2025", "year:2024", "year:2023");
        assertThat(three).isEmpty();

        List<String> wide = new ArrayList<>();
        assertThat(ids(RetrievalLanes.plan("2022到2025年", "2022到2025年", List.of(), Set.of(),
                new int[]{2022, 2025}, Set.of(2022, 2023, 2024, 2025), wide)))
                .containsExactly("full");
        assertThat(wide).containsExactly(RetrievalLanes.WARN_YEAR_TOO_WIDE);
    }

    @Test
    @DisplayName("作用域里没有该年文档：不加那一路，直接记 year_coverage_missing:2024")
    void missingScopeYearIsWarnedNotLaned() {
        List<String> warnings = new ArrayList<>();

        List<RetrievalLane> lanes = RetrievalLanes.plan("2024年和2025年营业总收入",
                "2024年和2025年营业总收入", List.of(), Set.of(), new int[]{2024, 2025},
                Set.of(2025), warnings);

        assertThat(ids(lanes)).containsExactly("full", "year:2025");
        assertThat(warnings).containsExactly("year_coverage_missing:2024");
    }

    @Test
    @DisplayName("归因/结构形态加一路「第三节」；两种形态同时命中只加一路")
    void sectionLaneAddedOnce() {
        List<RetrievalLane> lanes = RetrievalLanes.plan("收入增长的原因与结构", "q", List.of(),
                Set.of(AnswerShape.ATTRIBUTION, AnswerShape.BREAKDOWN), new int[]{2025, 2025},
                Set.of(2025), new ArrayList<>());

        assertThat(ids(lanes)).containsExactly("full", "attr");
        assertThat(lanes.get(1).sectionPrefix()).isEqualTo("第三节");
        assertThat(lanes.get(1).topK()).isEqualTo(RetrievalLanes.SECTION_TOP_K);
    }

    @Test
    @DisplayName("子问题各占一路，编号从 0 开始，空片段跳过")
    void subQuestionsBecomeLanes() {
        List<RetrievalLane> lanes = RetrievalLanes.plan("整句", "整句",
                List.of("东方财富2025年营业收入增长的原因", "  ", "东方财富2025年毛利率"),
                Set.of(), new int[]{2025, 2025}, Set.of(2025), new ArrayList<>());

        assertThat(ids(lanes)).containsExactly("full", "sub:0", "sub:2");
    }

    @Test
    @DisplayName("车道转请求：年份车道带 docYear，章节车道带 sectionPathPrefix，整句不带过滤")
    void laneToRequestCarriesFilters() {
        RetrievalRequest year = RetrievalLane.year(2024, "q", RetrievalLanes.YEAR_TOP_K).toRequest();
        assertThat(year.filters().docYear()).isEqualTo(2024);
        assertThat(year.filters().sectionPathPrefix()).isNull();
        assertThat(year.topRerank()).isEqualTo(RetrievalLanes.YEAR_TOP_K);

        RetrievalRequest section = RetrievalLane.section("q", RetrievalLanes.SECTION_TOP_K).toRequest();
        assertThat(section.filters().sectionPathPrefix()).isEqualTo("第三节");
        assertThat(section.filters().docYear()).isNull();

        RetrievalRequest full = RetrievalLane.full("q").toRequest();
        assertThat(full.filters().docYear()).isNull();
        assertThat(full.filters().sectionPathPrefix()).isNull();
        assertThat(full.topRerank()).isEqualTo(RetrievalRequest.DEFAULT_TOP_RERANK);
    }

    @Test
    @DisplayName("年份覆盖校验：两年都有证据 → 不缺；只命中 2025 → 缺 2024")
    void missingYearsReflectsHits() {
        ResolvedScope scope = scope();
        List<Map<String, Object>> both = List.of(hit(7), hit(38));

        assertThat(RetrievalLanes.missingYears(both, scope, new int[]{2024, 2025})).isEmpty();
        assertThat(RetrievalLanes.missingYears(List.of(hit(7)), scope, new int[]{2024, 2025}))
                .containsExactly(2024);
        // 单年问题不校验（yearsOf 返回空）
        assertThat(RetrievalLanes.missingYears(List.of(hit(7)), scope, new int[]{2025, 2025})).isEmpty();
    }

    @Test
    @DisplayName("年份区间倒置（2025~2024）不抛异常，照样按两年处理")
    void reversedRangeIsNormalized() {
        assertThat(RetrievalLanes.yearsOf(new int[]{2025, 2024}, null)).containsExactly(2025, 2024);
        assertThat(RetrievalLanes.yearsOf(new int[]{0, 2025}, null)).isEmpty();
        assertThat(RetrievalLanes.yearsOf(null, null)).isEmpty();
    }

    private static Map<String, Object> hit(long documentId) {
        Map<String, Object> hit = new LinkedHashMap<>();
        hit.put("documentId", documentId);
        return hit;
    }

    private static ResolvedScope scope() {
        return ResolvedScope.of(ScopeKind.CORPUS, List.of(
                new DocMeta(7L, 1L, "300059", "东方财富", 2025, "ANNUAL",
                        LocalDate.of(2025, 1, 1), LocalDate.of(2025, 12, 31), 2, "东方财富2025年年度报告"),
                new DocMeta(38L, 1L, "300059", "东方财富", 2024, "ANNUAL",
                        LocalDate.of(2024, 1, 1), LocalDate.of(2024, 12, 31), 1, "东方财富2024年年度报告")),
                ResolvedScope.REASON_SESSION, List.of());
    }
}
