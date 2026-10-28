package com.finagent.memory;

import java.util.List;

/**
 * 背景信息的四层内容（阶段 9 分任务逐步填满）。
 *
 * <p>顺序就是注入顺序：会话上下文 → 摘要 → 最近对话 → 长期偏好。
 * 为空的那层整段省略（渲染层负责），不做「（无）」占位 —— 占位会占 token 还误导模型。
 */
public record MemorySections(String structured, String summary,
                             List<ConversationMessage> shortTerm, List<String> longTerm) {

    public static final MemorySections EMPTY = new MemorySections(null, null, List.of(), List.of());

    public MemorySections {
        shortTerm = shortTerm == null ? List.of() : List.copyOf(shortTerm);
        longTerm = longTerm == null ? List.of() : List.copyOf(longTerm);
    }

    public boolean isBlank() {
        return (structured == null || structured.isBlank())
                && (summary == null || summary.isBlank())
                && shortTerm.isEmpty()
                && longTerm.isEmpty();
    }
}
