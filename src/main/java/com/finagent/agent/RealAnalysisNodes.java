package com.finagent.agent;

import com.finagent.answer.AnswerResult;
import com.finagent.answer.AnswerService;
import com.finagent.answer.PeriodLabeler;
import com.finagent.chat.MemoryTrace;
import com.finagent.chunk.ChunkRepository;
import com.finagent.chunk.ChunkRepository.ChunkView;
import com.finagent.citation.CitationValidator;
import com.finagent.context.ContextBuilder;
import com.finagent.context.ContextPackage;
import com.finagent.fact.ComputedFact;
import com.finagent.fact.FactCollector;
import com.finagent.fact.FactPack;
import com.finagent.fact.MetricCalculator;
import com.finagent.fact.ReportedFact;
import com.finagent.fact.UnitConverter;
import com.finagent.memory.LongTermMemory;
import com.finagent.memory.LongTermMemoryRecaller;
import com.finagent.memory.MemoryAssembler;
import com.finagent.memory.MemoryContext;
import com.finagent.memory.MemoryGate;
import com.finagent.retrieval.HybridRetriever;
import com.finagent.retrieval.RetrievalProgress;
import com.finagent.retrieval.RetrievalRequest;
import com.finagent.retrieval.RetrievedChunk;
import com.finagent.scope.ResolvedScope;
import com.finagent.scope.ScopeEmptyException;
import com.finagent.scope.ScopeResolver;
import com.finagent.understanding.AnswerShape;
import com.finagent.understanding.CompanyResolver;
import com.finagent.understanding.MetricDictionary;
import com.finagent.understanding.QueryType;
import com.finagent.understanding.QueryUnderstanding;
import com.finagent.understanding.RouterService;
import com.finagent.understanding.TurnContext;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 八个节点的真实现（阶段 11 Task 4）：全部构造器注入，节点方法里不 new 任何依赖。
 *
 * <p>三条设计约束（ST11 §13）：
 * <ul>
 *   <li>状态里只放 JDK 类型：作用域存「意图」、每次检索前重新 {@code resolve}（顺便重过 ACL）；</li>
 *   <li>流式回调不放状态，走 {@link AnswerStreamBridge}（按 runId 绑定）；</li>
 *   <li>节点只写自己负责的键（C17 硬规则 2），所以每个方法返回的 Map 都很短。</li>
 * </ul>
 */
@Component
public class RealAnalysisNodes implements AnalysisNodes {

    /** 从答案正文里抠引用编号（与 AnswerService.renumberCitations 同一个口径）。 */
    private static final Pattern CITE = Pattern.compile("[\\[［]\\s*E\\s*(\\d+)\\s*[\\]］]");

    /** 拆子问题时认这些连接词（中文里「和/与/、/，/；」足够覆盖本语料）。 */
    private static final Pattern CLAUSE_SPLIT = Pattern.compile("[和与、，,；;]");

    /** 原句切出来的片段短于这个长度就不是一个诉求（「以及」「并」），直接丢；剥连接词后再判空。 */
    private static final int MIN_FRAGMENT_CHARS = 4;

    /** 句首连接词与停顿：切完之后要先剥掉，剩下的才是「诉求」本身（ST15 §3.1）。 */
    private static final Pattern LEADING_CONJUNCTION =
            Pattern.compile("^(并且|并|结合|同时|以及|还有|且|而且|另外|再|和|与|及|、|，|,|；|;)+");

    private static final int SNIPPET_CHARS = 160;

    /**
     * 归因类问题的「驱动类证据」判定（ST16 Task 1）。
     *
     * <p>一个问「为什么增长」的问题，命中的可能全是财务附注（有数字、没原因）。
     * 两类命中任一即算覆盖：落在管理层讨论这类章节里，或片段里出现经营驱动指标。
     * 词表保守：宁可漏判（少一次改写），不要误判（把正常问题逼去 rewrite）。
     */
    private static final List<String> DRIVER_SECTIONS = List.of(
            "管理层讨论", "主营业务分析", "行业情况", "经营情况");

    private static final List<String> DRIVER_TERMS = List.of(
            "交易额", "保有", "销售额", "手续费及佣金", "利息净收入", "同比增减", "主要原因");

    /** 改写时补的驱动词：让第二路检索偏向「原因/驱动」，而不是再查一遍数字。 */
    private static final String ATTRIBUTION_BOOST = "原因 驱动 交易额 保有规模 手续费";

    /** 归因证据缺失的告警名；测试与排障都按这个字符串匹配。 */
    public static final String WARNING_COVERAGE_ATTRIBUTION = "coverage_missing:attribution";

