package com.finagent.chat;

/**
 * 流式事件的出口（使用注解声明为函数式接口）。
 *
 * <p>抽成接口是为了让 {@link ChatService} 不认识 Servlet / SseEmitter：
 * 单测里直接收集事件列表，e2e 里换成真的 SseEmitter。
 */
@FunctionalInterface
public interface ChatStreamSink {

    void send(ChatStreamEvent event);
}
