package com.finagent.document;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 解析入库进度上报（ST18 需求 2）。
 *
 * <p>为什么单独一个类而不是各处直接调 {@link DocumentRepository#updateProgress}：
 * <b>进度上报永远不能让主流程失败</b>。文档已经解析完、只是进度写不进去时，正确的行为是
 * 「日志一条、继续入库」，而不是把整份文档标成 FAILED。这个 try/catch 只写一次，
 * 比在五个调用点各写一遍可靠。
 *
 * <p>检查点常量集中在这里 —— 前端文案与后端数字才不会一边说「切块中」一边显示 45%。
 * 口径见 {@code docs/关键契约.md} C28。
 */
@Service
public class DocumentProgress {

    private static final Logger log = LoggerFactory.getLogger(DocumentProgress.class);

    /** 已入队（事件已投递，等 worker 取）。 */
    public static final int QUEUED = 2;
    public static final String STAGE_QUEUED = "排队中";

    /** worker 开始干活：从 COS 读回 PDF 并提交解析作业。 */
    public static final int SUBMITTING = 8;
    public static final String STAGE_SUBMITTING = "提交解析作业";

    /** 解析服务已受理作业，正在转换（这一段最久，180 页约 20 分钟）。 */
    public static final int PARSING = 12;
    public static final String STAGE_PARSING = "解析中";

    /** Docling 转换完成、产物已落 COS 并写回 document。 */
    public static final int PARSED = 45;
    public static final String STAGE_PARSED = "解析完成";

    /** 开始切块（父块 + 子块，纯 CPU，几秒）。 */
    public static final int CHUNKING = 50;
    public static final String STAGE_CHUNKING = "切块中";

    /** 切块完成，开始向量化。 */
    public static final int CHUNKED = 55;
    public static final String STAGE_CHUNKED = "切块完成";

    /** 向量化区间上界：CHUNKED → VECTORIZED 之间按批次插值。 */
    public static final int VECTORIZED = 95;
    public static final String STAGE_EMBEDDING = "向量化";

    /** 全部完成（chunk 已原子切换到 ACTIVE 版本）。 */
    public static final int DONE = 100;
    public static final String STAGE_DONE = "已完成";

    private final DocumentRepository documents;

    public DocumentProgress(DocumentRepository documents) {
        this.documents = documents;
    }

    /**
     * 上报一个检查点。
     *
     * <p>吞掉异常只记 warn：进度是「观测」，不是「状态」。写不进去不该把文档拖成失败。
     */
    public void step(long documentId, int percent, String stage) {
        try {
            documents.updateProgress(documentId, percent, stage);
        } catch (RuntimeException e) {
            log.warn("进度上报失败（忽略）document_id={} {}% {}：{}",
                    documentId, percent, stage, e.toString());
        }
    }

    /**
     * 向量化批次回调：把「已嵌 n / 共 total」映射到 {@link #CHUNKED}~{@link #VECTORIZED} 区间。
     *
     * <p>为什么按条数插值而不是按批次数：最后一批可能不满 10 条，按批次算会提前跳到 95。
     */
    public void embeddingStep(long documentId, int done, int total) {
        if (total <= 0) {
            return;
        }
        int span = VECTORIZED - CHUNKED;
        int percent = CHUNKED + (int) Math.round((double) span * Math.min(done, total) / total);
        step(documentId, percent, STAGE_EMBEDDING + " " + Math.min(done, total) + "/" + total);
    }
}
