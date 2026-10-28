package com.finagent.memory;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 记忆的预算与开关（设计 §11.9，全部可配）。
 *
 * <p>为什么配额按「模型窗口的百分比」而不是按条数：模型换窗口（8k → 32k）时，
 * 百分比自动跟着放大，条数不会。条数只用来限上限（{@code long-term-max-items}）。
 *
 * <p>注意这张表只约束<b>业务内容</b>：System 里的规则、用户显式指令（INSTRUCTION）
 * 不参与这些比例（D10）。
 */
@ConfigurationProperties(prefix = "finagent.memory")
public record MemoryProperties(
        /** 记忆是否启用；关了之后行为与阶段 8 完全一致（回归开关）。 */
        @DefaultValue("true") boolean enabled,
        /** 模型上下文窗口的估算值，所有比例的基数。 */
        @DefaultValue("8000") int contextWindowTokens,
        /** 短期窗口最多保留几轮（1 轮 = USER + ASSISTANT）。 */
        @DefaultValue("6") int shortTermRounds,
        /** 短期窗口占窗口的比例。 */
        @DefaultValue("0.15") double shortTermRatio,
        /** 会话摘要占窗口的比例。 */
        @DefaultValue("0.10") double summaryRatio,
        /** 结构化上下文（公司/年份/指标）占窗口的比例。 */
        @DefaultValue("0.05") double structuredRatio,
        /** 长期记忆（CONTEXTUAL / WATCHLIST）占窗口的比例。 */
        @DefaultValue("0.05") double longTermRatio,
        /** 证据占窗口的比例（超出由检索层截断，不在记忆层处理）。 */
        @DefaultValue("0.40") double evidenceRatio,
        /** 用户显式指令（INSTRUCTION）的独立上限：只合并压缩，不丢弃。 */
        @DefaultValue("600") int instructionTokens,
        /** 长期记忆最多注入几条。 */
        @DefaultValue("3") int longTermMaxItems,
        /** 未汇总消息数超过这个值就触发摘要（1 轮 = 2 条）。 */
        @DefaultValue("12") int summaryTriggerMessages,
        /** 向量召回的相似度下限（0.6 = 余弦距离 0.4）；低于它就是「不相关」。 */
        @DefaultValue("0.6") double longTermMinScore,
        /** 向量召回的候选条数（进阈值过滤之前）。 */
        @DefaultValue("8") int longTermCandidates,
        /** 召回闸门总开关；关掉后一律不召回（也不调 embedding），用于基线对照。 */
        @DefaultValue("true") boolean gateEnabled
) {

    public int shortTermBudgetTokens() {
        return budget(shortTermRatio);
    }

    public int summaryBudgetTokens() {
        return budget(summaryRatio);
    }

    public int structuredBudgetTokens() {
        return budget(structuredRatio);
    }

    public int longTermBudgetTokens() {
        return budget(longTermRatio);
    }

    private int budget(double ratio) {
        if (contextWindowTokens <= 0 || ratio <= 0) {
            return 0;
        }
        return (int) Math.floor(contextWindowTokens * ratio);
    }
}
