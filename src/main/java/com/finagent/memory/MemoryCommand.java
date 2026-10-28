package com.finagent.memory;

/**
 * 从用户原话里解析出来的一条待写入记忆。
 *
 * @param memoryType INSTRUCTION / CONTEXTUAL / WATCHLIST
 * @param memoryKey  结构化键（answer.style / focus.company.300059 ...）
 * @param content    去掉命令词之后的内容（「以后回答先给结论」→「回答先给结论」）
 * @param sourceRef  原话片段（写进 long_term_memory.source_ref，审计时能回溯用户到底说了什么）
 */
public record MemoryCommand(String memoryType, String memoryKey, String content, String sourceRef) {
}
