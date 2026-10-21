package com.finagent.context;

/**
 * 一块送进 LLM 的证据。
 *
 * <p><b>content 永远是原文</b>，绝不放 embedding_text ——
 * embedding_text 里有 doc_title 与 section_path 的副本，既浪费 token，
 * 又会让模型把「为了检索拼出来的文本」当成文档内容引用出去。
 *
 * @param evidenceNo 从 1 起，答案里用 [E1] 引用。模型只回编号，后端映射回 chunk ——
 *                   这样「引用必须真实存在」是结构性保证，而不是靠校验兜
 * @param role       HIT（检索命中）/ SIBLING（邻居补齐）/ PARENT_WHOLE（整段 Parent）
 */
public record ContextBlock(
        int evidenceNo,
        long chunkId,
        long parentId,
        long documentId,
        String docTitle,
        String sectionPath,
        String title,
        String content,
        Integer pageStart,
        Integer pageEnd,
        String chunkType,
        String caption,
        String unit,
        String headersJson,
        String bboxJson,
        double score,
        int chunkIndex,
        /** 库里的 token_count，预算按它累加；不用渲染文本重算，避免与切块口径不一致 */
        int tokenCount,
        String role
) {
    public static final String ROLE_HIT = "HIT";
    public static final String ROLE_SIBLING = "SIBLING";
    public static final String ROLE_PARENT_WHOLE = "PARENT_WHOLE";

    /** 换一个编号，其余不变（装配时先收集、最后统一编号）。 */
    public ContextBlock withEvidenceNo(int no) {
        return new ContextBlock(no, chunkId, parentId, documentId, docTitle, sectionPath, title,
                content, pageStart, pageEnd, chunkType, caption, unit, headersJson, bboxJson,
                score, chunkIndex, tokenCount, role);
    }

    /** 是否表格块（决定渲染时要不要「表：/表头：」两行）。 */
    public boolean isTable() {
        return "TABLE".equalsIgnoreCase(chunkType);
    }
}
