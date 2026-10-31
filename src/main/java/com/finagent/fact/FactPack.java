package com.finagent.fact;

import com.finagent.answer.AnswerResult;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 事实包：进入提示词的两段事实 + 合并进 {@code AnswerResult.facts} 的行 + 允许的数字白名单。
 *
 * <p>为什么三件事放一处：提示词里说「[F3] 是系统算的」、facts 里说「origin=COMPUTED」、
 * 数字校验说「90.55 允许出现」—— 这三句必须是同一个事实，分开算迟早对不上。
 *
 * <p>状态里只存这一组 map（JDK 类型，C17 硬规则），本类负责把它渲染成文本。
 */
public record FactPack(String prompt, List<AnswerResult.Fact> rows, Set<String> allowedValues) {

    public static final FactPack EMPTY = new FactPack("", List.of(), Set.of());

    public FactPack {
        prompt = prompt == null ? "" : prompt;
        rows = rows == null ? List.of() : List.copyOf(rows);
        allowedValues = allowedValues == null ? Set.of() : Set.copyOf(allowedValues);
    }

    public boolean isEmpty() {
        return rows.isEmpty();
    }

    /** 从状态行还原（compute 节点写、answer 节点读）。 */
    public static FactPack from(List<Map<String, Object>> stateRows) {
        if (stateRows == null || stateRows.isEmpty()) {
            return EMPTY;
        }
        List<Map<String, Object>> ordered = new ArrayList<>(stateRows);
        ordered.sort(Comparator.comparingInt(r -> intOf(r.get("no"))));

        StringBuilder reported = new StringBuilder();
        StringBuilder computed = new StringBuilder();
        List<AnswerResult.Fact> facts = new ArrayList<>();
        Set<String> allowed = new LinkedHashSet<>();
        for (Map<String, Object> row : ordered) {
            String origin = str(row.get("origin"));
            String metric = str(row.get("metric"));
            String period = str(row.get("period"));
            String value = str(row.get("value"));
            String unit = str(row.get("unit"));
            String evidence = str(row.get("evidence"));
            String formula = str(row.get("formula"));
            String display = str(row.get("display"));
            String no = "F" + intOf(row.get("no"));

            if (AnswerResult.ORIGIN_COMPUTED.equals(origin)) {
                computed.append('[').append(no).append("] ").append(metric);
                if (!period.isEmpty()) {
                    computed.append(" / ").append(period);
                }
                computed.append(" = ").append(value).append(unit);
                if (!formula.isEmpty()) {
                    computed.append("（= ").append(formula).append("；系统计算");
                    String note = str(row.get("note"));
                    if (!note.isEmpty()) {
                        computed.append("；").append(note);
                    }
                    computed.append("）");
                }
                computed.append('\n');
            } else {
                reported.append('[').append(no).append("] ").append(metric);
                if (!period.isEmpty()) {
                    reported.append(" / ").append(period);
                }
                reported.append(" = ").append(value).append(unit);
                if (!display.isEmpty() && !display.equals(value)) {
                    reported.append("（≈").append(display).append("）");
                }
                if (!evidence.isEmpty()) {
                    reported.append("〔").append(evidence).append('〕');
                }
                reported.append('\n');
            }
            facts.add(new AnswerResult.Fact(metric, period, value, unit, str(row.get("scope")), evidence,
                    AnswerResult.Fact.SOURCE_PDF, origin, formula.isEmpty() ? null : formula));
            addNumbers(allowed, value);
            addNumbers(allowed, display);
        }

        StringBuilder prompt = new StringBuilder();
        if (reported.length() > 0) {
            prompt.append("【已报事实】（证据直给，数字与单位照抄，不要改写）\n").append(reported).append('\n');
        }
        if (computed.length() > 0) {
            prompt.append("【已计算事实】（系统按公式计算，可直接引用；不要自己再算一遍）\n").append(computed).append('\n');
        }
        return new FactPack(prompt.toString(), facts, allowed);
    }

    /** 白名单里只放数字串（去千分位），校验时与正文里的数字直接比对。 */
    private static void addNumbers(Set<String> target, String text) {
        if (text == null || text.isBlank()) {
            return;
        }
        var m = java.util.regex.Pattern.compile("-?\\d+(?:\\.\\d+)?").matcher(text.replace(",", ""));
        while (m.find()) {
            target.add(m.group());
        }
    }

    private static String str(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private static int intOf(Object value) {
        if (value instanceof Number n) {
            return n.intValue();
        }
        try {
            return value == null ? 0 : Integer.parseInt(String.valueOf(value).trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
