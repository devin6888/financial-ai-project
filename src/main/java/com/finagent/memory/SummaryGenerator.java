package com.finagent.memory;

/**
 * 生成一段会话摘要。
 *
 * <p>抽成接口只为两件事：① 单测不用去 mock Spring AI 那串 Builder 链；
 * ② 将来想换模型/换成本地小模型，只改一个实现类。
 */
@FunctionalInterface
public interface SummaryGenerator {

    /**
     * @param prompt 已经拼好的「旧摘要 + 待汇总对话」
     * @return 摘要正文；实现方原样返回，截断与兜底由 {@link ConversationSummarizer} 做
     */
    String generate(String prompt);
}
