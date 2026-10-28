package com.finagent.memory;

/** 记忆不存在，或存在但不属于当前用户 —— 对外一律 404（不确认存在性，契约 C11）。
 * @author 67564*/
public class MemoryNotFoundException extends RuntimeException {

    public MemoryNotFoundException(long id) {
        super("记忆不存在：" + id);
    }
}
