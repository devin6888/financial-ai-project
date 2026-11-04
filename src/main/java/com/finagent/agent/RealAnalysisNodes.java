package com.finagent.agent;

import com.finagent.answer.AnswerResult;
import com.finagent.answer.AnswerService;
import com.finagent.answer.PeriodLabeler;
import com.finagent.context.EvidenceProfile;
import com.finagent.understanding.AnswerSectionPlan;
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
import com.finagent.market.MarketApiException;
import com.finagent.market.MarketGateway;
import com.finagent.market.MarketToolSelector;
import com.finagent.market.MarketUnavailableException;
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
    /** 行情取数（阶段 23）：未启用时是 {@code UnavailableMarketGateway}，不是 null。 */
    private final MarketGateway market;
    /** 让模型选工具的可选路径；模型选不出来就回落到规则调用。 */
    private final MarketToolSelector marketTools;
    /** 公司名 → 股票代码的三级解析。 */
    private final MarketCompanyResolver marketCompanies;

    public RealAnalysisNodes(RouterService router, ScopeResolver scopes, HybridRetriever retriever,
                             ContextBuilder contextBuilder, AnswerService answers,
                             CitationValidator citations, AnswerStreamBridge bridge,
                             AgentProperties props, ChunkRepository chunks,
                             MemoryGate memoryGate, LongTermMemoryRecaller longTermRecaller,
                             MemoryAssembler memoryAssembler, PeriodLabeler periods,
                             MarketGateway market, MarketToolSelector marketTools,
                             MarketCompanyResolver marketCompanies) {
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
        this.market = market;
        this.marketTools = marketTools;
        this.marketCompanies = marketCompanies;
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

        // 走ACL校验用户的文档访问权限
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

        // 不是问年报的问题（问系统、问界面、寒暄）：直接说明能力边界，不进检索、也不弹审批（阶段 22）
        if (OffTopicDetector.isOffTopic(state.question(), qu.metrics(), qu.companyMentions(), qu.yearRange())) {
            plan.add("不是年报问题 → 说明能力边界");
            patch.put(AnalysisState.REFUSE_REASON, OffTopicDetector.reason());
            patch.put(AnalysisState.WARNINGS, List.of("off_topic"));
            patch.put(AnalysisState.PLAN, plan);
            return patch;
        }

        // 人批要问什么：作用域里有多个年份/公司，而问题没点名（纯规则，不调模型）。
        // 这里算而不是开跑前算，因为「几家公司/几年」要解析作用域，而 understand 已经解析好了
        List<String> approvalReasons = AmbiguityDetector.reasons(scope.documents(), state.question());
        if (!approvalReasons.isEmpty()) {
            patch.put(AnalysisState.APPROVAL_REASONS, approvalReasons);
            patch.put(AnalysisState.APPROVAL_CANDIDATES,
                    AmbiguityDetector.candidates(scope.documents()));
            plan.add("问题没说清（" + String.join(" / ", approvalReasons) + "）→ 先请人确认");
        }

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
        // 行情意图（阶段 23）：只判「问的是不是行情」，公司代码解析要靠 company 表/MCP 搜索。
        // 判不出来公司就不进行情支路 —— 猜错公司比答不出更糟，问题仍走年报检索
        MarketIntent intent = MarketIntentDetector.detect(state.question());
        LOG.debug("行情意图：matched={} history={} days={}", intent.matched(), intent.history(), intent.days());
        if (intent.matched()) {
            java.util.Optional<MarketCompanyResolver.MarketCompany> target = marketCompanies.resolve(state.question(), qu.companyCodes(), qu.companyMentions());
            LOG.debug("行情意图命中：question=「{}」codes={} mentions={} → {}", state.question(),
                    qu.companyCodes(), qu.companyMentions(),
                    target.map(t -> t.code() + "/" + t.name() + "(" + t.source() + ")").orElse("未解析出公司"));
            if (target.isPresent()) {
                MarketCompanyResolver.MarketCompany mc = target.get();
                patch.put(AnalysisState.MARKET_INTENT, Boolean.TRUE);
                Map<String, Object> marketPlan = new LinkedHashMap<>();
                marketPlan.put("tool", intent.tool());
                marketPlan.put("code", mc.code());
                marketPlan.put("name", mc.name());
                marketPlan.put("days", intent.history() ? intent.days() : 0);
                marketPlan.put("codeSource", mc.source());
                patch.put(AnalysisState.MARKET_PLAN, marketPlan);
                // 纯行情：没有任何财报指标、也没指定年份。有指标/年份说明还要看年报，得先检索
                // 纯行情：没有任何「年报口径」的指标、也没指定年份。有指标/年份说明还要看年报，得先检索。
                // 注意 QU 常把「股价/市值/市盈率」也当指标抽出来 —— 这些本来就是行情口径，
                // 不能算「要看年报」（否则「东方财富股价」会被送去检索年报，最后证据不足拒答）。
                List<String> reportMetrics = nullToEmpty(qu.metrics()).stream()
                        .filter(m -> !isMarketMetric(m))
                        .toList();
                boolean reportLike = !reportMetrics.isEmpty() || qu.yearRange() != null;
                LOG.debug("行情口径判定：metrics={} reportMetrics={} yearRange={} reportLike={}",
                        qu.metrics(), reportMetrics, qu.yearRange(), reportLike);
                patch.put(AnalysisState.MARKET_ONLY, !reportLike);
                plan.add("实时行情：" + (mc.name().isEmpty() ? mc.code() : mc.name() + "（" + mc.code() + "）"));
            } else {
                plan.add("像在问行情，但没确定是哪家公司 → 按年报问题处理");
                warnings.add("market_company_unresolved");
            }
        }
        if (company.blocked()) {
            String reason = "问题里的公司不在当前可检索范围内：" + String.join("、", company.unresolved());
            plan.add("公司不在作用域 → 拒答");
            patch.put(AnalysisState.REFUSE_REASON, reason);
        }
        if (!warnings.isEmpty()) {
            patch.put(AnalysisState.WARNINGS, List.copyOf(warnings));
        }
        patch.put(AnalysisState.PLAN, plan);
        return patch;
    }

    /** QU 抽出来的指标里哪些其实是行情口径（股价、市值、估值倍数之类），不算「要看年报」。 */
    private static final java.util.regex.Pattern MARKET_METRICS = java.util.regex.Pattern.compile(
            "股价|价格|股价|市值|市盈率|市净率|估值|换手率?|成交额|成交量|涨跌幅?|现价|收盘价|开盘价|PE|PB");

    static boolean isMarketMetric(String metric) {
        return metric != null && MARKET_METRICS.matcher(metric).find();
    }

    // ---------------------------------------------------------------- plan

    @Override
    public Map<String, Object> plan(AnalysisState state) {
        if (!state.refuseReason().isBlank()) {
            return Map.of(AnalysisState.PLAN, List.of("拒答：" + state.refuseReason()));
        }
        Map<String, Object> patch = new LinkedHashMap<>();
        // 纯行情问题：没有可拆的子问题、也没有证据形态，直接进行情支路（plan → market）
        if (state.marketOnly()) {
            patch.put(AnalysisState.PLAN, List.of(
                    "只需实时行情：" + textOf(state.marketPlan().get("name"))
                            + "（" + textOf(state.marketPlan().get("code")) + "）",
                    "查行情快照，不检索年报",
                    "数字与数据时间由模板渲染，不经模型"));
            return patch;
        }
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
        if (state.marketIntent()) {
            planSteps.add("先检索年报证据，再查实时行情补充当前估值");
        }
        planSteps.add("证据不足会改写一次再检索");
        planSteps.add("生成答案后做引用校验");
        patch.put(AnalysisState.PLAN, planSteps);
        return patch;
    }

    /** 规则拆解（不调模型，见 ST11 §13.3）：复杂问题才拆，按连接词切句。 */
    private List<String> decompose(AnalysisState state) {
        // 这里的queryType 是由上一步 QU 中LLM 分析出来的结果
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
                    new int[]{state.yearFrom() == null ? 0 : state.yearFrom(), state.yearTo() == null ? 0 : state.yearTo()},
                    new LinkedHashSet<>(scope.docYears()), warnings)
            );

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

        // 章节计划（阶段 17）：先用这一轮真实的证据块算画像，再按形态 + 画像出候选章节。
        // 放在 compute 而不是 plan 节点，因为 plan 阶段还没有证据（画像必然全 false）。
        EvidenceProfile profile = EvidenceProfile.of(pkg.blocks());
        AnswerSectionPlan plan = AnswerSectionPlan.of(shapesOf(state), profile, pkg.blocks(),
                state.question());

        Map<String, Object> patch = new LinkedHashMap<>();
        patch.put(AnalysisState.FACTS, List.copyOf(rows));
        if (!plan.isEmpty()) {
            patch.put(AnalysisState.ANSWER_PLAN,
                    plan.sections().stream().map(AnswerSectionPlan.Section::toMap).toList());
            patch.put(AnalysisState.PLAN, List.of(
                    "答案按 " + plan.sections().size() + " 节组织：" + String.join(" / ", plan.titles())));
        }
        if (!warnings.isEmpty()) {
            patch.put(AnalysisState.WARNINGS, List.copyOf(warnings));
        }
        LOG.debug("证据画像 {} → 章节计划 {}", profile.describe(), plan.titles());
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
        AnswerSectionPlan plan = AnswerSectionPlan.fromRows(state.answerPlan(), shapesOf(state));
        AnswerResult result = answers.answerStreaming(state.question(), qu, pkg, memory,
                FactPack.from(withMarketFacts(state)), delta -> bridge.delta(runId, delta), plan);
        result = withPeriodLabel(result, qu, scope);
        result = withMarketStamp(result, state);
        // 完整结果（facts / citations / confidence / note / 丢弃数）走旁路，不进可序列化的状态
        bridge.result(runId, result);

        Map<String, Object> patch = new LinkedHashMap<>();
        List<String> warnings = new ArrayList<>(result.warnings());
        if (!state.marketFacts().isEmpty()) {
            // 答案里混了实时行情：让前端/排障能区分「全是年报数」与「含当日行情」
            warnings.add("market_live");
        }
        patch.put(AnalysisState.ANSWER, result.answer() == null ? "" : result.answer());
        patch.put(AnalysisState.WARNINGS, List.copyOf(warnings));
        patch.put(AnalysisState.STATUS, AgentRun.STATUS_DONE);
        return patch;
    }

    /**
     * 混合问题的答案补一条确定性的行情脚注（C40.3：没有时间戳的行情等于错答案）。
     *
     * <p>为什么不让模型写：模型会把「2026-11-04」写成日期、还会漏掉时区与「这是快照不是年报口径」。
     * 这里只在三件事同时成立时补：本轮确实取到了行情事实、有 asOf、答案里本来没有时间行。
     * 纯年报问题的答案是原样返回的（不加任何后缀）。
     */
    private static AnswerResult withMarketStamp(AnswerResult result, AnalysisState state) {
        List<Map<String, Object>> rows = state.marketFacts();
        String asOf = state.marketAsOf();
        if (rows.isEmpty() || asOf == null || asOf.isBlank()) {
            return result;
        }
        String answer = result.answer() == null ? "" : result.answer();
        if (answer.contains("数据时间")) {
            return result;
        }
        String stamp = readable(asOf);
        // 正文已经写过的数字不再重复；一项都没写过（模型常见：只说「股价未披露」）就把行情写全。
        // 数字由代码渲染，模型转述漏不失真。
        String quote = marketLine(rows, answer);
        String block = quote.isEmpty()
                ? "\n\n> 行情数据时间：" + stamp + "，来源：东方财富公开行情。"
                : "\n\n> 实时行情：" + quote + "（数据时间：" + stamp + "，来源：东方财富公开行情）";
        if (answer.contains(block.strip())) {
            return result;
        }
        return new AnswerResult(result.sufficient(), answer + block, result.facts(), result.citations(),
                result.confidence(), result.note(), result.warnings(), result.citationsDropped());
    }

    /** 行情事实行 → 一句可读的数字串（最多 4 项，避免脚注比答案还长；正文已出现过的项跳过）。 */
    private static String marketLine(List<Map<String, Object>> rows, String answer) {
        // 比对前把空白全去掉：正文写「3037.86亿元」，事实行 display 是「3037.86 亿元」
        String flat = answer == null ? "" : answer.replaceAll("\\s+", "");
        List<String> parts = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            if (parts.size() >= 4) {
                break;
            }
            String value = textOf(row.get("value"));
            if (value.isEmpty()) {
                continue;
            }
            String written = textOf(row.get("display"));
            String probe = (written.isEmpty() ? value : written).replaceAll("\\s+", "");
            if (!flat.isEmpty() && (flat.contains(probe) || flat.contains(value))) {
                continue;
            }
            String unit = textOf(row.get("unit"));
            // 百分号紧贴数字（1.80%），其余单位空一格（19.20 元）——与 answer_market 模板一致
            String shown = written.isEmpty() ? ("%".equals(unit) ? value + unit : value + " " + unit) : written;
            parts.add(marketLabel(textOf(row.get("metric"))) + " " + shown);
        }
        return String.join("，", parts);
    }

    private static String marketLabel(String metric) {
        return switch (metric) {
            case "股价" -> "最新价";
            case "市盈率TTM" -> "市盈率(TTM)";
            default -> metric;
        };
    }

    /** 已有事实 + 行情事实（no 从 900 起，排在最后）。行情数字必须进白名单，否则会被判成编造。 */
    private static List<Map<String, Object>> withMarketFacts(AnalysisState state) {
        List<Map<String, Object>> market = state.marketFacts();
        if (market.isEmpty()) {
            return state.facts();
        }
        List<Map<String, Object>> all = new ArrayList<>(state.facts());
        all.addAll(market);
        return all;
    }

    // ---------------------------------------------------------------- market

    @Override
    public Map<String, Object> market(AnalysisState state) {
        Map<String, Object> patch = new LinkedHashMap<>();
        Map<String, Object> plan = state.marketPlan();
        String code = textOf(plan.get("code"));
        String name = textOf(plan.get("name"));
        if (code.isBlank()) {
            patch.put(AnalysisState.MARKET_ERROR, "没能确定要查哪只股票");
            return patch;
        }
        String tool = "get_history".equals(textOf(plan.get("tool"))) ? "get_history" : "get_quote";
        int days = intOf(plan.get("days"), 20);

        // 模型选工具（增强路径）：选不出来、超时、参数不合法都静默回落规则调用
        String pickedBy = "rule";
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("code", code);
        if ("get_history".equals(tool)) {
            args.put("days", days);
        }
        try {
            java.util.Optional<MarketToolSelector.Picked> picked = marketTools.pick(state.question(), code, market.tools());
            if (picked.isPresent()) {
                tool = picked.get().tool();
                args = new LinkedHashMap<>(picked.get().arguments());
                pickedBy = "model";
            }
        } catch (RuntimeException e) {
            LOG.warn("模型选行情工具失败，改用规则：{}", e.toString());
        }

        try {
            MarketGateway.Snapshot snapshot = market.execute(tool, args, days);
            patch.put(AnalysisState.MARKET_TOOL, tool);
            patch.put(AnalysisState.MARKET_DATA, snapshot.data());
            patch.put(AnalysisState.MARKET_AS_OF, snapshot.asOf());
            patch.put(AnalysisState.MARKET_ERROR, "");
            patch.put(AnalysisState.MARKET_FACTS, factsOf(tool, snapshot, name));
            patch.put(AnalysisState.PLAN, List.of("行情：" + label(name, code) + " · " + tool
                    + "（" + ("model".equals(pickedBy) ? "模型选的工具" : "规则兜底") + "）"));
            bridge.marketTrace(state.runId(), new com.finagent.chat.MarketTrace(
                    tool, code, name, snapshot.asOf(), "",
                    ((List<?>) patch.get(AnalysisState.MARKET_FACTS)).size()));
            LOG.info("行情取数成功：tool={} code={} by={} asOf={}", tool, code, pickedBy, snapshot.asOf());
        } catch (MarketApiException e) {
            LOG.info("行情工具返回业务错误：code={} reason={}", code, e.getMessage());
            patch.put(AnalysisState.MARKET_ERROR, e.getMessage());
        } catch (MarketUnavailableException e) {
            LOG.warn("行情服务不可用：code={} reason={}", code, e.getMessage());
            patch.put(AnalysisState.MARKET_ERROR, "行情服务暂时不可用");
        } catch (RuntimeException e) {
            LOG.error("行情取数出现未预期错误：code={}", code, e);
            patch.put(AnalysisState.MARKET_ERROR, "行情服务异常");
        }
        if (!textOf(patch.get(AnalysisState.MARKET_ERROR)).isEmpty()) {
            bridge.marketTrace(state.runId(), new com.finagent.chat.MarketTrace(
                    tool, code, name, "", textOf(patch.get(AnalysisState.MARKET_ERROR)), 0));
        }
        return patch;
    }

    /** 行情事实行：形状与 compute 写的 FACTS 完全一致，只是 source 标成实时行情。 */
    private static List<Map<String, Object>> factsOf(String tool, MarketGateway.Snapshot snapshot, String name) {
        Map<String, Object> data = snapshot.data();
        String asOf = snapshot.asOf();
        String subject = name.isBlank() ? textOf(data.get("name")) : name;
        String evidence = "实时行情 " + asOf;
        List<Map<String, Object>> rows = new ArrayList<>();
        int no = 900;
        if ("get_history".equals(tool)) {
            List<Map<String, Object>> bars = barsOf(data);
            if (bars.isEmpty()) {
                return rows;
            }
            Map<String, Object> first = bars.get(0);
            Map<String, Object> last = bars.get(bars.size() - 1);
            double firstClose = dblOf(first.get("close"));
            double lastClose = dblOf(last.get("close"));
            double high = bars.stream().mapToDouble(b -> dblOf(b.get("high"))).max().orElse(0);
            double low = bars.stream().mapToDouble(b -> dblOf(b.get("low"))).min().orElse(0);
            double changePct = firstClose == 0 ? 0 : (lastClose - firstClose) / firstClose * 100;
            rows.add(factRow(no++, "收盘价", asOf.substring(0, 10), money(lastClose), "元", "", subject, evidence));
            rows.add(factRow(no++, "区间涨跌幅", textOf(last.get("date")), pct(changePct), "%", "", subject, evidence));
            rows.add(factRow(no++, "区间最高价", textOf(last.get("date")), money(high), "元", "", subject, evidence));
            rows.add(factRow(no++, "区间最低价", textOf(last.get("date")), money(low), "元", "", subject, evidence));
            return rows;
        }
        String period = asOf.substring(0, Math.min(10, asOf.length()));
        if (data.get("price") != null) {
            rows.add(factRow(no++, "股价", period, money(dblOf(data.get("price"))), "元", "", subject, evidence));
        }
        if (data.get("changePct") != null) {
            rows.add(factRow(no++, "涨跌幅", period, pct(dblOf(data.get("changePct"))), "%", "", subject, evidence));
        }
        if (data.get("totalMarketCap") != null) {
            long cap = (long) dblOf(data.get("totalMarketCap"));
            rows.add(factRow(no++, "总市值", period, String.valueOf(cap), "元", yi(cap), subject, evidence));
        }
        if (data.get("peTtm") != null) {
            rows.add(factRow(no++, "市盈率TTM", period, pct(dblOf(data.get("peTtm"))), "倍", "", subject, evidence));
        }
        if (data.get("pb") != null) {
            rows.add(factRow(no++, "市净率", period, pct(dblOf(data.get("pb"))), "倍", "", subject, evidence));
        }
        if (data.get("turnoverPct") != null) {
            rows.add(factRow(no++, "换手率", period, pct(dblOf(data.get("turnoverPct"))), "%", "", subject, evidence));
        }
        return rows;
    }

    @Override
    public Map<String, Object> answerMarket(AnalysisState state) {
        Map<String, Object> plan = state.marketPlan();
        String code = textOf(plan.get("code"));
        String name = textOf(plan.get("name"));
        String error = state.marketError();
        if (!error.isBlank()) {
            String answer = "暂时查不到" + label(name, code) + "的实时行情：" + error
                    + "。可以稍后再试；如果问题里还有财报要素，也可以先只看年报数据。";
            return Map.of(
                    AnalysisState.ANSWER, answer,
                    AnalysisState.WARNINGS, List.of("market_unavailable"),
                    AnalysisState.STATUS, AgentRun.STATUS_DONE);
        }
        Map<String, Object> data = state.marketData();
        String asOf = state.marketAsOf();
        String subject = name.isBlank() ? textOf(data.get("name")) : name;
        StringBuilder answer = new StringBuilder();
        // 走到这里的「混合问题」意味着检索已判证据不足（图里 market_fallback 边）：
        // 先把话说清楚，再给能给的行情，避免读者以为下面这些数字来自年报
        boolean partial = state.insufficient();
        if (partial) {
            answer.append("年报里没有找到能支撑这个问题的证据，下面只给实时行情：\n\n");
        }
        if ("get_history".equals(state.marketTool())) {
            List<Map<String, Object>> bars = barsOf(data);
            if (bars.isEmpty()) {
                answer.append("没有查到").append(label(subject, code)).append("的历史行情。");
            } else {
                Map<String, Object> first = bars.get(0);
                Map<String, Object> last = bars.get(bars.size() - 1);
                double firstClose = dblOf(first.get("close"));
                double lastClose = dblOf(last.get("close"));
                double changePct = firstClose == 0 ? 0 : (lastClose - firstClose) / firstClose * 100;
                double high = bars.stream().mapToDouble(b -> dblOf(b.get("high"))).max().orElse(0);
                double low = bars.stream().mapToDouble(b -> dblOf(b.get("low"))).min().orElse(0);
                answer.append(label(subject, code)).append("最近 ").append(bars.size()).append(" 个交易日：")
                        .append("最新收盘 ").append(money(lastClose)).append(" 元，")
                        .append("区间涨跌幅 ").append(pct(changePct)).append("%，")
                        .append("区间最高 ").append(money(high)).append(" 元 / 最低 ")
                        .append(money(low)).append(" 元。");
            }
        } else {
            answer.append(label(subject, code));
            if (data.get("price") != null) {
                answer.append(" 最新价 ").append(money(dblOf(data.get("price")))).append(" 元");
            }
            if (data.get("changePct") != null) {
                answer.append("，涨跌幅 ").append(pct(dblOf(data.get("changePct")))).append("%");
            }
            if (data.get("totalMarketCap") != null) {
                answer.append("，总市值 ").append(yi((long) dblOf(data.get("totalMarketCap"))));
            }
            if (data.get("peTtm") != null) {
                answer.append("，市盈率(TTM) ").append(pct(dblOf(data.get("peTtm")))).append(" 倍");
            }
            if (data.get("pb") != null) {
                answer.append("，市净率 ").append(pct(dblOf(data.get("pb")))).append(" 倍");
            }
            answer.append("。");
        }
        answer.append("\n\n数据时间：").append(readable(asOf)).append("，来源：东方财富公开行情。");
        List<String> warnings = partial
                ? List.of("market_live", "market_partial")
                : List.of("market_live");
        return Map.of(
                AnalysisState.ANSWER, answer.toString(),
                AnalysisState.WARNINGS, warnings,
                AnalysisState.STATUS, AgentRun.STATUS_DONE);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> barsOf(Map<String, Object> data) {
        Object bars = data.get("bars");
        if (!(bars instanceof List<?> list)) {
            return List.of();
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof Map<?, ?> map) {
                out.add((Map<String, Object>) map);
            }
        }
        return out;
    }

    private static Map<String, Object> factRow(int no, String metric, String period, String value,
                                               String unit, String display, String scope, String evidence) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("no", no);
        row.put("metric", metric);
        row.put("period", period == null ? "" : period);
        row.put("value", value);
        row.put("unit", unit);
        row.put("scope", scope == null ? "" : scope);
        row.put("evidence", evidence);
        row.put("origin", AnswerResult.ORIGIN_EXTRACTED);
        row.put("formula", "");
        row.put("display", display == null ? "" : display);
        row.put("note", "");
        row.put("source", AnswerResult.Fact.SOURCE_MARKET);
        return row;
    }

    private static String label(String name, String code) {
        if (name == null || name.isBlank()) {
            return code;
        }
        if (code == null || code.isBlank()) {
            return name;
        }
        return name + "（" + code + "）";
    }

    private static String money(double value) {
        return String.format(java.util.Locale.ROOT, "%.2f", value);
    }

    /** 比率/倍数保留两位；行情接口本身已放大 100 倍还原过，这里只负责格式。 */
    private static String pct(double value) {
        return String.format(java.util.Locale.ROOT, "%.2f", value);
    }

    /** 元 → 亿元（保留两位），只用于展示；白名单里同时有原始值与展示值。 */
    private static String yi(long yuan) {
        return java.math.BigDecimal.valueOf(yuan, 8).setScale(2, java.math.RoundingMode.HALF_UP) + " 亿元";
    }

    /** 2026-11-04T15:00:00+08:00 → 2026-11-04 15:00（北京时间）。 */
    private static String readable(String asOf) {
        if (asOf == null || asOf.isBlank()) {
            return "未知";
        }
        String text = asOf.replace('T', ' ');
        int plus = text.indexOf('+');
        if (plus > 0) {
            text = text.substring(0, plus);
        }
        if (text.length() > 16) {
            text = text.substring(0, 16);
        }
        return text + "（北京时间）";
    }

    private static String textOf(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private static int intOf(Object value, int fallback) {
        if (value instanceof Number n) {
            return n.intValue();
        }
        try {
            return value == null ? fallback : Integer.parseInt(String.valueOf(value).trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static double dblOf(Object value) {
        if (value instanceof Number n) {
            return n.doubleValue();
        }
        try {
            return value == null ? 0 : Double.parseDouble(String.valueOf(value).trim());
        } catch (NumberFormatException e) {
            return 0;
        }
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
        boolean offTopic = state.refuseReason().startsWith(OffTopicDetector.REASON_PREFIX);
        String answer = offTopic
                ? state.refuseReason()
                : state.refuseReason().isBlank()
                        ? "证据不足：检索到的内容里没有能支撑这个问题的信息。"
                        : "无法回答：" + state.refuseReason() + "。";
        String warning;
        if (offTopic) {
            warning = "off_topic";
        } else if (state.warnings().contains("scope_empty")) {
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
        return new MemoryContext(state.memoryText(), MemoryContext.Stats.EMPTY, List.of(), null);
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