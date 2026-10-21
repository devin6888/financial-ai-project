package com.finagent.scope;

import java.util.List;

/**
 * 作用域解析出 0 份可检索文档。
 *
 * <p><b>为什么是异常而不是返回空集合：</b>空集合往上走会变成「检索返回 0 条」，
 * 于是用户看到的是「没找到相关证据」—— 但真实原因是「选的那份文档还没解析完」
 * 或者「2023 年的报告库里根本没有」。两种情况的处理方式完全不同，必须分开报。
 */
public class ScopeEmptyException extends RuntimeException {

    private final List<String> warnings;

    public ScopeEmptyException(String message, List<String> warnings) {
        super(message);
        this.warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }

    public List<String> warnings() {
        return warnings;
    }
}
