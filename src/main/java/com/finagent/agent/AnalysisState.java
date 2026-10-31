package com.finagent.agent;

import org.bsc.langgraph4j.state.AgentState;
import org.bsc.langgraph4j.state.Channel;
import org.bsc.langgraph4j.state.Channels;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Agent 图的唯一状态（契约 C17 §2.1）。
 *
 * <p>三条硬规则：
 * <ol>
 *   <li><b>只放 JDK 可序列化类型</b>（String / Integer / Boolean / List / Map）—— 默认序列化器是
 *       {@code ObjectStreamStateSerializer}，放 record 或 {@code float[]} 会在写检查点时炸；</li>
 *   <li><b>节点只写自己负责的键</b>（见每个 getter 的注释与 C17 表），别顺手改别人的字段；</li>
 *   <li><b>{@code question} 永不被改写</b>：改写只写 {@code rewrittenQuery}，这样「用户到底问了什么」永远可追溯。</li>
 * </ol>
 *
 * <p>通道语义（{@code Channels.base} vs {@code Channels.appender}）决定「节点返回的值怎么合并」：
 * 覆盖语义用 base，追加语义用 appender，计数用带 reducer 的 base。
 */
public class AnalysisState extends AgentState {

    public static final String RUN_ID = "runId";
    public static final String QUESTION = "question";
    public static final String REWRITTEN_QUERY = "rewrittenQuery";
    public static final String QUERY_TYPE = "queryType";
    public static final String COMPANY_CODES = "companyCodes";
    /** 问题原文里的公司名字面（如「东方财富」）。拆子问题时用来自包含化，见 ST15 §3.1。 */
    public static final String COMPANY_MENTIONS = "companyMentions";
    public static final String YEAR_FROM = "yearFrom";
    public static final String YEAR_TO = "yearTo";
    public static final String PLAN = "plan";
    public static final String SUB_QUESTIONS = "subQuestions";
    public static final String HITS = "hits";
    public static final String EVIDENCE_COUNT = "evidenceCount";
    public static final String INSUFFICIENT = "insufficient";
    public static final String RETRY_COUNT = "retryCount";
    public static final String ANSWER = "answer";
    public static final String CITATION_ISSUES = "citationIssues";
    public static final String CITE_ATTEMPTS = "citeAttempts";
    public static final String DECISION = "decision";
    public static final String DECISION_NOTE = "decisionNote";
    public static final String STATUS = "status";
    public static final String SCOPE_KIND = "scopeKind";
    public static final String SCOPE_DOCUMENT_IDS = "scopeDocumentIds";
    public static final String SCOPE_COMPANY_CODES = "scopeCompanyCodes";
    public static final String SCOPE_YEARS = "scopeYears";
    public static final String SCOPE_DOC_TYPES = "scopeDocTypes";
    public static final String CONVERSATION_ID = "conversationId";
    public static final String MEMORY_TEXT = "memoryText";
    public static final String REFUSE_REASON = "refuseReason";
    public static final String METRICS = "metrics";
    /**
     * 答案形态（{@code ATTRIBUTION} / {@code BREAKDOWN} / {@code COMPARISON} 的名字），plan 节点写。
     *
     * <p>为什么进状态而不是让 answer 节点自己算：verify 的「要素覆盖」与 rewrite 的「补驱动关键词」
     * 都要用同一份判定结果，判定两次就有两个口径（ST16 Task 1）。
     */
    public static final String ANSWER_SHAPES = "answerShapes";
    /**
     * 事实清单（{@code List<Map<String,Object>>}），compute 节点写、answer 节点读。
     *
     * <p>为什么用 Map 而不是 record：默认序列化器只认 JDK 类型（本类头部硬规则 1），
     * 而且这一份要原样拼进提示词、原样回给前端 —— Map 正好是两边都认的形状。
     * 每行键：no / metric / period / value / unit / scope / evidence / origin / formula / display / note。
     */
    public static final String FACTS = "facts";
    /** 本轮实际跑过的检索车道标识（full / sub:0 / year:2024 / attr），只用于排障。 */
    public static final String LANES = "lanes";
    public static final String WARNINGS = "warnings";
    /**
     * 公司或年份是从上文补出来的，不是这一轮说的（{@code QueryUnderstanding.inferredFromContext}）。
     *
     * <p>为什么要在响应里留痕：用户问「那 2024 年呢」，答案用的是上文的公司 ——
     * 猜对了要能解释、猜错了要能定位。这个码只由 understand 节点写，前端不解析。
     */
    public static final String WARN_INFERRED_FROM_CONTEXT = "query_inferred_from_context";
    public static final String CITATION_COUNT = "citationCount";

