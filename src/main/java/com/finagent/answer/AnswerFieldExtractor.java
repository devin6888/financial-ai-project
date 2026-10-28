package com.finagent.answer;

/**
 * 从模型输出的 JSON 里<b>增量</b>提取 answer 字段的字符串值。
 *
 * <p>存在的理由：流式与非流式必须用<b>同一次调用、同一份 JSON</b>。
 * 如果为了流式改一套纯文本提示词，就等于有两套答案质量，而评测只覆盖非流式那条，
 * 线上会被流式那条坑。所以这里做一件很小的事：JSON 字节流不断进来，
 * 只把 answer 字段的字符串内容解出来推给前端，其余字段（facts / usedEvidence /
 * confidence / note）等流结束后照常解析、照常走四层校验。
 *
 * <p>边界处理（都有单测）：
 * <ul>
 *   <li>key 可能被切成两段（"ans" + "wer"）→ 缓冲不够时不动，等下一段；</li>
 *   <li>note 等字段的内容里出现 "answer" 字样 → seek 阶段跟踪字符串状态，不会误判；</li>
 *   <li>转义被切开（反斜杠在段尾、或 u 转义只到了一半）→ 只消费完整的转义，否则回退等下一段；</li>
 *   <li>模型不吐 answer（sufficient=false 只给 note）→ 一直返回空串，不抛异常。</li>
 * </ul>
 *
 * <p>不是 JSON 解析器，也不打算是：它只处理「prompt 已固定 schema」这一种输出。
 * 真正的合法性判断仍由 {@link AnswerParser} 做。
 */
public final class AnswerFieldExtractor {

    private static final String KEY = "\"answer\"";

    /** 已收到的全部原文。不裁剪：pos 只前进，旧内容不会再被读到，但保留便于排查。 */
    private final StringBuilder buffer = new StringBuilder();
    private int pos = 0;

    /** seek 阶段的字符串状态：JSON 的 key 与字符串值都可能出现 "answer" 字样。 */
    private boolean inString = false;
    private boolean escaped = false;

    private boolean valueFound = false;
    private boolean done = false;

    /**
     * 喂一段原始输出，返回这一段里新解出的 answer 文本（可能为空串）。
     * 拼接所有返回值，必然等于最终 JSON 里 answer 字段的值。
     */
    public String feed(String chunk) {
        if (done) {
            return "";
        }
        if (chunk != null && !chunk.isEmpty()) {
            buffer.append(chunk);
        }
        if (!valueFound && !seekValueStart()) {
            return "";
        }
        return readValue();
    }

    /** 是否已经读到 answer 值的结束引号。 */
    public boolean done() {
        return done;
    }

    /** 找到 {@code "answer" : "} 之后的位置。数据不够时返回 false，等下一段。 */
    private boolean seekValueStart() {
        while (pos < buffer.length()) {
            char c = buffer.charAt(pos);
            if (escaped) {
                escaped = false;
                pos++;
                continue;
            }
            if (inString) {
                if (c == '\\') {
                    escaped = true;
                } else if (c == '"') {
                    inString = false;
                }
                pos++;
                continue;
            }
            if (c != '"') {
                pos++;
                continue;
            }
            int keyEnd = pos + KEY.length();
            if (keyEnd > buffer.length()) {
                return false;    // key 被切开，等下一段
            }
            if (!buffer.substring(pos, keyEnd).equals(KEY)) {
                inString = true;    // 普通字符串，跳到它的结束引号
                pos++;
                continue;
            }
            int p = skipWhitespace(keyEnd);
            if (p < 0) {
                return false;
            }
            if (buffer.charAt(p) != ':') {
                pos++;
                continue;
            }
            p = skipWhitespace(p + 1);
            if (p < 0) {
                return false;
            }
            if (buffer.charAt(p) != '"') {
                pos++;
                continue;
            }
            pos = p + 1;
            valueFound = true;
            return true;
        }
        return false;
    }

    /** @return 下一个非空白字符下标；数据不够返回 -1 */
    private int skipWhitespace(int from) {
        int p = from;
        while (p < buffer.length() && Character.isWhitespace(buffer.charAt(p))) {
            p++;
        }
        return p >= buffer.length() ? -1 : p;
    }

    private String readValue() {
        StringBuilder out = new StringBuilder();
        while (pos < buffer.length()) {
            char c = buffer.charAt(pos);
            if (c == '"') {
                done = true;
                pos++;
                return out.toString();
            }
            if (c != '\\') {
                out.append(c);
                pos++;
                continue;
            }
            if (pos + 1 >= buffer.length()) {
                return out.toString();    // 转义被切开，等下一段
            }
            char e = buffer.charAt(pos + 1);
            if (e == 'u') {
                if (pos + 6 > buffer.length()) {
                    return out.toString();
                }
                String hex = buffer.substring(pos + 2, pos + 6);
                try {
                    out.append((char) Integer.parseInt(hex, 16));
                } catch (NumberFormatException ex) {
                    out.append("\\u").append(hex);
                }
                pos += 6;
                continue;
            }
            out.append(switch (e) {
                case 'n' -> '\n';
                case 't' -> '\t';
                case 'r' -> '\r';
                case 'b' -> '\b';
                case 'f' -> '\f';
                case '"' -> '"';
                case '\\' -> '\\';
                case '/' -> '/';
                default -> e;
            });
            pos += 2;
        }
        return out.toString();
    }
}
