package com.finagent.scope;

import com.finagent.document.DocumentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 把请求里的"意图"翻译成确定的文档集合。
 *
 * <p>规则只有一条是硬的：<b>解析不出来就报错，绝不静默放宽成全库。</b>
 * 阶段 4 的教训是 {@code qu_filter_empty_fallback} 会把范围悄悄扩大 ——
 * 检索那边放宽还能说是"召回优化"，作用域这边放宽等于回答了用户没问的问题。
 *
 * <p>两种"空"必须分开：
 * <ul>
 *   <li>A 模式选了 3 份，2 份还没解析完 → 剩 1 份，警告后继续（用户能理解）；</li>
 *   <li>A 模式选了 3 份，1 份都不剩 / B 模式条件是"2023 年"但库里没这份 → 报错（用户无法理解为什么答非所问）。</li>
 * </ul>
 */
@Component
public class ScopeResolver {

    private static final Logger log = LoggerFactory.getLogger(ScopeResolver.class);

    private final DocumentRepository documents;
    private final ScopeAuthorizer authorizer;
    private final ScopeProperties properties;

    public ScopeResolver(DocumentRepository documents, ScopeAuthorizer authorizer,
                         ScopeProperties properties) {
        this.documents = documents;
        this.authorizer = authorizer;
        this.properties = properties;
    }

    /**
     * 解析本轮作用域。
     *
     * @param scope 用户传入的作用域；为 null 等价于 {@link ChatScope#corpus()}
     * @throws ScopeEmptyException 解析出 0 份可检索文档
     */
    public ResolvedScope resolve(ChatScope scope) {
        ChatScope request = scope == null ? ChatScope.corpus() : scope;
        return switch (request.kind()) {
            case DOCUMENTS -> resolveDocuments(request.documentIds());
            case FILTER -> resolveFilter(request.query());
            case CORPUS -> resolveCorpus();
        };
    }

    /**
     * C 模式：全库。
     *
     * <p>「全库」指<b>当前账号能看到的全库</b>：先取可检索文档，再过 ACL。
     * RLS 只保证不跨租户，同一租户内谁能看哪份文档由 ACL 决定，所以这一步不能省。
     *
     * <p>过滤后为空要报错而不是返回空列表：空列表在检索层的语义是「不过滤 = 全库」，
     * 静默放宽比报错危险得多（与 {@link ScopeEmptyException} 的注释同一条理由）。
     */
    public ResolvedScope resolveCorpus() {
        List<DocMeta> candidates = documents.findRetrievable(null, null, null, null, null);
        if (candidates.isEmpty()) {
            throw new ScopeEmptyException("知识库中没有可检索的文档", List.of("scope_corpus_empty"));
        }
        List<String> warnings = new ArrayList<>();
        List<DocMeta> docs = aclFilter(candidates, warnings);
        if (docs.isEmpty()) {
            warnings.add("scope_acl_filtered_all");
            throw new ScopeEmptyException("知识库中没有当前账号可检索的文档", warnings);
        }
        boolean narrowed = docs.size() < candidates.size();
        log.debug("作用域解析：CORPUS -> {} 份文档（候选 {}，ACL 收窄={}）",
                docs.size(), candidates.size(), narrowed);
        return ResolvedScope.of(ScopeKind.CORPUS, docs, ResolvedScope.REASON_SESSION, warnings)
                .withAclNarrowed(narrowed);
    }

    /**
     * A 模式：用户直接点选。
     *
     * <p>顺序按用户给的顺序保留 —— 用户勾选的第 1 份通常是他心里的"主证据"，
     * 后续配额与引用排序会用到这个信号，而 SQL 的 ORDER BY 是 doc_year DESC。
     */
    public ResolvedScope resolveDocuments(List<Long> requestedIds) {
        List<Long> requested = requestedIds == null ? List.of() : requestedIds;
        if (requested.isEmpty()) {
            throw new ScopeEmptyException("未选择任何文档", List.of("scope_documents_empty"));
        }

        List<Long> readable = authorizer.filterReadable(requested);
        List<String> warnings = new ArrayList<>();
        if (readable.size() < requested.size()) {
            // 同 aclFilter：警告里不带条数，避免把「有多少份看不见」写进响应正文
            log.debug("请求的 {} 份文档中 {} 份无权限", requested.size(), requested.size() - readable.size());
            warnings.add("scope_unauthorized");
        }

        Map<Long, DocumentRepository.DocStatus> status = new LinkedHashMap<>();
        for (DocumentRepository.DocStatus s : documents.findStatusByIds(readable)) {
            status.put(s.id(), s);
        }
        for (Long id : readable) {
            DocumentRepository.DocStatus s = status.get(id);
            if (s == null) {
                warnings.add("scope_unknown_document_" + id);
            } else if (!s.retrievable()) {
                warnings.add("scope_not_retrievable_" + id + "_" + s.parseStatus());
            }
        }

        Map<Long, DocMeta> byId = new LinkedHashMap<>();
        for (DocMeta d : documents.findRetrievable(readable, null, null, null, null)) {
            byId.put(d.id(), d);
        }
        List<DocMeta> docs = new ArrayList<>();
        for (Long id : readable) {
            DocMeta d = byId.get(id);
            if (d != null) {
                docs.add(d);
            }
        }
        if (docs.isEmpty()) {
            throw new ScopeEmptyException(
                    "所选文档均不可检索（未解析完成或不存在）：" + readable, warnings);
        }
        if (docs.size() > properties.getDocumentsSoftLimit()) {
            warnings.add("scope_over_soft_limit_" + docs.size() + "_gt_" + properties.getDocumentsSoftLimit());
        }
        boolean narrowed = readable.size() < requested.size();
        log.debug("作用域解析：DOCUMENTS {} -> {} 份（ACL 收窄={}）", requested, docs.size(), narrowed);
        return ResolvedScope.of(ScopeKind.DOCUMENTS, docs, ResolvedScope.REASON_SESSION, warnings)
                .withAclNarrowed(narrowed);
    }