    /**
     * 身份四键：图的节点跑在 LangGraph4j 自己的工作线程上，而 {@code TenantContext} 是 ThreadLocal ——
     * 不随状态带过去，节点里第一次查库就会抛 {@code 缺少认证上下文}（2026-10-29 真跑时抓到）。
     * TenantContext 只用于「当前线程是谁」，所以这里存的是快照，节点进入前重建、退出后清空。
     */
    public static final String TENANT_ID = "tenantId";
    public static final String USER_ID = "userId";
    public static final String USERNAME = "username";
    public static final String ROLES = "roles";

    /** 年份通道的哨兵值：通道默认值不能为 null，0 表示「没指定年份」。 */
    public static final int UNKNOWN_YEAR = 0;

    /** 状态 schema：键 → 通道。顺序无关，但键名必须与上面的常量一致。 */
    public static final Map<String, Channel<?>> SCHEMA = Map.ofEntries(
            Map.entry(RUN_ID, Channels.base(() -> "")),
            Map.entry(QUESTION, Channels.base(() -> "")),
            Map.entry(REWRITTEN_QUERY, Channels.base(() -> "")),
            Map.entry(QUERY_TYPE, Channels.base(() -> "")),
            Map.entry(COMPANY_CODES, Channels.<String>appender(ArrayList::new)),
            // 通道默认值不能是 null（库初始化状态时 Collectors.toMap 会 NPE），用 0 表示「未指定」，
            // getter 里再翻成 null —— 这样 C17 表里的类型仍是 Integer
            Map.entry(YEAR_FROM, Channels.base(() -> UNKNOWN_YEAR)),
            Map.entry(YEAR_TO, Channels.base(() -> UNKNOWN_YEAR)),
            Map.entry(PLAN, Channels.<String>appender(ArrayList::new)),
            Map.entry(SUB_QUESTIONS, Channels.<String>appender(ArrayList::new)),
            Map.entry(HITS, Channels.<Map<String, Object>>appender(ArrayList::new)),
            Map.entry(EVIDENCE_COUNT, Channels.base(() -> 0)),
            Map.entry(INSUFFICIENT, Channels.base(() -> Boolean.FALSE)),
            Map.entry(RETRY_COUNT, Channels.base((Integer oldV, Integer newV) -> oldV + newV, () -> 0)),
            Map.entry(ANSWER, Channels.base(() -> "")),
            // 覆盖语义（不是追加）：cite_check 重写后「问题已清空」必须能覆盖掉上一轮的问题
            Map.entry(CITATION_ISSUES, Channels.<java.util.List<String>>base(() -> java.util.List.of())),
            Map.entry(CITE_ATTEMPTS, Channels.base((Integer oldV, Integer newV) -> oldV + newV, () -> 0)),
            Map.entry(DECISION, Channels.base(() -> "")),
            Map.entry(DECISION_NOTE, Channels.base(() -> "")),
            Map.entry(STATUS, Channels.base(() -> "RUNNING")),
            Map.entry(SCOPE_KIND, Channels.base(() -> "CORPUS")),
            Map.entry(SCOPE_DOCUMENT_IDS, Channels.<List<Long>>base(() -> List.of())),
            Map.entry(SCOPE_COMPANY_CODES, Channels.<List<String>>base(() -> List.of())),
            Map.entry(SCOPE_YEARS, Channels.<List<Integer>>base(() -> List.of())),
            Map.entry(SCOPE_DOC_TYPES, Channels.<List<String>>base(() -> List.of())),
            Map.entry(CONVERSATION_ID, Channels.base(() -> 0L)),
            Map.entry(MEMORY_TEXT, Channels.base(() -> "")),
            Map.entry(REFUSE_REASON, Channels.base(() -> "")),
            Map.entry(METRICS, Channels.<List<String>>base(() -> List.of())),
            Map.entry(ANSWER_SHAPES, Channels.<List<String>>base(() -> List.of())),
            Map.entry(FACTS, Channels.<List<Map<String, Object>>>base(() -> List.of())),
            Map.entry(LANES, Channels.<String>appender(ArrayList::new)),
            Map.entry(WARNINGS, Channels.<String>appender(ArrayList::new)),
            Map.entry(CITATION_COUNT, Channels.base(() -> 0)),
            Map.entry(TENANT_ID, Channels.base(() -> 0L)),
            Map.entry(USER_ID, Channels.base(() -> 0L)),
            Map.entry(USERNAME, Channels.base(() -> "")),
            Map.entry(ROLES, Channels.<String>appender(ArrayList::new)));

    public AnalysisState(Map<String, Object> data) {
        super(data);
    }

