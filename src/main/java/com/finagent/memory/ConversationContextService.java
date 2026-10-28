package com.finagent.memory;

import com.finagent.scope.DocMeta;
import com.finagent.scope.ResolvedScope;
import com.finagent.scope.ScopeKind;
import com.finagent.understanding.QueryUnderstanding;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * 结构化上下文：读、按轮合并、用户纠错。
 *
 * <p>写入时机很重要：<b>本轮回答成功返回后</b>才落库。原因有两个 ——
 * ① 抽取出来的东西先以内存态参与本轮检索，落库失败不该让这一轮不可用；
 * ② 只有「用户真的问过」，这个上下文才值得记住（半路抛异常的轮次不该改会话状态）。
 */
@Service
public class ConversationContextService {

    private static final Logger log = LoggerFactory.getLogger(ConversationContextService.class);

    private final ConversationContextRepository repository;
    private final StructuredContextMerger merger;
    private final MemoryEventRepository events;
    private final ObjectMapper mapper;

    public ConversationContextService(ConversationContextRepository repository,
                                      StructuredContextMerger merger,
                                      MemoryEventRepository events,
                                      ObjectMapper mapper) {
        this.repository = repository;
        this.merger = merger;
        this.events = events;
        this.mapper = mapper;
    }

    public Optional<ConversationContext> find(long conversationId) {
        return repository.find(conversationId);
    }

    /** 一轮问答之后调用：把 QU 的实体与作用域合并进会话上下文。 */
    @Transactional
    public ConversationContext applyTurn(Conversation conversation, QueryUnderstanding qu, ResolvedScope scope) {
        if (conversation == null) {
            return null;
        }
        ContextDelta delta = deltaOf(qu, scope);
        ConversationContext existing = repository.find(conversation.id()).orElse(null);
        if (delta.isEmpty()) {
            return existing;
        }
        // 本轮的实体是用户自己说的（或用户问题里明确写出），按「显式」处理
        StructuredContextMerger.Merged merged = merger.merge(existing, delta, true);
        return persist(conversation, existing, merged, "turn", null);
    }

    /** 用户显式纠错：clear 时先清空，再按非空字段重建。 */
    @Transactional
    public ConversationContext patch(Conversation conversation, ContextPatch request) {
        ConversationContext existing = repository.find(conversation.id()).orElse(null);
        ContextDelta delta = request == null ? ContextDelta.EMPTY
                : new ContextDelta(request.companyId(), request.yearFrom(), request.yearTo(),
                        request.documentIds(), request.currentMetric());
        ConversationContext base = request != null && request.cleared() ? null : existing;
        StructuredContextMerger.Merged merged = merger.merge(base, delta, true);
        return persist(conversation, existing, merged, "user_patch", null);
    }

    // ------------------------------------------------------------ 内部

    private ConversationContext persist(Conversation conversation, ConversationContext existing,
                                        StructuredContextMerger.Merged merged, String reason,
                                        String traceId) {
        if (unchanged(existing, merged)) {
            return existing;
        }
        ConversationContext saved = new ConversationContext(conversation.id(), conversation.tenantId(),
                merged.companyId(), null, null, merged.yearFrom(), merged.yearTo(),
                merged.documentIds(), merged.currentMetric(), null, null);
        repository.upsert(conversation.tenantId(), saved);
        events.insert(conversation.tenantId(), null, MemoryEventRepository.TARGET_CONTEXT,
                conversation.id(), MemoryEventRepository.ACTION_CONTEXT_UPDATE,
                jsonOf(existing), jsonOf(merged), reason, traceId);
        log.debug("会话 {} 结构化上下文更新：{}", conversation.id(), saved.render());
        return repository.find(conversation.id()).orElse(saved);
    }

    private static boolean unchanged(ConversationContext existing, StructuredContextMerger.Merged merged) {
        if (existing == null) {
            return merged.companyId() == null && merged.yearFrom() == null
                    && merged.documentIds().isEmpty()
                    && (merged.currentMetric() == null || merged.currentMetric().isBlank());
        }
        return Objects.equals(existing.companyId(), merged.companyId())
                && Objects.equals(existing.yearFrom(), merged.yearFrom())
                && Objects.equals(existing.yearTo(), merged.yearTo())
                && existing.documentIds().equals(merged.documentIds())
                && Objects.equals(existing.currentMetric(), merged.currentMetric());
    }

    private String jsonOf(ConversationContext existing) {
        return existing == null ? null : jsonOf(new StructuredContextMerger.Merged(existing.companyId(),
                existing.yearFrom(), existing.yearTo(), existing.documentIds(),
                existing.currentMetric(), List.of()));
    }

    private String jsonOf(StructuredContextMerger.Merged merged) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("companyId", merged.companyId());
        map.put("yearFrom", merged.yearFrom());
        map.put("yearTo", merged.yearTo());
        map.put("documentIds", merged.documentIds());
        map.put("currentMetric", merged.currentMetric());
        return mapper.writeValueAsString(map);
    }

    /**
     * 从 QU 与本轮作用域抽出可落库的字段。
     *
     * <p>公司 id 由 QU 的公司代码在作用域文档里反查：<b>只在作用域里出现过才记</b> ——
     * 用户随口提到的公司不该变成会话上下文，否则下一轮「那净利润呢」会被带偏。
     */
    static ContextDelta deltaOf(QueryUnderstanding qu, ResolvedScope scope) {
        Long companyId = null;
        if (qu != null && qu.companyCodes() != null && scope != null) {
            for (String code : qu.companyCodes()) {
                companyId = scope.documents().stream()
                        .filter(d -> code != null && code.equals(d.companyCode()))
                        .map(DocMeta::companyId)
                        .filter(Objects::nonNull)
                        .findFirst()
                        .orElse(null);
                if (companyId != null) {
                    break;
                }
            }
        }

        Integer from = null;
        Integer to = null;
        if (qu != null && qu.yearRange() != null && qu.yearRange().length >= 2 && qu.yearRange()[0] > 0) {
            from = qu.yearRange()[0];
            to = qu.yearRange()[1] > 0 ? qu.yearRange()[1] : qu.yearRange()[0];
        }

        List<Long> documents = scope != null && scope.kind() == ScopeKind.DOCUMENTS
                ? scope.documentIds() : List.of();

        String metric = qu != null && qu.metrics() != null && !qu.metrics().isEmpty()
                ? qu.metrics().get(0) : null;

        return new ContextDelta(companyId, from, to, documents, metric);
    }
}
