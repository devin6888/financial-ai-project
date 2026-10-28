package com.finagent.memory;

import java.util.List;

/**
 * {@code PATCH /api/conversations/{id}/context} 的请求体。
 *
 * <p>{@code clear=true} 的语义是「清空后按本体的非空字段重建」，不是「逐字段置空」。
 * 为什么这样设计：JSON 里「字段没传」与「字段传了 null」在 record 上分不开，
 * 与其引入三层 Optional，不如给一个明确的整体重置开关 —— 用户想改结构就整体重设，
 * 想微调就不带 clear。
 *
 * <p>三个宽松点，都是为了让客户端能省字段：
 * <ul>
 *   <li>{@code clear} 用 Boolean 而不是 boolean —— 缺省即 {@code false}，
 *       否则 Jackson 会因为「原始类型 creator 属性缺失」直接 400，一个空 body 就变成错误；</li>
 *   <li>{@code documentIds} 缺省归一成空列表，下游不必到处判 null；</li>
 *   <li>空白 {@code currentMetric} 归一成 null，避免把「」当成一个真指标存进库。</li>
 * </ul>
 */
public record ContextPatch(Long companyId, Integer yearFrom, Integer yearTo,
                           List<Long> documentIds, String currentMetric, Boolean clear) {

    public ContextPatch {
        documentIds = documentIds == null ? List.of() : List.copyOf(documentIds);
        currentMetric = currentMetric == null || currentMetric.isBlank() ? null : currentMetric.strip();
    }

    /** 三态 Boolean 的读法：只有明确的 true 才算清空。 */
    public boolean cleared() {
        return Boolean.TRUE.equals(clear);
    }
}
