package com.finagent.chat;

import com.finagent.memory.MemoryContext;

/**
 * 记忆段轨迹（D7：记忆进 trace，但不进引用编号）。
 *
 * <p>为什么不把记忆塞进 <b>证据块</b>：引用编号 [E1..En] 的「E 后面的数字」是数组下标
 * （见 {@code CitationValidator}），而记忆没有 documentId / page / bbox —— 塞进去会让编号整体错位，
 * 又会产出「看起来有出处却点不开」的引用。所以记忆只在这里留痕，供评测与排障使用。
 *
 * @param shortTermMessages 进 Prompt 的短期消息条数
 * @param shortTermDropped  因预算被整条丢掉的消息条数
 * @param summaryChars      摘要字符数（0 表示没有摘要）
 * @param longTermItems     进 Prompt 的长期记忆条数
 * @param gateHit           召回闸门是否通过（false 时上面 longTermItems 必为 0，且没有花 embedding）
 */
public record MemoryTrace(int shortTermMessages, int shortTermDropped, int summaryChars,
                          int longTermItems, boolean gateHit) {

    public static final MemoryTrace EMPTY = new MemoryTrace(0, 0, 0, 0, false);

    public static MemoryTrace from(MemoryContext memory) {
        if (memory == null || memory.stats() == null) {
            return EMPTY;
        }
        MemoryContext.Stats stats = memory.stats();
        return new MemoryTrace(stats.shortTermMessages(), stats.shortTermDropped(),
                stats.summaryChars(), stats.longTermItems(), stats.gateHit());
    }
}
