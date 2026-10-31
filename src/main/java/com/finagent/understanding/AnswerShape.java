package com.finagent.understanding;

import java.util.ArrayList;
import java.util.regex.Pattern;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * 答案形态：问题「要什么样的答案」（ST16 Task 1）。
 *
 * <p><b>为什么需要它</b>：同一份证据，问「多少」要一个数，问「为什么」要归因，问「结构」要占比。
 * 提示词里不写结构要求时，模型永远选最省力的那种 —— 复述表格。2026-10-30 与腾讯 ima 的同题对照里，
 * 我们的答案数字全对却没有一句归因，根因就在这里（ST16 §1.1）。
 *
 * <p><b>为什么用规则而不是让模型判</b>：这是「问题里有没有这些词」的确定性判断，
 * 一次 LLM 调用既慢又可能漂移；而它只影响提示词里的一段要求，判错的代价很低。
 * 同理也不把「增长 / 下降」当触发词 —— 「分析增长的原因」要的是归因，不是趋势。
 */
public enum AnswerShape {

    /** 要原因：为什么涨 / 什么驱动 / 归因。 */
    ATTRIBUTION("归因"),

    /** 要结构：分行业、分产品、占比、构成。 */
    BREAKDOWN("结构"),

    /** 要对比：两期并列、同比、变化。 */
    COMPARISON("对比");

    /** 词表刻意保守：宁可漏判（少一段要求），不要误判（逼模型编归因）。 */
    private static final List<String> ATTRIBUTION_WORDS = List.of(
            "原因", "为什么", "为何", "驱动", "因素", "归因", "得益于", "受益于", "拉动", "带动", "主因");

    private static final List<String> BREAKDOWN_WORDS = List.of(
            "结构", "构成", "占比", "分行业", "分产品", "分地区", "明细", "拆开", "板块");

    private static final List<String> COMPARISON_WORDS = List.of(
            "对比", "相比", "同比", "环比", "较", "差异");

    /**
     * 「变化」二字本身不构成对比诉求，要看它在问什么。
     *
     * <p>2026-10-31 评测 Q18 抓到：「2024年公司应对行业变化的措施是什么？」被判成 COMPARISON，
     * 提示词于是要求「两个期间并列、给出变化量」，模型看着一堆定性的应对措施表述直接拒答 ——
     * 它被要求回答一个没人问的问题。这里只认「问变化本身」的几种说法。
     */
    private static final Pattern CHANGE_ASK =
            Pattern.compile("(有什么|有何|哪些|什么|多大|多少)变化|变化(了)?(多少|多大|几何|率|幅度|趋势|情况)");

    private final String label;

    AnswerShape(String label) {
        this.label = label;
    }

    /** 中文短名，用于计划卡片与日志（不要拿枚举名直接给用户看）。 */
    public String label() {
        return label;
    }

    /**
     * 判定问题要哪些形态。
     *
     * @param question  用户原话（null / 空白返回空集，不抛异常）
     * @param yearRange Query Understanding 的年份区间，可为 null；跨年时补一个 COMPARISON
     *                  —— 「2024 和 2025 年的营业总收入」这种问句里没有「对比」两个字，但答案必须是两期并列
     */
    public static EnumSet<AnswerShape> detect(String question, int[] yearRange) {
        EnumSet<AnswerShape> shapes = EnumSet.noneOf(AnswerShape.class);
        if (question != null && !question.isBlank()) {
            String q = question.strip();
            if (containsAny(q, ATTRIBUTION_WORDS)) {
                shapes.add(ATTRIBUTION);
            }
            if (containsAny(q, BREAKDOWN_WORDS)) {
                shapes.add(BREAKDOWN);
            }
            if (containsAny(q, COMPARISON_WORDS) || CHANGE_ASK.matcher(q).find()) {
                shapes.add(COMPARISON);
            }
        }
        if (spansYears(yearRange)) {
            shapes.add(COMPARISON);
        }
        return shapes;
    }

    public static EnumSet<AnswerShape> detect(String question) {
        return detect(question, null);
    }

    private static boolean spansYears(int[] range) {
        // 长度不足或 to <= from 都表示「没有跨年」（单年问题由 COMPARISON_WORDS 负责）
        return range != null && range.length >= 2 && range[0] > 0 && range[1] > range[0];
    }

    private static boolean containsAny(String text, List<String> words) {
        for (String word : words) {
            if (text.contains(word)) {
                return true;
            }
        }
        return false;
    }

    /** 状态里只放 JDK 类型（C17 硬规则 1），所以写状态前先转成名字列表。 */
    public static List<String> names(Set<AnswerShape> shapes) {
        if (shapes == null || shapes.isEmpty()) {
            return List.of();
        }
        List<String> out = new ArrayList<>(shapes.size());
        for (AnswerShape shape : shapes) {
            out.add(shape.name());
        }
        return List.copyOf(out);
    }

    /** 读取状态里的名字列表；不认识的名字直接跳过（旧检查点里可能有已删掉的形态）。 */
    public static EnumSet<AnswerShape> parse(List<String> names) {
        EnumSet<AnswerShape> shapes = EnumSet.noneOf(AnswerShape.class);
        if (names == null) {
            return shapes;
        }
        for (String name : names) {
            if (name == null || name.isBlank()) {
                continue;
            }
            try {
                shapes.add(valueOf(name.strip().toUpperCase(java.util.Locale.ROOT)));
            } catch (IllegalArgumentException ignored) {
                // 单个坏值不该炸掉整轮推理（与 QueryType.parse 的容错口径一致）
            }
        }
        return shapes;
    }
}