    /**
     * 用户指令型长期记忆超出独立预算：仍全部注入，只是留一条痕（静默丢弃用户指令是更严重的错误）。
     */
    public static final String WARNING_MEMORY_OVERFLOW = "instruction_overflow";

    /** 长期记忆召回抛异常（向量服务/库挂了）：本轮不用记忆，答案照出。 */
    public static final String WARNING_MEMORY_RECALL_FAILED = "memory_recall_failed";

    private static final org.slf4j.Logger LOG =
            org.slf4j.LoggerFactory.getLogger(RealAnalysisNodes.class);

    private final RouterService router;
    private final ScopeResolver scopes;
    /** verify 的指标核对要读命中块的全文（片段只有 160 字，长表格会把靠后的行截掉）。 */
    private final ChunkRepository chunks;
    private final HybridRetriever retriever;
    private final ContextBuilder contextBuilder;
    private final AnswerService answers;
    private final CitationValidator citations;
    private final AnswerStreamBridge bridge;
    private final AgentProperties props;
    /** 长期记忆的召回闸门与召回器：QU 之后才跑，否则「这一问要不要记忆」无从判断。 */
    private final MemoryGate memoryGate;
    private final LongTermMemoryRecaller longTermRecaller;
    private final MemoryAssembler memoryAssembler;
    /** 期间标注：问 2023 却引用了 2024 年报的上年同期对比列时要留痕（不改数字）。 */
    private final PeriodLabeler periods;

    public RealAnalysisNodes(RouterService router, ScopeResolver scopes, HybridRetriever retriever,
                             ContextBuilder contextBuilder, AnswerService answers,
                             CitationValidator citations, AnswerStreamBridge bridge,
                             AgentProperties props, ChunkRepository chunks,
                             MemoryGate memoryGate, LongTermMemoryRecaller longTermRecaller,
                             MemoryAssembler memoryAssembler, PeriodLabeler periods) {
        this.router = router;
        this.scopes = scopes;
        this.chunks = chunks;
        this.memoryGate = memoryGate;
        this.longTermRecaller = longTermRecaller;
        this.memoryAssembler = memoryAssembler;
        this.periods = periods;
        this.retriever = retriever;
        this.contextBuilder = contextBuilder;
        this.answers = answers;
        this.citations = citations;
        this.bridge = bridge;
        this.props = props;
    }

    // ---------------------------------------------------------------- understand

