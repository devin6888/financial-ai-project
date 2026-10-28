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

    /**
     * 流式生成：每拿到一段就回调一次，全部完成后返回。
     *
     * <p>默认实现退化为「一次性 complete 再整段回调」—— 这样所有测试里的假实现
     * 都不用改，也保证「不支持流式的模型」在语义上仍然正确（只是没有中间态）。
     * 真正的流式实现见 {@code ChatClientAnswerGenerator}。
     */
    default void stream(String systemPrompt, String userPrompt, java.util.function.Consumer<String> onDelta) {
        String raw = complete(systemPrompt, userPrompt);
        if (raw != null && !raw.isEmpty()) {
            onDelta.accept(raw);
        }
    }
}
