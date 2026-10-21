package com.finagent.context;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 上下文装配的可调参数（§9.8 / §9.15）。
 */
@ConfigurationProperties(prefix = "finagent.context")
public class ContextProperties {

    /**
     * 证据 token 预算。
     *
     * <p>qwen-plus 是 32k 上下文，6000 是给答案留余量：
     * 财报证据动辄几千字，如果证据占满，模型会没有空间写完整答案，还容易在中段丢引用。
     */
    private int maxTokens = 6000;

    /** §9.15 的 0.6：Parent 内二次选择的相对阈值。 */
    private double minScoreFactor = 0.6;

    /** 命中块前后各补几个邻居（同 Parent 内）。 */
    private int neighborRadius = 1;

    /** 整段 Parent 的 token 上限；只有 COMPLEX_ANALYSIS 才会考虑整段。 */
    private int parentWholeMaxTokens = 1500;

    public int getMaxTokens() {
        return maxTokens;
    }

    public void setMaxTokens(int maxTokens) {
        this.maxTokens = maxTokens;
    }

    public double getMinScoreFactor() {
        return minScoreFactor;
    }

    public void setMinScoreFactor(double minScoreFactor) {
        this.minScoreFactor = minScoreFactor;
    }

    public int getNeighborRadius() {
        return neighborRadius;
    }

    public void setNeighborRadius(int neighborRadius) {
        this.neighborRadius = neighborRadius;
    }

    public int getParentWholeMaxTokens() {
        return parentWholeMaxTokens;
    }

    public void setParentWholeMaxTokens(int parentWholeMaxTokens) {
        this.parentWholeMaxTokens = parentWholeMaxTokens;
    }
}
