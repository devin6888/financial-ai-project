package com.finagent.memory;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 结构化上下文的合并规则（设计 §11.10），纯函数、无 IO。
 *
 * <p>三条优先级，顺序不能调：
 * <ol>
 *   <li><b>用户显式给的</b>（本轮问题里说了、或 PATCH 里改了）→ 直接覆盖；</li>
 *   <li><b>模型从上下文推断的</b> → 只在现有为空时补进来，否则<b>不生效</b>并留 warning
 *       （推断错了会静默改掉用户设定的范围，比补不上更危险）；</li>
 *   <li><b>本轮没提到的</b> → 继承上一轮（尤其年份，「那净利润呢」显然还是同一个年度）。</li>
 * </ol>
 */
@Component
public class StructuredContextMerger {

    /** @param warnings 形如 context_inferred_ignored_company，写进 trace 便于解释「为什么没更新」 */
    public record Merged(Long companyId, Integer yearFrom, Integer yearTo, List<Long> documentIds,
                         String currentMetric, List<String> warnings) { }

    public Merged merge(ConversationContext existing, ContextDelta delta, boolean explicit) {
        Long baseCompany = existing == null ? null : existing.companyId();
        Integer baseFrom = existing == null ? null : existing.yearFrom();
        Integer baseTo = existing == null ? null : existing.yearTo();
        List<Long> baseDocs = existing == null ? List.of() : existing.documentIds();
        String baseMetric = existing == null ? null : existing.currentMetric();
        ContextDelta d = delta == null ? ContextDelta.EMPTY : delta;

        List<String> warnings = new ArrayList<>();

        Long company = baseCompany;
        if (d.companyId() != null) {
            if (explicit || baseCompany == null) {
                company = d.companyId();
            } else {
                warnings.add("context_inferred_ignored_company");
            }
        }

        Integer from = baseFrom;
        Integer to = baseTo;
        if (d.yearFrom() != null) {
            if (explicit || baseFrom == null) {
                from = d.yearFrom();
                to = d.yearTo() == null ? d.yearFrom() : d.yearTo();
            } else {
                warnings.add("context_inferred_ignored_years");
            }
        }

        List<Long> docs = baseDocs;
        if (!d.documentIds().isEmpty()) {
            if (explicit || baseDocs.isEmpty()) {
                docs = d.documentIds();
            } else {
                warnings.add("context_inferred_ignored_documents");
            }
        }

        String metric = baseMetric;
        if (d.currentMetric() != null && !d.currentMetric().isBlank()) {
            if (explicit || baseMetric == null || baseMetric.isBlank()) {
                metric = d.currentMetric().strip();
            } else {
                warnings.add("context_inferred_ignored_metric");
            }
        }

        return new Merged(company, from, to, List.copyOf(docs), metric, List.copyOf(warnings));
    }
}
