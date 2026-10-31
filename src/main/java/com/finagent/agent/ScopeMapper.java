package com.finagent.agent;

import com.finagent.scope.ChatScope;
import com.finagent.scope.ScopeKind;
import com.finagent.scope.ScopeQuery;
import com.finagent.understanding.QueryType;
import com.finagent.understanding.QueryUnderstanding;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 在「请求层面的 {@link ChatScope}」与「状态里的 JDK 键值」之间来回转换。
 *
 * <p>为什么状态里不直接存 {@code ChatScope}：C17 硬规则 1 只允许 JDK 类型；
 * 而且存「意图」而不是「解析后的集合」才能让 Task 6 的「重启后 resume」重新过一遍 ACL。
 */
public final class ScopeMapper {

    private ScopeMapper() {
    }

    /** 初始状态的六个作用域键。 */
    public static Map<String, Object> initialScope(ChatScope scope) {
        ChatScope s = scope == null ? ChatScope.corpus() : scope;
        ScopeQuery q = s.query();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put(AnalysisState.SCOPE_KIND, s.kind().name());
        out.put(AnalysisState.SCOPE_DOCUMENT_IDS, s.documentIds());
        out.put(AnalysisState.SCOPE_COMPANY_CODES, q.companyCodes());
        out.put(AnalysisState.SCOPE_YEARS, q.years());
        out.put(AnalysisState.SCOPE_DOC_TYPES, q.docTypes());
        return out;
    }

    /** 状态 → 请求层面作用域（每次检索前重新解析，等于重新过 ACL）。 */
    public static ChatScope toScope(AnalysisState state) {
        ScopeKind kind;
        try {
            kind = ScopeKind.valueOf(state.scopeKind());
        } catch (IllegalArgumentException e) {
            kind = ScopeKind.CORPUS;
        }
        if (kind == ScopeKind.DOCUMENTS) {
            return ChatScope.documents(state.scopeDocumentIds());
        }
        if (kind == ScopeKind.FILTER) {
            return ChatScope.filter(new ScopeQuery(
                    state.scopeCompanyCodes(), state.scopeYears(), state.scopeDocTypes(), List.of()));
        }
        return ChatScope.corpus();
    }

    /**
     * 状态 → {@link QueryUnderstanding}（检索与答案都还要用它）。
     *
     * <p>只还原会影响检索与 Prompt 的字段；{@code companyMentions} 留空 —— 公司代码在
     * understand 节点已经由 {@code CompanyResolver} 查库补好，检索阶段不再需要原文名字。
     */
    public static QueryUnderstanding toQuery(AnalysisState state) {
        QueryType type = QueryType.parse(state.queryType());
        if (type == null) {
            type = QueryType.SEMANTIC_EXPLANATION;
        }
        Integer from = state.yearFrom();
        Integer to = state.yearTo();
        int[] years = (from == null && to == null)
                ? null
                : new int[]{from == null ? 0 : from, to == null ? 0 : to};
        return new QueryUnderstanding(type, state.rewrittenQuery().isBlank()
                ? state.question() : state.rewrittenQuery(),
                state.companyCodes(), years, state.metrics(), false, false, null, false, "AGENT",
                false, false, List.of());
    }
}