    @Override
    public Map<String, Object> understand(AnalysisState state) {
        String runId = state.runId();
        // 有会话上下文才走带上下文的 QU：那条路会让模型带着最近几轮解析「那净利润呢」，
        // 并在公司/年份是从上文补出来的时候打上 inferredFromContext（单轮路径永远为 false）
        TurnContext context = bridge.turnContext(runId);
        QueryUnderstanding qu = context.isEmpty()
                ? router.understand(state.question())
                : router.understand(state.question(), context);
        if (qu.queryType() == null) {
            qu = QueryUnderstanding.failed(state.question(), "query_type_null");
        }
        // 收尾时用它更新会话结构化上下文（桥里存，不进状态的理由见 AnswerStreamBridge）
        bridge.query(runId, qu);

        Map<String, Object> patch = new LinkedHashMap<>();
        patch.put(AnalysisState.QUERY_TYPE, qu.queryType().name());
        patch.put(AnalysisState.METRICS, nullToEmpty(qu.metrics()));
        patch.put(AnalysisState.COMPANY_CODES, nullToEmpty(qu.companyCodes()));
        // 公司「字面」要单独存：拆子问题时要把它补进每个片段（只存代码补不进去，
        // 而代码进检索式会把向量拉向无关方向），见 ST15 §3.1
        patch.put(AnalysisState.COMPANY_MENTIONS, nullToEmpty(qu.companyMentions()));
        if (qu.yearRange() != null && qu.yearRange().length >= 2) {
            if (qu.yearRange()[0] > 0) {
                patch.put(AnalysisState.YEAR_FROM, qu.yearRange()[0]);
            }
            if (qu.yearRange()[1] > 0) {
                patch.put(AnalysisState.YEAR_TO, qu.yearRange()[1]);
            }
        }

        List<String> plan = new ArrayList<>();
        plan.add("理解问题：" + shorten(state.question(), 40));

        ResolvedScope scope;
        try {
            scope = scopes.resolve(ScopeMapper.toScope(state));
        } catch (ScopeEmptyException e) {
            plan.add("作用域为空或无权访问 → 拒答");
            patch.put(AnalysisState.REFUSE_REASON, e.getMessage());
            // 打上标记：respondInsufficient 与 AgentStreamService 靠它把「看不到任何文档」
            // 与「证据不足」区分开（前端/HTTP 语义对齐 chat 链路的 409 SCOPE_EMPTY）
            patch.put(AnalysisState.WARNINGS, List.of("scope_empty"));
            patch.put(AnalysisState.PLAN, plan);
            return patch;
        }
        plan.add("检索范围：" + scope.size() + " 份文档");
        bridge.scope(runId, scope);

        CompanyResolver.Resolution company = CompanyResolver.resolve(qu.companyCodes(), qu.companyMentions(), scope);
        if (company.hasMatch()) {
            patch.put(AnalysisState.COMPANY_CODES, company.codes());
        }
        // 上文的公司/年份被借用了：正常回答，但要在 warnings 里留痕（阶段 10 起测试按这个码验指代消解）
        List<String> warnings = new ArrayList<>();
        if (qu.inferredFromContext()) {
            warnings.add(AnalysisState.WARN_INFERRED_FROM_CONTEXT);
        }
        if (company.warnings() != null) {
            warnings.addAll(company.warnings());
        }
        // 长期记忆：闸门要读 QU（needLongTermMemory），所以只能在这里召，不能在图开跑前
        MemoryContext memory = bridge.memory(runId);
        MemoryContext base = memory == null ? MemoryContext.EMPTY : memory;
        MemoryTrace memoryStats = MemoryTrace.from(base);
        // 注意：闸门与「基础记忆是否为空」无关 —— 新会话第一句就可能带偏好（「按我的要求标注出处」），
        // 这时短期窗口是空的，但长期记忆仍要召回（阶段 9 的教训，2026-10-31 重验）
        if (memoryGate.shouldRecall(state.question(), qu)) {
            try {
                List<LongTermMemory> recalled = longTermRecaller.recall(state.question());
                MemoryAssembler.WithLongTerm merged = memoryAssembler.withLongTerm(base, recalled);
                memoryStats = MemoryTrace.from(merged.context());
                if (!recalled.isEmpty()) {
                    // 只改背景信息，不动证据：记忆永远不进 [E*] 编号（D7）
                    patch.put(AnalysisState.MEMORY_TEXT,
                            merged.context().text() == null ? "" : merged.context().text());
                    plan.add("召回长期记忆 " + recalled.size() + " 条");
                }
                if (merged.instructionOverflow()) {
                    warnings.add(WARNING_MEMORY_OVERFLOW);
                }
            } catch (RuntimeException e) {
                // 记忆召回失败不该让这一轮失败：答案照出，只在 warnings 里留痕（阶段 9 的既定行为）
                LOG.warn("长期记忆召回失败，本轮不使用：{}", e.toString());
                warnings.add(WARNING_MEMORY_RECALL_FAILED);
            }
        }
        bridge.memoryTrace(runId, memoryStats);
        if (!warnings.isEmpty()) {
            patch.put(AnalysisState.WARNINGS, List.copyOf(warnings));
        }
        if (company.blocked()) {
            String reason = "问题里的公司不在当前可检索范围内：" + String.join("、", company.unresolved());
            plan.add("公司不在作用域 → 拒答");
            patch.put(AnalysisState.REFUSE_REASON, reason);
        }
        patch.put(AnalysisState.PLAN, plan);
        return patch;
    }

    // ---------------------------------------------------------------- plan

    @Override
    public Map<String, Object> plan(AnalysisState state) {
        if (!state.refuseReason().isBlank()) {
            return Map.of(AnalysisState.PLAN, List.of("拒答：" + state.refuseReason()));
        }
        Map<String, Object> patch = new LinkedHashMap<>();
        List<String> planSteps = new ArrayList<>();

        List<String> subQuestions = decompose(state);
        if (subQuestions.size() > 1) {
            patch.put(AnalysisState.SUB_QUESTIONS, subQuestions);
            planSteps.add("拆成 " + subQuestions.size() + " 个子问题，逐个检索");
        } else {
            planSteps.add("单轮检索");
        }
        EnumSet<AnswerShape> shapes = shapesOf(state);
        if (!shapes.isEmpty()) {
            patch.put(AnalysisState.ANSWER_SHAPES, AnswerShape.names(shapes));
            planSteps.add("答案形态：" + labels(shapes));
        }
        planSteps.add("证据不足会改写一次再检索");
        planSteps.add("生成答案后做引用校验");
        patch.put(AnalysisState.PLAN, planSteps);
        return patch;
    }

