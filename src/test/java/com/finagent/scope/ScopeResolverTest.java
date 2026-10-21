package com.finagent.scope;

import com.finagent.document.DocumentRepository;
import com.finagent.document.DocumentRepository.DocStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ScopeResolver 的规则测试。
 *
 * <p>不需要 Spring 容器：resolver 只是"查库 + 判规则"，仓储用 mock，
 * 这样每条规则都能单独构造出触发条件（比如"选择的文档全部未解析完成"在真库里很难摆出来）。
 */
class ScopeResolverTest {

    private DocumentRepository repository;
    private ScopeResolver resolver;

    @BeforeEach
    void setUp() {
        repository = mock(DocumentRepository.class);
        resolver = new ScopeResolver(repository, new AllowAllAuthorizer(), new ScopeProperties());
    }

    // ---------- C 模式 ----------

    @Test
    @DisplayName("null 作用域等价于全库")
    void nullScopeResolvesToCorpus() {
        when(repository.findRetrievable(isNull(), isNull(), isNull(), isNull(), isNull()))
                .thenReturn(List.of(doc(38, 2024), doc(7, 2025)));

        ResolvedScope scope = resolver.resolve(null);

        assertThat(scope.kind()).isEqualTo(ScopeKind.CORPUS);
        assertThat(scope.documentIds()).containsExactly(38L, 7L);
        assertThat(scope.effectiveReason()).isEqualTo(ResolvedScope.REASON_SESSION);
        assertThat(scope.isEmpty()).isFalse();
    }

    @Test
    @DisplayName("全库一份文档都没有时报错，而不是返回空作用域")
    void corpusWithNoDocumentsThrows() {
        when(repository.findRetrievable(any(), any(), any(), any(), any())).thenReturn(List.of());

        assertThatThrownBy(() -> resolver.resolveCorpus())
                .isInstanceOf(ScopeEmptyException.class)
                .hasMessageContaining("没有可检索的文档");
    }

    // ---------- A 模式 ----------

    @Test
    @DisplayName("A 模式保留用户给的顺序，而不是 SQL 的排序")
    void documentsKeepsRequestedOrder() {
        when(repository.findStatusByIds(any())).thenReturn(List.of(status(7, "DONE"), status(38, "DONE")));
        when(repository.findRetrievable(any(), any(), any(), any(), any()))
                .thenReturn(List.of(doc(38, 2024), doc(7, 2025)));

        ResolvedScope scope = resolver.resolve(ChatScope.documents(7L, 38L));

        assertThat(scope.kind()).isEqualTo(ScopeKind.DOCUMENTS);
        assertThat(scope.documentIds()).containsExactly(7L, 38L);
    }

    @Test
    @DisplayName("不存在的文档 id 给出警告后剔除")
    void documentsDropsUnknownIdWithWarning() {
        when(repository.findStatusByIds(any())).thenReturn(List.of(status(7, "DONE")));
        when(repository.findRetrievable(any(), any(), any(), any(), any())).thenReturn(List.of(doc(7, 2025)));

        ResolvedScope scope = resolver.resolve(ChatScope.documents(7L, 999L));

        assertThat(scope.documentIds()).containsExactly(7L);
        assertThat(scope.warnings()).contains("scope_unknown_document_999");
    }

    @Test
    @DisplayName("解析未完成的文档给出警告后剔除")
    void documentsDropsNotRetrievableWithWarning() {
        when(repository.findStatusByIds(any()))
                .thenReturn(List.of(status(7, "DONE"), status(2, "FAILED")));
        when(repository.findRetrievable(any(), any(), any(), any(), any())).thenReturn(List.of(doc(7, 2025)));

        ResolvedScope scope = resolver.resolve(ChatScope.documents(7L, 2L));

        assertThat(scope.documentIds()).containsExactly(7L);
        assertThat(scope.warnings()).contains("scope_not_retrievable_2_FAILED");
    }

    @Test
    @DisplayName("所选文档一份都不可用时报错（带诊断信息），不放宽成全库")
    void documentsAllUnavailableThrows() {
        when(repository.findStatusByIds(any())).thenReturn(List.of(status(2, "FAILED")));
        when(repository.findRetrievable(any(), any(), any(), any(), any())).thenReturn(List.of());

        assertThatThrownBy(() -> resolver.resolve(ChatScope.documents(2L)))
                .isInstanceOf(ScopeEmptyException.class)
                .satisfies(e -> assertThat(((ScopeEmptyException) e).warnings())
                        .contains("scope_not_retrievable_2_FAILED"));
        // 关键：没有回退去查全库
        verify(repository).findRetrievable(eq(List.of(2L)), isNull(), isNull(), isNull(), isNull());
    }

    @Test
    @DisplayName("超过软上限只警告不拦")
    void documentsOverSoftLimitWarns() {
        List<Long> ids = List.of(1L, 2L, 3L, 4L, 5L, 6L, 7L);
        when(repository.findStatusByIds(any())).thenReturn(ids.stream().map(id -> status(id, "DONE")).toList());
        when(repository.findRetrievable(any(), any(), any(), any(), any()))
                .thenReturn(ids.stream().map(id -> doc(id, 2025)).toList());

        ResolvedScope scope = resolver.resolve(ChatScope.documents(ids));

        assertThat(scope.size()).isEqualTo(7);
        assertThat(scope.warnings()).contains("scope_over_soft_limit_7_gt_5");
    }

