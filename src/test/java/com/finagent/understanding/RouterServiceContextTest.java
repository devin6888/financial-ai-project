package com.finagent.understanding;

import com.finagent.document.DocumentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 多轮 QU：带上下文时怎么补、补错时怎么退、没有上下文时怎么保持原样。
 *
 * <p>单轮路径的行为由 {@link RouterServiceTest} 守住；这里只测「加上上下文之后多出来的那部分」。
 */
class RouterServiceContextTest {

    private RuleBasedRouter rule;
    private LlmRouter llm;
    private RouterService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        rule = mock(RuleBasedRouter.class);
        when(rule.tryRoute(anyString())).thenReturn(null);
        llm = mock(LlmRouter.class);
        ObjectProvider<LlmRouter> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(llm);
        service = new RouterService(rule, provider, mock(DocumentRepository.class));
    }

    @Test
    @DisplayName("规则命中时不消解：问题本身已经带了实体")
    void ruleHitSkipsContextResolution() {
        when(rule.tryRoute("600519 2025年营业收入")).thenReturn(new QueryUnderstanding(
                QueryType.EXACT_FACT, "600519 2025年营业收入", List.of("600519"),
                new int[]{2025, 2025}, List.of("营业收入"), false, false, null, false, "RULE"));

        QueryUnderstanding qu = service.understand("600519 2025年营业收入", context());

        assertThat(qu.route()).isEqualTo("RULE");
        assertThat(qu.inferredFromContext()).isFalse();
        verify(llm, never()).route(anyString(), anyString());
    }

    @Test
    @DisplayName("带上下文调 LLM：背景块非空，resolved=true 时标记「推出来的」")
    void llmGetsContextBlockAndMarksInferred() {
        when(llm.route(anyString(), anyString())).thenReturn(new QueryUnderstanding(
                QueryType.EXACT_FACT, "东方财富（300059） 2025年 那净利润呢", List.of("300059"),
                new int[]{2025, 2025}, List.of("净利润"), false, false, null, false, "LLM", true));

        QueryUnderstanding qu = service.understand("那净利润呢", context());

        assertThat(qu.inferredFromContext()).isTrue();
        assertThat(qu.companyCodes()).containsExactly("300059");
        assertThat(qu.normalizedQuery()).startsWith("东方财富（300059）");
        verify(llm).route(org.mockito.ArgumentMatchers.eq("那净利润呢"),
                org.mockito.ArgumentMatchers.contains("company=东方财富（300059）"));
    }

    @Test
    @DisplayName("LLM 挂了 → 降级也要把公司/年份补上，但绝不补指标")
    void llmFailureStillFillsCompanyAndYear() {
        when(llm.route(anyString(), anyString())).thenThrow(new RuntimeException("timeout"));

        QueryUnderstanding qu = service.understand("那净利润呢", context());

        assertThat(qu.degraded()).isTrue();
        assertThat(qu.route()).isEqualTo("DEGRADED:llm_failed");
        assertThat(qu.queryType()).isEqualTo(QueryType.SEMANTIC_EXPLANATION);
        assertThat(qu.companyCodes()).containsExactly("300059");
        assertThat(qu.yearRange()).containsExactly(2025, 2025);
        // 指标是语义判断，补错会把检索带偏 —— 降级路径必须留空
        assertThat(qu.metrics()).isEmpty();
        assertThat(qu.inferredFromContext()).isTrue();
        assertThat(qu.normalizedQuery()).isEqualTo("东方财富（300059） 2025年 那净利润呢");
    }

    @Test
    @DisplayName("LLM 没给年份 → 用上下文补；已经给了年份 → 不动")
    void fillsOnlyMissingSlots() {
        when(llm.route(anyString(), anyString())).thenReturn(new QueryUnderstanding(
                QueryType.SEMANTIC_EXPLANATION, "q", List.of(), null, List.of(),
                false, false, null, false, "LLM"));

        QueryUnderstanding filled = service.understand("q", context());

        assertThat(filled.companyCodes()).containsExactly("300059");
        assertThat(filled.yearRange()).containsExactly(2025, 2025);

        when(llm.route(anyString(), anyString())).thenReturn(new QueryUnderstanding(
                QueryType.SEMANTIC_EXPLANATION, "q", List.of("600519"), new int[]{2023, 2023},
                List.of(), false, false, null, false, "LLM"));

        QueryUnderstanding kept = service.understand("600519 2023年净利润是多少", context());

        assertThat(kept.companyCodes()).containsExactly("600519");
        assertThat(kept.yearRange()).containsExactly(2023, 2023);
    }

    @Test
    @DisplayName("上下文为空 → 走单轮重载（阶段 8 的行为零变化）")
    void emptyContextUsesSingleTurnPath() {
        when(llm.route(anyString())).thenReturn(new QueryUnderstanding(
                QueryType.SEMANTIC_EXPLANATION, "q", List.of(), null, List.of(),
                false, false, null, false, "LLM"));

        QueryUnderstanding qu = service.understand("q", TurnContext.EMPTY);

        assertThat(qu.inferredFromContext()).isFalse();
        assertThat(qu.normalizedQuery()).isEqualTo("q");
        verify(llm).route("q");
        verify(llm, never()).route(anyString(), anyString());
    }

    @Test
    @DisplayName("问题里已经有公司名 → 归一化查询不重复加前缀")
    void normalizedQueryDoesNotDuplicateCompany() {
        TurnContext ctx = new TurnContext("300059", "东方财富", 2025, 2025, List.of(), null, List.of());

        assertThat(RouterService.selfContained(ctx, "东方财富的净利润是多少"))
                .isEqualTo("2025年 东方财富的净利润是多少");
        assertThat(RouterService.selfContained(ctx, "300059 2025年净利润"))
                .isEqualTo("300059 2025年净利润");
    }

    @Test
    @DisplayName("判 ExactFact 但三要素不全 → 降级，且保留已补的槽位")
    void weakEvidenceKeepsFilledSlots() {
        when(llm.route(anyString(), anyString())).thenReturn(new QueryUnderstanding(
                QueryType.EXACT_FACT, "那净利润呢", List.of(), null, List.of(),
                false, false, null, false, "LLM"));

        QueryUnderstanding qu = service.understand("那净利润呢", context());

        assertThat(qu.queryType()).isEqualTo(QueryType.SEMANTIC_EXPLANATION);
        assertThat(qu.degraded()).isTrue();
        assertThat(qu.route()).isEqualTo("DEGRADED:weak_evidence");
        assertThat(qu.companyCodes()).containsExactly("300059");
        assertThat(qu.inferredFromContext()).isTrue();
    }

    private static TurnContext context() {
        return new TurnContext("300059", "东方财富", 2025, 2025, List.of(7L), "营业总收入",
                List.of(ContextTurn.of("东方财富2025年营业收入是多少", "1200 亿")));
    }
}
