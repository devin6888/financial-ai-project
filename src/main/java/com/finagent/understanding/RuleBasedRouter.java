package com.finagent.understanding;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 规则快路径（§9.14 第一层防线）。
 *
 * <p>覆盖形态：「600519 2025 年营业收入是多少」 —— 证券代码 + 年份 + 指标名俱全时，
 * 直接判定 EXACT_FACT 并<b>不调用 LLM</b>。
 *
 * <p>为什么它不能省：§9.14 要求规则路径 ≤5ms 且零错误。全走 LLM 的话，
 * P95 800ms 会直接压垮检索体验，而确定性问法本可以零错误。
 */
@Component
public class RuleBasedRouter {

    /**
     * A 股代码：6 位数字，60/68/00/30 开头。
     *
     * <p>前后加 lookaround 防止把长数字串（如金额 11842000）切成代码 ——
     * 少了这两个断言，任何 6 位以上的数字都会被误判成股票代码。
     */
    private static final Pattern STOCK_CODE = Pattern.compile(
            "(?<!\\d)(60\\d{4}|68\\d{4}|00\\d{4}|30\\d{4})(?!\\d)");

    /** 年份：19xx / 20xx。 */
    private static final Pattern YEAR = Pattern.compile("(19|20)\\d{2}");

    private final MetricDictionary metrics;

    public RuleBasedRouter(MetricDictionary metrics) {
        this.metrics = metrics;
    }

    /**
     * @return 三要素齐全时返回结果；否则 {@code null}，交给 LLM
     */
    public QueryUnderstanding tryRoute(String query) {
        if (query == null || query.isBlank()) {
            return null;
        }
        List<String> codes = findAll(STOCK_CODE, query);
        List<Integer> years = findAll(YEAR, query).stream().map(Integer::parseInt).toList();
        List<String> found = metrics.findIn(query);

        // §9.14 第三层防线的规则版：ExactFact 要求三要素同时非空，缺一即证据不足
        if (codes.isEmpty() || years.isEmpty() || found.isEmpty()) {
            return null;
        }

        int minYear = years.stream().mapToInt(Integer::intValue).min().orElse(0);
        int maxYear = years.stream().mapToInt(Integer::intValue).max().orElse(0);
        return new QueryUnderstanding(QueryType.EXACT_FACT, query, codes,
                new int[]{minYear, maxYear}, found, false, false,
                DocTypes.detect(query), false, "RULE");
    }

    private static List<String> findAll(Pattern p, String text) {
        List<String> out = new ArrayList<>();
        Matcher m = p.matcher(text);
        while (m.find()) {
            out.add(m.group());
        }
        return out;
    }
}