    /**
     * B 模式：条件筛选。
     *
     * <p>条件全空时不报错也不返回空 —— 按 D10（报告类型默认全部）推导，
     * "打开筛选但没填条件"的用户意图就是全库，只是他能看到条件面板。
     */
    public ResolvedScope resolveFilter(ScopeQuery query) {
        ScopeQuery q = query == null ? ScopeQuery.empty() : query;
        List<String> warnings = new ArrayList<>();
        if (q.isEmpty()) {
            warnings.add("scope_filter_empty_all_corpus");
        }
        List<DocMeta> candidates = documents.findRetrievable(null, q.companyCodes(), q.years(),
                q.docTypes(), q.industries());
        if (candidates.isEmpty()) {
            throw new ScopeEmptyException("筛选条件没有匹配到任何可检索文档：" + describe(q), warnings);
        }
        List<DocMeta> docs = aclFilter(candidates, warnings);
        if (docs.isEmpty()) {
            warnings.add("scope_acl_filtered_all");
            throw new ScopeEmptyException("筛选条件匹配到的文档当前账号都不可读：" + describe(q), warnings);
        }
        boolean narrowed = docs.size() < candidates.size();
        log.debug("作用域解析：FILTER {} -> {} 份（候选 {}，ACL 收窄={}）",
                describe(q), docs.size(), candidates.size(), narrowed);
        return ResolvedScope.of(ScopeKind.FILTER, docs, ResolvedScope.REASON_SESSION, warnings)
                .withAclNarrowed(narrowed);
    }

    /**
     * 扩展当前作用域（设计文档 §2.4 的"仅本轮使用 / 加入当前会话"）。
     *
     * <p>两种情况走同一个方法：turnOnly=true 返回的作用域只在这一轮用，不写回会话；
     * false 则由调用方把结果存回会话。解析逻辑完全一样，差别只在外层存不存。
     *
     * <p>扩展出去的文档同样要过权限和可检索性 —— 否则"仅本轮使用"会变成绕过 ACL 的后门。
     */
    public ResolvedScope extend(ResolvedScope current, List<Long> extraIds, boolean turnOnly) {
        if (current == null) {
            throw new IllegalArgumentException("current scope must not be null");
        }
        List<Long> extra = extraIds == null ? List.of() : extraIds;
        List<Long> present = current.documentIds();
        List<Long> needed = extra.stream().filter(id -> !present.contains(id)).toList();
        if (needed.isEmpty()) {
            return current;
        }
        List<Long> readable = authorizer.filterReadable(needed);
        List<DocMeta> added = documents.findRetrievable(readable, null, null, null, null);
        if (added.isEmpty()) {
            return current;
        }
        String reason = turnOnly ? ResolvedScope.REASON_TURN_ONLY : ResolvedScope.REASON_SESSION_EXTENSION;
        log.debug("作用域扩展：{} + {} -> {}", present, added.size(), reason);
        return current.withDocuments(added, reason);
    }

    /**
     * 把候选文档按 ACL 过一遍，保持原有顺序。
     *
     * <p>为什么放在「取到可检索文档之后」而不是把 ACL 条件下推进 {@code findRetrievable}：
     * 现在租户内只有个位数文档，先取再过滤最直白；文档数上到几百时再把 ACL 子查询下推
     * （见 ST8 §7 R3 的已知限制）。
     */
    private List<DocMeta> aclFilter(List<DocMeta> candidates, List<String> warnings) {
        List<Long> ids = candidates.stream().map(DocMeta::id).toList();
        Set<Long> readable = new LinkedHashSet<>(authorizer.filterReadable(ids));
        List<DocMeta> kept = candidates.stream().filter(d -> readable.contains(d.id())).toList();
        if (kept.size() < candidates.size()) {
            // 警告里故意不带数字：带数字等于告诉调用方「你还有 N 份看不到的文档」，
            // 属于「存在性」泄漏。精确条数写服务端日志，给运维看。
            log.debug("ACL 隐藏了 {} 份文档（候选 {} 条）", candidates.size() - kept.size(), candidates.size());
            warnings.add("scope_acl_hidden");
        }
        return kept;
    }

    private static String describe(ScopeQuery q) {
        List<String> parts = new ArrayList<>();
        if (!q.companyCodes().isEmpty()) {
            parts.add("公司=" + q.companyCodes());
        }
        if (!q.years().isEmpty()) {
            parts.add("年份=" + q.years());
        }
        if (!q.docTypes().isEmpty()) {
            parts.add("类型=" + q.docTypes());
        }
        if (!q.industries().isEmpty()) {
            parts.add("行业=" + q.industries());
        }
        return parts.isEmpty() ? "全部" : String.join(", ", parts);
    }
}
