package com.finagent.retrieval;

import com.finagent.document.DocumentRepository;
import com.finagent.retrieval.rerank.RerankModel;
import com.finagent.retrieval.rerank.RerankResult;
import com.finagent.scope.DocMeta;
import com.finagent.scope.ResolvedScope;
import com.finagent.scope.ScopeKind;
import com.finagent.understanding.QueryType;
import com.finagent.understanding.QueryUnderstanding;
import com.finagent.understanding.RouterService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 混合检索编排（§9.3 第 3~7 步）。
 *
 * <p>顺序是硬要求：<b>先 RRF 融合，再 Rerank，最后才做上下文扩展</b>（§9.1）。
 * 反过来会让 Reranker 处理大段 Parent，成本爆炸且精度下降。
 *
 * <p><b>为什么 QU 放在这一层而不是让人先算好再传进来</b>：QU 的产物
 * （Sparse 走哪个模式、实体变成哪些过滤条件）与召回是强耦合的，
 * 拆开调用方迟早会传错一套组合。需要「无 QU」基线时用
 * {@link #retrieve(RetrievalRequest, QueryUnderstanding)} 显式传 {@code null}，
 * 那是一次可审计的降级，而不是忘了调。
 */
@Service
public class HybridRetriever {

    private static final Logger log = LoggerFactory.getLogger(HybridRetriever.class);

    private final RouterService router;
    private final DocumentRepository documents;
    private final DenseRetriever dense;
    private final SparseRetriever sparse;
    private final RerankModel rerank;

    public HybridRetriever(RouterService router, DocumentRepository documents,
                           DenseRetriever dense, SparseRetriever sparse, RerankModel rerank) {
        this.router = router;
        this.documents = documents;
        this.dense = dense;
        this.sparse = sparse;
        this.rerank = rerank;
    }

    /** 正常入口：自己做 Query Understanding，不限制作用域（全库）。 */
    public Result retrieve(RetrievalRequest req) {
        List<String> warnings = new ArrayList<>();
        return doRetrieve(req, understand(req.query(), warnings), null, warnings, RetrievalProgress.NONE);
    }

    /**
     * 带作用域的入口：自己做 Query Understanding。
     *
     * <p><b>为什么不叫 {@code retrieve(req, scope)} 重载</b>：那样
     * {@code retrieve(req, null)} 会在两个重载间产生歧义编译错误 ——
     * 而「传 null 表示无 QU 基线」是阶段 4 定下的显式用法，不能因为新重载而失效。
     */
    public Result retrieveInScope(RetrievalRequest req, ResolvedScope scope) {
        List<String> warnings = new ArrayList<>();
        return doRetrieve(req, understand(req.query(), warnings), scope, warnings, RetrievalProgress.NONE);
    }

    /**
     * 指定 QU 的入口：单元测试固定输入，以及阶段 6 的「有 QU vs 无 QU」对比。
     *
     * @param qu 传 {@code null} 表示不启用 QU（等价于 {@link QueryUnderstanding#none}）
     */
    public Result retrieve(RetrievalRequest req, QueryUnderstanding qu) {
        return retrieve(req, qu, null);
    }

    /**
     * 带作用域 + 指定 QU 的入口。
     *
     * @param qu    传 {@code null} 表示不启用 QU
     * @param scope 传 {@code null} 表示不限制作用域（全库）；CORPUS 类型等价于 null，
     *              但显式传进来能让 trace 记下「本轮是全库模式」
     */
    public Result retrieve(RetrievalRequest req, QueryUnderstanding qu, ResolvedScope scope) {
        return retrieve(req, qu, scope, RetrievalProgress.NONE);
    }

    /**
     * 带进度回调的入口（阶段 13，契约 C16）。
     *
     * <p>流式请求靠它知道「什么时候开始精排」—— 精排是一次外部 HTTP 调用，
     * 是用户等待感最强的一段；没有这个回调，后端只能笼统说一句「检索中」。
     *
     * @param progress 传 {@code null} 等价于 {@link RetrievalProgress#NONE}
     */
    public Result retrieve(RetrievalRequest req, QueryUnderstanding qu, ResolvedScope scope,
                           RetrievalProgress progress) {
        List<String> warnings = new ArrayList<>();
        return doRetrieve(req, qu == null ? QueryUnderstanding.none(req.query()) : qu, scope, warnings,
                progress == null ? RetrievalProgress.NONE : progress);
    }

    // ---------------------------------------------------------------- 编排

    private Result doRetrieve(RetrievalRequest req, QueryUnderstanding qu, ResolvedScope scope,
                              List<String> warnings, RetrievalProgress progress) {
        // 基础的过滤条件
        RetrievalFilters base = applyScope(req.filters(), scope, warnings);

        // 把 QU 的实体并进过滤条件。
        EffectiveFilters eff = mergeFilters(base, qu, scope, warnings);
        SparseQuery sq = sparseQuery(qu);

        long denseMs = 0;
        long sparseMs = 0;


        //-----dense 检索-----
        long t = System.currentTimeMillis();
        progress.onPhase("dense", 0);
        List<RetrievedChunk> denseHits = dense.retrieve(req.query(), eff.filters(), req.topDense());
        denseMs += System.currentTimeMillis() - t;


        //-----sparse 检索-----
        t = System.currentTimeMillis();
        progress.onPhase("sparse", 0);
        List<RetrievedChunk> sparseHits = sparse.retrieve(req.query(), sq.terms(), sq.mode(),
                eff.filters(), req.topSparse());
        if (sparseHits.isEmpty() && sq.mode() == SparseMode.EXACT) {
            // QU 抽到了实体，但文档里的写法不一样（「每股收益」vs「基本每股收益(元/股)」）。
            // 精确子串落空时退回词级召回，否则这一路等于白设。
            warnings.add("sparse_exact_empty_fallback");
            sparseHits = sparse.retrieve(req.query(), List.of(), SparseMode.FTS_OR,
                    eff.filters(), req.topSparse());
        }
        sparseMs += System.currentTimeMillis() - t;

        // QU 推出来的过滤条件是「猜测」，猜错（比如年份写成 2024 而库里有 2025）会
        // 静默清空结果。调用方给的显式过滤不走这条退路 —— 那是他明确要的。
        if (denseHits.isEmpty() && sparseHits.isEmpty() && eff.hasQuPart()) {
            warnings.add("qu_filter_empty_fallback");
            log.debug("QU 过滤条件导致结果为空，去掉 QU 部分重试：{}", req.query());
            RetrievalFilters relaxed = eff.base();

            t = System.currentTimeMillis();
            denseHits = dense.retrieve(req.query(), relaxed, req.topDense());
            denseMs += System.currentTimeMillis() - t;

            t = System.currentTimeMillis();
            sparseHits = sparse.retrieve(req.query(), sq.terms(), sq.mode(), relaxed, req.topSparse());
            if (sparseHits.isEmpty() && sq.mode() == SparseMode.EXACT) {
                sparseHits = sparse.retrieve(req.query(), List.of(), SparseMode.FTS_OR,
                        relaxed, req.topSparse());
            }
            sparseMs += System.currentTimeMillis() - t;
        }

        // 放松到只剩作用域仍然 0 条：说明范围内确实没有可用的证据。
        // 这时必须返回空，而不是把作用域也一起放开 —— 那等于回答了用户没问的那份文档。
        if (denseHits.isEmpty() && sparseHits.isEmpty() && scope != null && !scope.isEmpty()
                && scope.kind() != ScopeKind.CORPUS) {
            warnings.add("no_evidence_in_scope");
            log.debug("作用域内无证据：{}（范围 {} 份文档）", req.query(), scope.size());
        }

        // 路内去重：同一 chunk 被两路都召回时合并成一条，两路名次都保留 —— RRF 要的就是两个名次。
        progress.onPhase("fuse", denseHits.size() + sparseHits.size());
        t = System.currentTimeMillis();
        Map<Long, RetrievedChunk> merged = new LinkedHashMap<>();
        for (RetrievedChunk c : denseHits) {
            merged.put(c.chunkId(), c);
        }
        for (RetrievedChunk c : sparseHits) {
            merged.merge(c.chunkId(), c, (a, b) -> new RetrievedChunk(
                    a.chunkId(), a.parentId(), a.documentId(), a.chunkType(),
                    a.sectionPath(), a.title(), a.content(),
                    a.pageStart(), a.pageEnd(), a.sourceBlockIds(),
                    a.denseRank(), b.sparseRank(), 0.0, null));
        }
        List<RetrievedChunk> fused = RrfFusion.fuse(new ArrayList<>(merged.values()));
        int fusedCount = fused.size();
        if (quotaEnabled(qu, scope)) {
            List<RetrievedChunk> balanced = DocumentQuotaBalancer.apply(fused, req.fusionLimit(), scope.size());
            if (balanced != fused) {
                // 记进 trace：评测时要能区分「两份文档都有证据」是配额起的作用还是自然召回
                warnings.add("doc_quota_balanced_" + scope.size() + "_docs");
            }
            fused = balanced;
        }
        List<RetrievedChunk> candidates = fused.subList(0, Math.min(req.fusionLimit(), fused.size()));
        long fuseMs = System.currentTimeMillis() - t;

        // 截断到 fusionLimit 再送 Rerank：模型按条计费，50 条已是 §9.7 的上界
        t = System.currentTimeMillis();
        RerankStatus status = RerankStatus.SKIPPED;
        List<RetrievedChunk> result;
        int limit = Math.min(req.topRerank(), candidates.size());

        //-----rerank-----
        if (!req.useRerank()) {
            // 评测时关掉精排看 RRF 的原始排序 —— 这是「没启用」，不是「降级」
            result = candidates.subList(0, limit);
        } else if (candidates.isEmpty()) {
            warnings.add("no_candidates");
            result = List.of();
        } else {
            // 只在「真的有候选要精排」时才报这个阶段：没有候选却显示「正在精排」是骗人
            progress.onPhase(RetrievalProgress.PHASE_RERANK, candidates.size());
            try {
                List<String> texts = candidates.stream().map(HybridRetriever::rerankText).toList();
                List<RerankResult> rr = rerank.rerank(req.query(), texts, req.topRerank());
                List<RetrievedChunk> ranked = new ArrayList<>(rr.size());
                for (RerankResult r : rr) {
                    ranked.add(candidates.get(r.index()).withRerankScore(r.relevanceScore()));
                }
                result = ranked.size() > limit ? List.copyOf(ranked.subList(0, limit)) : ranked;
                status = RerankStatus.APPLIED;
            } catch (RuntimeException e) {
                // §9.7：降级到 RRF 结果并标记 —— 不能因为模型挂了让整个 API 不可用。
                // 这里只降级、不重试：重试会把一次 15 秒的超时放大成 30 秒。
                log.warn("Rerank 失败，降级到 RRF 结果：{}", e.toString());
                warnings.add("rerank_failed: " + e.getClass().getSimpleName());
                status = RerankStatus.FAILED;
                result = candidates.subList(0, limit);
            }
        }
        long rerankMs = System.currentTimeMillis() - t;

        RetrievalTrace trace = new RetrievalTrace(req.query(),
                qu.queryType(), qu.route(), qu.degraded(),
                denseHits.size(), sparseHits.size(), fusedCount, result.size(),
                status, rerank.modelName(),
                denseMs, sparseMs, fuseMs, rerankMs, warnings);
        log.debug("检索完成：{}", trace.summary());
        return new Result(result, trace);
    }

    // ---------------------------------------------------------------- QU

    private QueryUnderstanding understand(String query, List<String> warnings) {
        try {
            return router.understand(query);
        } catch (RuntimeException e) {
            // RouterService 内部已做四层降级，这里兜的是「降级代码本身炸了」
            log.warn("Query Understanding 抛异常，按无 QU 处理：{}", e.toString());
            warnings.add("qu_failed: " + e.getClass().getSimpleName());
            return QueryUnderstanding.failed(query, "qu_exception");
        }
    }

    /**
     * QU 决定 Sparse 走哪条路（§9.4）：
     * 抽到实体 → 精确子串（实测 6 条 vs 分词后的 86 条）；没抽到 → FTS（AND 优先、OR 回退）。
     */
    static SparseQuery sparseQuery(QueryUnderstanding qu) {
        if (!qu.hasEntities()) {
            return new SparseQuery(List.of(), SparseMode.FTS_OR);
        }
        List<String> terms = new ArrayList<>();
        if (qu.metrics() != null) {
            terms.addAll(qu.metrics());
        }
        if (qu.companyCodes() != null) {
            terms.addAll(qu.companyCodes());
        }
        return new SparseQuery(List.copyOf(terms), SparseMode.EXACT);
    }

    /**
     * 把 QU 的实体并进过滤条件。
     *
     * <p>两条刻意的保守规则，都是为了不让「猜测」清空结果：
     * <ul>
     *   <li><b>多公司不过滤</b>：问「茅台和东方财富谁毛利率高」时只留一家，等于直接答错；</li>
     *   <li><b>年份区间不过滤</b>：跨年对比同理，只有单一年份才当硬条件。</li>
     * </ul>
     * 显式传入的过滤条件<b>永远优先</b> —— 调用方比 QU 更清楚自己在限定什么。
     */
    EffectiveFilters mergeFilters(RetrievalFilters base, QueryUnderstanding qu, ResolvedScope scope,
                                  List<String> warnings) {
        Long companyId = base.companyId();
        boolean quCompany = false;
        List<String> codes = qu.companyCodes() == null ? List.of() : qu.companyCodes();

        if (companyId == null && codes.size() == 1) {
            Long id = documents.findCompanyIdByCode(codes.get(0));
            if (id == null) {
                // 代码存在但库里没有对应文档，硬过滤会得到 0 条 —— 宁可不过滤
                warnings.add("qu_company_not_found:" + codes.get(0));
            } else if (scope != null && scope.kind() != ScopeKind.CORPUS
                    && !scope.companyIds().isEmpty() && !scope.companyIds().contains(id)) {
                // 用户问的是范围外的公司（「对比东方财富和贵州茅台」但只选了东方财富的年报）：
                // 这条过滤条件会直接把结果清空，所以丢弃 —— 边界由作用域决定，
                // 「要不要扩范围」是交互层的问题（§2.4 的三选一），不是检索层能替用户决定的。
                warnings.add("qu_company_out_of_scope:" + codes.get(0));
            } else {
                companyId = id;
                quCompany = true;
            }
        } else if (companyId == null && codes.size() > 1) {
            warnings.add("qu_multi_company_no_filter");
        }

        Integer docYear = base.docYear();
        boolean quYear = false;
        int[] range = qu.yearRange();
        if (docYear == null && range != null) {
            if (range.length == 2 && range[0] == range[1]) {
                if (scope != null && scope.kind() != ScopeKind.CORPUS
                        && !scope.docYears().isEmpty() && !scope.docYears().contains(range[0])) {
                    // 专治「问 2023 但库里只有 2024/2025」：不丢弃的话过滤条件必然清空结果，
                    // 然后触发回退，用户会看到一堆跨年份的块却不知道为什么。
                    warnings.add("qu_year_out_of_scope:" + range[0]);
                } else {
                    docYear = range[0];
                    quYear = true;
                }
            } else {
                warnings.add("qu_year_range_no_filter");
            }
        }

        String docType = base.docType();
        boolean quDocType = false;
        if (docType == null && qu.hasDocType()) {
            if (scope != null && scope.kind() != ScopeKind.CORPUS && !scope.isEmpty()
                    && !scopeDocTypes(scope).isEmpty()
                    && !scopeDocTypes(scope).contains(qu.docType())) {
                warnings.add("qu_doc_type_out_of_scope:" + qu.docType());
            } else {
                docType = qu.docType();
                quDocType = true;
            }
        }

        RetrievalFilters merged = new RetrievalFilters(base.documentIds(), companyId, docYear, docType,
                base.sectionPathPrefix(), base.chunkVersion());
        return new EffectiveFilters(base, merged, quCompany, quYear, quDocType);
    }

    /**
     * 是否需要按文档配额保底。
     *
     * <p>两个条件必须同时成立：
     * <ol>
     *   <li>作用域里有多于一份文档（只有一份时配额没有意义）；</li>
     *   <li>问题本身是跨文档的：判为 COMPARISON，或者年份区间跨年。</li>
     * </ol>
     *
     * <p>为什么单文档问题不套配额：配额会把不相关的块塞进精排，白花精排的钱，
     * 还会把真正相关的那份文档的候选挤掉一部分。
     */
    static boolean quotaEnabled(QueryUnderstanding qu, ResolvedScope scope) {
        if (scope == null || scope.size() < 2) {
            return false;
        }
        if (qu.queryType() == QueryType.COMPARISON) {
            return true;
        }
        int[] range = qu.yearRange();
        return range != null && range.length == 2 && range[0] != range[1];
    }

    // ---------------------------------------------------------------- 作用域

    /**
     * SQL 里「空列表 = 不过滤」，所以「空集」不能也用一个空列表表示。
     * 文档 id 来自序列（从 1 起），-1 永不匹配，用它当空集的哨兵值。
     */
    static final long EMPTY_DOCUMENT_SET = -1L;

    /**
     * 把作用域套到调用方给的过滤条件上。
     *
     * <p>三种情况：
     * <ol>
     *   <li>无作用域 / CORPUS → 原样（全库不该把 654 个 id 拼进 IN，那会让优化器放弃索引）；</li>
     *   <li>调用方没指定文档 → 用作用域的 id；</li>
     *   <li>调用方指定了文档 → 取交集。作用域是<b>上界</b>，调用方的条件只能更窄不能更宽。</li>
     * </ol>
     */
    static RetrievalFilters applyScope(RetrievalFilters base, ResolvedScope scope, List<String> warnings) {
        // CORPUS 只有在「ACL 没删过候选」时才能跳过 id 过滤：全库 ≠ 能看全。
        // 阶段 8 之前 CORPUS 一定是全库，所以这条捷径当时等价于「不过滤」；
        // 有了 ACL 之后，被卡过的 CORPUS 必须把 id 下推到 SQL，否则 ACL 只影响提示词、不影响能取到哪些块。
        if (scope == null || scope.isEmpty()
                || (scope.kind() == ScopeKind.CORPUS && !scope.aclNarrowed())) {
            return base;
        }
        List<Long> ids = scope.documentIds();
        if (base.documentIds().isEmpty()) {
            return base.withDocumentIds(ids);
        }
        List<Long> keep = base.documentIds().stream().filter(ids::contains).toList();
        if (keep.size() == base.documentIds().size()) {
            return base;
        }
        warnings.add("scope_narrowed_caller_documents");
        return base.withDocumentIds(keep.isEmpty() ? List.of(EMPTY_DOCUMENT_SET) : keep);
    }

    /** 作用域内出现过的报告类型（用于判断 QU 的类型猜测是否越界）。 */
    static Set<String> scopeDocTypes(ResolvedScope scope) {
        Set<String> out = new LinkedHashSet<>();
        if (scope != null) {
            for (DocMeta d : scope.documents()) {
                if (d.docType() != null) {
                    out.add(d.docType());
                }
            }
        }
        return out;
    }

    // ---------------------------------------------------------------- Rerank

    /**
     * 送给 Reranker 的文本：章节路径 + 标题 + 正文。
     *
     * <p>只用 content 会丢掉「这段属于哪一章」的信号 —— 而财报里同一句话在不同章节含义不同。
     */
    static String rerankText(RetrievedChunk c) {
        StringBuilder sb = new StringBuilder();
        if (c.sectionPath() != null && !c.sectionPath().isBlank()) {
            sb.append(c.sectionPath()).append('\n');
        }
        if (c.title() != null && !c.title().isBlank()) {
            sb.append(c.title()).append('\n');
        }
        sb.append(c.content());
        return sb.toString();
    }

    /** Sparse 路的入参组合，抽出来是为了让它能被单独断言。 */
    record SparseQuery(List<String> terms, SparseMode mode) {
    }

    /**
     * 合并后的过滤条件，同时记住哪几项是 QU 猜的。
     *
     * @param base    调用方原始条件（去掉 QU 部分时回退到它）
     */
    record EffectiveFilters(RetrievalFilters base, RetrievalFilters filters,
                            boolean quCompany, boolean quYear, boolean quDocType) {

        boolean hasQuPart() {
            return quCompany || quYear || quDocType;
        }
    }

    public record Result(List<RetrievedChunk> chunks, RetrievalTrace trace) {
    }
}
