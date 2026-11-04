package com.finagent.market;

/**
 * 行情工具内部报的业务错误（代码不合法、停牌无数据）。
 *
 * <p>这个异常的 {@code message} 已经是人话（MCP 服务端自己的错误契约），可以直接给用户看。
 */
public class MarketApiException extends RuntimeException {

    public MarketApiException(String message) {
        super(message);
    }
}
