package com.finagent.understanding;

import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 财务指标词典 —— 规则快路径的基础。
 *
 * <p>为什么用固定词典而不是完全依赖 LLM：§9.14 的第一层防线要求高频确定性问法
 * （证券代码 + 年份 + 指标名）<b>零延迟、零错误</b>地被识别。LLM 只处理规则覆盖不到的部分，
 * 二者是互补关系，不是替代关系。
 *
 * <p>为什么硬编码而不查数据库：指标名是<b>业务词汇表</b>，不是业务数据。
 * 放进库里只会多一次查询，还失去版本控制的好处。
 *
 * <p>⚠️ 列表<b>不完整</b>（真实财报有上百个指标）。本阶段先覆盖常见项，
 * 长尾由 LLM 兜住。
 */
@Component
public class MetricDictionary {

    /** 按长度降序排列，保证长词优先匹配。 */
    private static final List<String> METRICS = List.of(
            "归属于母公司股东的净利润",
            "扣除非经常性损益的净利润",
            "经营活动产生的现金流量净额",
            "投资活动产生的现金流量净额",
            "筹资活动产生的现金流量净额",
            "加权平均净资产收益率",
            "营业总收入", "营业收入", "营业成本", "营业利润", "利润总额", "净利润",
            "基本每股收益", "稀释每股收益", "每股收益",
            "资产负债率", "总资产", "净资产", "研发投入", "研发费用", "毛利率",
            "商誉", "应收账款", "存货", "货币资金");

    /**
     * 同义写法分组：同一件事在「用户嘴里」与「财报表头里」写法往往不同。
     *
     * <p><b>为什么需要它</b>：2026-10-31 评测抓到三题被<b>误判为证据不足</b> ——
     * 用户问「归母净利润」，表头写「归属于上市公司股东的净利润」；问「员工总数」，
     * 表里写「在职员工的数量合计」。字面 {@code contains} 判不出来，verify 的
     * 「指标是否出现在命中块里」就会返回 false，整题直接拒答，<b>而数值其实就在证据里</b>。
     *
     * <p><b>只做同义词，不做上下位词</b>：「净利润」与「归母净利润」是两个口径，
     * 合并会把「扣非净利润」也吞进来 —— 宁可漏判，不可错配。
     */
    private static final List<List<String>> VARIANTS = List.of(
            List.of("归属于母公司股东的净利润", "归属于上市公司股东的净利润",
                    "归属于母公司所有者的净利润", "归母净利润", "归母净利"),
            List.of("员工总数", "员工人数", "在职员工总数", "在职员工的数量合计", "在职员工人数"),
            List.of("经营活动产生的现金流量净额", "经营活动现金流量净额",
                    "经营活动现金净流量", "经营现金流净额"),
            List.of("总资产", "资产总额", "资产总计"),
            List.of("净资产", "所有者权益合计"),
            List.of("研发投入", "研发投入金额", "研发投入合计", "研发投入总额"));

    public List<String> all() {
        return METRICS;
    }

    /**
     * 找出文本里命中的全部指标名，按长度降序。
     *
     * <p>为什么按长度降序：避免「营业收入」在「营业总收入」之前被返回，
     * 也避免短词把长词的语义吃掉。
     */
    public List<String> findIn(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        return METRICS.stream()
                .filter(text::contains)
                .sorted((a, b) -> b.length() - a.length())
                .toList();
    }

    /**
     * 一个指标名的全部同义写法；词典里没有的（LLM 直抽的口语词）原样返回。
     *
     * <p>static 是因为它只读常量表：verify 的指标命中判定在节点里做，
     * 不想为了一个纯函数给节点再塞一个依赖。
     */
    public static List<String> variants(String metric) {
        if (metric == null || metric.isBlank()) {
            return List.of();
        }
        for (List<String> group : VARIANTS) {
            for (String name : group) {
                if (name.equals(metric)) {
                    return group;
                }
            }
        }
        return List.of(metric);
    }

    /** 例：{@code variantsOf("归母净利润")} → 整组写法。 */
    public List<String> variantsOf(String metric) {
        return variants(metric);
    }
}
