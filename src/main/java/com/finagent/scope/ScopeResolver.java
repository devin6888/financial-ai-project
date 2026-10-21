package com.finagent.scope;

import com.finagent.document.DocumentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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

    /** C 模式：全库。文档数为 0 说明知识库是空的，这是部署问题不是用户问题，但仍要报出来。 */
    public ResolvedScope resolveCorpus() {
        List<DocMeta> docs = documents.findRetrievable(null, null, null, null, null);
        if (docs.isEmpty()) {
            throw new ScopeEmptyException("知识库中没有可检索的文档", List.of("scope_corpus_empty"));
        }
        log.debug("作用域解析：CORPUS -> {} 份文档", docs.size());
        return ResolvedScope.of(ScopeKind.CORPUS, docs, ResolvedScope.REASON_SESSION, List.of());
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
            warnings.add("scope_unauthorized_" + (requested.size() - readable.size()));
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
        log.debug("作用域解析：DOCUMENTS {} -> {} 份", requested, docs.size());
        return ResolvedScope.of(ScopeKind.DOCUMENTS, docs, ResolvedScope.REASON_SESSION, warnings);
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
        List<DocMeta> docs = documents.findRetrievable(null, q.companyCodes(), q.years(),
                q.docTypes(), q.industries());
        if (docs.isEmpty()) {
            throw new ScopeEmptyException("筛选条件没有匹配到任何可检索文档：" + describe(q), warnings);
        }
        log.debug("作用域解析：FILTER {} -> {} 份", describe(q), docs.size());
        return ResolvedScope.of(ScopeKind.FILTER, docs, ResolvedScope.REASON_SESSION, warnings);
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
