package com.finagent.answer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Component;

import java.util.function.Consumer;

/** 走 Spring AI ChatClient 的默认实现（qwen-plus）。 */
@Component
public class ChatClientAnswerGenerator implements AnswerGenerator {

    private static final Logger log = LoggerFactory.getLogger(ChatClientAnswerGenerator.class);

    private final ChatClient chat;

    public ChatClientAnswerGenerator(ChatClient.Builder builder) {
        this.chat = builder.build();
    }

    @Override
    public String complete(String systemPrompt, String userPrompt) {
        String raw = chat.prompt().system(systemPrompt).user(userPrompt).call().content();
        log.debug("答案生成返回 {} 字符", raw == null ? 0 : raw.length());
        return raw;
    }

    /**
     * 真流式：模型一边生成一边回调。
     *
     * <p>{@code stream().content()} 拿到的是<b>原始文本增量</b>（不是结构化对象），
     * 正好交给 {@link AnswerFieldExtractor} 去增量提取 answer 字段。
     * 这里只负责把 Flux 拉完（blockLast），异常向上抛给 AnswerService 统一降级。
     */
    @Override
    public void stream(String systemPrompt, String userPrompt, Consumer<String> onDelta) {
        chat.prompt().system(systemPrompt).user(userPrompt)
                .stream().content()
                .doOnNext(chunk -> {
                    if (chunk != null && !chunk.isEmpty()) {
                        onDelta.accept(chunk);
                    }
                })
                .blockLast();
    }
}
