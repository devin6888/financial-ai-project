package com.finagent.agent;

import com.finagent.answer.AnswerResult;
import com.finagent.chat.MemoryTrace;
import com.finagent.memory.MemoryContext;
import com.finagent.retrieval.RetrievalTrace;
import com.finagent.scope.ResolvedScope;
import com.finagent.understanding.QueryUnderstanding;
import com.finagent.understanding.TurnContext;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * 把「节点里生成的 token」送回「正在等待的 HTTP 响应」。
 *
 * <p>为什么不用状态：{@code Consumer} 不能序列化，塞进 {@code AnalysisState} 会让检查点直接炸；
 * 而且回调的生命周期是「一次请求」，比一次 run 还短。
 *
 * <p>为什么 key 是 runId 而不是 threadId：两者在 Task 5 之后是同一个 UUID，
 * 但这里只认业务 id，避免以后换 checkpoint 实现时被绑死。
 *
 * <p><b>2026-10-31 扩容</b>：这里开始承担「图外装配、图内要用」的几样东西 ——
 * 会话记忆、指代消解上下文、本轮 QU 与解析后的作用域。它们都<b>不能</b>进状态（未实现
 * {@code Serializable}，进了检查点就炸），但都由 HTTP 线程装配、由节点消费，bridge 是唯一
 * 同时看得见两边的位置。共同点是：生命周期等于一次请求，{@link #clear} 一并清掉。
 */
@Component
public class AnswerStreamBridge {

    private final Map<String, Consumer<String>> sinks = new ConcurrentHashMap<>();

    /**
     * 节点里算出来的完整答案（含 facts / citations / confidence / note / 丢弃数）。
     *
     * <p>为什么不塞进 {@link AnalysisState}：状态要用 JDK 序列化写进检查点，
     * 而这些 record 没有实现 Serializable；硬塞会让「停下等审批」那一步直接炸。
     * 走这里与 delta 同理：生命周期就是一次请求（ST14 P2）。
     */
    private final Map<String, AnswerResult> results = new ConcurrentHashMap<>();

    /** 检索轨迹（第一个子问题的）；图有多次检索，trace 里只放一次代表性的。 */
    private final Map<String, RetrievalTrace> traces = new ConcurrentHashMap<>();

    /**
     * 图开跑之前装配好的会话记忆（短期窗口 + 摘要 + 结构化上下文）。
     *
     * <p>为什么由 HTTP 线程装好再递进来：节点跑在别的线程上，{@code TenantContext} 不跟过去，
     * 装配要查库就得自己重建身份；而「一轮用哪份记忆」本来就该在开跑前定死（C17 §17.1）。
     * understand 节点用它在 QU 之后做长期记忆召回，并把最终的统计写回 {@link #memoryTraces}。
     */
    private final Map<String, MemoryContext> memories = new ConcurrentHashMap<>();

    /**
     * 记忆段统计（最终版：含长期记忆召回结果）。
     *
     * <p>为什么单独存一份而不是从 {@link #memories} 现算：长期记忆是 QU 之后才召回的，
     * 统计里的 longTermItems / gateHit 只有 understand 节点知道；阶段 14 把记忆搬进图外之后
     * 这段统计一直报 0，e2e 的 gateHit 断言因此变红（2026-10-31 抓到）。
     */
    private final Map<String, MemoryTrace> memoryTraces = new ConcurrentHashMap<>();

    /**
     * 指代消解的输入（会话已确认的公司/年份/指标 + 最近几轮原文）。
     *
     * <p>为什么不是节点自己查：它要用「最近几轮」—— 那是装配记忆时顺手读出来的，
     * 节点再查一次就是同一份数据读两遍，还可能与 Prompt 里的那份不一致。
     */
    private final Map<String, TurnContext> turnContexts = new ConcurrentHashMap<>();

    /** 本轮的查询理解结果（understand 节点写入；收尾时用它更新会话结构化上下文）。 */
    private final Map<String, QueryUnderstanding> querys = new ConcurrentHashMap<>();

    /** 本轮解析后的作用域（understand 节点写入；收尾时用它决定「哪些文档算会话已确认」）。 */
    private final Map<String, ResolvedScope> scopes = new ConcurrentHashMap<>();

    /** 绑定一次请求的出口；重复绑定以最后一次为准（同一条 run 不会有两个消费者）。 */
    public void bind(String runId, Consumer<String> sink) {
        if (runId == null || runId.isBlank() || sink == null) {
            return;
        }
        sinks.put(runId, sink);
    }

    /** 节点产出增量时调用；没人监听就静默丢弃（非流式调用就是这种情形）。 */
    public void delta(String runId, String text) {
        if (runId == null || text == null || text.isEmpty()) {
            return;
        }
        Consumer<String> sink = sinks.get(runId);
        if (sink != null) {
            sink.accept(text);
        }
    }

    public boolean isBound(String runId) {
        return runId != null && sinks.containsKey(runId);
    }

    /** answer 节点算完后登记完整结果；同一 run 只保留最后一次（cite_check 回边会重算答案）。 */
    public void result(String runId, AnswerResult result) {
        if (runId != null && result != null) {
            results.put(runId, result);
        }
    }

    public AnswerResult result(String runId) {
        return runId == null ? null : results.get(runId);
    }

    /** retrieve 节点登记检索轨迹；只有第一次生效（多子问题时不覆盖）。 */
    public void retrievalTrace(String runId, RetrievalTrace trace) {
        if (runId != null && trace != null) {
            traces.putIfAbsent(runId, trace);
        }
    }

    public RetrievalTrace retrievalTrace(String runId) {
        return runId == null ? null : traces.get(runId);
    }

    // ------------------------------------------------------------ 记忆与上下文

    /** 登记本轮装配好的记忆（HTTP 线程在开跑前调用）。 */
    public void memory(String runId, MemoryContext memory) {
        if (runId != null && memory != null) {
            memories.put(runId, memory);
            memoryTraces.put(runId, MemoryTrace.from(memory));
        }
    }

    /** 没登记过时返回 {@code null}（审批后 resume 的 run 拿不到装配结果）。 */
    public MemoryContext memory(String runId) {
        return runId == null ? null : memories.get(runId);
    }

    /** understand 节点召回长期记忆后覆盖统计（最终版）。 */
    public void memoryTrace(String runId, MemoryTrace trace) {
        if (runId != null && trace != null) {
            memoryTraces.put(runId, trace);
        }
    }

    public MemoryTrace memoryTrace(String runId) {
        if (runId == null) {
            return MemoryTrace.EMPTY;
        }
        return memoryTraces.getOrDefault(runId, MemoryTrace.EMPTY);
    }

    /** 登记本轮指代消解上下文（HTTP 线程在开跑前调用）。 */
    public void turnContext(String runId, TurnContext context) {
        if (runId != null && context != null) {
            turnContexts.put(runId, context);
        }
    }

    /** 没有会话时返回 {@link TurnContext#EMPTY}，RouterService 据此走单轮路径。 */
    public TurnContext turnContext(String runId) {
        if (runId == null) {
            return TurnContext.EMPTY;
        }
        return turnContexts.getOrDefault(runId, TurnContext.EMPTY);
    }

    public void query(String runId, QueryUnderstanding qu) {
        if (runId != null && qu != null) {
            querys.put(runId, qu);
        }
    }

    public QueryUnderstanding query(String runId) {
        return runId == null ? null : querys.get(runId);
    }

    public void scope(String runId, ResolvedScope scope) {
        if (runId != null && scope != null) {
            scopes.put(runId, scope);
        }
    }

    public ResolvedScope scope(String runId) {
        return runId == null ? null : scopes.get(runId);
    }

    /** 请求结束（无论成功失败）必须解绑，否则表会随请求数增长。 */
    public void clear(String runId) {
        if (runId != null) {
            sinks.remove(runId);
            results.remove(runId);
            traces.remove(runId);
            memories.remove(runId);
            memoryTraces.remove(runId);
            turnContexts.remove(runId);
            querys.remove(runId);
            scopes.remove(runId);
        }
    }
}
