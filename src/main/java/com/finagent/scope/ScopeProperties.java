package com.finagent.scope;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 作用域相关的可调参数。
 *
 * <p>documentsSoftLimit 的 5 不是拍脑袋：fusionLimit 是 50，50 / 5 = 10，
 * 每份文档至少 10 条进精排；再多于 5 份，单份就分不到 10 条，
 * Reranker 没有足够样本区分「同一份文档里的细微差别」。
 */
@ConfigurationProperties(prefix = "finagent.scope")
public class ScopeProperties {

    /** A 模式的软上限：超过只提示切 B，不拦住（D3 / D12）。 */
    private int documentsSoftLimit = 5;

    /** 文档级路由阈值：可检索文档超过这个数，单池检索开始不够用（D11）。 */
    private int routingThresholdDocuments = 20;

    /** 文档级路由阈值：chunk 总数超过这个数（D11）。 */
    private long routingThresholdChunks = 50_000L;

    public int getDocumentsSoftLimit() {
        return documentsSoftLimit;
    }

    public void setDocumentsSoftLimit(int documentsSoftLimit) {
        this.documentsSoftLimit = documentsSoftLimit;
    }

    public int getRoutingThresholdDocuments() {
        return routingThresholdDocuments;
    }

    public void setRoutingThresholdDocuments(int routingThresholdDocuments) {
        this.routingThresholdDocuments = routingThresholdDocuments;
    }

    public long getRoutingThresholdChunks() {
        return routingThresholdChunks;
    }

    public void setRoutingThresholdChunks(long routingThresholdChunks) {
        this.routingThresholdChunks = routingThresholdChunks;
    }
}
