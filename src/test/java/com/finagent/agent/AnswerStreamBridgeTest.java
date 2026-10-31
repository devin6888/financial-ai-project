package com.finagent.agent;

import com.finagent.chat.MemoryTrace;
import com.finagent.memory.MemoryContext;
import com.finagent.scope.ResolvedScope;
import com.finagent.scope.ScopeKind;
import com.finagent.understanding.QueryUnderstanding;
import com.finagent.understanding.QueryType;
import com.finagent.understanding.TurnContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 桥的四条旁路（记忆 / 指代消解上下文 / QU / 作用域）与 {@code clear} 的清理。
 *
 * <p>为什么值得单独测：这四样东西是「HTTP 线程装配、图节点消费」，走的是同一个并发 Map，
 * 少清一个就会随请求数增长（内存泄漏），而清了不该清的会让 resume 后的收尾拿不到 QU。
 */
class AnswerStreamBridgeTest {

    @Test
    @DisplayName("记忆：登记后能读回统计；没登记返回 EMPTY")
    void memoryRoundTrip() {
        AnswerStreamBridge bridge = new AnswerStreamBridge();
        MemoryContext memory = new MemoryContext("背景", new MemoryContext.Stats(3, 1, 20, 0, false), List.of());

        bridge.memory("run-1", memory);

        assertThat(bridge.memoryTrace("run-1").shortTermMessages()).isEqualTo(3);
        assertThat(bridge.memoryTrace("run-1").summaryChars()).isEqualTo(20);
        assertThat(bridge.memoryTrace("run-2")).isEqualTo(MemoryTrace.EMPTY);
        assertThat(bridge.memory("run-2")).isNull();
    }

    @Test
    @DisplayName("记忆：understand 节点召回后用 memoryTrace 覆盖统计（gateHit / longTermItems）")
    void memoryTraceCanBeOverwritten() {
        AnswerStreamBridge bridge = new AnswerStreamBridge();
        bridge.memory("run-1", new MemoryContext("背景", MemoryContext.Stats.EMPTY, List.of()));

        bridge.memoryTrace("run-1", new MemoryTrace(0, 0, 0, 2, true));

        assertThat(bridge.memoryTrace("run-1").gateHit()).isTrue();
        assertThat(bridge.memoryTrace("run-1").longTermItems()).isEqualTo(2);
    }

    @Test
    @DisplayName("指代消解：没有会话时返回 EMPTY，有会话时原样读回")
    void turnContextDefaultsToEmpty() {
        AnswerStreamBridge bridge = new AnswerStreamBridge();
        assertThat(bridge.turnContext("run-1")).isEqualTo(TurnContext.EMPTY);

        TurnContext ctx = new TurnContext("300059", "东方财富", 2025, 2025, List.of(7L), "营业总收入", List.of());
        bridge.turnContext("run-1", ctx);

        assertThat(bridge.turnContext("run-1").companyLabel()).isEqualTo("东方财富（300059）");
    }

    @Test
    @DisplayName("QU 与作用域：收尾落库要用的两样都要能读回")
    void queryAndScopeRoundTrip() {
        AnswerStreamBridge bridge = new AnswerStreamBridge();
        QueryUnderstanding qu = new QueryUnderstanding(QueryType.EXACT_FACT, "q", List.of("300059"),
                new int[]{2025, 2025}, List.of("营业总收入"), false, false, null, false, "LLM");
        ResolvedScope scope = ResolvedScope.of(ScopeKind.CORPUS, List.of(), ResolvedScope.REASON_SESSION, List.of());

        bridge.query("run-1", qu);
        bridge.scope("run-1", scope);

        assertThat(bridge.query("run-1").metrics()).containsExactly("营业总收入");
        assertThat(bridge.scope("run-1").kind()).isEqualTo(ScopeKind.CORPUS);
    }

    @Test
    @DisplayName("clear：四条旁路一起清掉，避免按请求数增长")
    void clearRemovesEverything() {
        AnswerStreamBridge bridge = new AnswerStreamBridge();
        bridge.memory("run-1", new MemoryContext("背景", MemoryContext.Stats.EMPTY, List.of()));
        bridge.turnContext("run-1", new TurnContext("300059", null, null, null, List.of(), null, List.of()));
        bridge.query("run-1", new QueryUnderstanding(QueryType.EXACT_FACT, "q", List.of(),
                new int[]{2025, 2025}, List.of(), false, false, null, false, "LLM"));
        bridge.scope("run-1", ResolvedScope.of(ScopeKind.CORPUS, List.of(), ResolvedScope.REASON_SESSION, List.of()));

        bridge.clear("run-1");

        assertThat(bridge.memory("run-1")).isNull();
        assertThat(bridge.memoryTrace("run-1")).isEqualTo(MemoryTrace.EMPTY);
        assertThat(bridge.turnContext("run-1")).isEqualTo(TurnContext.EMPTY);
        assertThat(bridge.query("run-1")).isNull();
        assertThat(bridge.scope("run-1")).isNull();
    }
}