    /** 规则拆解（不调模型，见 ST11 §13.3）：复杂问题才拆，按连接词切句。 */
    private List<String> decompose(AnalysisState state) {
        if (!QueryType.COMPLEX_ANALYSIS.name().equals(state.queryType())) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (String part : CLAUSE_SPLIT.split(state.question())) {
            String raw = part.strip();
            if (raw.length() < MIN_FRAGMENT_CHARS) {
                continue;   // 「以及」「并」这类纯连接词，剥完也是空的
            }
            String piece = LEADING_CONJUNCTION.matcher(raw).replaceAll("").strip();
            if (piece.isEmpty()) {
                continue;
            }
            out.add(contextPrefix(state, piece) + piece);
            if (out.size() >= props.maxSubQuestions()) {
                break;
            }
        }
        return out.size() > 1 ? List.copyOf(out) : List.of();
    }

    /**
     * 给片段补「主体 + 年份」前缀，片段里已经有的那部分不重复补（ST15 §3.1）。
     *
     * <p><b>为什么必须补</b>：拆出来的「并结合净利润」脱离原句后既没有主体也没有年份，
     * 它不再是一个可独立检索的问题，而是一串关键词 —— 关键词会命中「把这个词说得多」的
     * 财务报表附注（实测 rerank 0.602），而不是给出汇总表的那一页（0.523），
     * 于是主表被挤出 6000 token 的上下文预算（ST15 §2.2 的四组对比）。
     */
    private static String contextPrefix(AnalysisState state, String piece) {
        StringBuilder sb = new StringBuilder();
        for (String mention : state.companyMentions()) {
            String name = mention == null ? "" : mention.strip();
            if (!name.isEmpty() && !piece.contains(name)) {
                sb.append(name).append(' ');
            }
        }
        Integer from = state.yearFrom();
        Integer to = state.yearTo();
        if (from != null && from > 0 && !piece.contains(from + "年")) {
            sb.append(to != null && to > from ? from + "年至" + to + "年" : from + "年").append(' ');
        }
        return sb.toString();
    }

    // ---------------------------------------------------------------- retrieve

    @Override
    public Map<String, Object> retrieve(AnalysisState state) {
        // 上一节点已经判过「不可答」就不要查库：查了也会把错主体的证据带进后面的装配
        if (!state.refuseReason().isBlank()) {
            return Map.of(AnalysisState.HITS, List.of(), AnalysisState.EVIDENCE_COUNT, 0);
        }
        Map<String, Object> patch = new LinkedHashMap<>();
        try {
            ResolvedScope scope = scopes.resolve(ScopeMapper.toScope(state));
            QueryUnderstanding qu = ScopeMapper.toQuery(state);
            List<String> warnings = new ArrayList<>();

            // 车道 = 文本 + 过滤条件（ST16 Task 3）：整句永远占一路，
            // 拆解是「多路召回」而不是「只召回碎片」（ST15 §3.2）；
            // 跨年问题每年单独一路，归因/结构问题再加一路「第三节」
            List<RetrievalLane> lanes = new ArrayList<>(RetrievalLanes.plan(
                    state.question(), qu.normalizedQuery(), state.subQuestions(), shapesOf(state),
                    new int[]{state.yearFrom() == null ? 0 : state.yearFrom(),
                            state.yearTo() == null ? 0 : state.yearTo()},
                    new LinkedHashSet<>(scope.docYears()), warnings));

            List<Map<String, Object>> hits = new ArrayList<>();
            Map<Long, Integer> posById = new LinkedHashMap<>();
            runLanes(lanes, qu, scope, state, hits, posById);

            // 年份覆盖校验：合并之后每年都要有证据。
            // 缺年份时只补一轮（用 warnings 里的标记判断补过没有），补完仍缺就写缺口，不拒答。
            String fullText = lanes.isEmpty() ? state.question() : lanes.get(0).text();
            if (!warnings.contains(RetrievalLanes.WARN_YEAR_RETRIED)) {
                List<Integer> missing = RetrievalLanes.missingYears(hits, scope, yearRange(state));
                if (!missing.isEmpty()) {
                    warnings.add(RetrievalLanes.WARN_YEAR_RETRIED);
                    List<RetrievalLane> extra = new ArrayList<>();
                    for (Integer year : missing) {
                        extra.add(RetrievalLane.year(year, fullText, RetrievalLanes.YEAR_RETRY_TOP_K));
                    }
                    lanes.addAll(extra);
                    runLanes(extra, qu, scope, state, hits, posById);
                    for (Integer year : RetrievalLanes.missingYears(hits, scope, yearRange(state))) {
                        addOnce(warnings, RetrievalLanes.WARN_YEAR_MISSING_PREFIX + year);
                    }
                }
            }

            patch.put(AnalysisState.HITS, hits);
            patch.put(AnalysisState.EVIDENCE_COUNT, hits.size());
            patch.put(AnalysisState.LANES, lanes.stream().map(RetrievalLane::id).toList());
            if (!warnings.isEmpty()) {
                patch.put(AnalysisState.WARNINGS, List.copyOf(warnings));
            }
        } catch (ScopeEmptyException e) {
            // 「一份都看不到」不是「检索没命中」：写成原因，让图走拒答，
            // 也让 AgentStreamService 能发 SCOPE_EMPTY（与 chat 链路的 409 对齐）
            patch.put(AnalysisState.HITS, List.of());
            patch.put(AnalysisState.EVIDENCE_COUNT, 0);
            patch.put(AnalysisState.WARNINGS, List.of("scope_empty"));
            patch.put(AnalysisState.REFUSE_REASON, e.getMessage());
        }
        return patch;
    }

