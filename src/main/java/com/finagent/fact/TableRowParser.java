package com.finagent.fact;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 把渲染后的 markdown 表格解析成「行标签 + 各年数值」。
 *
 * <p>为什么不用库：我们只处理自己 {@code TableRenderer} 生成的管道表格，形状可控；
 * 引一个 HTML/markdown 解析器反而要处理一堆用不上的语法。
 *
 * <p>三条保命规则：
 * <ol>
 *   <li><b>表头必须能唯一确定「列 → 年份」</b>，否则整张表跳过 —— 猜错列就是把 2024 的数写成 2025；</li>
 *   <li>表里出现第二段表头（如 7216 的「2025年末 / 2024年末」）就重设列映射，不沿用上一段；</li>
 *   <li>解析失败一律返回空，绝不抛异常 —— 答案生成不能因为一张怪表挂掉。</li>
 * </ol>
 */
public final class TableRowParser {

    private static final Pattern YEAR = Pattern.compile("(19|20)\\d{2}");
    private static final Pattern NUMBER = Pattern.compile("-?\\d+(?:\\.\\d+)?");

    private static final String[] PERCENT_WORDS = {"占比", "比重", "比例"};
    private static final String[] AMOUNT_WORDS = {"金额"};
    private static final String[] GROWTH_WORDS = {"增减", "增长", "变动"};

    private TableRowParser() {
    }

    /** 单元格：数值 + 证据里的原始字面量（公式与事实表要照抄原文）。 */
    public record Cell(BigDecimal value, String raw) {

        public static final Cell NONE = new Cell(null, "");
    }

    /** 一个数据行；amounts / percents 的 key 是四位数年份。 */
    public record Row(String label, String group, Map<Integer, Cell> amounts,
                      Map<Integer, Cell> percents, Cell growth) {

        public Row {
            amounts = amounts == null ? Map.of() : Map.copyOf(amounts);
            percents = percents == null ? Map.of() : Map.copyOf(percents);
            growth = growth == null ? Cell.NONE : growth;
        }

        /** 金额列里的最晚年份。 */
        public Integer latestYear() {
            return amounts.keySet().stream().max(Integer::compareTo).orElse(null);
        }

        /** 金额列里的次晚年分。 */
        public Integer previousYear() {
            List<Integer> years = new ArrayList<>(amounts.keySet());
            years.sort(Integer::compareTo);
            return years.size() >= 2 ? years.get(years.size() - 2) : null;
        }

        public boolean isTotal() {
            String l = label == null ? "" : label;
            return l.contains("合计") || l.contains("总计");
        }
    }

    /**
     * @return 解析出的数据行；表头里找不到年份、或没有数据行时返回空列表
     */
    public static List<Row> parse(String content) {
        if (content == null || content.isBlank()) {
            return List.of();
        }
        List<String[]> rows = new ArrayList<>();
        for (String line : content.split("\\R")) {
            String t = line.trim();
            if (!t.startsWith("|")) {
                continue;
            }
            if (isSeparator(t)) {
                continue;
            }
            rows.add(cells(t));
        }
        if (rows.size() < 2) {
            return List.of();
        }
        int width = 0;
        for (String[] r : rows) {
            width = Math.max(width, r.length);
        }

        Integer[] year = new Integer[width];
        Kind[] kind = new Kind[width];
        List<Row> out = new ArrayList<>();
        String group = null;
        boolean headerSeen = false;

        for (String[] cells : rows) {
            if (numbersIn(cells) == 0) {
                int yearCells = yearCells(cells);
                if (yearCells >= 2) {
                    // 新一段表头：列映射从零开始，绝不沿用上一段的年份（7216 有两段）
                    year = new Integer[width];
                    kind = new Kind[width];
                    for (int i = 0; i < cells.length && i < width; i++) {
                        year[i] = yearOf(cells[i]);
                        kind[i] = kindOf(cells[i]);
                    }
                    headerSeen = true;
                    group = null;
                } else if (nonBlank(cells) == 1) {
                    group = clean(cells[0]);
                } else {
                    // 子表头（金额 / 占营业总收入比重）：补 kind 之后还要修年份，见 regroup
                    for (int i = 0; i < cells.length && i < width; i++) {
                        Kind k = kindOf(cells[i]);
                        if (k != null) {
                            kind[i] = k;
                        }
                    }
                    regroup(year, kind);
                }
                continue;
            }
            if (!headerSeen) {
                continue;
            }
            String label = clean(cells[0]);
            if (label.isEmpty()) {
                continue;
            }
            Map<Integer, Cell> amounts = new LinkedHashMap<>();
            Map<Integer, Cell> percents = new LinkedHashMap<>();
            Cell growth = Cell.NONE;
            boolean ambiguous = false;
            for (int i = 1; i < cells.length && i < width; i++) {
                BigDecimal value = number(cells[i]);
                if (value == null) {
                    continue;
                }
                Cell cell = new Cell(value, cells[i].trim());
                boolean percentCell = cells[i].contains("%");
                if (kind[i] == Kind.GROWTH) {
                    growth = cell;
                } else if (kind[i] == Kind.PERCENT || percentCell) {
                    if (year[i] != null) {
                        percents.put(year[i], cell);
                    }
                } else if (year[i] != null) {
                    if (amounts.containsKey(year[i])) {
                        // 两列被认成同一年 = 表头认不准，整行丢弃（猜错列比少一条事实严重得多）
                        ambiguous = true;
                        break;
                    }
                    amounts.put(year[i], cell);
                } else if (growth.value() == null) {
                    // 没有年份也没有关键字：只能当「同比增减」列（这类列表头一定不含年份）
                    growth = cell;
                }
            }
            if (ambiguous || (amounts.isEmpty() && percents.isEmpty() && growth.value() == null)) {
                continue;
            }
            out.add(new Row(label, group, amounts, percents, growth));
        }
        return List.copyOf(out);
    }

