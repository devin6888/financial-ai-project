package com.finagent.answer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Component;

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
}
