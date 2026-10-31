package com.finagent.chat;

/**
 * 流式事件的出口（使用注解声明为函数式接口）。
 *
 * 单测里直接收集事件列表，e2e 里换成真的 SseEmitter。
 */
@FunctionalInterface
public interface ChatStreamSink {

    void send(ChatStreamEvent event);
}
