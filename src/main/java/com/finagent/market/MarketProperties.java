package com.finagent.market;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 行情支路的配置：{@code finagent.market.*}。
 *
 * <p>用 {@code enabled} 而不是「靠 MCP client 有没有装配出来」判断：MCP 装配失败时
 * {@code ObjectProvider} 会给 null，但那种情况下我们想知道的是「人为关掉了」还是「配错了」。
 */
@ConfigurationProperties(prefix = "finagent.market")
public class MarketProperties {

    /** 关掉后整条行情支路直接答「未启用」，不再尝试拉起子进程。 */
    private boolean enabled = true;

    /** 行情问题的默认历史天数（只对 get_history 生效）。 */
    private int days = 20;

    /** 公司名解析兜底时，MCP search_stock 的候选最多取几个。 */
    private int searchLimit = 5;

    /** asOf 缺失时用当前时间兜底，并在日志里留一条 WARN。 */
    private boolean warnMissingAsOf = true;

    /** 单次行情请求的超时；握手超时单独放宽到 30 s（{@code MarketConfig} 里的 customizer）。 */
    private java.time.Duration requestTimeout = java.time.Duration.ofSeconds(8);

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getDays() {
        return days;
    }

    public void setDays(int days) {
        this.days = days;
    }

    public int getSearchLimit() {
        return searchLimit;
    }

    public void setSearchLimit(int searchLimit) {
        this.searchLimit = searchLimit;
    }

    public boolean isWarnMissingAsOf() {
        return warnMissingAsOf;
    }

    public void setWarnMissingAsOf(boolean warnMissingAsOf) {
        this.warnMissingAsOf = warnMissingAsOf;
    }

    public java.time.Duration getRequestTimeout() {
        return requestTimeout;
    }

    public void setRequestTimeout(java.time.Duration requestTimeout) {
        this.requestTimeout = requestTimeout;
    }
}
