package com.finagent.chat;

import com.finagent.scope.ScopeKind;

import java.util.List;

/** 本轮作用域在 trace 里的快照。 */
public record ScopeTrace(ScopeKind kind, List<Long> documentIds, String resolverVersion,
                         String effectiveReason, boolean docRoutingAvailable) { }
