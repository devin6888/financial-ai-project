package com.finagent.chunk;

/**
 * token 估算。
 *
 * <p>为什么估算而不引入 tokenizer：§8.2 用 token 定义 Child（300~700）与 Parent 的粒度，
 * 但切块边界本来就允许误差（设计文档也说"优先按 paragraph/sentence 边界拆"）。
 * 为了切块边界而引入一个真正的 tokenizer，是过度依赖 —— 而且换 tokenizer 会让
 * 已入库的 chunk 边界全部失效。
 *
 * <p>估算规则（对中文财报足够）：
 * <ul>
 *   <li>每个 CJK 字符算 1 个 token（Qwen 系对中文约合一字一 token）</li>
 *   <li>连续 ASCII 字母/数字算 1 个 token（按「词」而非「字符」）</li>
 *   <li>其余非空白字符（标点、括号、全角符号）各算 1 个</li>
 * </ul>
 *
 * <p>若要精确，替换本类即可 —— 切块逻辑只依赖这个静态方法。
 * <b>但替换时必须同时升 {@link ChunkStrategy#VERSION}</b>，否则已入库的 chunk 与新切法不一致。
 */
public final class TokenEstimator {

    public static int estimate(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        int tokens = 0;
        boolean inAsciiWord = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isWhitespace(c)) {
                inAsciiWord = false;
                continue;
            }
            if (isCjk(c)) {
                tokens++;
                inAsciiWord = false;
            } else if (isAsciiWordChar(c)) {
                if (!inAsciiWord) {
                    tokens++;
                    inAsciiWord = true;
                }
            } else {
                tokens++;
                inAsciiWord = false;
            }
        }
        return tokens;
    }

    private static boolean isCjk(char c) {
        return (c >= 0x4E00 && c <= 0x9FFF)      // 基本汉字
                || (c >= 0x3400 && c <= 0x4DBF)  // 扩展 A
                || (c >= 0xF900 && c <= 0xFAFF); // 兼容汉字
    }

    private static boolean isAsciiWordChar(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                || (c >= '0' && c <= '9');
    }

    private TokenEstimator() {
    }
}