    /** 跑一组车道，把结果并进 hits（同一 chunkId 取最高分）。 */
    private void runLanes(List<RetrievalLane> lanes, QueryUnderstanding qu, ResolvedScope scope,
                          AnalysisState state, List<Map<String, Object>> hits, Map<Long, Integer> posById) {
        for (RetrievalLane lane : lanes) {
            HybridRetriever.Result result = retriever.retrieve(lane.toRequest(), qu, scope, RetrievalProgress.NONE);
            // 第一次检索的轨迹留给 trace（Bridge 用 putIfAbsent，多路不覆盖）
            bridge.retrievalTrace(state.runId(), result.trace());
            for (RetrievedChunk chunk : result.chunks()) {
                Map<String, Object> hit = toHit(chunk);
                Integer pos = posById.get(chunk.chunkId());
                if (pos == null) {
                    posById.put(chunk.chunkId(), hits.size());
                    hits.add(hit);
                } else if (number(hits.get(pos).get("score")) < number(hit.get("score"))) {
                    // 同一块在多路里出现：保留最高分。先到先得会让它带着某一路的低分
                    // 一路走到装配，名次被低估（ST15 §3.2）
                    hits.set(pos, hit);
                }
            }
        }
    }

    private static int[] yearRange(AnalysisState state) {
        Integer from = state.yearFrom();
        Integer to = state.yearTo();
        return new int[]{from == null ? 0 : from, to == null ? 0 : to};
    }

    private static void addOnce(List<String> warnings, String warning) {
        if (!warnings.contains(warning)) {
            warnings.add(warning);
        }
    }

    private static Map<String, Object> toHit(RetrievedChunk chunk) {
        Map<String, Object> hit = new LinkedHashMap<>();
        hit.put("chunkId", chunk.chunkId());
        hit.put("parentId", chunk.parentId());
        hit.put("documentId", chunk.documentId());
        hit.put("page", chunk.pageStart());
        hit.put("sectionPath", blankToEmpty(chunk.sectionPath()));
        hit.put("type", blankToEmpty(chunk.chunkType()));
        hit.put("score", chunk.rerankScore() == null ? chunk.rrfScore() : chunk.rerankScore());
        hit.put("snippet", shorten(chunk.content(), SNIPPET_CHARS));
        return hit;
    }

    // ---------------------------------------------------------------- verify

    @Override
    public Map<String, Object> verify(AnalysisState state) {
        boolean tooFew = state.hits().size() < Math.max(1, props.minEvidence());
        boolean metricMissing = !tooFew && !metricHit(state);
        // 第三道：问「为什么」却只召回财务附注时，证据不完整。
        // 只在第一次（retryCount==0）判 —— 改写后仍缺就照常生成、由答案第 ④ 段写缺口，
        // 否则「缺归因」会变成「整题拒答」，比答案不够专业更糟（ST16 Task 1.4）
        boolean attributionMissing = !tooFew && !metricMissing
                && state.retryCount() == 0
                && shapesOf(state).contains(AnswerShape.ATTRIBUTION)
                && !attributionCovered(state);
        Map<String, Object> patch = new LinkedHashMap<>();
        patch.put(AnalysisState.INSUFFICIENT, tooFew || metricMissing || attributionMissing);
        if (attributionMissing) {
            patch.put(AnalysisState.WARNINGS, List.of(WARNING_COVERAGE_ATTRIBUTION));
        }
        return patch;
    }

