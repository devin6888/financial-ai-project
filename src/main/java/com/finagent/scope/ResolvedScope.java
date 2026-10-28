package com.finagent.scope;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 解析后的作用域 —— 检索层唯一认的范围对象。
 *
 * <p><b>documentIds 是从 documents 派生的，不是独立字段。</b>
 * 两个字段各存一份的话，「往 documents 里加一份文档却忘了同步 documentIds」
 * 会让检索与引用看到两个不同的世界，而且不会报错。
 *
 * <p>空集合的含义随 kind 变化：CORPUS 为空表示「全库」；
 * DOCUMENTS / FILTER 为空则根本不该存在 —— {@link ScopeResolver} 会在构造前抛
 * {@link ScopeEmptyException}。这是阶段 4 的教训：静默放宽比报错危险得多。
 */
public record ResolvedScope(ScopeKind kind, List<DocMeta> documents,
                            String resolverVersion, String effectiveReason,
                            List<String> warnings,
                            /**
                             * 解析过程中 ACL 是否真的删掉过候选文档。
                             *
                             * <p>为什么需要它：检索层对 CORPUS 有一条优化——「全库」时不把
                             * 几百个 document id 拼进 IN 子句（否则优化器会放弃索引）。但有了 ACL
                             * 之后，「全库」不再等于「能看全」：analyst 的 CORPUS 只应取到被授权的那几份。
                             * 用这个标记把「没被 ACL 卡过」（可继续跳过 id 过滤）与「被卡过」
                             * （必须把 id 下推到 SQL）区分开，优化与安全都不丢。
                             */
                            boolean aclNarrowed) {

    /** 解析算法版本。算法改了要 +1，历史 trace 才能解释「当时为什么解析成这几份」。 */
    public static final String RESOLVER_VERSION = "scope-v1";

    public static final String REASON_SESSION = "SESSION";
    public static final String REASON_TURN_ONLY = "TURN_ONLY_EXTENSION";
    public static final String REASON_SESSION_EXTENSION = "SESSION_EXTENSION";

    public ResolvedScope {
        documents = documents == null ? List.of() : List.copyOf(documents);
        resolverVersion = resolverVersion == null ? RESOLVER_VERSION : resolverVersion;
        effectiveReason = effectiveReason == null ? REASON_SESSION : effectiveReason;
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }

    public static ResolvedScope of(ScopeKind kind, List<DocMeta> documents, String reason,
                                   List<String> warnings) {
        return new ResolvedScope(kind, documents, RESOLVER_VERSION, reason, warnings, false);
    }

    /** 标记「ACL 删掉过候选」。幂等：值没变时返回 this，避免无意义的新对象。 */
    public ResolvedScope withAclNarrowed(boolean narrowed) {
        if (this.aclNarrowed == narrowed) {
            return this;
        }
        return new ResolvedScope(kind, documents, resolverVersion, effectiveReason, warnings, narrowed);
    }

    public List<Long> documentIds() {
        return documents.stream().map(DocMeta::id).toList();
    }

    public boolean isEmpty() {
        return documents.isEmpty();
    }

    public int size() {
        return documents.size();
    }

    /** 涉及的公司（去空、去重）。QU 抽出的 companyId 必须落在这个集合里才允许当过滤条件。 */
    public Set<Long> companyIds() {
        Set<Long> out = new LinkedHashSet<>();
        for (DocMeta d : documents) {
            if (d.companyId() != null) {
                out.add(d.companyId());
            }
        }
        return out;
    }

    /** 涉及的年份集合（去空）。QU 抽出的年份不在其中时，过滤条件会被丢弃。 */
    public Set<Integer> docYears() {
        Set<Integer> out = new LinkedHashSet<>();
        for (DocMeta d : documents) {
            if (d.docYear() != null) {
                out.add(d.docYear());
            }
        }
        return out;
    }

    public DocMeta document(long id) {
        for (DocMeta d : documents) {
            if (d.id() == id) {
                return d;
            }
        }
        return null;
    }

    /**
     * 合并额外文档（扩展/加入会话）。已存在的不重复加，顺序保持「原有在前、扩展在后」。
     *
     * <p>纯函数：不做数据库查询。解析额外 id 由 {@link ScopeResolver#extend} 负责，
     * 这样这里可以脱离数据库单测。
     */
    public ResolvedScope withDocuments(List<DocMeta> extra, String reason) {
        if (extra == null || extra.isEmpty()) {
            return this;
        }
        Map<Long, DocMeta> merged = new LinkedHashMap<>();
        documents.forEach(d -> merged.put(d.id(), d));
        extra.forEach(d -> merged.putIfAbsent(d.id(), d));
        List<String> next = new ArrayList<>(warnings);
        next.add("scope_extended_to_" + merged.size());
        return new ResolvedScope(kind, new ArrayList<>(merged.values()), resolverVersion,
                reason == null ? effectiveReason : reason, next, aclNarrowed);
    }

    /** 供日志与 trace 的一行摘要。 */
    public String summary() {
        return kind + "[" + size() + "] " + documentIds() + " " + effectiveReason
                + (aclNarrowed ? " aclNarrowed" : "");
    }
}
