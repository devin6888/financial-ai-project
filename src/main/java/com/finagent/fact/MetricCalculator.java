package com.finagent.fact;

import com.finagent.understanding.AnswerShape;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 派生指标计算器：只用证据里的原值算，算不出就说原因。
 *
 * <p>三条纪律：
 * <ol>
 *   <li>证据已经披露同一个数（且对得上）→ 计算值保留公式并标注「与披露一致」；对不上 → 放弃计算值，以披露为准；</li>
 *   <li>分母为 0 / 缺一期 → 不算，记 compute_skipped:原因（让「为什么没有这个数」可查）；</li>
 *   <li>全部 BigDecimal + HALF_UP 2 位，绝不 double。</li>
 * </ol>
 */
public final class MetricCalculator {

    /** 提示词里最多放这么多条计算事实，再多就是噪声。 */
    public static final int MAX_COMPUTED = 8;

    private static final BigDecimal HUNDRED = new BigDecimal("100");
    private static final BigDecimal TOLERANCE = new BigDecimal("0.011");

    private MetricCalculator() {
    }

    public record Result(List<ComputedFact> facts, List<String> warnings) {

        public static final Result EMPTY = new Result(List.of(), List.of());
    }

    public static Result compute(List<ReportedFact> reported, Collection<AnswerShape> shapes) {
        return compute(reported, shapes, List.of());
    }