    /** 命中块里有没有「驱动类证据」；两类任一成立即算覆盖。 */
    private static boolean attributionCovered(AnalysisState state) {
        for (Map<String, Object> hit : state.hits()) {
            String section = String.valueOf(hit.get("sectionPath"));
            String snippet = String.valueOf(hit.get("snippet"));
            if (containsAny(section, DRIVER_SECTIONS) || containsAny(snippet, DRIVER_TERMS)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 目标指标至少出现在一条命中里；没有指定指标时视为命中。
     *
     * <p><b>为什么要回库读全文</b>：状态里每块只留 {@link #SNIPPET_CHARS} 字片段，
     * 而「主要会计数据和财务指标」那张表有 800 字、目标行在 156 字之后 ——
     * 只看片段会把「明明检索到了」判成「没检索到」，整题被拒答（2026-10-31 评测抓到：
     * 2024 年归母净利润、2024 年末资产总额两题，指标分别落在第 156 / 631 字）。
     * 这里只读本轮命中的 ≤8 块，与装配上下文读的量同一数量级。
     */
    private boolean metricHit(AnalysisState state) {
        List<String> metrics = state.metrics();
        if (metrics.isEmpty()) {
            return true;
        }
        List<Long> ids = new ArrayList<>();
        for (Map<String, Object> hit : state.hits()) {
            String text = String.valueOf(hit.get("sectionPath")) + String.valueOf(hit.get("snippet"));
            if (containsAnyMetric(text, metrics)) {
                return true;
            }
            Object id = hit.get("chunkId");
            if (id instanceof Number number) {
                ids.add(number.longValue());
            }
        }
        for (ChunkView view : chunks.findViewsByIds(ids)) {
            String text = blankToEmpty(view.sectionPath()) + blankToEmpty(view.title())
                    + blankToEmpty(view.content());
            if (containsAnyMetric(text, metrics)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 指标命中：用户嘴里的说法与表头写法可能不同，按词典的同义组逐个试。
     *
     * <p>2026-10-31 评测：问「归母净利润」表头写「归属于上市公司股东的净利润」、
     * 问「员工总数」表里写「在职员工的数量合计」，字面比对必然落空 → 整题被拒答。
     */
    private static boolean containsAnyMetric(String text, List<String> metrics) {
        for (String metric : metrics) {
            for (String variant : MetricDictionary.variants(metric)) {
                if (!variant.isBlank() && text.contains(variant)) {
                    return true;
                }
            }
        }
        return false;
    }

    // ---------------------------------------------------------------- rewrite

    @Override
    public Map<String, Object> rewrite(AnalysisState state) {
        String rewritten = state.question();
        if (!state.metrics().isEmpty()) {
            rewritten = rewritten + " " + String.join(" ", state.metrics());
        }
        if (shapesOf(state).contains(AnswerShape.ATTRIBUTION)) {
            rewritten = rewritten + " " + ATTRIBUTION_BOOST;
        }
        return Map.of(
                AnalysisState.REWRITTEN_QUERY, rewritten,
                AnalysisState.RETRY_COUNT, 1,
                AnalysisState.PLAN, List.of("证据不足，改写检索式后重试：" + shorten(rewritten, 40)));
    }

    // ---------------------------------------------------------------- compute

    /**
     * 事实层（ST16 Task 2）：「证据里有哪些数」+「据此能算出什么」。
     *
     * <p>三条约束：
     * <ul>
     *   <li>只读证据、只写 {@link AnalysisState#FACTS} 与警告 —— 不改 hits、不改 answer；</li>
     *   <li>数字一律来自表格单元格（{@link FactCollector} 只认 TABLE 块），正文里的数字不进事实层；</li>
     *   <li>算不出来的就跳过并留 {@code compute_skipped:*} 警告，绝不编一个数。</li>
     * </ul>
     */
    @Override
    public Map<String, Object> compute(AnalysisState state) {
        ResolvedScope scope = scopes.resolve(ScopeMapper.toScope(state));
        QueryUnderstanding qu = ScopeMapper.toQuery(state);
        ContextPackage pkg = packageOf(state, qu, scope);

        FactCollector.Result collected = FactCollector.collect(pkg.blocks(), qu);
        MetricCalculator.Result computed = MetricCalculator.compute(collected.facts(), shapesOf(state), state.metrics());

        List<Map<String, Object>> rows = new ArrayList<>();
        List<String> warnings = new ArrayList<>(collected.warnings());
        warnings.addAll(computed.warnings());

        int no = 1;
        for (ReportedFact fact : collected.facts()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("no", no++);
            row.put("metric", fact.metric());
            row.put("period", fact.period() == null ? "" : fact.period());
            row.put("value", plain(fact.value()));
            row.put("unit", fact.unit());
            row.put("scope", "");
            row.put("evidence", fact.evidence());
            row.put("origin", AnswerResult.ORIGIN_REPORTED);
            row.put("formula", "");
            row.put("display", displayOf(fact.value(), fact.unit()));
            row.put("note", "");
            rows.add(row);
        }
        for (ComputedFact fact : computed.facts()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("no", no++);
            row.put("metric", fact.metric());
            row.put("period", fact.period() == null ? "" : fact.period());
            row.put("value", plain(fact.value()));
            row.put("unit", fact.unit());
            row.put("scope", "");
            row.put("evidence", fact.evidence());
            row.put("origin", AnswerResult.ORIGIN_COMPUTED);
            row.put("formula", fact.formula() == null ? "" : fact.formula());
            row.put("display", "");
            row.put("note", fact.note() == null ? "" : fact.note());
            rows.add(row);
        }

        Map<String, Object> patch = new LinkedHashMap<>();
        patch.put(AnalysisState.FACTS, List.copyOf(rows));
        if (!warnings.isEmpty()) {
            patch.put(AnalysisState.WARNINGS, List.copyOf(warnings));
        }
        return patch;
    }

    /** 只有「元」金额才给换算展示（亿元 / 万元）；比率与百分点原样输出，不然会出现「0.38 亿%」。 */
    private static String displayOf(BigDecimal value, String unit) {
        if (value == null || !ReportedFact.UNIT_YUAN.equals(unit)) {
            return "";
        }
        return UnitConverter.convert(value).text();
    }

    private static String plain(BigDecimal value) {
        return value == null ? "" : UnitConverter.plain(value);
    }

    // ---------------------------------------------------------------- answer

    @Override
    public Map<String, Object> answer(AnalysisState state) {
        // 最后一道兜底：即使将来有新的边绕过前面两个条件判断，也不允许在「已判不可答」时调模型
        if (!state.refuseReason().isBlank()) {
            return respondInsufficient(state);
        }
        ResolvedScope scope = scopes.resolve(ScopeMapper.toScope(state));
        QueryUnderstanding qu = ScopeMapper.toQuery(state);
        ContextPackage pkg = packageOf(state, qu, scope);
        MemoryContext memory = memoryOf(state);

        String runId = state.runId();
        // 事实包来自 state.facts()（compute 节点写的 Map 行），这里只做渲染，不重算
        AnswerResult result = answers.answerStreaming(state.question(), qu, pkg, memory,
                FactPack.from(state.facts()), delta -> bridge.delta(runId, delta));
        result = withPeriodLabel(result, qu, scope);
        // 完整结果（facts / citations / confidence / note / 丢弃数）走旁路，不进可序列化的状态
        bridge.result(runId, result);

        Map<String, Object> patch = new LinkedHashMap<>();
        patch.put(AnalysisState.ANSWER, result.answer() == null ? "" : result.answer());
        patch.put(AnalysisState.WARNINGS, result.warnings());
        patch.put(AnalysisState.STATUS, AgentRun.STATUS_DONE);
        return patch;
    }

    /**
     * 期间标注：引用文档的年份与问题年份不一致时，补一条 warning 与一句 note。
     *
     * <p><b>为什么在 answer 之后做</b>：要判「引用了哪几年」必须先有 citations；
     * 图和状态都不能带 Citation 列表（要可序列化），所以只能在拿到结果的这一处补。
     *
     * <p><b>为什么改 note 而不是改数字</b>：从 2024 年报的上年同期列取 2023 的数是对的，
     * 错的是「没说这个数出自哪一年的报告」。替用户改数才是越界。
     */
    private AnswerResult withPeriodLabel(AnswerResult result, QueryUnderstanding qu, ResolvedScope scope) {
        PeriodLabeler.Result period = periods.label(qu, result.citations(), scope);
        if (period.warnings().isEmpty()) {
            return result;
        }
        List<String> warnings = new ArrayList<>(result.warnings());
        warnings.addAll(period.warnings());
        String note = result.note() == null || result.note().isBlank()
                ? period.note()
                : result.note() + " " + period.note();
        return new AnswerResult(result.sufficient(), result.answer(), result.facts(), result.citations(),
                result.confidence(), note, List.copyOf(warnings), result.citationsDropped());
    }

    // ---------------------------------------------------------------- cite_check

    @Override
    public Map<String, Object> citeCheck(AnalysisState state) {
        ResolvedScope scope = scopes.resolve(ScopeMapper.toScope(state));
        QueryUnderstanding qu = ScopeMapper.toQuery(state);
        ContextPackage pkg = packageOf(state, qu, scope);

        List<Integer> numbers = emitted(state.answer());
        CitationValidator.Result result = citations.validate(numbers, pkg.blocks());
        List<String> issues = new ArrayList<>();
        if (!result.dropped().isEmpty()) {
            issues.add("citations_dropped:" + result.dropped().size());
        }
        // 一条编号都没写 ≠ 编号写错：前者模型没守 Prompt 第 3 条，后者是编了不存在的编号
        if (numbers.isEmpty() && !state.answer().isBlank()) {
            issues.add("no_citation_marker");
        }
        return Map.of(
                AnalysisState.CITATION_ISSUES, List.copyOf(issues),
                AnalysisState.CITE_ATTEMPTS, 1,
                AnalysisState.CITATION_COUNT, result.citations().size());
    }

    // ---------------------------------------------------------------- respond_insufficient

    @Override
    public Map<String, Object> respondInsufficient(AnalysisState state) {
        String answer = state.refuseReason().isBlank()
                ? "证据不足：检索到的内容里没有能支撑这个问题的信息。"
                : "无法回答：" + state.refuseReason() + "。";
        String warning;
        if (state.warnings().contains("scope_empty")) {
            warning = "scope_empty";
        } else if (!state.refuseReason().isBlank() && state.refuseReason().contains("公司")) {
            // 主体越界与「证据不够」是两回事：前者永远重试不出结果，后者换检索式还有机会
            warning = "company_unresolved";
        } else {
            warning = AnswerResult.WARNING_EVIDENCE_INSUFFICIENT;
        }
        return Map.of(
                AnalysisState.ANSWER, answer,
                AnalysisState.WARNINGS, List.of(warning),
                AnalysisState.STATUS, AgentRun.STATUS_REJECTED);
    }

    // ---------------------------------------------------------------- helpers

    /** 用状态里的 hits 重建证据包（ContextBuilder 只按 id 回库取正文，所以状态里不需要正文）。 */
    // 包内可见：真库回归用例（ComplexEvidenceDbTest）要直接验装配结果，而不重造一遍 RetrievedChunk
    ContextPackage packageOf(AnalysisState state, QueryUnderstanding qu, ResolvedScope scope) {
        List<RetrievedChunk> chunks = new ArrayList<>();
        for (Map<String, Object> hit : state.hits()) {
            double score = number(hit.get("score"));
            chunks.add(new RetrievedChunk(
                    longOf(hit.get("chunkId")), longOf(hit.get("parentId")), longOf(hit.get("documentId")),
                    String.valueOf(hit.get("type")), String.valueOf(hit.get("sectionPath")), "", "",
                    (int) longOf(hit.get("page")), (int) longOf(hit.get("page")), "",
                    null, null, score, score));
        }
        QueryType type = QueryType.parse(state.queryType());
        return contextBuilder.build(state.question(),
                type == null ? QueryType.SEMANTIC_EXPLANATION : type, chunks, scope);
    }

    private static MemoryContext memoryOf(AnalysisState state) {
        if (state.memoryText() == null || state.memoryText().isBlank()) {
            return MemoryContext.EMPTY;
        }
        return new MemoryContext(state.memoryText(), MemoryContext.Stats.EMPTY);
    }

    /**
     * 状态里的答案形态。
     *
     * <p>plan 一定先于 verify/rewrite 跑，但它可能没写（问题没有形态），所以这里补一次纯函数判定：
     * 单测直接调 verify 时状态里也没有这个键 —— 两种情况下结果必须一致（同一套词表、同一个入参）。
     */
    private static EnumSet<AnswerShape> shapesOf(AnalysisState state) {
        EnumSet<AnswerShape> shapes = AnswerShape.parse(state.answerShapes());
        if (!shapes.isEmpty()) {
            return shapes;
        }
        Integer from = state.yearFrom();
        Integer to = state.yearTo();
        int[] range = from == null || to == null ? null : new int[]{from, to};
        return AnswerShape.detect(state.question(), range);
    }

    private static String labels(EnumSet<AnswerShape> shapes) {
        List<String> out = new ArrayList<>();
        for (AnswerShape shape : shapes) {
            out.add(shape.label());
        }
        return String.join("、", out);
    }

    private static boolean containsAny(String text, List<String> words) {
        if (text == null || text.isEmpty()) {
            return false;
        }
        for (String word : words) {
            if (text.contains(word)) {
                return true;
            }
        }
        return false;
    }

    private static List<Integer> emitted(String answer) {
        List<Integer> out = new ArrayList<>();
        if (answer == null) {
            return out;
        }
        Matcher m = CITE.matcher(answer);
        while (m.find()) {
            out.add(Integer.parseInt(m.group(1)));
        }
        return out;
    }

    private static long longOf(Object value) {
        return value instanceof Number n ? n.longValue() : 0L;
    }

    private static double number(Object value) {
        return value instanceof Number n ? n.doubleValue() : 0.0;
    }

    private static String blankToEmpty(String in) {
        return in == null ? "" : in;
    }

    private static List<String> nullToEmpty(List<String> in) {
        return in == null ? List.of() : in;
    }

    private static String shorten(String text, int max) {
        if (text == null) {
            return "";
        }
        String flat = text.replace('\n', ' ').strip();
        return flat.length() <= max ? flat : flat.substring(0, max) + "…";
    }
}