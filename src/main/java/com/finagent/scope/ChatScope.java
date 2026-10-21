package com.finagent.scope;

import java.util.List;
import java.util.Objects;

/**
 * 请求层面的作用域（用户传什么）。
 *
 * <p>与 {@link ResolvedScope} 的区别：这里是「意图」，那里是「解析后的确定集合」。
 * 意图可以含糊（比如 FILTER 没匹配到任何文档），确定集合不可以 ——
 * 解析不出来就要报错，不能带着含糊的意图去检索。
 */
public record ChatScope(ScopeKind kind, List<Long> documentIds, ScopeQuery query) {

    public ChatScope {
        kind = kind == null ? ScopeKind.CORPUS : kind;
        documentIds = documentIds == null ? List.of()
                : documentIds.stream().filter(Objects::nonNull).distinct().toList();
        query = query == null ? ScopeQuery.empty() : query;
    }

    public static ChatScope corpus() {
        return new ChatScope(ScopeKind.CORPUS, null, null);
    }

    public static ChatScope documents(List<Long> ids) {
        return new ChatScope(ScopeKind.DOCUMENTS, ids, null);
    }

    public static ChatScope documents(Long... ids) {
        return documents(ids == null ? List.of() : List.of(ids));
    }

    public static ChatScope filter(ScopeQuery query) {
        return new ChatScope(ScopeKind.FILTER, null, query);
    }
}
