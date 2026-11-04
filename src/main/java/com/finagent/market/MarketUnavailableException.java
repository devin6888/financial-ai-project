package com.finagent.market;

/**
 * 行情能力本身不可用：子进程没起来、握手失败、连接超时。
 *
 * <p>和 {@link MarketApiException} 分开的理由：这一类不能把技术细节甩给用户，
 * 文案统一换成「行情服务暂时不可用」。
 */
public class MarketUnavailableException extends RuntimeException {

    public MarketUnavailableException(String message) {
        super(message);
    }

    public MarketUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
