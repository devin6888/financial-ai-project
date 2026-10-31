package com.finagent.agent;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link AgentRunRepository} 的真库测试（阶段 11 Task 2）。
 *
 * <p>清理按主键删（docs/测试规范.md §7.3）：{@code DELETE FROM agent_run WHERE id = ?}，
 * {@code agent_run_step} 由外键级联带走，不按标题/前缀猜。
 */
@Tag("db")
@SpringBootTest
@ActiveProfiles("test")
class AgentRunRepositoryTest {

    private static final long TENANT = 1L;
    private static final long USER = 1L;

    @Autowired
    private AgentRunRepository runs;

    @Autowired
    private JdbcTemplate jdbc;

    private UUID runId;

    @BeforeEach
    void setUp() {
        runId = UUID.randomUUID();
        runs.create(runId, TENANT, USER, null, "东方财富2025年营业总收入是多少");
    }

    @AfterEach
    void tearDown() {
        jdbc.update("DELETE FROM agent_run WHERE id = ?", runId);
    }

    @Test
    @DisplayName("create + find：字段原样回来了，初始状态是 RUNNING")
    void createThenFind() {
        AgentRun run = runs.find(runId, TENANT).orElseThrow();

        assertThat(run.question()).isEqualTo("东方财富2025年营业总收入是多少");
        assertThat(run.status()).isEqualTo(AgentRun.STATUS_RUNNING);
        assertThat(run.finished()).isFalse();
        assertThat(run.waitingForApproval()).isFalse();
        assertThat(run.userId()).isEqualTo(USER);
        assertThat(run.createdAt()).isNotNull();
    }

    @Test
    @DisplayName("查询带租户：换个 tenant_id 查不到（RLS 之外的第二道防线）")
    void findIsTenantScoped() {
        assertThat(runs.find(runId, 2L)).isEmpty();
        assertThat(runs.find(runId, TENANT)).isPresent();
    }

    @Test
    @DisplayName("状态机：RUNNING → WAITING_APPROVAL → DONE，规划与决定都留痕")
    void stateTransitions() {
        runs.updateCurrentNode(runId, TENANT, "plan");
        runs.markWaitingApproval(runId, TENANT, "answer",
                "{\"subQuestions\":[\"营业总收入\",\"同比\"]}");

        AgentRun waiting = runs.find(runId, TENANT).orElseThrow();
        assertThat(waiting.status()).isEqualTo(AgentRun.STATUS_WAITING_APPROVAL);
        assertThat(waiting.waitingForApproval()).isTrue();
        assertThat(waiting.currentNode()).isEqualTo("answer");
        assertThat(waiting.planJson()).contains("subQuestions");

        runs.recordDecision(runId, TENANT, AgentRun.DECISION_APPROVE, "可以发");
        runs.markDone(runId, TENANT, "cite_check");

        AgentRun done = runs.find(runId, TENANT).orElseThrow();
        assertThat(done.status()).isEqualTo(AgentRun.STATUS_DONE);
        assertThat(done.finished()).isTrue();
        assertThat(done.decision()).isEqualTo(AgentRun.DECISION_APPROVE);
        assertThat(done.decisionNote()).isEqualTo("可以发");
    }

    @Test
    @DisplayName("失败与驳回是两个不同的终态，error 与 note 各归各位")
    void failedAndRejectedAreDistinct() {
        runs.markFailed(runId, TENANT, "IllegalStateException: 模型超时");
        AgentRun failed = runs.find(runId, TENANT).orElseThrow();
        assertThat(failed.status()).isEqualTo(AgentRun.STATUS_FAILED);
        assertThat(failed.error()).contains("模型超时");
        assertThat(failed.finished()).isTrue();

        UUID other = UUID.randomUUID();
        runs.create(other, TENANT, USER, null, "另一个问题");
        try {
            runs.markRejected(other, TENANT, "证据不足，先不发");
            AgentRun rejected = runs.find(other, TENANT).orElseThrow();
            assertThat(rejected.status()).isEqualTo(AgentRun.STATUS_REJECTED);
            assertThat(rejected.decision()).isEqualTo(AgentRun.DECISION_REJECT);
            assertThat(rejected.decisionNote()).isEqualTo("证据不足，先不发");
        } finally {
            jdbc.update("DELETE FROM agent_run WHERE id = ?", other);
        }
    }

    @Test
    @DisplayName("步骤流水：seq 递增、按 seq 排序返回、duration 可空")
    void stepsAreAppendedAndOrdered() {
        runs.appendStep(runId, TENANT, runs.nextSeq(runId), "understand",
                AgentRunStep.STATUS_OK, "问题已结构化", 120);
        runs.appendStep(runId, TENANT, runs.nextSeq(runId), "retrieve",
                AgentRunStep.STATUS_OK, "召回 16 条", 480);
        runs.appendStep(runId, TENANT, runs.nextSeq(runId), "answer",
                AgentRunStep.STATUS_SKIPPED, "未走到", null);

        List<AgentRunStep> steps = runs.steps(runId, TENANT);

        assertThat(steps).hasSize(3);
        assertThat(steps).extracting(AgentRunStep::seq).containsExactly(1, 2, 3);
        assertThat(steps).extracting(AgentRunStep::node)
                .containsExactly("understand", "retrieve", "answer");
        assertThat(steps.get(1).durationMs()).isEqualTo(480);
        assertThat(steps.get(2).durationMs()).isNull();
    }

    @Test
    @DisplayName("同一个 run 里 seq 不能重复（并发写时的兜底）")
    void duplicateSeqIsRejected() {
        runs.appendStep(runId, TENANT, 1, "understand", AgentRunStep.STATUS_OK, "第一次", 10);

        assertThatThrownBy(() -> runs.appendStep(runId, TENANT, 1, "plan",
                AgentRunStep.STATUS_OK, "重复步序", 10))
                .isInstanceOf(DuplicateKeyException.class);
    }

    @Test
    @DisplayName("删除运行会级联删掉它的步骤")
    void deletingRunCascadesSteps() {
        runs.appendStep(runId, TENANT, 1, "understand", AgentRunStep.STATUS_OK, "x", 1);

        jdbc.update("DELETE FROM agent_run WHERE id = ?", runId);

        Integer left = jdbc.queryForObject(
                "SELECT count(*) FROM agent_run_step WHERE run_id = ?", Integer.class, runId);
        assertThat(left).isZero();
    }
}