package com.finagent.scope;

import java.util.List;

/**
 * 作用域权限检查（阶段 5 只占位）。
 *
 * <p>为什么现在就要有这个接口：设计文档 §9.14 明确「ACL / tenant 不由 Router 生成」，
 * 而作用域解析是唯一能一次性完成权限过滤的地方（解析阶段就知道最终是哪几份）。
 * 等到阶段 8 再加，就要在检索、上下文、引用三处各补一次。
 */
public interface ScopeAuthorizer {

    /** 过滤出当前调用方有权读取的文档 id。未知 id 原样保留，存在性由 ScopeResolver 判断。 */
    List<Long> filterReadable(List<Long> documentIds);
}