    /**
     * @param reported 证据直给的事实（已按上下文块抽好）
     * @param shapes   答案形态：没有形态（纯取数）时只算同比增长率，不塞结构与贡献度
     * @param focus    问题点名的指标（QU 的 metrics）。命中 focus 的计算值优先占预算 ——
     *                 一张分行业表能算出 10 个同比，全塞进提示词反而把用户问的那个挤掉
     */
    public static Result compute(List<ReportedFact> reported, Collection<AnswerShape> shapes,
                                 Collection<String> focus) {
        if (reported == null || reported.isEmpty()) {
            return Result.EMPTY;
        }
        EnumSet<AnswerShape> set = EnumSet.noneOf(AnswerShape.class);
        if (shapes != null) {
            set.addAll(shapes);
        }

        Map<String, TreeMap<Integer, ReportedFact>> amounts = new LinkedHashMap<>();
        Map<String, TreeMap<Integer, ReportedFact>> percents = new LinkedHashMap<>();
        Map<String, ReportedFact> growth = new LinkedHashMap<>();
        Map<String, Long> chunkOf = new LinkedHashMap<>();

        for (ReportedFact f : reported) {
            if (f.isAmount()) {
                Integer year = f.year();
                if (year != null) {
                    amounts.computeIfAbsent(f.metric(), k -> new TreeMap<>()).putIfAbsent(year, f);
                    chunkOf.putIfAbsent(f.metric(), f.chunkId());
                }
            } else if (f.isPercent()) {
                Integer year = f.year();
                if (year != null) {
                    percents.computeIfAbsent(f.metric(), k -> new TreeMap<>()).putIfAbsent(year, f);
                } else {
                    growth.putIfAbsent(f.metric(), f);
                }
            }
        }

        List<String> warnings = new ArrayList<>();
        List<ComputedFact> yoy = new ArrayList<>();
        Map<String, ComputedFact> deltaOf = new LinkedHashMap<>();
        List<ComputedFact> contributions = new ArrayList<>();
        List<ComputedFact> shareDeltas = new ArrayList<>();
        List<ComputedFact> shares = new ArrayList<>();
        Map<String, TreeMap<Integer, BigDecimal>> shareByMetric = new LinkedHashMap<>();

        // 1) 同比增长率（任何形态都可能用到：取数题也会问「增长多少」）
        for (Map.Entry<String, TreeMap<Integer, ReportedFact>> e : amounts.entrySet()) {
            ComputedFact fact = yoyOf(e.getKey(), e.getValue(), growth.get(e.getKey()), warnings);
            if (fact != null) {
                yoy.add(fact);
                ComputedFact delta = deltaOf(e.getKey(), e.getValue());
                if (delta != null) {
                    deltaOf.put(e.getKey(), delta);
                }
            }
        }

        boolean structure = set.contains(AnswerShape.BREAKDOWN) || set.contains(AnswerShape.ATTRIBUTION);
        if (structure) {
            Map<String, TreeMap<Integer, ReportedFact>> totals = new LinkedHashMap<>();
            for (Map.Entry<String, TreeMap<Integer, ReportedFact>> e : amounts.entrySet()) {
                if (isTotal(e.getKey())) {
                    totals.putIfAbsent(chunkKey(e.getKey(), chunkOf), e.getValue());
                }
            }
            // 2) 结构占比 + 3) 占比变动 + 4) 收入贡献度
            for (Map.Entry<String, TreeMap<Integer, ReportedFact>> e : amounts.entrySet()) {
                String metric = e.getKey();
                if (isTotal(metric)) {
                    continue;
                }
                TreeMap<Integer, ReportedFact> total = totals.get(chunkKey(metric, chunkOf));
                if (total == null) {
                    continue;
                }
                TreeMap<Integer, BigDecimal> shareOfMetric = new TreeMap<>();
                Integer latestYear = e.getValue().lastKey();
                for (Map.Entry<Integer, ReportedFact> year : e.getValue().entrySet()) {
                    ReportedFact item = year.getValue();
                    ReportedFact totalFact = total.get(year.getKey());
                    if (totalFact == null) {
                        continue;
                    }
                    if (totalFact.value().signum() == 0) {
                        addOnce(warnings, "compute_skipped:zero_total:" + metric);
                        continue;
                    }
                    BigDecimal value = item.value().divide(totalFact.value(), 6, RoundingMode.HALF_UP)
                            .multiply(HUNDRED).setScale(2, RoundingMode.HALF_UP);
                    shareOfMetric.put(year.getKey(), value);
                    ReportedFact reportedShare = percents.getOrDefault(metric, new TreeMap<>()).get(year.getKey());
                    String note = MetricFormula.SHARE.scope();
                    if (reportedShare != null) {
                        if (reportedShare.value().subtract(value).abs().compareTo(TOLERANCE) <= 0) {
                            note = "与证据披露的 " + UnitConverter.percent(reportedShare.value()) + " 一致";
                        } else {
                            addOnce(warnings, "compute_conflict:" + metric + ":" + year.getKey());
                            continue;
                        }
                    }
                    // 只出最新一年的占比：上一年占比已经有「占比变动」这一条，两条都进提示词等于浪费预算
                    if (year.getKey().equals(latestYear)) {
                        shares.add(new ComputedFact(metric + "结构占比", String.valueOf(year.getKey()), value, "%",
                                item.raw() + " / " + totalFact.raw() + " × 100",
                                List.of(item, totalFact), nos(item, totalFact), note));
                    }
                }
                shareByMetric.put(metric, shareOfMetric);
                ComputedFact contribution = contributionOf(metric, e.getValue(), total, warnings);
                if (contribution != null) {
                    contributions.add(contribution);
                }
            }
            // 占比变动：两年占比都有才比，缺一年就跳过
            for (Map.Entry<String, TreeMap<Integer, BigDecimal>> e : shareByMetric.entrySet()) {
                TreeMap<Integer, BigDecimal> byYear = e.getValue();
                if (byYear.size() < 2) {
                    continue;
                }
                List<Integer> years = new ArrayList<>(byYear.keySet());
                Integer latest = years.get(years.size() - 1);
                Integer prev = years.get(years.size() - 2);
                BigDecimal delta = byYear.get(latest).subtract(byYear.get(prev));
                shareDeltas.add(new ComputedFact(e.getKey() + "占比变动", latest + "vs" + prev, delta, "百分点",
                        UnitConverter.percent(byYear.get(latest)) + " − " + UnitConverter.percent(byYear.get(prev)),
                        List.of(), List.of(), MetricFormula.SHARE_DELTA.scope()));
            }
        }

        // 预算分配（不是简单拼接）：一张分行业表能算出十几个同比，先到先得会把
        // 「营业总收入同比增长率」和「证券业贡献度」一起挤掉 —— 这两条恰恰是答案最需要的。
        List<String> asked = focus == null ? List.of() : new ArrayList<>(focus);
        List<ComputedFact> primaryYoy = new ArrayList<>();
        List<ComputedFact> otherYoy = new ArrayList<>();
        for (ComputedFact f : yoy) {
            if (matches(f.metric(), asked) || isTotal(f.metric())) {
                primaryYoy.add(f);
            } else {
                otherYoy.add(f);
            }
        }
        List<ComputedFact> primaryDelta = new ArrayList<>();
        for (Map.Entry<String, ComputedFact> e : deltaOf.entrySet()) {
            if (matches(e.getKey(), asked) || isTotal(e.getKey())) {
                primaryDelta.add(e.getValue());
            }
        }
        List<ComputedFact> out = new ArrayList<>();
        take(out, primaryYoy, 3);
        take(out, primaryDelta, 2);
        take(out, contributions, 2);
        take(out, shareDeltas, 2);
        take(out, shares, 1);
        take(out, otherYoy, MAX_COMPUTED - out.size());
        return new Result(List.copyOf(out), List.copyOf(warnings));
    }

