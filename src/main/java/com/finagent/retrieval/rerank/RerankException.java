package com.finagent.retrieval.rerank;

/**
 * Rerank 调用失败。
 *
 * <p>单独一个类型，是为了让降级日志一眼能分清「外部服务挂了」和「我们自己的 bug」：
 * 编排层捕获后写进 trace.warnings，内容是 rerank_failed: RerankException。
 */
public class RerankException extends RuntimeException {

    public RerankException(String message) {
        super(message);
    }

    public RerankException(String message, Throwable cause) {
        super(message, cause);
    }
}
