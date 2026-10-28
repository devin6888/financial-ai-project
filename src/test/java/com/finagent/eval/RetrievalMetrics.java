package com.finagent.eval;

import java.util.List;

/** 检索指标：纯函数，不碰数据库、不碰模型，便于单测与复用。 */
public final class RetrievalMetrics {

    private RetrievalMetrics() { }

    /**
     * @param rank      首个命中证据的名次（1 起）；没命中记 0
     * @param topDocIds top-K 里出现的文档 id（去重前的顺序无所谓）
     */
    public record Case(String id, String type, int rank, List<Long> topDocIds, List<Long> expectedDocIds) { }

    /**
     * @param recallAt5  可答题里 top-5 至少含一条标注证据的比例
     * @param docRecall10 可答题里 top-10 出现「期望文档」的比例（只答对文档、没答对块也算分）
     */
    public record Summary(int cases, double recallAt5, double recallAt10, double docRecall10, double mrr) {

        public String toJson() {
            return String.format("{\"cases\":%d,\"recallAt5\":%.4f,\"recallAt10\":%.4f,"
                            + "\"docRecall10\":%.4f,\"mrr\":%.4f}",
                    cases, recallAt5, recallAt10, docRecall10, mrr);
        }

        public String line() {
            return String.format("cases=%d recall@5=%.3f recall@10=%.3f docRecall@10=%.3f mrr=%.3f",
                    cases, recallAt5, recallAt10, docRecall10, mrr);
        }
    }

    public static Summary summarize(List<Case> cases) {
        if (cases.isEmpty()) {
            return new Summary(0, 0, 0, 0, 0);
        }
        int hit5 = 0;
        int hit10 = 0;
        int docHit = 0;
        double rr = 0;
        for (Case c : cases) {
            if (c.rank() == 0) {
                continue;
            }
            if (c.rank() <= 5) {
                hit5++;
            }
            if (c.rank() <= 10) {
                hit10++;
                rr += 1.0 / c.rank();
                if (c.topDocIds().stream().anyMatch(c.expectedDocIds()::contains)) {
                    docHit++;
                }
            }
        }
        int n = cases.size();
        return new Summary(n, (double) hit5 / n, (double) hit10 / n, (double) docHit / n, rr / n);
    }
}
