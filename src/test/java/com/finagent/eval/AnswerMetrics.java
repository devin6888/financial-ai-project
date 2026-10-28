package com.finagent.eval;

import java.util.List;

/** 答案层指标：引用准确率、数值准确率、拒答正确率、期间标注率。 */
public final class AnswerMetrics {

    private AnswerMetrics() { }

    /**
     * @param citationAccuracy 本题引用里「文档对且块里确有期望数值」的比例；无引用记 -1（不参与平均）
     * @param periodExpected   本题是否存在「引用文档年份 ≠ 问题年份」的引用
     * @param periodFlagged    响应里是否带 evidence_period_mismatch
     */
    public record Row(String id, String type, boolean sufficient, String confidence, boolean valuesOk,
                      int citations, double citationAccuracy, boolean periodExpected, boolean periodFlagged,
                      long ms) { }

    public record Summary(int total, int answerable, double citationAccuracy, double valueAccuracy,
                          double refusalAccuracy, int periodSamples, double periodRate, double avgMs) {

        public String line() {
            return String.format("total=%d answerable=%d citationAccuracy=%.3f valueAccuracy=%.3f "
                            + "refusalAccuracy=%.3f periodRate=%s(n=%d) avgMs=%.0f",
                    total, answerable, citationAccuracy, valueAccuracy, refusalAccuracy,
                    periodSamples == 0 ? "n/a" : String.format("%.3f", periodRate), periodSamples, avgMs);
        }

        public String toJson() {
            return String.format("{\"total\":%d,\"answerable\":%d,\"citationAccuracy\":%.4f,"
                            + "\"valueAccuracy\":%.4f,\"refusalAccuracy\":%.4f,\"periodSamples\":%d,"
                            + "\"periodRate\":%.4f,\"avgMs\":%.0f}",
                    total, answerable, citationAccuracy, valueAccuracy, refusalAccuracy,
                    periodSamples, periodSamples == 0 ? 1.0 : periodRate, avgMs);
        }
    }

    public static Summary summarize(List<Row> rows) {
        if (rows.isEmpty()) {
            return new Summary(0, 0, 0, 0, 0, 0, 1.0, 0);
        }
        List<Row> answerable = rows.stream().filter(r -> !r.type().equals("TRAP")).toList();
        List<Row> traps = rows.stream().filter(r -> r.type().equals("TRAP")).toList();

        double citationSum = 0;
        int citationItems = 0;
        int valuesOk = 0;
        for (Row r : answerable) {
            if (r.citations() > 0) {
                citationSum += r.citationAccuracy();
                citationItems++;
            }
            if (r.valuesOk()) {
                valuesOk++;
            }
        }
        long refused = traps.stream().filter(r -> !r.sufficient()).count();
        long periodSamples = rows.stream().filter(Row::periodExpected).count();
        long periodFlagged = rows.stream().filter(r -> r.periodExpected() && r.periodFlagged()).count();
        long totalMs = rows.stream().mapToLong(Row::ms).sum();

        return new Summary(rows.size(), answerable.size(),
                citationItems == 0 ? 0 : citationSum / citationItems,
                answerable.isEmpty() ? 0 : (double) valuesOk / answerable.size(),
                traps.isEmpty() ? 1.0 : (double) refused / traps.size(),
                (int) periodSamples,
                periodSamples == 0 ? 1.0 : (double) periodFlagged / periodSamples,
                totalMs / rows.size());
    }
}
