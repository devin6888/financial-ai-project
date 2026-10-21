package com.finagent.answer;

/**
 * LLM 调用的唯一出口。
 *
 * <p>抽成接口不是为了「以后换模型」，而是为了让 AnswerService 的规则（拒答、越界剔除、
 * 冲突降级）能在不启动模型、不烧 token 的情况下被测试覆盖。
 */
public interface AnswerGenerator {

    /** @return 模型原始输出（可能带 markdown 围栏、可能不是 JSON） */
    String complete(String systemPrompt, String userPrompt);
}
