package com.finagent.eval;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * 人工核验表：把机器指标落成一张人能用眼睛过一遍的表。
 *
 * <p>机器只能判「引用块里有没有这个数字」，判不了「这个数字是不是回答了这个问题」。
 * 所以表里留一列「人工结论」给人填：正确 / 部分正确 / 错误 / 无法判断。
 */
public final class HumanReviewExporter {

    private HumanReviewExporter() { }

    public record Row(String id, String type, String question, String answer, String confidence,
                      int citationValid, int citationTotal, boolean valuesOk, String periodNote, long ms) { }

    public static void write(Path target, List<Row> rows, AnswerMetrics.Summary summary) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("# 阶段 6 人工核验表\n\n");
        sb.append("> 由 `AnswerEvalTest` 自动生成（").append(java.time.LocalDate.now()).append("）。\n");
        sb.append("> 机器指标：").append(summary.line()).append("\n\n");
        sb.append("复核方法：对每一行读「答案」列，确认三件事 ——（1）数值与报告一致；");
        sb.append("（2）引用页码能翻到那张表；（3）期间标注与你的预期一致。结论填最后一列。\n\n");
        sb.append("| 题号 | 类型 | 问题 | 置信 | 引用有效/总 | 数值 | 期间 | 耗时 | 答案摘要 | 人工结论 |\n");
        sb.append("| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |\n");
        for (Row r : rows) {
            sb.append("| ").append(r.id())
                    .append(" | ").append(r.type())
                    .append(" | ").append(escape(r.question()))
                    .append(" | ").append(r.confidence())
                    .append(" | ").append(r.citationValid()).append("/").append(r.citationTotal())
                    .append(" | ").append(r.valuesOk() ? "命中" : "缺失")
                    .append(" | ").append(r.periodNote() == null ? "-" : escape(r.periodNote()))
                    .append(" | ").append(r.ms()).append(" ms")
                    .append(" | ").append(escape(trim(r.answer(), 120)))
                    .append(" |  |\n");
        }
        Files.createDirectories(target.getParent());
        Files.writeString(target, sb.toString());
    }

    private static String trim(String s, int max) {
        if (s == null) {
            return "";
        }
        String one = s.replace('\n', ' ').replace('|', '/').strip();
        return one.length() <= max ? one : one.substring(0, max) + "…";
    }

    private static String escape(String s) {
        return s == null ? "" : s.replace("|", "/").replace("\n", " ");
    }
}
