package com.finagent.agent;

import com.finagent.scope.ChatScope;
import com.finagent.security.CurrentUser;
import com.finagent.security.TenantContext;
import org.bsc.langgraph4j.checkpoint.MemorySaver;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 编排的真库测试：图用假节点（不调模型、不检索），但 {@code agent_run} / {@code agent_run_step}
 * 是真表、真事务。
 *
 * <p>验证的是 Task 4 的「能跑通并留痕」，节点行为已由 {@link RealAnalysisNodesTest} 单测覆盖。
 */
@Tag("db")
@SpringBootTest
@ActiveProfiles("test")
class AgentRunServiceDbTest {

    private static final long TENANT = 1L;
    private static final long USER = 1L;

    @Autowired
    private AgentRunRepository runs;

    @Autowired
    private JdbcTemplate jdbc;

    private UUID runId;

    @BeforeEach
    void setUp() {
        TenantContext.set(new CurrentUser(USER, TENANT, "analyst", "演示分析师", Set.of("ANALYST")));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
        if (runId != null) {
            jdbc.update("DELETE FROM agent_run WHERE id = ?", runId);
        }
    }

    private AgentRunService service(FakeAnalysisNodes nodes, boolean enabled) {
        AnalysisGraph graph = new AnalysisGraph(nodes, new MemorySaver(), 25);
        return new AgentRunService(graph, runs, new AgentProperties(enabled, 2, 4, 25, false, 30));
    }

    @Test
    @DisplayName("跑完一条问题：状态 DONE、答案是节点产出、步骤按节点顺序落库")
    void runPersistsStepsAndFinishes() {
        AgentRunService.Result result = service(FakeAnalysisNodes.happy(), true)
                .run("东方财富2025年营业总收入是多少", ChatScope.corpus(), null, "");
        runId = result.runId();

        assertThat(result.status()).isEqualTo(AgentRun.STATUS_DONE);
        assertThat(result.answer()).startsWith("fake-answer:");
        assertThat(result.steps()).extracting(AgentRunStep::node)
                .containsExactly("understand", "plan", "retrieve", "verify", "compute", "answer", "cite_check");
        assertThat(result.steps()).extracting(AgentRunStep::seq).containsExactly(1, 2, 3, 4, 5, 6, 7);

        AgentRun run = runs.find(runId, TENANT).orElseThrow();
        assertThat(run.status()).isEqualTo(AgentRun.STATUS_DONE);
        assertThat(run.currentNode()).isEqualTo("cite_check");
        assertThat(run.userId()).isEqualTo(USER);
    }

    @Test
    @DisplayName("证据始终不足：终态 REJECTED，拒答文案进 decision_note，步骤里有 respond_insufficient")
    void runRejectsWhenEvidenceNeverArrives() {
        AgentRunService.Result result = service(new FakeAnalysisNodes(false, false, 0), true)
                .run("东方财富2050年营业总收入是多少", ChatScope.corpus(), null, "");
        runId = result.runId();

        assertThat(result.rejected()).isTrue();
        assertThat(result.answer()).contains("证据不足");
        assertThat(result.steps()).extracting(AgentRunStep::node)
                .contains("rewrite", "respond_insufficient");

        AgentRun run = runs.find(runId, TENANT).orElseThrow();
        assertThat(run.status()).isEqualTo(AgentRun.STATUS_REJECTED);
        assertThat(run.decisionNote()).contains("证据不足");
    }

    @Test
    @DisplayName("开关关着也照跑：P4 起图是唯一执行引擎，enabled 不再是入口闸门")
    void disabledSwitchNoLongerBlocks() {
        AgentRunService.Result result = service(FakeAnalysisNodes.happy(), false)
                .run("东方财富2025年营业总收入是多少", ChatScope.corpus(), null, "");
        runId = result.runId();

        assertThat(result.status()).isEqualTo(AgentRun.STATUS_DONE);
        assertThat(runs.find(result.runId(), TENANT)).isPresent();
    }

    @Test
    @DisplayName("没有身份（CLI/测试漏设 TenantContext）：抛 UnauthorizedException，不落库")
    void withoutIdentityItRefuses() {
        TenantContext.clear();

        assertThatThrownBy(() -> service(FakeAnalysisNodes.happy(), true)
                .run("东方财富2025年营业总收入是多少", ChatScope.corpus(), null, ""))
                .isInstanceOf(com.finagent.security.UnauthorizedException.class);
    }

    @Test
    @DisplayName("别人的 run：requireOwned 抛 404 语义异常")
    void foreignRunIsNotFound() {
        AgentRunService.Result result = service(FakeAnalysisNodes.happy(), true)
                .run("东方财富2025年营业总收入是多少", ChatScope.corpus(), null, "");
        runId = result.runId();

        TenantContext.set(new CurrentUser(2L, TENANT, "outsider", "演示无权限用户", Set.of("VIEWER")));
        assertThatThrownBy(() -> service(FakeAnalysisNodes.happy(), true).requireOwned(runId))
                .isInstanceOf(AgentRunNotFoundException.class);
    }
}