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
}
