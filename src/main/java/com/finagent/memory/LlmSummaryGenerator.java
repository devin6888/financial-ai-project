package com.finagent.memory;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Component;

/**
 * 用 qwen-plus 生成摘要（D3：摘要用 LLM，失败降级为「不摘要」）。
 *
 * <p>Prompt 只要求三件事：保留已确认的结论（带数字与年份）、未完成的问题、用户声明的偏好；
 * 丢掉寒暄与重复。摘要<b>不做引用</b>，所以这里也不要求它标注出处。
 */
/**
 * 注意：这个 Bean <b>一直存在</b>，不随 {@code finagent.memory.enabled} 卸载。
 *
 * <p>「关掉记忆」是运行时行为开关（装配器与摘要器各自判它），不是「把 Bean 拆掉」——
 * 后者会让 {@code ChatService} 直接启动失败，等于关不掉。
 */
@Component
public class LlmSummaryGenerator implements SummaryGenerator {

    static final String SYSTEM = """
            你是金融研报问答助手的会话摘要模块。把给定的旧摘要与新对话压缩成一段新的中文摘要。

            必须保留：
            1. 已经确认的结论，尤其是数字与年份（如「2025年营业收入 1200 亿」）；
            2. 用户提出但还没解决的问题；
            3. 用户明确声明过的偏好或约束。

            必须丢弃：寒暄、重复表述、助手自己的推理过程。
            只输出摘要正文，不要标题、不要列表符号、不要引用标记。控制在 200 字以内。
            """;

    private final ChatClient chat;

    public LlmSummaryGenerator(ChatClient.Builder builder) {
        this.chat = builder.build();
    }

    @Override
    public String generate(String prompt) {
        return chat.prompt().system(SYSTEM).user(prompt).call().content();
    }
}
