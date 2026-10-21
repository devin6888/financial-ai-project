package com.finagent.context;

import java.util.List;

/**
 * 装配好的证据包。
 *
 * @param text            渲染后的全文（送进 LLM 的就是它）
 * @param blocks          与 text 里的 [E1]..[En] 一一对应
 * @param tokenCount      实际占用的预算（块 token 之和）
 * @param warnings        装配过程中的降级记录
 * @param parentsExpanded 展开过多少个 Parent
 * @param siblingsAdded   补齐了多少个邻居块
 */
public record ContextPackage(String text, List<ContextBlock> blocks, int tokenCount,
                             List<String> warnings, int parentsExpanded, int siblingsAdded) {

    public ContextPackage {
        text = text == null ? "" : text;
        blocks = blocks == null ? List.of() : List.copyOf(blocks);
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }

    public static ContextPackage empty() {
        return new ContextPackage("", List.of(), 0, List.of(), 0, 0);
    }

    public boolean isEmpty() {
        return blocks.isEmpty();
    }
}
