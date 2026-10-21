package com.finagent.document;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 报告期回填（V6 的 period_start / period_end）的集成测试（真实 PostgreSQL）。
 *
 * <p>为什么值得单独测：报告期的唯一用途是阶段 5 判定「这条证据是不是本期」。
 * 算错的后果不是报错，而是「引用到了年报里的上期对比列」—— 数字对、出处错，
 * 而且从答案表面完全看不出来。
 */
@SpringBootTest
@ActiveProfiles("test")
class DocumentPeriodTest {

    @Autowired
    private DocumentRepository documents;

    @Autowired
    private JdbcTemplate jdbc;

    private final List<Long> created = new ArrayList<>();

    @AfterEach
    void cleanup() {
        created.forEach(id -> jdbc.update("DELETE FROM document WHERE id = ?", id));
        created.clear();
    }

    @Test
    void annualCoversWholeYear() {
        long id = insert("ANNUAL", 2091);
        assertThat(documents.backfillPeriod(id)).isEqualTo(1);
        assertThat(periodStart(id)).isEqualTo(LocalDate.of(2091, 1, 1));
        assertThat(periodEnd(id)).isEqualTo(LocalDate.of(2091, 12, 31));
    }

    @Test
    void q1EndsAtMarch31() {
        long id = insert("Q1", 2092);
        assertThat(documents.backfillPeriod(id)).isEqualTo(1);
        assertThat(periodStart(id)).isEqualTo(LocalDate.of(2092, 1, 1));
        assertThat(periodEnd(id)).isEqualTo(LocalDate.of(2092, 3, 31));
    }

    @Test
    void semiAnnualEndsAtJune30() {
        long id = insert("SEMI_ANNUAL", 2093);
        assertThat(documents.backfillPeriod(id)).isEqualTo(1);
        assertThat(periodStart(id)).isEqualTo(LocalDate.of(2093, 1, 1));
        assertThat(periodEnd(id)).isEqualTo(LocalDate.of(2093, 6, 30));
    }

    @Test
    void q3StartsInJuly() {
        long id = insert("Q3", 2094);
        assertThat(documents.backfillPeriod(id)).isEqualTo(1);
        assertThat(periodStart(id)).isEqualTo(LocalDate.of(2094, 7, 1));
        assertThat(periodEnd(id)).isEqualTo(LocalDate.of(2094, 9, 30));
    }

    @Test
    void missingYearLeavesPeriodEmpty() {
        long id = insert("ANNUAL", null);
        assertThat(documents.backfillPeriod(id)).isZero();
        assertThat(periodStart(id)).isNull();
    }

    @Test
    void secondCallIsNoOp() {
        long id = insert("ANNUAL", 2095);
        assertThat(documents.backfillPeriod(id)).isEqualTo(1);
        // 已经有了就不再覆盖：避免「重新解析后期间被改错」这种无声修改
        assertThat(documents.backfillPeriod(id)).isZero();
    }

    private long insert(String docType, Integer year) {
        String hash = "period-test-" + UUID.randomUUID();
        Long id = jdbc.queryForObject(
                "INSERT INTO document (company_id, doc_type, doc_year, title, object_key, "
                        + "content_hash, parse_status, version) "
                        + "VALUES (7, ?, ?, 'period-test', 'test/key', ?, 'DONE', 1) "
                        + "RETURNING id",
                Long.class, docType, year, hash);
        created.add(id);
        return id;
    }

    private LocalDate periodStart(long id) {
        return jdbc.queryForObject("SELECT period_start FROM document WHERE id = ?",
                LocalDate.class, id);
    }

    private LocalDate periodEnd(long id) {
        return jdbc.queryForObject("SELECT period_end FROM document WHERE id = ?",
                LocalDate.class, id);
    }
}
