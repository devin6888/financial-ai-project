package com.finagent.scope;

import java.util.List;

/**
 * B 模式的筛选条件。每个字段为空表示「不限」。
 *
 * <p>四个条件之间是 <b>AND</b>：选了「东方财富 + 2024」就要同时满足。
 *
 * <p>为什么允许四个全空：D10 定的是「报告类型默认全部类型，由措辞收窄」。
 * 全空的 FILTER 与 CORPUS 语义上等价，但 kind 仍保留 FILTER ——
 * trace 里要能看出「用户是主动选了筛选、只是没填条件」。
 */
public record ScopeQuery(List<String> companyCodes, List<Integer> years,
                         List<String> docTypes, List<String> industries) {

    public ScopeQuery {
        companyCodes = normalizeText(companyCodes);
        years = years == null ? List.of() : years.stream().filter(y -> y != null).distinct().toList();
        docTypes = normalizeText(docTypes);
        industries = normalizeText(industries);
    }

    public static ScopeQuery empty() {
        return new ScopeQuery(null, null, null, null);
    }

    /** 是否一个条件都没给。 */
    public boolean isEmpty() {
        return companyCodes.isEmpty() && years.isEmpty()
                && docTypes.isEmpty() && industries.isEmpty();
    }

    private static List<String> normalizeText(List<String> in) {
        if (in == null) {
            return List.of();
        }
        return in.stream()
                .filter(s -> s != null && !s.isBlank())
                .map(String::strip)
                .distinct()
                .toList();
    }
}
