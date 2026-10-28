package com.finagent.task;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * task_message 仓储的集成测试（真实 PostgreSQL，Flyway 已应用 V7）。
 *
 * <p>重点验证消息级幂等的 DB 保障：event_id 唯一约束在重复插入时抛
 * {@link DuplicateKeyException}，而 {@link TaskMessageRepository#upsert} 走幂等路径不抛。
 */
@SpringBootTest
@ActiveProfiles("test")
class TaskMessageRepositoryTest {

    @Autowired
    TaskMessageRepository repo;

    @Autowired
    JdbcTemplate jdbc;

    @AfterEach
    void cleanup() {
        jdbc.update("DELETE FROM task_message");
    }

    @Test
    void insertsAndFindsByEventId() {
        String eid = UUID.randomUUID().toString();
        long id = repo.insert(eid, "document.parse", "7", 7L);
        assertThat(id).isPositive();

        TaskMessage m = repo.findByEventId(eid).orElseThrow();
        assertThat(m.status()).isEqualTo("RECEIVED");
        assertThat(m.topic()).isEqualTo("document.parse");
        assertThat(m.documentId()).isEqualTo(7L);
        assertThat(m.retryCount()).isZero();
    }

    @Test
    void duplicateEventIdThrows() {
        String eid = UUID.randomUUID().toString();
        repo.insert(eid, "document.parse", "7", 7L);
        assertThatThrownBy(() -> repo.insert(eid, "document.reindex", "7", 7L))
                .isInstanceOf(DuplicateKeyException.class);
    }

    @Test
    void upsertIsIdempotent() {
        String eid = UUID.randomUUID().toString();
        long first = repo.upsert(eid, "document.parse", "7", 7L);
        long second = repo.upsert(eid, "document.parse", "7", 7L);
        assertThat(second).isEqualTo(first);

        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM task_message WHERE event_id = ?", Integer.class, eid);
        assertThat(count).isEqualTo(1);
    }

    @Test
    void markStatusAndIncrementRetryUpdateRow() {
        String eid = UUID.randomUUID().toString();
        long id = repo.upsert(eid, "document.parse", "7", 7L);

        repo.markStatus(id, "PROCESSING");
        repo.incrementRetry(id, "connection refused");

        TaskMessage m = repo.findByEventId(eid).orElseThrow();
        assertThat(m.status()).isEqualTo("PROCESSING");
        assertThat(m.retryCount()).isEqualTo(1);
        assertThat(m.lastError()).isEqualTo("connection refused");
    }
}
