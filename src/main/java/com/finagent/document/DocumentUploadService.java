package com.finagent.document;

import com.finagent.ingest.IngestFlow;
import com.finagent.ingest.IngestTracker;
import com.finagent.security.TenantContext;
import com.finagent.storage.ObjectStorage;
import com.finagent.task.DocumentTaskProducer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * 文档上传入口（阶段 7 REST）：上传 PDF 到 COS → 登记 document（PENDING）→ 投递 document.parse 事件。
 *
 * <p>与 {@link DocumentParseService#parseAndStore} 的「登记 + 同步解析」不同：
 * 本类只负责「登记 + 投递」，真正的解析在 worker（{@code DocumentParseListener}）里异步完成，
 * REST 秒回 documentId，不阻塞上传方。
 *
 * <p>与 {@link DocumentParseService} 用同一个装配条件（COS 四件套齐备）：没有 COS，
 * 产物无处可放，上传链路整体不装配 —— 但应用仍能正常启动（阶段 1 定下的原则）。
 */
@Service
@ConditionalOnProperty(prefix = "finagent.cos",
        name = {"secret-id", "secret-key", "region", "bucket"})
public class DocumentUploadService {

    private static final Logger log = LoggerFactory.getLogger(DocumentUploadService.class);

    private final DocumentRepository documents;
    private final ObjectStorage storage;
    private final DocumentTaskProducer producer;
    private final DocumentProgress progress;
    private final IngestTracker tracker;

    public DocumentUploadService(DocumentRepository documents, ObjectStorage storage,
                                 DocumentTaskProducer producer, DocumentProgress progress,
                                 IngestTracker tracker) {
        this.documents = documents;
        this.storage = storage;
        this.producer = producer;
        this.progress = progress;
        this.tracker = tracker;
    }

    /**
     * 上传 PDF 并触发异步解析，返回 documentId 与当前解析状态。
     *
     * <p>幂等快路径：同一份 PDF（同 content_hash）已解析且入库（DONE + 有 ACTIVE chunk）时，
     * 直接返回已有 id，不重复上传、不重复投递事件。
     *
     * @return parseStatus 为 PENDING（已入队）或 DONE（命中幂等快路径）
     */
    public UploadResult upload(String companyCode, String companyName, String market,
                               String docType, Integer docYear, String title, byte[] pdf) {
        if (pdf == null || pdf.length == 0) {
            throw new IllegalArgumentException("PDF 内容为空");
        }
        if (companyCode == null || companyCode.isBlank()) {
            throw new IllegalArgumentException("companyCode 不能为空");
        }
        if (companyName == null || companyName.isBlank()) {
            throw new IllegalArgumentException("companyName 不能为空");
        }
        if (docType == null || docType.isBlank()) {
            throw new IllegalArgumentException("docType 不能为空");
        }
        if (docYear == null) {
            throw new IllegalArgumentException("docYear 不能为空");
        }
        if (title == null || title.isBlank()) {
            throw new IllegalArgumentException("title 不能为空");
        }

        String contentHash = DocumentParseService.sha256(pdf);

        // 幂等快路径：已解析入库的同一份 PDF 直接返回，不重复解析（省 50 分钟 + 一次 API 费）
        Optional<Long> done = documents.findCompletedIdByContentHash(contentHash);
        if (done.isPresent()) {
            log.info("content_hash={} 已解析入库（document_id={}），跳过重复上传", contentHash, done.get());
            return new UploadResult(done.get(), "DONE");
        }

        String sourceKey = sourceKey(companyCode, docType, docYear);
        storage.put(sourceKey, pdf, "application/pdf");

        // 上传是 HTTP 请求：租户只有一个可信来源（Bearer token → 上下文）
        long tenantId = TenantContext.require().tenantId();
        long companyId = documents.upsertCompany(companyCode, companyName, market, tenantId);
        long documentId = documents.upsertDocument(companyId, docType, docYear, title,
                sourceKey, contentHash);
        log.info("文档已登记 document_id={} hash={} sourceKey={}，投递 document.parse",
                documentId, contentHash, sourceKey);
        // 入队即给 2%：用户上传后立刻看到「排队中」，而不是盯着 0% 猜有没有收到
        progress.step(documentId, DocumentProgress.QUEUED, DocumentProgress.STAGE_QUEUED);
        // 执行记录在「投递之前」开：worker 可能几百毫秒就被唤醒，先开 run 才不会丢第一步
        tracker.start(documentId, IngestFlow.PARSE);
        producer.sendParse(documentId);
        return new UploadResult(documentId, "PENDING");
    }

    /** 源文件对象键：samples/{code}-{year}-{docType 小写}.pdf（与 IngestReportTest 一致，便于追溯）。 */
    static String sourceKey(String companyCode, String docType, Integer docYear) {
        return "samples/" + companyCode + "-" + docYear + "-" + docType.toLowerCase() + ".pdf";
    }

    /** 上传结果：documentId + 当前解析状态（PENDING=已入队，DONE=命中幂等快路径）。 */
    public record UploadResult(long documentId, String parseStatus) {
    }
}
