package com.finagent.chat;

import com.finagent.scope.ScopeKind;

import java.util.List;

/**
 * 响应里的作用域回执：用户看得到「这轮到底用了哪些文档」。
 *
 * <p>阶段 5 还没有持久会话，{@code session} 就是本轮解析出的范围；
 * 阶段 9 引入 conversation 表后，session 才是独立于单轮的配置。
 */
public record ScopeInfo(SessionScope session, EffectiveScope effective,
                        List<Long> resolvedDocumentIds, String resolverVersion) {

    public record SessionScope(ScopeKind kind, List<Long> documentIds) { }

    /** reason：SESSION（会话配置）/ TURN_ONLY（仅本轮扩展）/ SESSION_EXTENSION */
    public record EffectiveScope(ScopeKind kind, List<Long> documentIds, String reason) { }
}
