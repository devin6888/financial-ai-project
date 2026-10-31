package com.finagent.agent;

import com.finagent.scope.ChatScope;
import com.finagent.security.CurrentUser;
import com.finagent.security.TenantContext;
import org.bsc.langgraph4j.checkpoint.BaseCheckpointSaver;
import org.bsc.langgraph4j.checkpoint.MemorySaver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * HITL 的真库测试：图用假节点（不调模型、不检索），但 agent_run / agent_run_step 是真表。
 *
 * <p>重点验证四件事：停下时状态与 plan_json 落库、三种决定各自的下场、重复 resume 幂等、超时不自动通过。
 */
@Tag("db")
@SpringBootTest
@ActiveProfiles("test")
class AgentHitlDbTest {

    private static final long TENANT = 1L;
    private static final long USER = 1L;
    private static final String COMPLEX = "复杂问题：营业总收入和净利润分别是多少";

    @Autowired
    private AgentRunRepository runs;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private BaseCheckpointSaver checkpointSaver;

    private final List<UUID> created = new ArrayList<>();

    @BeforeEach
    void setUp() {
        TenantContext.set(new CurrentUser(USER, TENANT, "analyst", "演示分析师", Set.of("ANALYST")));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
        for (UUID id : created) {
            jdbc.update("DELETE FROM agent_run WHERE id = ?", id);
            jdbc.update("DELETE FROM lg4jcheckpoint WHERE thread_id IN"
                    + " (SELECT thread_id FROM lg4jthread WHERE thread_name = ?)", id.toString());
            jdbc.update("DELETE FROM lg4jthread WHERE thread_name = ?", id.toString());
        }
        created.clear();
    }

    /** 一个服务实例贯穿 run → resume：检查点存在它的 MemorySaver 里（Task 6 换 PostgresSaver）。 */
    private AgentRunService service(boolean enabled) {
        AnalysisGraph graph = new AnalysisGraph(FakeAnalysisNodes.happy(), new MemorySaver(), 25);
        return new AgentRunService(graph, runs, new AgentProperties(enabled, 2, 4, 25, true, 30));
    }

    /** 换一个检查点实现（Task 6：真库检查点）。 */
    private AgentRunService serviceWith(BaseCheckpointSaver saver) {
        AnalysisGraph graph = new AnalysisGraph(FakeAnalysisNodes.happy(), saver, 25);
        return new AgentRunService(graph, runs, new AgentProperties(true, 2, 4, 25, true, 30));
    }

    private AgentRunService.Result start(AgentRunService service) {
        AgentRunService.Result result = service.run(COMPLEX, ChatScope.corpus(), null, "", true);
        created.add(result.runId());
        return result;
    }

    @Test
    @DisplayName("复杂问题首次运行停在 approve 前：WAITING_APPROVAL、current_node=approve、plan_json 有步骤")
    void stopsBeforeApproveAndRecordsPlan() {
        AgentRunService.Result result = start(service(true));

        assertThat(result.waitingForApproval()).isTrue();
        assertThat(result.currentNode()).isEqualTo(AnalysisGraph.NODE_APPROVE);
        assertThat(result.steps()).extracting(AgentRunStep::node)
                .containsExactly("understand", "plan", "retrieve", "verify", "approval");
        assertThat(result.steps().get(4).status()).isEqualTo(AgentRunStep.STATUS_SKIPPED);
        assertThat(result.answer()).isEmpty();

        AgentRun run = runs.find(result.runId(), TENANT).orElseThrow();
        assertThat(run.status()).isEqualTo(AgentRun.STATUS_WAITING_APPROVAL);
        assertThat(run.planJson()).contains("steps").contains("理解问题").contains("子问题");
    }

    @Test
    @DisplayName("开关关着也能 resume：聊天流默认配置下弹的确认卡点「通过」不能 500")
    void resumeWorksWhileSwitchIsOff() {
        AgentRunService service = service(false);
        AgentRunService.Result waiting = start(service);
        assertThat(waiting.waitingForApproval()).isTrue();

        AgentRunService.Result done = service.resume(waiting.runId(), "APPROVE", null);

        assertThat(done.status()).isEqualTo(AgentRun.STATUS_DONE);
        assertThat(done.answer()).startsWith("fake-answer:");
    }

    @Test
    @DisplayName("APPROVE：继续跑 answer + cite_check，终态 DONE，decision=APPROVE")
    void approveResumesAndFinishes() {
        AgentRunService service = service(true);
        AgentRunService.Result waiting = start(service);

        AgentRunService.Result done = service.resume(waiting.runId(), "APPROVE", null);

        assertThat(done.status()).isEqualTo(AgentRun.STATUS_DONE);
        assertThat(done.answer()).startsWith("fake-answer:").contains("[E1]");
        assertThat(done.steps()).extracting(AgentRunStep::node)
                .containsExactly("understand", "plan", "retrieve", "verify", "approval",
                        "approval", "compute", "answer", "cite_check");

        AgentRun run = runs.find(waiting.runId(), TENANT).orElseThrow();
        assertThat(run.decision()).isEqualTo(AgentRun.DECISION_APPROVE);
    }