    /** 同比增长率：错位一期，上期为 0 不算。 */
    private static ComputedFact yoyOf(String metric, TreeMap<Integer, ReportedFact> byYear,
                                      ReportedFact reportedGrowth, List<String> warnings) {
        if (byYear.size() < 2) {
            return null;
        }
        List<Integer> years = new ArrayList<>(byYear.keySet());
        Integer latestYear = years.get(years.size() - 1);
        Integer prevYear = years.get(years.size() - 2);
        ReportedFact latest = byYear.get(latestYear);
        ReportedFact prev = byYear.get(prevYear);
        if (prev.value().signum() == 0) {
            addOnce(warnings, "compute_skipped:zero_base:" + metric);
            return null;
        }
        BigDecimal value = latest.value().subtract(prev.value())
                .divide(prev.value().abs(), 6, RoundingMode.HALF_UP)
                .multiply(HUNDRED).setScale(2, RoundingMode.HALF_UP);
        String period = latestYear + "vs" + prevYear;
        String note = MetricFormula.YOY.scope();
        if (reportedGrowth != null) {
            if (reportedGrowth.value().subtract(value).abs().compareTo(TOLERANCE) > 0) {
                // 披露优先：算出来和披露对不上时，宁可不出计算值，也不能让两个数同时进提示词
                addOnce(warnings, "compute_conflict:" + metric);
                return null;
            }
            note = "与证据披露的 " + UnitConverter.percent(reportedGrowth.value()) + " 一致";
        }
        String denominator = prev.value().signum() < 0 ? "|" + prev.raw() + "|" : prev.raw();
        return new ComputedFact(metric + MetricFormula.YOY.label(), period, value, "%",
                "(" + latest.raw() + " − " + prev.raw() + ") / " + denominator + " × 100",
                List.of(latest, prev), nos(latest, prev), note);
    }

    /** 增长额：本期 − 上期（单位跟原表一致，元）。 */
    private static ComputedFact deltaOf(String metric, TreeMap<Integer, ReportedFact> byYear) {
        if (byYear.size() < 2) {
            return null;
        }
        List<Integer> years = new ArrayList<>(byYear.keySet());
        Integer latestYear = years.get(years.size() - 1);
        Integer prevYear = years.get(years.size() - 2);
        ReportedFact latest = byYear.get(latestYear);
        ReportedFact prev = byYear.get(prevYear);
        return new ComputedFact(metric + MetricFormula.DELTA.label(), latestYear + "vs" + prevYear,
                latest.value().subtract(prev.value()), "元",
                latest.raw() + " − " + prev.raw(),
                List.of(latest, prev), nos(latest, prev), MetricFormula.DELTA.scope());
    }

