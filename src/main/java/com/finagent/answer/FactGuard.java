package com.finagent.answer;

import com.finagent.answer.AnswerDto.FactDto;
import com.finagent.context.ContextBlock;
import com.finagent.understanding.QueryUnderstanding;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 数字事实的守门人（D9 单文档取证）。
 *
 * <p>只做三件事，都是「同指标同期在不同报告里对不上」这一类现实问题：
 * <ol>
 *   <li>同一 metric + period 的 fact 来自多份文档 → 问题年份能唯一确定就用那份；</li>
 *   <li>确定不了 → 全留但置信降 low，记 fact_multi_doc_conflict；</li>
 *   <li>数值本身不同 → 再记 fact_value_mismatch，并在 note 里写明。</li>
 * </ol>
 * 不做单位换算、不做精度归一 —— 改了就是「替模型编数」。
 */
@Component
public class FactGuard {

    public static final String WARNING_YEAR_MISSING = "fact_year_missing:";

    public Result guard(List<FactDto> facts, List<ContextBlock> blocks, QueryUnderstanding qu) {
        if (facts == null || facts.isEmpty()) {
            return Result.EMPTY;
        }
        Map<Integer, ContextBlock> byNo = new LinkedHashMap<>();
        for (ContextBlock b : blocks) {
            byNo.put(b.evidenceNo(), b);
        }
        Integer questionYear = singleYear(qu);

        // 按「指标」分组，不按「指标+期间」：同一指标在 2023 与 2024 的两条 fact 必须放在一起，
        // 才可能用问题年份选出该留的那条。按期间分组会让它们互不相见，年份规则永远不触发。
        Map<String, List<FactDto>> groups = new LinkedHashMap<>();
        for (FactDto f : facts) {
            groups.computeIfAbsent(f.metric(), k -> new ArrayList<>()).add(f);
        }

        List<FactDto> kept = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        String cap = AnswerResult.CONFIDENCE_HIGH;
        StringBuilder note = new StringBuilder();

        for (List<FactDto> group : groups.values()) {
            // 先按问题年份筛（2024 的题只留 period=2024 的那些），筛不到才谈「冲突」
            List<FactDto> chosen = group;
            if (questionYear != null) {
                List<FactDto> matched = group.stream()
                        .filter(f -> matchesYear(f, questionYear))
                        .toList();
                if (!matched.isEmpty()) {
                    chosen = matched;
                }
            }
            // 冲突的最小单位是「同指标 + 同期」，不是「同指标」：
            // 2024 年报给 2024 的数、2025 年报给 2025 的数，这是对比题的正常形态，
            // 当成冲突会把每道对比题都降成 low（2026-10-22 写跨年覆盖测试时踩到）。
            Map<String, List<FactDto>> byPeriod = new LinkedHashMap<>();
            for (FactDto f : chosen) {
                byPeriod.computeIfAbsent(f.period() == null ? "" : f.period(), k -> new ArrayList<>()).add(f);
            }
            boolean multiDoc = false;
            boolean valueMismatch = false;
            for (List<FactDto> samePeriod : byPeriod.values()) {
                Set<Long> docs = documentsOf(samePeriod, byNo);
                if (docs.size() > 1) {
                    multiDoc = true;
                }
                Set<String> values = new LinkedHashSet<>();
                for (FactDto f : samePeriod) {
                    values.add(normalizeValue(f.value()));
                }
                if (docs.size() > 1 && values.size() > 1) {
                    valueMismatch = true;
                }
            }
            if (multiDoc) {
                cap = lowest(cap, AnswerResult.CONFIDENCE_LOW);
                addOnce(warnings, AnswerResult.WARNING_MULTI_DOC_CONFLICT);
                appendNote(note, "同一指标在多份报告中出现，无法判定应取哪一份");
            }
            if (valueMismatch) {
                addOnce(warnings, AnswerResult.WARNING_VALUE_MISMATCH);
                appendNote(note, "两份报告数值不一致");
            }
            kept.addAll(chosen);
        }
        // 对比题（跨年）必须每年都有 fact：阶段 5 实测「2024 vs 2025 对比」只产出了 2025 一条，
        // 前端表格会少一行，而 confidence 还是 high —— 这种「静默缺一半」必须变成可见的 warning。
        if (qu != null && qu.yearRange() != null && qu.yearRange().length == 2
                && qu.yearRange()[0] != qu.yearRange()[1]) {
            for (int year = qu.yearRange()[0]; year <= qu.yearRange()[1]; year++) {
                final int y = year;
                boolean present = kept.stream().anyMatch(f -> matchesYear(f, y));
                if (!present) {
                    addOnce(warnings, WARNING_YEAR_MISSING + y);
                    cap = lowest(cap, AnswerResult.CONFIDENCE_MEDIUM);
                }
            }
        }
        return new Result(List.copyOf(kept), cap, List.copyOf(warnings),
                note.isEmpty() ? null : note.toString());
    }

    private static Set<Long> documentsOf(List<FactDto> facts, Map<Integer, ContextBlock> byNo) {
        Set<Long> docs = new LinkedHashSet<>();
        for (FactDto f : facts) {
            ContextBlock b = byNo.get(evidenceNo(f.evidence()));
            if (b != null) {
                docs.add(b.documentId());
            }
        }
        return docs;
    }

    private static Integer singleYear(QueryUnderstanding qu) {
        if (qu == null || qu.yearRange() == null || qu.yearRange().length != 2) {
            return null;
        }
        return qu.yearRange()[0] == qu.yearRange()[1] ? qu.yearRange()[0] : null;
    }

    private static boolean matchesYear(FactDto f, int year) {
        String period = f.period();
        if (period == null) {
            return false;
        }
        return period.contains(String.valueOf(year));
    }

    /** "E12" / "[E12]" / "12" / "E12,E13" → 12（取第一个能解析的）。 */
    static Integer evidenceNo(String evidence) {
        if (evidence == null) {
            return null;
        }
        StringBuilder digits = new StringBuilder();
        for (int i = 0; i < evidence.length(); i++) {
            char c = evidence.charAt(i);
            if (Character.isDigit(c)) {
                digits.append(c);
            } else if (digits.length() > 0) {
                break;
            }
        }
        if (digits.isEmpty()) {
            return null;
        }
        try {
            return Integer.parseInt(digits.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 只用于比较：去掉千分位与空格，保留原始字符串给答案。 */
    private static String normalizeValue(String v) {
        return v == null ? "" : v.replace(",", "").replace(" ", "").trim();
    }

    private static String lowest(String a, String b) {
        return rank(a) <= rank(b) ? a : b;
    }

    private static int rank(String confidence) {
        if (confidence == null) {
            return 0;
        }
        return switch (confidence) {
            case AnswerResult.CONFIDENCE_HIGH -> 3;
            case AnswerResult.CONFIDENCE_MEDIUM -> 2;
            case AnswerResult.CONFIDENCE_LOW -> 1;
            default -> 0;
        };
    }

    private static void addOnce(List<String> list, String value) {
        if (!list.contains(value)) {
            list.add(value);
        }
    }

    private static void appendNote(StringBuilder note, String text) {
        if (note.length() > 0) {
            note.append("；");
        }
        note.append(text);
    }

    /**
     * @param confidenceCap 置信上限：high 表示没发现问题
     * @param note          额外说明，null 表示没有
     */
    public record Result(List<FactDto> facts, String confidenceCap, List<String> warnings, String note) {
        public static final Result EMPTY = new Result(List.of(), AnswerResult.CONFIDENCE_HIGH, List.of(), null);
    }
}
