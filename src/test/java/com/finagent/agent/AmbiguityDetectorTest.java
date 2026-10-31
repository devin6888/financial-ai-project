package com.finagent.agent;

import com.finagent.scope.ChatScope;
import com.finagent.scope.DocMeta;
import com.finagent.scope.ResolvedScope;
import com.finagent.scope.ScopeEmptyException;
import com.finagent.scope.ScopeKind;
import com.finagent.scope.ScopeResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/** 「要不要问人」的判定：只认主体与年份两个信号，不调模型。 */
@ExtendWith(MockitoExtension.class)
class AmbiguityDetectorTest {

    @Mock
    private ScopeResolver scopeResolver;

    private AmbiguityDetector detector;

    @BeforeEach
    void setUp() {
        detector = new AmbiguityDetector(scopeResolver);
    }

    private void scope(DocMeta... docs) {
        when(scopeResolver.resolve(any())).thenReturn(ResolvedScope.of(ScopeKind.CORPUS, List.of(docs), "TEST", List.of()));
    }

    private static DocMeta doc(long id, String code, String name, Integer year) {
        return new DocMeta(id, 7L, code, name, year, "ANNUAL", null, null, 3, name + year);
    }

    @Test
    @DisplayName("一家公司一份年报：任何问题都不歧义")
    void singleCompanyIsNeverAmbiguous() {
        scope(doc(7, "300059", "东方财富", 2025));

        AmbiguityDetector.Decision decision = detector.detect(ChatScope.corpus(), "经营情况怎么样");

        assertThat(decision.ambiguous()).isFalse();
        assertThat(decision.reasons()).isEmpty();
    }

    @Test
    @DisplayName("两家公司、问题没点主体 → 要问（company_not_specified）")
    void twoCompaniesWithoutMentionAsk() {
        scope(doc(7, "300059", "东方财富", 2025), doc(38, "600519", "贵州茅台", 2025));

        AmbiguityDetector.Decision decision = detector.detect(ChatScope.corpus(), "2025年净利润是多少");

        assertThat(decision.ambiguous()).isTrue();
        assertThat(decision.reasons()).containsExactly("company_not_specified");
    }

    @Test
    @DisplayName("两家公司、问题点了代码 → 不问")
    void companyCodeRemovesAmbiguity() {
        scope(doc(7, "300059", "东方财富", 2025), doc(38, "600519", "贵州茅台", 2025));

        assertThat(detector.detect(ChatScope.corpus(), "300059 2025年净利润是多少").ambiguous()).isFalse();
    }

    @Test
    @DisplayName("同一家公司两份年报、问题没点年份 → 要问（year_not_specified）")
    void twoYearsWithoutYearAsk() {
        scope(doc(7, "300059", "东方财富", 2025), doc(38, "300059", "东方财富", 2024));

        AmbiguityDetector.Decision decision = detector.detect(ChatScope.corpus(), "东方财富净利润是多少");

        assertThat(decision.ambiguous()).isTrue();
        assertThat(decision.reasons()).containsExactly("year_not_specified");
    }

    @Test
    @DisplayName("问题里写了年份 → 不问")
    void yearInQuestionRemovesAmbiguity() {
        scope(doc(7, "300059", "东方财富", 2025), doc(38, "300059", "东方财富", 2024));

        assertThat(detector.detect(ChatScope.corpus(), "东方财富2025年净利润是多少").ambiguous()).isFalse();
    }

    @Test
    @DisplayName("两家公司两份年报、只点了公司 → 仍缺年份，只报 year_not_specified")
    void companyMentionStillLeavesYearOpen() {
        scope(doc(7, "300059", "东方财富", 2025), doc(38, "600519", "贵州茅台", 2024));

        AmbiguityDetector.Decision decision = detector.detect(ChatScope.corpus(), "东方财富净利润是多少");

        assertThat(decision.reasons()).containsExactly("year_not_specified");
    }

    @Test
    @DisplayName("作用域为空：不是歧义，是拒答（异常往上抛）")
    void emptyScopePropagates() {
        when(scopeResolver.resolve(any())).thenThrow(new ScopeEmptyException("没有可读文档", List.of("scope_empty")));

        assertThatThrownBy(() -> detector.detect(ChatScope.corpus(), "净利润"))
                .isInstanceOf(ScopeEmptyException.class);
    }
}
