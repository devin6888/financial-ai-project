package com.finagent.document;

import com.finagent.storage.ObjectStorage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;

/**
 * 解析编排：登记文档 -> 提交作业 -> 产物写 COS -> 更新 document。
 *
 * <p>分三层，职责不混：Adapter 只管 HTTP、Repository 只管 DB、本类负责编排。
 * 这样换解析引擎不影响持久化，换存储也不影响解析。
 * 持久化全部在这里完成 —— Python 解析服务不接触 DB 与 COS。
 */
@Service
// 与 ObjectStorageConfig 用同一个条件：没有 COS 凭据时，产物无处可放，
// 因此不装配本服务 —— 但应用仍能正常启动（阶段 1 定下的原则）。
@ConditionalOnProperty(prefix = "finagent.cos",
        name = {"secret-id", "secret-key", "region", "bucket"})
public class DocumentParseService {

    private static final Logger log = LoggerFactory.getLogger(DocumentParseService.class);

    private final ParserAdapter parser;
    private final DocumentRepository documents;
    private final ObjectStorage storage;

    public DocumentParseService(ParserAdapter parser, DocumentRepository documents,
                                ObjectStorage storage) {
        this.parser = parser;
        this.documents = documents;
        this.storage = storage;
    }

    /** 便捷入口：先幂等登记公司，再解析。 */
    public long parseAndStore(String companyCode, String companyName, String market,
                              String docType, Integer docYear, String title,
                              String pdfObjectKey, byte[] pdf) {
        long companyId = documents.upsertCompany(companyCode, companyName, market);
        return parseAndStore(companyId, docType, docYear, title, pdfObjectKey, pdf);
    }

    /** 解析并持久化，返回 document id。 */
    public long parseAndStore(long companyId, String docType, Integer docYear, String title,
                              String pdfObjectKey, byte[] pdf) {
        String contentHash = sha256(pdf);
        // 先落库拿 document_id —— 进程在此之后崩了，也留得下「这个文档待解析」的痕迹
        long documentId = documents.upsertDocument(companyId, docType, docYear, title,
                pdfObjectKey, contentHash);
        log.info("文档已登记 document_id={} hash={}", documentId, contentHash);
        try {
            // 提交与取结果**分成两步**，中间把 job_id 落库 —— 这是「durable」的支点：
            // 客户端在轮询期间断开时，作业仍在服务端跑，恢复流程能凭 job_id 把它接回来。
            // （初版让适配器把 submit+poll 包在一起，job_id 拿不到，断线后就只能判定"从未提交"。）
            String jobId = parser.submit(pdf, title + ".pdf", null, null);
            documents.markRunning(documentId, jobId);

            ParsedDocument parsed = parser.fetchResult(jobId);
            persistResult(documentId, contentHash, parsed);
            return documentId;
        } catch (RuntimeException e) {
            documents.markFailed(documentId, e.getClass().getSimpleName() + ": " + e.getMessage());
            throw e;
        }
    }

    /**
     * 恢复在途作业。由 {@link DocumentParseRecovery} 在启动时调用。
     *
     * <p>两类行分开处理，这是「durable」真正兑现的地方：
     * <ul>
     *   <li>parse_job_id 为空 —— 进程在「登记文档」与「提交作业」之间崩了，作业从未提交成功。
     *       标记为 FAILED 等待重提交（不自动重提交：181 页要 25 分钟，开机静默重跑不合适）</li>
     *   <li>parse_job_id 有值 —— 继续取回结果；解析服务已重启导致作业丢失时标记 FAILED</li>
     * </ul>
     */
    public void recoverInFlight() {
        List<DocumentRepository.InFlightDocument> inFlight = documents.findInFlight();
        if (inFlight.isEmpty()) {
            return;
        }
        log.warn("发现 {} 个在途解析作业，开始恢复", inFlight.size());
        for (DocumentRepository.InFlightDocument doc : inFlight) {
            if (doc.jobId() == null || doc.jobId().isBlank()) {
                documents.markFailed(doc.documentId(),
                        "进程在提交作业前中断，作业从未提交（需重新提交）");
                log.warn("document_id={} 无 job_id -> FAILED", doc.documentId());
                continue;
            }
            try {
                ParsedDocument parsed = parser.fetchResult(doc.jobId());
                persistResult(doc.documentId(), doc.contentHash(), parsed);
                log.info("document_id={} 已从作业 {} 恢复完成", doc.documentId(), doc.jobId());
            } catch (RuntimeException e) {
                documents.markFailed(doc.documentId(), "恢复失败：" + e.getMessage());
                log.warn("document_id={} 恢复失败", doc.documentId(), e);
            }
        }
    }

    /** 产物写 COS 并更新 document。主流程与恢复流程共用。 */
    private void persistResult(long documentId, String contentHash, ParsedDocument parsed) {
        // key 里带 parser 版本：重解析写新对象，老产物不被覆盖（合并方案的代价缓解）
        String resultKey = "parsed/" + contentHash + "/" + parsed.parserName() + "-"
                + parsed.parserVersion() + "/normalized.json";
        storage.put(resultKey, parsed.rawJson().getBytes(StandardCharsets.UTF_8),
                "application/json");
        documents.markDone(documentId, resultKey, parsed.parserName(),
                parsed.parserVersion(), parsed.pageCount());
        // 报告期是「类型 + 年份」的纯函数，解析完成即可确定；阶段 5 靠它判定「本期证据」
        documents.backfillPeriod(documentId);
        log.info("解析完成 document_id={} 产物={}", documentId, resultKey);
    }

    static String sha256(byte[] data) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(data);
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("计算 SHA-256 失败", e);
        }
    }
}
