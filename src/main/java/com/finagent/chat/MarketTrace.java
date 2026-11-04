package com.finagent.chat;

/**
 * 一次行情取数的轨迹（阶段 23）。
 *
 * <p>为什么进 trace 而不是只写在答案里：答案里的数字要能解释「几点几分、从哪只票取的」；
 * 排障时「行情到底调没调、调的是哪只」也必须能一眼看出来。
 *
 * @param tool  get_quote / get_history；失败时是尝试过的那个
 * @param code  6 位代码
 * @param name  公司名（可能为空：只给了代码）
 * @param asOf  数据时间（ISO-8601 带时区）；失败时为空
 * @param error 失败原因；成功时为空
 * @param facts 写进 FactPack 的行情事实条数
 */
public record MarketTrace(String tool, String code, String name, String asOf, String error, int facts) {

    public static final MarketTrace EMPTY = new MarketTrace("", "", "", "", "", 0);

    public MarketTrace {
        tool = tool == null ? "" : tool;
        code = code == null ? "" : code;
        name = name == null ? "" : name;
        asOf = asOf == null ? "" : asOf;
        error = error == null ? "" : error;
    }

    public boolean used() {
        return !tool.isEmpty() || !error.isEmpty();
    }

    public boolean failed() {
        return !error.isEmpty();
    }
}
