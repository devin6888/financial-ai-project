package com.finagent.scope;

import com.finagent.document.DocumentRepository;
import com.finagent.document.DocumentRepository.DocStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
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
 * <p>数据前提：库里有两份可检索的年报 —— 7（300059/2025/DONE）、38（300059/2024/DONE）。
 * 「解析失败」的那份**由本测试自己造、自己删**：2026-10-28 清理演示数据时删掉了原有的
 * FAILED 文档（id 2/4），依赖固定 id 的测试会随数据漂移而红 —— 测试要自带夹具，不借别人的行。
 */
@SpringBootTest
@ActiveProfiles("test")
class DocumentScopeQueryTest {

    @Autowired
    private DocumentRepository repository;

    @Autowired
    private JdbcTemplate jdbc;

    /** 本类自造的「解析失败」文档 id；@AfterEach 删掉，不给库里留垃圾。 */
    private long failedId;

    @BeforeEach
    void createFailedDocument() {
        failedId = jdbc.queryForObject("""
                INSERT INTO document (tenant_id, doc_type, title, object_key, content_hash, parse_status)
                VALUES (1, 'ANNUAL', '作用域测试-解析失败', 'test/scope-failed.pdf', 'scope-query-failed', 'FAILED')
                RETURNING id
                """, Long.class);
    }

    @AfterEach
    void dropFailedDocument() {
        jdbc.update("DELETE FROM document WHERE id = ?", failedId);
    }

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
        assertThat(repository.findRetrievable(List.of(failedId), null, null, null, null)).isEmpty();
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
        Map<Long, DocStatus> status = repository.findStatusByIds(List.of(failedId, 7L)).stream()
                .collect(Collectors.toMap(DocStatus::id, Function.identity()));

        assertThat(status).containsOnlyKeys(failedId, 7L);
        assertThat(status.get(failedId).retrievable()).isFalse();
        assertThat(status.get(failedId).parseStatus()).isEqualTo("FAILED");
        assertThat(status.get(7L).retrievable()).isTrue();
    }

    @Test
    @DisplayName("空 id 列表返回空，不拼出 IN () 这种语法错误")
    void emptyIdsAreSafe() {
        assertThat(repository.findStatusByIds(List.of())).isEmpty();
        assertThat(repository.findStatusByIds(null)).isEmpty();
    }
}
