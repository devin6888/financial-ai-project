package com.finagent.answer;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 增量提取 answer 字段。用例覆盖的都是真实流式里会遇到、平时想不到的边界：
 * key 被切开、转义被切开、note 里出现 answer 字样。
 */
class AnswerFieldExtractorTest {

    /** 反斜杠与引号都用字符码构造，避开 Java 的 Unicode 转义预处理。 */
    private static final String BS = String.valueOf((char) 92);
    private static final String Q = String.valueOf((char) 34);

    private static String doc(String answerRaw) {
        return """
                {"sufficient":true,"answer":"%s","facts":[],"usedEvidence":["E1"],
                 "confidence":"high","note":""}
                """.formatted(answerRaw);
    }

    @Test
    @DisplayName("一次喂完整 JSON 就能提取 answer")
    void singleChunk() {
        AnswerFieldExtractor e = new AnswerFieldExtractor();
        assertThat(e.feed(doc("营业总收入 12 亿元 [E1]"))).isEqualTo("营业总收入 12 亿元 [E1]");
        assertThat(e.done()).isTrue();
    }

    @Test
    @DisplayName("key 被切成两段时等下一段再识别")
    void splitInsideKey() {
        String json = doc("答案");
        int cut = json.indexOf("answer") + 3;
        AnswerFieldExtractor e = new AnswerFieldExtractor();
        assertThat(e.feed(json.substring(0, cut))).isEmpty();
        assertThat(e.feed(json.substring(cut))).isEqualTo("答案");
    }

    @Test
    @DisplayName("值被切成多段时每段只返回新增内容")
    void splitInsideValue() {
        String json = doc("东方财富 2025 年营业总收入为 160.68 亿元");
        int from = json.indexOf("东方财富");
        AnswerFieldExtractor e = new AnswerFieldExtractor();
        StringBuilder out = new StringBuilder();
        out.append(e.feed(json.substring(0, from + 3)));
        out.append(e.feed(json.substring(from + 3, from + 8)));
        out.append(e.feed(json.substring(from + 8)));
        assertThat(out.toString()).isEqualTo("东方财富 2025 年营业总收入为 160.68 亿元");
    }

    @Test
    @DisplayName("转义引号在输出里还原成普通引号")
    void escapedQuote() {
        String raw = "a" + BS + Q + "b";
        AnswerFieldExtractor e = new AnswerFieldExtractor();
        assertThat(e.feed(doc(raw))).isEqualTo("a" + Q + "b");
    }

    @Test
    @DisplayName("转义被切开（反斜杠落在段尾）时不丢字符")
    void splitInsideEscape() {
        String raw = "前" + BS + Q + "后";
        String json = doc(raw);
        int at = json.indexOf(BS);
        AnswerFieldExtractor e = new AnswerFieldExtractor();
        String first = e.feed(json.substring(0, at + 1));
        String rest = e.feed(json.substring(at + 1));
        assertThat(first + rest).isEqualTo("前" + Q + "后");
    }

    @Test
    @DisplayName("Unicode 转义解码成中文")
    void unicodeEscape() {
        String raw = BS + "u4e2d" + "文";
        AnswerFieldExtractor e = new AnswerFieldExtractor();
        assertThat(e.feed(doc(raw))).isEqualTo("中文");
    }

    @Test
    @DisplayName("note 里出现 answer 字样时不会被当成 key")
    void ignoresAnswerWordInsideOtherString() {
        String json = """
                {"sufficient":false,"answer":"真答案","facts":[],"usedEvidence":[],
                 "confidence":"low","note":"the answer is not available"}
                """;
        AnswerFieldExtractor e = new AnswerFieldExtractor();
        assertThat(e.feed(json)).isEqualTo("真答案");
    }

    @Test
    @DisplayName("没有 answer 字段时一直返回空串且不抛异常")
    void noAnswerField() {
        AnswerFieldExtractor e = new AnswerFieldExtractor();
        assertThat(e.feed("""
                {"sufficient":false,"note":"x"}
                """)).isEmpty();
        assertThat(e.done()).isFalse();
    }

    @Test
    @DisplayName("读到结束引号后不再输出后续内容")
    void stopsAtClosingQuote() {
        AnswerFieldExtractor e = new AnswerFieldExtractor();
        assertThat(e.feed(doc("答案"))).isEqualTo("答案");
        assertThat(e.feed(doc("另一个"))).isEmpty();
    }
}