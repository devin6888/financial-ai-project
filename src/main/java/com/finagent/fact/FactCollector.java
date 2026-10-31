package com.finagent.fact;

import com.finagent.context.ContextBlock;
import com.finagent.understanding.QueryUnderstanding;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 从上下文块里抽取「证据直给的事实」。
 *
 * <p>只在<b>表格块</b>上抽：正文里的数字没有列语义（分不清年份列与口径），强行抽必然出错。
 * 表格表头能唯一确定「列 → 年份」时才算，否则整张表跳过（规则见 {@link TableRowParser}）。
 *
 * <p>过滤逻辑：问题点了指标（如「营业总收入」）时，只保留<b>含该指标的整张表</b> ——
 * 表里的分项（分行业/分产品/分地区）正是归因与结构需要的，不能只留合计那一行。
 */
public final class FactCollector {

    /** 一张大表 20~30 行，两张表就够用；再多说明过滤失效了。 */
    public static final int MAX_REPORTED = 60;

    /** 表格形状不支持自动抽数（列＝季度、无年份列的单期表）时的告警前缀。 */
    public static final String WARNING_TABLE_SKIPPED = "facts_table_skipped:";

    private FactCollector() {
    }

    public record Result(List<ReportedFact> facts, List<String> warnings) {

        public static final Result EMPTY = new Result(List.of(), List.of());
    }

    public static Result collect(List<ContextBlock> blocks, QueryUnderstanding qu) {
        if (blocks == null || blocks.isEmpty()) {
            return Result.EMPTY;
        }
        List<String> wanted = wanted(qu);
        List<ReportedFact> facts = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        for (ContextBlock block : blocks) {
            if (!"TABLE".equalsIgnoreCase(block.chunkType())) {
                continue;
            }
            List<TableRowParser.Row> rows = TableRowParser.parse(block.content());
            if (rows.isEmpty()) {
                // 跳过 ≠ 解析失败：7247 是「列＝季度」、7248 是「无年份列的单期分行业表」，
                // 这两种形状我们故意不猜（猜错列就是把 2024 的数写成 2025），
                // 所以用 skipped 而不是 unparsed 命名 —— 告警要能让排障的人分清「没做」和「做坏了」。
                if (containsAny(block.content(), wanted)) {
                    warnings.add(WARNING_TABLE_SKIPPED + block.chunkId());
                }
                continue;
            }
            if (!qualifies(rows, wanted)) {
                continue;
            }
            for (TableRowParser.Row row : rows) {
                String metric = row.group() == null ? row.label() : row.group() + "/" + row.label();
                for (Map.Entry<Integer, TableRowParser.Cell> e : row.amounts().entrySet()) {
                    facts.add(new ReportedFact(metric, String.valueOf(e.getKey()), e.getValue().value(),
                            ReportedFact.UNIT_YUAN, e.getValue().raw(), block.chunkId(), block.evidenceNo()));
                }
                for (Map.Entry<Integer, TableRowParser.Cell> e : row.percents().entrySet()) {
                    facts.add(new ReportedFact(metric, String.valueOf(e.getKey()), e.getValue().value(),
                            ReportedFact.UNIT_PERCENT, e.getValue().raw(), block.chunkId(), block.evidenceNo()));
                }
                Integer latest = row.latestYear();
                Integer prev = row.previousYear();
                if (row.growth().value() != null && latest != null && prev != null) {
                    facts.add(new ReportedFact(metric, latest + "vs" + prev, row.growth().value(),
                            ReportedFact.UNIT_PERCENT, row.growth().raw(), block.chunkId(), block.evidenceNo()));
                }
                if (facts.size() >= MAX_REPORTED) {
                    return new Result(List.copyOf(facts), List.copyOf(warnings));
                }
            }
        }
        return new Result(List.copyOf(facts), List.copyOf(warnings));
    }

    private static List<String> wanted(QueryUnderstanding qu) {
        if (qu == null || qu.metrics() == null) {
            return List.of();
        }
        return qu.metrics().stream().filter(m -> m != null && !m.isBlank()).toList();
    }

    private static boolean qualifies(List<TableRowParser.Row> rows, List<String> wanted) {
        if (wanted.isEmpty()) {
            return true;
        }
        for (TableRowParser.Row row : rows) {
            if (row.isTotal()) {
                return true;
            }
            for (String metric : wanted) {
                if (row.label() != null && row.label().contains(metric)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean containsAny(String text, List<String> words) {
        if (text == null || words.isEmpty()) {
            return false;
        }
        for (String w : words) {
            if (text.contains(w)) {
                return true;
            }
        }
        return false;
    }
}