    /** 收入贡献度：分项增量 / 合计增量。 */
    private static ComputedFact contributionOf(String metric, TreeMap<Integer, ReportedFact> item,
                                               TreeMap<Integer, ReportedFact> total, List<String> warnings) {
        List<Integer> years = new ArrayList<>(item.keySet());
        years.retainAll(total.keySet());
        if (years.size() < 2) {
            return null;
        }
        years.sort(Integer::compareTo);
        Integer latest = years.get(years.size() - 1);
        Integer prev = years.get(years.size() - 2);
        BigDecimal deltaTotal = total.get(latest).value().subtract(total.get(prev).value());
        if (deltaTotal.signum() == 0) {
            addOnce(warnings, "compute_skipped:zero_delta:" + metric);
            return null;
        }
        BigDecimal deltaItem = item.get(latest).value().subtract(item.get(prev).value());
        BigDecimal value = deltaItem.divide(deltaTotal, 6, RoundingMode.HALF_UP)
                .multiply(HUNDRED).setScale(2, RoundingMode.HALF_UP);
        return new ComputedFact(metric + MetricFormula.CONTRIBUTION.label(), latest + "vs" + prev, value, "%",
                "(" + item.get(latest).raw() + " − " + item.get(prev).raw() + ") / (" + total.get(latest).raw()
                        + " − " + total.get(prev).raw() + ") × 100",
                List.of(item.get(latest), item.get(prev), total.get(latest), total.get(prev)),
                nos(item.get(latest), item.get(prev), total.get(latest), total.get(prev)),
                MetricFormula.CONTRIBUTION.scope());
    }

    private static void take(List<ComputedFact> target, List<ComputedFact> source, int max) {
        int added = 0;
        for (ComputedFact f : source) {
            if (added >= max || target.size() >= MAX_COMPUTED) {
                return;
            }
            if (target.contains(f) || sameMath(target, f)) {
                continue;
            }
            target.add(f);
            added++;
        }
    }

    /**
     * 同一个算式算出的两个事实算重复。
     *
     * <p>为什么要这条：主表里「营业总收入合计」与利润表里的「一、营业总收入」是同一个数、同一组两期，
     * 于是会算出两条一模一样的同比增长率，只在指标名前缀上有差别（实测 {@code [F…]} 里占掉两格）。
     * 算式（期间 + formula）相同即视为同一次计算，保留先出现的那条。
     */
    private static boolean sameMath(List<ComputedFact> target, ComputedFact candidate) {
        for (ComputedFact f : target) {
            if (java.util.Objects.equals(f.period(), candidate.period())
                    && java.util.Objects.equals(f.formula(), candidate.formula())) {
                return true;
            }
        }
        return false;
    }

    /** 指标名里是否出现问题点名的指标（「营业总收入同比增长率」命中「营业总收入」）。 */
    private static boolean matches(String metric, List<String> asked) {
        if (metric == null) {
            return false;
        }
        for (String term : asked) {
            if (term != null && !term.isBlank() && metric.contains(term)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isTotal(String metric) {
        return metric != null && (metric.contains("合计") || metric.contains("总计"));
    }

    private static String chunkKey(String metric, Map<String, Long> chunkOf) {
        Long id = chunkOf.get(metric);
        return "chunk-" + (id == null ? 0 : id);
    }

    private static List<Integer> nos(ReportedFact... facts) {
        List<Integer> out = new ArrayList<>();
        for (ReportedFact f : facts) {
            if (f != null && f.evidenceNo() > 0 && !out.contains(f.evidenceNo())) {
                out.add(f.evidenceNo());
            }
        }
        return out;
    }

    private static void addOnce(List<String> list, String value) {
        if (!list.contains(value)) {
            list.add(value);
        }
    }
}
