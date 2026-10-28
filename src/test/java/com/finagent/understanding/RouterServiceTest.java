package com.finagent.understanding;

import com.finagent.document.DocumentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RouterServiceTest {

    private DocumentRepository documents;

    @BeforeEach
    void setUp() {
        documents = mock(DocumentRepository.class);
        when(documents.companyExists(anyString())).thenReturn(true);
    }

    @SuppressWarnings("unchecked")
    private RouterService serviceWith(LlmRouter router) {
        ObjectProvider<LlmRouter> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(router);
        return new RouterService(new RuleBasedRouter(new MetricDictionary()), provider, documents);
    }

    @Test
    void rulePathWinsWithoutCallingLlm() {
        // LLM 传 null —— 若规则路径失效会 NPE，正好暴露
        var u = serviceWith(null).understand("600519 2025年营业收入");
        assertThat(u.route()).isEqualTo("RULE");
        assertThat(u.queryType()).isEqualTo(QueryType.EXACT_FACT);
    }

    @Test
    void llmFailureDegradesToSemanticExplanation() {
        LlmRouter broken = mock(LlmRouter.class);
        when(broken.route(anyString())).thenThrow(new RuntimeException("timeout"));
        var u = serviceWith(broken).understand("营收为什么增长");

        assertThat(u.degraded()).isTrue();
        // 关键：降级方向必须是 SemanticExplanation，不是 ExactFact
        assertThat(u.queryType()).isEqualTo(QueryType.SEMANTIC_EXPLANATION);
        assertThat(u.route()).startsWith("DEGRADED");
    }

    @Test
    void missingLlmBeanDegrades() {
        var u = serviceWith(null).understand("这个问题没有代码没有年份没有指标");
        assertThat(u.degraded()).isTrue();
        assertThat(u.route()).isEqualTo("DEGRADED:llm_disabled");
        assertThat(u.queryType()).isEqualTo(QueryType.SEMANTIC_EXPLANATION);
    }

    @Test
    void exactFactWithoutAllThreeFieldsIsDegraded() {
        LlmRouter weak = mock(LlmRouter.class);
        when(weak.route(anyString())).thenReturn(new QueryUnderstanding(
                QueryType.EXACT_FACT, "q", List.of("600519"), new int[]{2025, 2025},
                List.of(), false, false, false, "LLM"));

        var u = serviceWith(weak).understand("600519 2025年那个数");

        assertThat(u.queryType()).isEqualTo(QueryType.SEMANTIC_EXPLANATION);
        assertThat(u.degraded()).isTrue();
        assertThat(u.route()).isEqualTo("DEGRADED:weak_evidence");
    }

    @Test
    void exactFactWithAllThreeFieldsSurvives() {
        LlmRouter good = mock(LlmRouter.class);
        when(good.route(anyString())).thenReturn(new QueryUnderstanding(
                QueryType.EXACT_FACT, "q", List.of("600519"), new int[]{2025, 2025},
                List.of("净利润"), false, false, false, "LLM"));

        // 问题里必须真出现代码：新规则要求代码是原文子串（ST9 §16.3）
        var u = serviceWith(good).understand("600519 2025年净利润是多少");

        assertThat(u.queryType()).isEqualTo(QueryType.EXACT_FACT);
        assertThat(u.degraded()).isFalse();
    }

    @Test
    void invalidYearIsDroppedButOtherFieldsSurvive() {
        LlmRouter bad = mock(LlmRouter.class);
        when(bad.route(anyString())).thenReturn(new QueryUnderstanding(
                QueryType.SEMANTIC_EXPLANATION, "q", List.of("600519"), new int[]{1800, 1800},
                List.of("净利润"), false, false, false, "LLM"));

        var u = serviceWith(bad).understand("某问题");

        assertThat(u.yearRange()).isNull();           // 年份被丢
        assertThat(u.metrics()).contains("净利润");    // 其余字段保留
        assertThat(u.degraded()).isFalse();
    }

    @Test
    void futureYearIsAlsoDropped() {
        LlmRouter bad = mock(LlmRouter.class);
        when(bad.route(anyString())).thenReturn(new QueryUnderstanding(
                QueryType.SEMANTIC_EXPLANATION, "q", List.of(), new int[]{2999, 2999},
                List.of(), false, false, false, "LLM"));

        assertThat(serviceWith(bad).understand("某问题").yearRange()).isNull();
    }

    @Test
    void companyCodeMustAppearInQuestion() {
        // 新规则（ST9 §16.3）：代码必须字面出现在问题原文里。模型编的 600519 就是被这一条挡掉的
        LlmRouter bad = mock(LlmRouter.class);
        when(bad.route(anyString())).thenReturn(new QueryUnderstanding(
                QueryType.SEMANTIC_EXPLANATION, "q", List.of("999999"), null,
                List.of("净利润"), false, false, false, "LLM"));

        assertThat(serviceWith(bad).understand("某问题").companyCodes()).isEmpty();
    }

    @Test
    void companyCodeInQuestionIsKeptForScopeCheck() {
        // 写字原文里的代码必须保留：作用域层要靠它判断「问的公司在不在范围内」并拒答（阶段 6 的教训）
        LlmRouter bad = mock(LlmRouter.class);
        when(bad.route(anyString())).thenReturn(new QueryUnderstanding(
                QueryType.SEMANTIC_EXPLANATION, "q", List.of("999999"), null,
                List.of("净利润"), false, false, false, "LLM"));

        assertThat(serviceWith(bad).understand("999999 2024年净利润").companyCodes())
                .containsExactly("999999");
    }

    @Test
    void malformedCompanyCodeIsDropped() {
        LlmRouter bad = mock(LlmRouter.class);
        when(bad.route(anyString())).thenReturn(new QueryUnderstanding(
                QueryType.SEMANTIC_EXPLANATION, "q", List.of("五粮液"), null,
                List.of("净利润"), false, false, false, "LLM"));

        assertThat(serviceWith(bad).understand("某问题").companyCodes()).isEmpty();
    }

    @Test
    void companyMentionMustBeSubstringOfQuestion() {
        // 模型编不出来：提到「贵州茅台」但原文里没有 → 丢弃（子串校验）
        LlmRouter bad = mock(LlmRouter.class);
        when(bad.route(anyString())).thenReturn(new QueryUnderstanding(
                QueryType.SEMANTIC_EXPLANATION, "q", List.of(), null,
                List.of("净利润"), false, false, null, false, "LLM", false, false, List.of("贵州茅台")));

        assertThat(serviceWith(bad).understand("东方财富2024年净利润").companyMentions()).isEmpty();
    }

    @Test
    void companyMentionFromQuestionIsKept() {
        LlmRouter bad = mock(LlmRouter.class);
        when(bad.route(anyString())).thenReturn(new QueryUnderstanding(
                QueryType.SEMANTIC_EXPLANATION, "q", List.of(), null,
                List.of("净利润"), false, false, null, false, "LLM", false, false, List.of("东方财富")));

        assertThat(serviceWith(bad).understand("东方财富2024年净利润").companyMentions())
                .containsExactly("东方财富");
    }

    @Test
    void badQueryTypeFallsBackToSemantic() {
        LlmRouter bad = mock(LlmRouter.class);
        when(bad.route(anyString())).thenReturn(new QueryUnderstanding(
                null, "q", List.of(), null, List.of(), false, false, false, "LLM"));

        assertThat(serviceWith(bad).understand("某问题").queryType())
                .isEqualTo(QueryType.SEMANTIC_EXPLANATION);
    }

    @Test
    void degradedKeepsQueryRetrievable() {
        var u = RouterService.degraded("原始问题", "test");
        assertThat(u.normalizedQuery()).isEqualTo("原始问题");
        assertThat(u.queryType()).isEqualTo(QueryType.SEMANTIC_EXPLANATION);
        assertThat(u.hasEntities()).isFalse();
    }

    @Test
    void toRangeNormalisesOrder() {
        assertThat(LlmRouter.toRange(2025, 2023)).containsExactly(2023, 2025);
        assertThat(LlmRouter.toRange(2025, null)).containsExactly(2025, 2025);
        assertThat(LlmRouter.toRange(null, null)).isNull();
    }
}