    @Test
    @DisplayName("EDIT：修改意见进状态，最终答案里带上这句话")
    void editNoteReachesTheAnswer() {
        AgentRunService service = service(true);
        AgentRunService.Result waiting = start(service);

        AgentRunService.Result done = service.resume(waiting.runId(), "EDIT", "补充同比增速");

        assertThat(done.status()).isEqualTo(AgentRun.STATUS_DONE);
        assertThat(done.answer()).contains("补充：补充同比增速");
        assertThat(done.steps()).extracting(AgentRunStep::summary).anyMatch(s -> s.contains("人工修改意见"));
    }

    @Test
    @DisplayName("EDIT / REJECT 不给理由：400 语义的 IllegalArgumentException，状态仍是等待审批")
    void editAndRejectRequireANote() {
        AgentRunService service = service(true);
        AgentRunService.Result waiting = start(service);

        assertThatThrownBy(() -> service.resume(waiting.runId(), "EDIT", "  "))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("EDIT");
        assertThatThrownBy(() -> service.resume(waiting.runId(), "REJECT", ""))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("REJECT");
        assertThat(runs.find(waiting.runId(), TENANT).orElseThrow().status())
                .isEqualTo(AgentRun.STATUS_WAITING_APPROVAL);
    }

    @Test
    @DisplayName("REJECT：不跑 answer，终态 REJECTED，驳回理由进 decision_note")
    void rejectStopsWithoutGenerating() {
        AgentRunService service = service(true);
        AgentRunService.Result waiting = start(service);

        AgentRunService.Result rejected = service.resume(waiting.runId(), "REJECT", "证据不够，换个口径再问");

        assertThat(rejected.rejected()).isTrue();
        assertThat(rejected.steps()).extracting(AgentRunStep::node).doesNotContain("answer");
        assertThat(rejected.steps().get(rejected.steps().size() - 1).summary()).contains("人工驳回");

        AgentRun run = runs.find(waiting.runId(), TENANT).orElseThrow();
        assertThat(run.status()).isEqualTo(AgentRun.STATUS_REJECTED);
        assertThat(run.decisionNote()).isEqualTo("证据不够，换个口径再问");
    }

    @Test
    @DisplayName("重复 resume：第二次直接返回，不跑图、不追加步骤（幂等）")
    void resumeIsIdempotent() {
        AgentRunService service = service(true);
        AgentRunService.Result waiting = start(service);

        AgentRunService.Result first = service.resume(waiting.runId(), "APPROVE", null);
        int steps = first.steps().size();
        AgentRunService.Result second = service.resume(waiting.runId(), "APPROVE", null);

        assertThat(second.status()).isEqualTo(AgentRun.STATUS_DONE);
        assertThat(second.steps()).hasSize(steps);
        assertThat(second.answer()).isEqualTo(first.answer());
    }

    @Test
    @DisplayName("还没停下就 resume（RUNNING）：抛 IllegalStateException，不静默接受")
    void resumeBeforeWaitingIsRejected() {
        UUID runId = UUID.randomUUID();
        created.add(runId);
        runs.create(runId, TENANT, USER, null, COMPLEX);

        assertThatThrownBy(() -> service(true).resume(runId, "APPROVE", null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("尚未进入等待审批");
    }

    @Test
    @DisplayName("超时未批：标记 FAILED（error=approval_timeout）并抛异常，不自动通过")
    void staleApprovalFailsInsteadOfAutoApproving() {
        AgentRunService service = service(true);
        AgentRunService.Result waiting = start(service);
        jdbc.update("UPDATE agent_run SET updated_at = now() - interval '2 hours' WHERE id = ?",
                waiting.runId());

        assertThatThrownBy(() -> service.resume(waiting.runId(), "APPROVE", null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("审批已超时");

        AgentRun run = runs.find(waiting.runId(), TENANT).orElseThrow();
        assertThat(run.status()).isEqualTo(AgentRun.STATUS_FAILED);
        assertThat(run.error()).isEqualTo("approval_timeout");
    }

    @Test
    @DisplayName("换一个全新的图实例（等价于进程重启）仍能恢复：检查点真落在 LG4J 表里")
    void resumeWorksAfterGraphIsRebuilt() {
        AgentRunService before = serviceWith(checkpointSaver);
        AgentRunService.Result waiting = start(before);
        assertThat(waiting.waitingForApproval()).isTrue();

        Integer checkpoints = jdbc.queryForObject(
                "SELECT count(*) FROM lg4jcheckpoint c JOIN lg4jthread t ON t.thread_id = c.thread_id"
                        + " WHERE t.thread_name = ?", Integer.class, waiting.runId().toString());
        assertThat(checkpoints).isNotNull().isGreaterThan(0);

        AgentRunService after = serviceWith(checkpointSaver);   // 新的 AnalysisGraph，内存里什么都没有
        AgentRunService.Result done = after.resume(waiting.runId(), "APPROVE", null);

        assertThat(done.status()).isEqualTo(AgentRun.STATUS_DONE);
        assertThat(done.answer()).startsWith("fake-answer:");
        assertThat(done.steps()).extracting(AgentRunStep::node).contains("answer", "cite_check");
    }

    @Test
    @DisplayName("别人的 run：resume 抛 404 语义异常")
    void resumeForeignRunIsNotFound() {
        AgentRunService service = service(true);
        AgentRunService.Result waiting = start(service);

        TenantContext.set(new CurrentUser(2L, TENANT, "outsider", "演示无权限用户", Set.of("VIEWER")));
        assertThatThrownBy(() -> service.resume(waiting.runId(), "APPROVE", null))
                .isInstanceOf(AgentRunNotFoundException.class);
    }
}
