package com.finagent.understanding;

/**
 * 一轮对话的「压缩形态」：用户说了什么、助手答了什么。
 *
 * <p>为什么不直接把 {@code ConversationMessage} 传进 QU：understanding 包不能反向依赖 memory 包
 * （memory 已经 import 了 understanding），否则两个包互相依赖，以后想单独复用 QU 就拆不出去。
 *
 * <p>为什么每侧截到 200 字：指代消解只需要看出「上一轮问的是谁、哪一年、哪个指标」，
 * 整段答案塞进 Prompt 只会挤掉真正要检索的内容。
 */
public record ContextTurn(String user, String assistant) {

    /** 单侧保留的最大字符数。 */
    public static final int MAX_CHARS = 200;

    public static ContextTurn of(String user, String assistant) {
        return new ContextTurn(shrink(user), shrink(assistant));
    }

    /** 渲染成两行（助手那行可能缺失，缺了就只写用户那行）。 */
    public String render() {
        StringBuilder sb = new StringBuilder();
        if (user != null && !user.isBlank()) {
            sb.append("用户：").append(user);
        }
        if (assistant != null && !assistant.isBlank()) {
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append("助手：").append(assistant);
        }
        return sb.toString();
    }

    static String shrink(String text) {
        if (text == null) {
            return null;
        }
        String stripped = text.strip();
        if (stripped.length() <= MAX_CHARS) {
            return stripped;
        }
        return stripped.substring(0, MAX_CHARS) + "…";
    }
}
