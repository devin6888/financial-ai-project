package com.finagent.context;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 证据画像：这一轮证据里「有没有某类素材」（阶段 17，ST17 Task 1）。
 *
 * <p><b>为什么要有它</b>：章节计划不能凭「问题问了什么」就决定章节 ——
 * 问「分析下营收情况」时，如果证据里根本没有费用表，硬给一节「成本与费用」，
 * 模型只能写「该部分未披露」占位，这正是计划里明令禁止的「为凑结构编内容」。
 * 所以章节是否允许出现，由<b>证据</b>说了算。
 *
 * <p>判据刻意用「关键词 + 结构」而不是 LLM：这是一次布尔判断，
 * 判错的代价只是少一节或多一节，且必须能在单测里断言。
 *
 * @param hasCostTable        证据里有费用表（销售/管理/财务/研发费用，且是表格块）
 * @param hasOperatingMetrics 证据里有经营指标（交易额 / 保有规模 / 销售额 / 客户数 / 产品数）
 * @param hasMultiPeriod      证据里有多期数字（同块出现两个年份，或出现「同比」）
 */
public record EvidenceProfile(
        boolean hasCostTable,
        boolean hasOperatingMetrics,
        boolean hasMultiPeriod
) {

    public static final EvidenceProfile EMPTY = new EvidenceProfile(false, false, false);

    private static final List<String> COST_WORDS = List.of(
            "销售费用", "管理费用", "财务费用", "研发费用", "期间费用", "营业成本");

    private static final List<String> OPERATION_WORDS = List.of(
            "交易额", "保有规模", "销售额", "客户数", "用户数", "只基金", "上线", "托管");

    private static final Pattern YEAR = Pattern.compile("(19|20)\\d{2}");

    public static EvidenceProfile of(List<ContextBlock> blocks) {
        if (blocks == null || blocks.isEmpty()) {
            return EMPTY;
        }
        boolean cost = false;
        boolean operation = false;
        boolean multiPeriod = false;
        for (ContextBlock b : blocks) {
            String head = join(b.title(), b.caption(), b.sectionPath());
            String content = b.content() == null ? "" : b.content();
            if (b.isTable() && costWords(head + content) >= 2) {
                cost = true;
            }
            if (containsAny(head + content, OPERATION_WORDS)) {
                operation = true;
            }
            if (!multiPeriod && (content.contains("同比") || years(content) >= 2)) {
                multiPeriod = true;
            }
        }
        return new EvidenceProfile(cost, operation, multiPeriod);
    }

    /**
     * 表里出现了几个不同的费用科目。
     *
     * <p>为什么要求 ≥2 个（不是「出现任意一个」）：真链路里 Q1 只命中了「分行业成本表」
     * （里面只有「营业成本」一个词），于是「成本与费用」这一节被排进了计划，
     * 但证据里根本没有销售/管理/财务/研发费用的数字 —— 模型只能在那一节里写「未披露」。
     * 一节标题下面写「没有数据」，比少一节难看得多。
     */
    private static int costWords(String text) {
        int n = 0;
        for (String word : COST_WORDS) {
            if (text.contains(word)) {
                n++;
            }
        }
        return n;
    }

    /** 一个块都没有时也算「没有素材」，避免章节计划凭空生成。 */
    public boolean isEmpty() {
        return !hasCostTable && !hasOperatingMetrics && !hasMultiPeriod;
    }

    /** 给日志/测试看的短描述。 */
    public String describe() {
        return "cost=" + hasCostTable + " operation=" + hasOperatingMetrics + " multi=" + hasMultiPeriod;
    }

    private static int years(String text) {
        Matcher m = YEAR.matcher(text);
        int count = 0;
        java.util.Set<String> distinct = new java.util.HashSet<>();
        while (m.find() && count < 6) {
            distinct.add(m.group());
            count++;
        }
        return distinct.size();
    }

    private static String join(String... parts) {
        StringBuilder sb = new StringBuilder();
        for (String part : parts) {
            if (part != null && !part.isBlank()) {
                sb.append(part).append(' ');
            }
        }
        return sb.toString();
    }

    private static boolean containsAny(String text, List<String> words) {
        for (String w : words) {
            if (text.contains(w)) {
                return true;
            }
        }
        return false;
    }
}
