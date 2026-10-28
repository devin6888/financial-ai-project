package com.finagent.memory;

import com.finagent.understanding.ContextTurn;

import java.util.List;

/**
 * 这一轮要注入 Prompt 的背景信息 + 它的统计 + 最近几轮（指代消解要用）。
 *
 * <p><b>为什么背景信息是「一整段文本」而不是结构化对象</b>：它要原样插进 Prompt，
 * 中间任何一步再加工都会让「Prompt 里到底写了什么」变得不可复现。trace 里也只存
 * 统计（条数/字符数），不存全文 —— 全文可能含用户隐私，进日志要谨慎。
 *
 * <p><b>为什么还要带 {@code recentTurns}</b>：短期窗口已经从库里读出来了，
 * 指代消解需要同一批消息的「配对形态」；再单独查一次库是重复查询。
 * 渲染 Prompt 用原始消息（要保留「用户：/助手：」标签与完整文本），消解用配对形态（每侧截断）。
 *
 * @param text        渲染好的会话记忆内容；null / 空白表示这一轮没有背景信息
 * @param stats       统计，供 trace 与报告使用
 * @param recentTurns 最近几轮的精简形态（从旧到新），喂给 QU 做指代消解
 * @param sections    渲染前的分层内容；长期记忆是在 QU 之后才召回的，要重新渲染时靠它，
 *                    否则只能把已经渲染好的文本硬拼一段进去（顺序与禁令句都会乱）。可为 null
 */
public record MemoryContext(String text, Stats stats, List<ContextTurn> recentTurns,
                           MemorySections sections) {

    public static final MemoryContext EMPTY = new MemoryContext(null, Stats.EMPTY, List.of(), null);

    public MemoryContext {
        recentTurns = recentTurns == null ? List.of() : List.copyOf(recentTurns);
    }

    /** 兼容阶段 9 Task 5 的 3 参调用点（sections 之前不存在）。 */
    public MemoryContext(String text, Stats stats, List<ContextTurn> recentTurns) {
        this(text, stats, recentTurns, null);
    }

    /** 兼容阶段 9 Task 3 的 2 参调用点（recentTurns 之前不存在）。 */
    public MemoryContext(String text, Stats stats) {
        this(text, stats, List.of(), null);
    }

    public record Stats(int shortTermMessages, int shortTermDropped, int summaryChars,
                        int longTermItems, boolean gateHit) {

        public static final Stats EMPTY = new Stats(0, 0, 0, 0, false);
    }

    public boolean isEmpty() {
        return text == null || text.isBlank();
    }
}
