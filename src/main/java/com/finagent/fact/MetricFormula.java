package com.finagent.fact;

import java.util.List;

/**
 * 公式登记表（第一版五条：同比、结构占比、占比变动、收入贡献度、增量）。
 *
 * <p>为什么要有这张表：允许系统算数，就必须能回答「你凭什么这么算」。
 * 每条公式带口径说明，进提示词时前缀「系统计算」，进 facts 时带 formula 字段。
 *
 * <p><b>不做</b>毛利率 / 净利率 / ROE：分母口径有争议（券商没有制造业口径的营业成本），
 * 见 docs/阶段15-多路检索证据覆盖报告.md §6。
 */
public enum MetricFormula {

    YOY("同比增长率", "(本期 − 上期) / |上期| × 100", "%", "上期为负时用绝对值做分母"),

    SHARE("结构占比", "分项 / 合计 × 100", "%", "合计必须与分项同期、同口径"),

    SHARE_DELTA("占比变动", "本期占比 − 上期占比", "百分点", "输出用「百分点」，不是「%」"),

    CONTRIBUTION("收入贡献度", "(分项本期 − 分项上期) / (合计本期 − 合计上期) × 100", "%", "合计增量为 0 时跳过"),

    /**
     * 增量（本期 − 上期）。
     *
     * <p>为什么加这条：模型讲归因必然要说「增加了 44.63 亿元」，不给它算好，它就在正文里心算，
     * 于是每个增量都会触发「数字不在证据里」告警（2026-10-30 端到端实测 5 个）。
     * 与其禁止它说增量，不如把增量作为系统计算事实交给它。
     */
    DELTA("增长额", "本期 − 上期", "元", "与同比增长率同期、同口径");

    private final String label;
    private final String expression;
    private final String unit;
    private final String scope;

    MetricFormula(String label, String expression, String unit, String scope) {
        this.label = label;
        this.expression = expression;
        this.unit = unit;
        this.scope = scope;
    }

    public String label() {
        return label;
    }

    public String expression() {
        return expression;
    }

    public String unit() {
        return unit;
    }

    /** 口径说明：写进 ComputedFact 的 note，让人能复核。 */
    public String scope() {
        return scope;
    }

    public static List<String> labels() {
        return List.of(YOY.label, SHARE.label, SHARE_DELTA.label, CONTRIBUTION.label, DELTA.label);
    }
}
