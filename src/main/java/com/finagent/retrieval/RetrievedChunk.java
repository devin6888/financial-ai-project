package com.finagent.retrieval;

/**
 * 一个候选 chunk 及其在各阶段的排名与分数。
 *
 * <p>为什么把每一路的排名都留着（而不是只留最终分）：排查召回问题时必须能回答
 * 「这条是被 Dense 捞到的还是被 Sparse 捞到的」「Rerank 前后名次怎么变」。
 *
 * <p><b>排名约定：1 起</b>；某路没召回时该路排名为 <b>null</b>。
 * 不要用 0 表示「没召回」—— RRF 公式里 1/(k+0) 会高于任何真实命中（见 RrfFusion）。
 */
public record RetrievedChunk(
        long chunkId,
        long parentId,
        long documentId,
        String chunkType,
        String sectionPath,
        String title,
        String content,
        int pageStart,
        int pageEnd,
        String sourceBlockIds,
        /** Dense 路名次（1 起），未召回为 null */
        Integer denseRank,
        /** Sparse 路名次（1 起），未召回为 null */
        Integer sparseRank,
        /** RRF 融合分 */
        double rrfScore,
        /** Rerank 分（0~1），未精排为 null */
        Double rerankScore
) {
    public RetrievedChunk withRrfScore(double score) {
        return new RetrievedChunk(chunkId, parentId, documentId, chunkType, sectionPath,
                title, content, pageStart, pageEnd, sourceBlockIds,
                denseRank, sparseRank, score, rerankScore);
    }

    public RetrievedChunk withRerankScore(Double score) {
        return new RetrievedChunk(chunkId, parentId, documentId, chunkType, sectionPath,
                title, content, pageStart, pageEnd, sourceBlockIds,
                denseRank, sparseRank, rrfScore, score);
    }

    /** 用于日志与冒烟评测的简短展示。 */
    public String brief() {
        String head = content == null ? "" : content.replace('\n', ' ').strip();
        return "#" + chunkId + " [" + chunkType + "] p." + pageStart
                + " " + (head.length() > 50 ? head.substring(0, 50) + "..." : head);
    }
}