    /** 解析单个单元格（千分位、百分号、括号负数、空 / 横线）。 */
    public static BigDecimal number(String cell) {
        if (cell == null) {
            return null;
        }
        String s = cell.replace(",", "").replace("，", "").replace(" ", "").replace("\u00A0", "").trim();
        if (s.isEmpty() || s.equals("-") || s.equals("—") || s.equals("/") || s.equals("不适用")) {
            return null;
        }
        boolean negative = s.startsWith("(") && s.endsWith(")");
        s = s.replace("(", "").replace(")", "");
        if (s.endsWith("%")) {
            s = s.substring(0, s.length() - 1);
        }
        if (!NUMBER.matcher(s).matches()) {
            return null;
        }
        BigDecimal value = new BigDecimal(s);
        return negative ? value.negate() : value;
    }

    // ---------------------------------------------------------------- 内部

    private enum Kind { AMOUNT, PERCENT, GROWTH }

    /**
     * 修正被 PDF 拆开的合并表头。
     *
     * <p>7216/7246 这类表头在 PDF 里是合并单元格（「2025 年」横跨金额与占比两列），
     * Docling 抽出来会变成「2025年 | 2025年 | 2025年 | 2024年」——同一个年份占了两列，
     * 照字面映射就会把 2024 的金额写进 2025。这里用子表头的「金额」列做分组信号：
     * 每遇到一个金额列就是新的一组，年份按表头里年份出现的先后顺序依次分配。
     *
     * <p>只在「表头里存在重复年份」时动手；表头本来就一年一列（如 7216 第一段）时保持原样。
     */
    private static void regroup(Integer[] year, Kind[] kind) {
        List<Integer> years = new ArrayList<>();
        java.util.Set<Integer> seen = new java.util.HashSet<>();
        boolean duplicated = false;
        for (Integer y : year) {
            if (y == null) {
                continue;
            }
            if (!seen.add(y)) {
                duplicated = true;
            } else {
                years.add(y);
            }
        }
        if (!duplicated || years.size() < 2) {
            return;
        }
        int group = -1;
        for (int i = 0; i < kind.length; i++) {
            if (kind[i] == Kind.AMOUNT) {
                group++;
            }
            if (group >= 0 && (kind[i] == Kind.AMOUNT || kind[i] == Kind.PERCENT)) {
                year[i] = years.get(Math.min(group, years.size() - 1));
            }
        }
    }

    private static boolean isSeparator(String line) {
        String t = line.replace("|", "").replace("-", "").replace(":", "").replace(" ", "");
        return t.isEmpty();
    }

    private static String[] cells(String line) {
        String body = line.trim();
        if (body.startsWith("|")) {
            body = body.substring(1);
        }
        if (body.endsWith("|")) {
            body = body.substring(0, body.length() - 1);
        }
        String[] parts = body.split("\\|", -1);
        for (int i = 0; i < parts.length; i++) {
            parts[i] = parts[i].trim();
        }
        return parts;
    }

    private static int numbersIn(String[] cells) {
        int n = 0;
        for (String c : cells) {
            if (number(c) != null) {
                n++;
            }
        }
        return n;
    }

    private static int yearCells(String[] cells) {
        int n = 0;
        for (String c : cells) {
            if (yearOf(c) != null) {
                n++;
            }
        }
        return n;
    }

    private static int nonBlank(String[] cells) {
        int n = 0;
        for (String c : cells) {
            if (c != null && !c.isBlank()) {
                n++;
            }
        }
        return n;
    }

    private static Integer yearOf(String cell) {
        if (cell == null) {
            return null;
        }
        Matcher m = YEAR.matcher(cell);
        return m.find() ? Integer.valueOf(m.group()) : null;
    }

    private static Kind kindOf(String cell) {
        if (cell == null || cell.isBlank()) {
            return null;
        }
        if (containsAny(cell, PERCENT_WORDS)) {
            return Kind.PERCENT;
        }
        if (containsAny(cell, GROWTH_WORDS)) {
            return Kind.GROWTH;
        }
        if (containsAny(cell, AMOUNT_WORDS)) {
            return Kind.AMOUNT;
        }
        return null;
    }

    private static boolean containsAny(String text, String[] words) {
        for (String w : words) {
            if (text.contains(w)) {
                return true;
            }
        }
        return false;
    }

    /** 去空白（中文标签里常有「归属于上市 公司股东的」这种排版断行）。 */
    static String clean(String cell) {
        if (cell == null) {
            return "";
        }
        return cell.replaceAll("\\s+", "").trim();
    }
}
