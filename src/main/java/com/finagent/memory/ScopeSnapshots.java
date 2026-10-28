package com.finagent.memory;

import com.finagent.scope.ChatScope;
import com.finagent.scope.ResolvedScope;
import com.finagent.scope.ScopeKind;
import com.finagent.scope.ScopeQuery;
import tools.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 作用域与快照的 JSON 形态。
 *
 * <p>为什么要一个专门的类：{@code conversation.scope_json} 与
 * {@code conversation_message.scope_snapshot} 是两种不同的东西 ——
 * 前者是「会话配置」（用户意图），后者是「这一轮实际解析出了什么」（确定集合 + 文档版本）。
 * 混用一个 DTO 会让人分不清哪个字段能改、哪个字段是历史留痕。
 */
public final class ScopeSnapshots {

    private ScopeSnapshots() {
    }

    /** scope_json 的形态。字段名刻意与 ChatScope 一致，便于前端直接回填下拉框。 */
    public record ScopePayload(String kind, List<Long> documentIds, ScopeQuery query) { }

    /**
     * scope_json 的序列化：手写字段表，不用 Jackson 反射。
     *
     * <p>为什么不用 {@code writeValueAsString(payloadOf(scope))}：{@link ScopeQuery} 上有个
     * {@code isEmpty()} 布尔 getter，Jackson 会把它当成属性吐成 {@code "empty": true} ——
     * 这条噪音会一直留在数据库的 jsonb 里，读回来又是个未知字段。手写四个字段最稳。
     */
    public static String scopeJson(ObjectMapper mapper, ChatScope scope) {
        ChatScope s = scope == null ? ChatScope.corpus() : scope;
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("kind", s.kind().name());
        root.put("documentIds", s.documentIds());
        root.put("query", queryMap(s.query()));
        return mapper.writeValueAsString(root);
    }

    private static Map<String, Object> queryMap(ScopeQuery query) {
        ScopeQuery q = query == null ? ScopeQuery.empty() : query;
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("companyCodes", q.companyCodes());
        map.put("years", q.years());
        map.put("docTypes", q.docTypes());
        map.put("industries", q.industries());
        return map;
    }

    public static ChatScope toScope(ScopePayload payload, ScopeKind fallback) {
        if (payload == null) {
            return new ChatScope(fallback, null, null);
        }
        return new ChatScope(parseKind(payload.kind(), fallback), payload.documentIds(), payload.query());
    }

    public static ScopeKind parseKind(String raw, ScopeKind fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            return ScopeKind.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return fallback;
        }
    }

    /**
     * 一轮消息的作用域快照：{@code {session, effective, chunkVersions, resolverVersion}}。
     *
     * <p>chunkVersions 的键是 documentId 的字符串形式（JSON 对象的键只能是字符串），
     * 值是这一轮实际读的 {@code active_chunk_version}。
     */
    public static String turnJson(ObjectMapper mapper, ChatScope session, ResolvedScope effective) {
        Map<String, Object> sessionPart = new LinkedHashMap<>();
        if (session == null) {
            sessionPart.put("kind", ScopeKind.CORPUS.name());
            sessionPart.put("documentIds", List.of());
        } else {
            sessionPart.put("kind", session.kind().name());
            sessionPart.put("documentIds", session.documentIds());
            sessionPart.put("query", queryMap(session.query()));
        }

        Map<String, Object> effectivePart = new LinkedHashMap<>();
        Map<String, Integer> chunkVersions = new LinkedHashMap<>();
        if (effective != null) {
            effectivePart.put("kind", effective.kind().name());
            effectivePart.put("documentIds", effective.documentIds());
            effectivePart.put("reason", effective.effectiveReason());
            effective.documents().forEach(d -> chunkVersions.put(String.valueOf(d.id()), d.chunkVersion()));
        }

        Map<String, Object> root = new LinkedHashMap<>();
        root.put("session", sessionPart);
        root.put("effective", effectivePart);
        root.put("chunkVersions", chunkVersions);
        root.put("resolverVersion", effective == null ? null : effective.resolverVersion());
        return mapper.writeValueAsString(root);
    }
}