    @Test
    @DisplayName("A 模式不给 id 直接报错")
    void documentsEmptyIdsThrows() {
        assertThatThrownBy(() -> resolver.resolve(ChatScope.documents(List.of())))
                .isInstanceOf(ScopeEmptyException.class)
                .hasMessageContaining("未选择任何文档");
    }

    @Test
    @DisplayName("权限过滤在查库之前，被拒的 id 不进检索")
    void authorizerFiltersBeforeLookup() {
        ScopeAuthorizer denyAll = ids -> List.of();
        resolver = new ScopeResolver(repository, denyAll, new ScopeProperties());

        assertThatThrownBy(() -> resolver.resolve(ChatScope.documents(7L)))
                .isInstanceOf(ScopeEmptyException.class);
        verify(repository).findStatusByIds(List.of());
    }

    // ---------- B 模式 ----------

    @Test
    @DisplayName("筛选条件全空 = 全库，但保留 FILTER 类型并警告")
    void filterEmptyMeansWholeCorpus() {
        // 空条件传的是空列表而不是 null：null 在 appendIn 里也是"跳过"，
        // 但空列表更能表达"四个条件都给了、只是都没填"
        when(repository.findRetrievable(isNull(), eq(List.of()), eq(List.of()), eq(List.of()), eq(List.of())))
                .thenReturn(List.of(doc(7, 2025)));

        ResolvedScope scope = resolver.resolveFilter(ScopeQuery.empty());

        assertThat(scope.kind()).isEqualTo(ScopeKind.FILTER);
        assertThat(scope.documentIds()).containsExactly(7L);
        assertThat(scope.warnings()).contains("scope_filter_empty_all_corpus");
    }

    @Test
    @DisplayName("筛选条件没有匹配时直接报错，不回退成全库")
    void filterNoMatchThrows() {
        when(repository.findRetrievable(any(), any(), any(), any(), any())).thenReturn(List.of());

        assertThatThrownBy(() -> resolver.resolveFilter(
                new ScopeQuery(List.of("300059"), List.of(2023), null, null)))
                .isInstanceOf(ScopeEmptyException.class)
                .hasMessageContaining("2023");
    }

    @Test
    @DisplayName("四个筛选条件原样传给仓储（顺序固定，避免拼错列）")
    void filterPassesCriteriaToRepository() {
        when(repository.findRetrievable(any(), any(), any(), any(), any())).thenReturn(List.of(doc(38, 2024)));

        resolver.resolve(ChatScope.filter(
                new ScopeQuery(List.of(" 300059 ", "300059"), List.of(2024, 2024),
                        List.of("ANNUAL"), List.of("证券"))));

        verify(repository).findRetrievable(isNull(), eq(List.of("300059")), eq(List.of(2024)),
                eq(List.of("ANNUAL")), eq(List.of("证券")));
    }

    // ---------- 扩展 ----------

    @Test
    @DisplayName("仅本轮的扩展带上 TURN_ONLY，并追加警告")
    void extendAddsNewDocumentsTurnOnly() {
        ResolvedScope current = ResolvedScope.of(ScopeKind.DOCUMENTS, List.of(doc(7, 2025)),
                ResolvedScope.REASON_SESSION, List.of());
        when(repository.findRetrievable(any(), any(), any(), any(), any())).thenReturn(List.of(doc(38, 2024)));

        ResolvedScope extended = resolver.extend(current, List.of(38L), true);

        assertThat(extended.documentIds()).containsExactly(7L, 38L);
        assertThat(extended.effectiveReason()).isEqualTo(ResolvedScope.REASON_TURN_ONLY);
        assertThat(extended.warnings()).contains("scope_extended_to_2");
    }

    @Test
    @DisplayName("扩展时已在范围内的文档不重复查库")
    void extendSkipsAlreadyPresent() {
        ResolvedScope current = ResolvedScope.of(ScopeKind.DOCUMENTS, List.of(doc(7, 2025)),
                ResolvedScope.REASON_SESSION, List.of());

        ResolvedScope extended = resolver.extend(current, List.of(7L), true);

        assertThat(extended).isSameAs(current);
    }

    @Test
    @DisplayName("扩展的文档不可检索时范围不变")
    void extendIgnoresUnretrievable() {
        ResolvedScope current = ResolvedScope.of(ScopeKind.DOCUMENTS, List.of(doc(7, 2025)),
                ResolvedScope.REASON_SESSION, List.of());
        when(repository.findRetrievable(any(), any(), any(), any(), any())).thenReturn(List.of());

        ResolvedScope extended = resolver.extend(current, List.of(2L), false);

        assertThat(extended.documentIds()).containsExactly(7L);
        assertThat(extended.effectiveReason()).isEqualTo(ResolvedScope.REASON_SESSION);
    }

    // ---------- 夹具 ----------

    private static DocMeta doc(long id, Integer year) {
        return new DocMeta(id, 1L, "300059", "东方财富", year, "ANNUAL",
                year == null ? null : LocalDate.of(year, 1, 1),
                year == null ? null : LocalDate.of(year, 12, 31), 1, "东方财富" + year + "年年度报告");
    }

    private static DocStatus status(long id, String parseStatus) {
        return new DocStatus(id, parseStatus, "DONE".equals(parseStatus) ? 1 : null, "doc" + id);
    }
}
