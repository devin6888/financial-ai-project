package com.finagent.understanding;

import com.finagent.scope.DocMeta;
import com.finagent.scope.ResolvedScope;
import com.finagent.scope.ScopeKind;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 公司落地（ST9 §16）：模型只抄名字，代码由这里查库得到。
 *
 * <p>为什么要有这一层：模型把「东方财富」翻译成「300059」靠的是训练记忆，
 * e2e 抓到它翻成 600519（贵州茅台）→ 整轮被拒答。名字→代码必须是查表，不是记忆。
 */
class CompanyResolverTest {

    private static final DocMeta EAST = new DocMeta(38L, 2L, "300059", "东方财富", 2024, "ANNUAL",
            LocalDate.of(2024, 1, 1), LocalDate.of(2024, 12, 31), 1, "东方财富2024年年度报告");
    private static final DocMeta THS = new DocMeta(37L, 5L, "300033", "同花顺", 2024, "ANNUAL",
            LocalDate.of(2024, 1, 1), LocalDate.of(2024, 12, 31), 1, "同花顺2024年年度报告");

    private static ResolvedScope scope(DocMeta... docs) {
        return ResolvedScope.of(ScopeKind.DOCUMENTS, List.of(docs), ResolvedScope.REASON_SESSION, List.of());
    }

    @Test
    @DisplayName("用户字面写了代码 → 精确命中，不需要模糊匹配")
    void codeResolvesExactly() {
        CompanyResolver.Resolution r = CompanyResolver.resolve(List.of("300059"), List.of(), scope(EAST));

        assertThat(r.codes()).containsExactly("300059");
        assertThat(r.unresolved()).isEmpty();
        assertThat(r.matches().get(0).companyId()).isEqualTo(2L);
    }

    @Test
    @DisplayName("公司名逐字命中")
    void nameResolvesExactly() {
        CompanyResolver.Resolution r = CompanyResolver.resolve(List.of(), List.of("东方财富"), scope(EAST));

        assertThat(r.codes()).containsExactly("300059");
        assertThat(r.names()).containsExactly("东方财富");
    }

    @Test
    @DisplayName("全称/带后缀的别名 → 归一化后仍能命中")
    void aliasWithSuffixIsNormalized() {
        CompanyResolver.Resolution r = CompanyResolver.resolve(List.of(),
                List.of("东方财富信息股份有限公司"), scope(EAST));

        assertThat(r.codes()).containsExactly("300059");
    }

    @Test
    @DisplayName("提到两家都在范围内 → 两家都返回（对比题要用）")
    void twoMentionsResolveTwoCompanies() {
        CompanyResolver.Resolution r = CompanyResolver.resolve(List.of(),
                List.of("东方财富", "同花顺"), scope(EAST, THS));

        assertThat(r.codes()).containsExactly("300059", "300033");
        assertThat(r.blocked()).isFalse();
    }

    @Test
    @DisplayName("提到范围外的公司 → 拒答（blocked），并且不允许退化成无约束检索")
    void unknownCompanyBlocks() {
        CompanyResolver.Resolution r = CompanyResolver.resolve(List.of(), List.of("五粮液"), scope(EAST));

        assertThat(r.matches()).isEmpty();
        assertThat(r.unresolved()).containsExactly("五粮液");
        assertThat(r.blocked()).isTrue();
    }

    @Test
    @DisplayName("代词（该公司/行业）不算公司 → 不拒答")
    void pronounsAreIgnored() {
        CompanyResolver.Resolution r = CompanyResolver.resolve(List.of(),
                List.of("该公司", "行业"), scope(EAST));

        assertThat(r.unresolved()).isEmpty();
        assertThat(r.blocked()).isFalse();
    }

    @Test
    @DisplayName("代码与名字指同一家 → 去重成一个 match")
    void codeAndMentionDeduplicate() {
        CompanyResolver.Resolution r = CompanyResolver.resolve(List.of("300059"),
                List.of("东方财富"), scope(EAST));

        assertThat(r.matches()).hasSize(1);
        assertThat(r.codes()).containsExactly("300059");
    }

    @Test
    @DisplayName("什么都没提 → 不加约束，不拒答（合法的全库问题）")
    void nothingMentionedLeavesNoConstraint() {
        CompanyResolver.Resolution r = CompanyResolver.resolve(List.of(), List.of(), scope(EAST));

        assertThat(r.matches()).isEmpty();
        assertThat(r.blocked()).isFalse();
    }

    @Test
    @DisplayName("短泛称（长度 < 3 且无公司后缀词）→ 只留警告，不拒答")
    void shortGenericWordIsIgnored() {
        CompanyResolver.Resolution r = CompanyResolver.resolve(List.of(), List.of("报告"), scope(EAST));

        assertThat(r.blocked()).isFalse();
        assertThat(r.warnings()).contains("company_mention_ignored");
    }

    @Test
    @DisplayName("作用域为空 → 空结果，不抛异常")
    void emptyScopeReturnsEmpty() {
        CompanyResolver.Resolution r = CompanyResolver.resolve(List.of("300059"), List.of(), scope());

        assertThat(r.matches()).isEmpty();
        assertThat(r.unresolved()).isEmpty();
    }
}
