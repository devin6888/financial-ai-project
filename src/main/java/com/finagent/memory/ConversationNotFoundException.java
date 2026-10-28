package com.finagent.memory;

/**
 * 会话不存在、不属于当前用户、或已删除 —— 三种情况对外都是 404。
 *
 * <p>为什么不是 403：403 等于确认「这个 id 存在，只是你不能看」，
 * 攻击者可以据此枚举会话 id 的分布。404 不确认存在性（C11 的同一条规则）。
 */
public class ConversationNotFoundException extends RuntimeException {

    public ConversationNotFoundException(long conversationId) {
        super("会话不存在：" + conversationId);
    }
}
