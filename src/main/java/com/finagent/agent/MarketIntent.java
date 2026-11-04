package com.finagent.agent;

/**
 * 一条问题是否在问行情。{@code history} 为真时用 {@code get_history}，否则 {@code get_quote}。
 *
 * <p>纯值对象，不是 Spring Bean：检测器是静态规则，单测不需要上下文。
 */
public record MarketIntent(boolean matched, boolean history, int days) {

    public static final MarketIntent NONE = new MarketIntent(false, false, 0);

    public String tool() {
        return history ? "get_history" : "get_quote";
    }
}