    public long tenantId() {
        return this.value(TENANT_ID, 0L);
    }

    public long userId() {
        return this.value(USER_ID, 0L);
    }

    public String username() {
        return this.value(USERNAME, "");
    }

    public List<String> roles() {
        return this.value(ROLES, List.of());
    }

    public String runId() {
        return this.value(RUN_ID, "");
    }

    /** 用户原话，永不改写（硬规则 3）。 */
    public String question() {
        return this.value(QUESTION, "");
    }

    /** 检索用的查询：plan 或 rewrite 写；空表示直接用原问。 */
    public String rewrittenQuery() {
        return this.value(REWRITTEN_QUERY, "");
    }

    public String queryType() {
        return this.value(QUERY_TYPE, "");
    }

    public List<String> companyCodes() {
        return this.value(COMPANY_CODES, List.of());
    }

    /** 问题原文里的公司名字面；拆子问题补前缀用（ST15 §3.1）。 */
    public List<String> companyMentions() {
        return this.value(COMPANY_MENTIONS, List.of());
    }

    public Integer yearFrom() {
        int v = this.value(YEAR_FROM, UNKNOWN_YEAR);
        return v == UNKNOWN_YEAR ? null : v;
    }

    public Integer yearTo() {
        int v = this.value(YEAR_TO, UNKNOWN_YEAR);
        return v == UNKNOWN_YEAR ? null : v;
    }

    /** 人可读的步骤（每项一句话），前端画计划卡片用。 */
    public List<String> plan() {
        return this.value(PLAN, List.of());
    }

    public List<String> subQuestions() {
        return this.value(SUB_QUESTIONS, List.of());
    }

    /** 每条形如 {chunkId, documentId, page, sectionPath, type, score}。 */
    public List<Map<String, Object>> hits() {
        return this.value(HITS, List.of());
    }

    public int evidenceCount() {
        return this.value(EVIDENCE_COUNT, 0);
    }

    public boolean insufficient() {
        return this.value(INSUFFICIENT, Boolean.FALSE);
    }

    public int retryCount() {
        return this.value(RETRY_COUNT, 0);
    }

    public String answer() {
        return this.value(ANSWER, "");
    }

    public List<String> citationIssues() {
        return this.value(CITATION_ISSUES, List.of());
    }

    public int citeAttempts() {
        return this.value(CITE_ATTEMPTS, 0);
    }

    public String decision() {
        return this.value(DECISION, "");
    }

    public String decisionNote() {
        return this.value(DECISION_NOTE, "");
    }

    public String status() {
        return this.value(STATUS, AgentRun.STATUS_RUNNING);
    }

    /** CORPUS / DOCUMENTS / FILTER（与 ChatScope.kind 同名）。 */
    public String scopeKind() {
        return this.value(SCOPE_KIND, "CORPUS");
    }

    public List<Long> scopeDocumentIds() {
        return this.value(SCOPE_DOCUMENT_IDS, List.of());
    }

    public List<String> scopeCompanyCodes() {
        return this.value(SCOPE_COMPANY_CODES, List.of());
    }

    public List<Integer> scopeYears() {
        return this.value(SCOPE_YEARS, List.of());
    }

    public List<String> scopeDocTypes() {
        return this.value(SCOPE_DOC_TYPES, List.of());
    }

    /** 0 表示这一轮没有会话。 */
    public long conversationId() {
        return this.value(CONVERSATION_ID, 0L);
    }

    /** 已渲染好的记忆段（空串 = 没有）；answer 节点用它重建 MemoryContext。 */
    public String memoryText() {
        return this.value(MEMORY_TEXT, "");
    }

    /** 非空表示不该检索（例如公司不在作用域内），plan 直接判 REFUSE。 */
    public String refuseReason() {
        return this.value(REFUSE_REASON, "");
    }

    public List<String> metrics() {
        return this.value(METRICS, List.of());
    }

    /** 答案形态的名字列表；空表示「问题没有明确形态」（纯取数）。 */
    public List<String> answerShapes() {
        return this.value(ANSWER_SHAPES, List.of());
    }

    /** 事实清单，每行键见 {@link #FACTS}；空表示这次没有表格或没抽到数。 */
    public List<Map<String, Object>> facts() {
        return this.value(FACTS, List.of());
    }

    /** 本轮跑过的检索车道（顺序即执行顺序）。 */
    public List<String> lanes() {
        return this.value(LANES, List.of());
    }

    public List<String> warnings() {
        return this.value(WARNINGS, List.of());
    }

    public int citationCount() {
        return this.value(CITATION_COUNT, 0);
    }
}