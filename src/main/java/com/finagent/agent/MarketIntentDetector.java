package com.finagent.agent;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 规则式行情意图判定。
 *
 * <p>为什么不用模型判意图：这段只在 understand 节点跑，模型判断要多 1~2 秒，
 * 而「股价 / 市值 / 涨幅」这类词几乎不存在歧义；判错也只是走错支路，
 * 后面 {@code answer_market} 有模板与错误兜底。
 *
 * <p>两个刻意的边界：
 * <ul>
 *   <li>「近 N 天」只认明确的数字，没说就取默认 20 天（不猜「近期」= 5 天）。</li>
 *   <li>问句里带「年报 / 财报 / 招股书」且没有行情词时不算行情 —— 防止
 *       「2025 年营收和市值」这类问题被抢到行情支路。</li>
 * </ul>
 */
public final class MarketIntentDetector {

    /** 只问快照的词。 */
    private static final Pattern QUOTE = Pattern.compile(
            "股价|价格|现价|最新价|收盘价|开盘价|市值|市盈率|市净率|。?\\bPE\\b|\\bPB\\b|估值|涨跌幅?|跌涨幅?|"
                    + "成交额|成交量|换手率?|行情|停牌|涨停|跌停|今开|昨收|最高价|最低价|"
                    + "现在多少钱|今天多少钱|多少钱一股");

    /** 需要时间序列的词（走势类）。 */
    private static final Pattern HISTORY = Pattern.compile(
            "走势|K线|日线|周线|月线|连涨|连跌|历史股价|最近.{0,3}(涨|跌)|"
                    + "近\\s*\\d+\\s*(个交易日|交易日|个?天|天|个?日|日|个?周|周|个?月|月)|最近\\s*\\d+\\s*(个交易日|个?天|天|个?日|日|个?周|周)|"
                    + "一个月|三个月|半年|今年以来的?股价");

    /** 财报语境的词，用来压住「市值」这种可能出现在年报讨论里的词。 */
    private static final Pattern REPORT_CONTEXT = Pattern.compile(
            "年报|半年报|季报|财报|招股书|审计报告|营业收入|净利润|毛利率|现金流");

    private static final Pattern DAYS = Pattern.compile(
            "近\\s*(\\d{1,3})\\s*(个交易日|交易日|个?天|个?日|个?周|个?月)");

    private static final Pattern CODE = Pattern.compile("(?<!\\d)(\\d{6})(?!\\d)");

    private MarketIntentDetector() {
    }

    public static MarketIntent detect(String question) {
        if (question == null || question.isBlank()) {
            return MarketIntent.NONE;
        }
        String text = question.strip();
        boolean history = HISTORY.matcher(text).find();
        boolean quote = QUOTE.matcher(text).find();
        if (!quote && !history) {
            return MarketIntent.NONE;
        }
        if (!quote && REPORT_CONTEXT.matcher(text).find() && !history) {
            return MarketIntent.NONE;
        }
        int days = history ? days(text) : 0;
        return new MarketIntent(true, history, days);
    }

    /** 「近 5 天」「最近 3 个交易日」「近 2 周」→ 天数，按每周 7 天、每月 30 天折算，上限 120。 */
    static int days(String text) {
        Matcher matcher = DAYS.matcher(text);
        if (!matcher.find()) {
            return 20;
        }
        int amount = Integer.parseInt(matcher.group(1));
        // group(2) 可能是「个月」「个周」—— 先把量词「个」剥掉再折算
        String unit = matcher.group(2).replace("个", "");
        int days = switch (unit) {
            case "周" -> amount * 7;
            case "月" -> amount * 30;
            default -> amount;
        };
        return Math.max(1, Math.min(days, 120));
    }

    /** 问题里直接写了的 6 位代码（代码优先于公司名）。 */
    public static String codeInQuestion(String question) {
        if (question == null) {
            return null;
        }
        Matcher matcher = CODE.matcher(question);
        return matcher.find() ? matcher.group(1) : null;
    }
}
