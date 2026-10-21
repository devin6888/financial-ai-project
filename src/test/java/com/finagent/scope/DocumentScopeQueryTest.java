package com.finagent.scope;

import com.finagent.document.DocumentRepository;
import com.finagent.document.DocumentRepository.DocStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link DocumentRepository#findRetrievable} 对真实库的查询测试。
 *
 * <p>为什么不用 mock：这个方法的全部风险都在 SQL 上 —— 动态 IN 拼占位符、
 * {@code doc_year IS NULL} 的行、parse_status 过滤。mock 掉仓储等于把这些风险全测掉不测。
 *
 * <p>数据前提（2026-10-21 的库）：document 4 行 ——
 * 7（300059/2025/DONE/version 2）、38（300059/2024/DONE/version 1）、
 * 2（600519/2025/FAILED）、4（无年份/FAILED）。
 */
@SpringBootTest
@ActiveProfiles("test")
class DocumentScopeQueryTest {

    @Autowired
    private DocumentRepository repository;

    @Test
    @DisplayName("不加条件只返回解析完成的文档，按年份倒序")
    void allReturnsOnlyDoneDocuments() {
        List<DocMeta> docs = repository.findRetrievable(null, null, null, null, null);

        assertThat(docs).extracting(DocMeta::id).containsExactly(7L, 38L);
        assertThat(docs).allSatisfy(d -> {
            assertThat(d.chunkVersion()).isPositive();
            assertThat(d.periodStart()).isNotNull();
            assertThat(d.periodEnd()).isNotNull();
        });
        // LEFT JOIN 出来公司信息要能取到，否则 B 模式按行业/代码筛就失效
        assertThat(docs).allSatisfy(d -> assertThat(d.companyCode()).isEqualTo("300059"));
    }

    @Test
    @DisplayName("按 id 限制只返回指定文档（IN 占位符拼参正确）")
    void idsRestrictResult() {
        assertThat(repository.findRetrievable(List.of(38L), null, null, null, null))
                .extracting(DocMeta::id).containsExactly(38L);
        assertThat(repository.findRetrievable(List.of(38L, 7L), null, null, null, null))
                .extracting(DocMeta::id).containsExactly(7L, 38L);
        assertThat(repository.findRetrievable(List.of(2L), null, null, null, null)).isEmpty();
    }

    @Test
    @DisplayName("公司与年份组合筛选，且年份不存在时返回空而不是全库")
    void companyAndYearFilter() {
        assertThat(repository.findRetrievable(null, List.of("300059"), List.of(2024), null, null))
                .extracting(DocMeta::id).containsExactly(38L);
        assertThat(repository.findRetrievable(null, List.of("300059"), List.of(2023), null, null)).isEmpty();
        assertThat(repository.findRetrievable(null, List.of("999999"), null, null, null)).isEmpty();
    }

    @Test
    @DisplayName("报告类型筛选：ANNUAL 有数据，Q3 在库里没有但查询不报错")
    void docTypeFilter() {
        assertThat(repository.findRetrievable(null, null, null, List.of("ANNUAL"), null))
                .extracting(DocMeta::id).containsExactly(7L, 38L);
        assertThat(repository.findRetrievable(null, null, null, List.of("Q3"), null)).isEmpty();
    }

    @Test
    @DisplayName("解析失败的文档能被查出来，用于解释「为什么它不在作用域里」")
    void statusesRevealWhyExcluded() {
        Map<Long, DocStatus> status = repository.findStatusByIds(List.of(2L, 7L)).stream()
                .collect(Collectors.toMap(DocStatus::id, Function.identity()));

        assertThat(status).containsOnlyKeys(2L, 7L);
        assertThat(status.get(2L).retrievable()).isFalse();
        assertThat(status.get(2L).parseStatus()).isEqualTo("FAILED");
        assertThat(status.get(7L).retrievable()).isTrue();
    }

    @Test
    @DisplayName("空 id 列表返回空，不拼出 IN () 这种语法错误")
    void emptyIdsAreSafe() {
        assertThat(repository.findStatusByIds(List.of())).isEmpty();
        assertThat(repository.findStatusByIds(null)).isEmpty();
    }
}
