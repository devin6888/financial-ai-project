package com.finagent.document;

import com.finagent.chunk.ReindexMode;
import com.finagent.chunk.ReindexService;
import com.finagent.ingest.IngestFlow;
import com.finagent.ingest.IngestStep;
import com.finagent.ingest.IngestTracker;
import com.finagent.storage.ObjectStorage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import com.finagent.security.CurrentUser;
import com.finagent.security.TenantContext;

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

    /** 没有 HTTP 上下文时使用的租户（本机 demo 租户）。 */
    private static final long FALLBACK_TENANT_ID = 1L;

    private final ParserAdapter parser;
    private final DocumentRepository documents;
    private final ObjectStorage storage;
    private final ReindexService reindex;
    private final DocumentProgress progress;
    private final IngestTracker tracker;

    public DocumentParseService(ParserAdapter parser, DocumentRepository documents,
                                ObjectStorage storage, ReindexService reindex,
                                DocumentProgress progress, IngestTracker tracker) {
        this.parser = parser;
        this.documents = documents;
        this.storage = storage;
        this.reindex = reindex;
        this.progress = progress;
        this.tracker = tracker;
    }

    /** 便捷入口：先幂等登记公司，再解析。 */
    public long parseAndStore(String companyCode, String companyName, String market,
                              String docType, Integer docYear, String title,
                              String pdfObjectKey, byte[] pdf) {
        // 这条入口只被 CLI 与测试调用（没有 HTTP 上下文）：有上下文用上下文的租户，
        // 没有就用兜底租户（本机 demo = 1）。生产的上传链路走 DocumentUploadService，那里有上下文。
        long tenantId = TenantContext.optional().map(CurrentUser::tenantId).orElse(FALLBACK_TENANT_ID);
        long companyId = documents.upsertCompany(companyCode, companyName, market, tenantId);
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
        // 同步链路（CLI/测试）也留执行记录：库里能查到「这份 PDF 走的是哪条链路、用了多久」
        tracker.start(documentId, IngestFlow.PARSE);
        try {
            parseAndPersist(documentId, contentHash, title, pdf);
            tracker.succeed(documentId, IngestTracker.summary("pages", parsedPages(documentId)));
            return documentId;
        } catch (RuntimeException e) {
            tracker.fail(documentId, e.getClass().getSimpleName() + ": " + e.getMessage());
            documents.markFailed(documentId, e.getClass().getSimpleName() + ": " + e.getMessage());
            throw e;
        }
    }

    /**
     * 异步解析编排（阶段 7 worker 入口）：从 COS 读回 PDF → 解析 → 切块 + 嵌入。
     *
     * <p>与 {@link #parseAndStore(long, String, Integer, String, String, byte[])} 的区别：
     * 后者是「登记 + 同步解析」，本方法是「文档已登记（PENDING），只做解析 + 后续切块」——
     * 上传链路在 REST 侧只登记并投递事件，真正的解析移到这里异步完成。
     * 两条链路共用 {@link #parseAndPersist}，不写第二套解析逻辑。
     */
    public void parseDocumentAsync(long documentId) {
        // 上传接口已经开了 run（RECEIVED 已写），这里复用；恢复场景（没有在跑的 run）则新开一条
        tracker.startOrResume(documentId, IngestFlow.RESUME);
        DocumentRepository.ParseInput input = documents.findParseInput(documentId);
        if (input.objectKey() == null || input.objectKey().isBlank()) {
            throw new IllegalStateException(
                    "文档 " + documentId + " 没有 PDF 对象键，无法异步解析");
        }
        try {
            byte[] pdf = storage.get(input.objectKey());
            parseAndPersist(documentId, input.contentHash(), input.title(), pdf);
            // 解析完成 → 复用 ReindexService 做切块 + 嵌入（RECHUNK 是阶段 3 已交付的能力）
            progress.step(documentId, DocumentProgress.CHUNKING, DocumentProgress.STAGE_CHUNKING);
            tracker.begin(documentId, IngestStep.CHUNKING);
            reindex.reindex(documentId, ReindexMode.RECHUNK);
        } catch (RuntimeException e) {
            // 先收执行记录再改文档状态：两步都失败时，至少流水里有原因
            tracker.fail(documentId, e.getClass().getSimpleName() + ": " + e.getMessage());
            documents.markFailed(documentId, e.getClass().getSimpleName() + ": " + e.getMessage());
            throw e;
        }
    }

    /**
     * 提交解析作业并落产物（主流程与异步流程共用）。
     *
     * <p>提交与取结果**分成两步**，中间把 job_id 落库 —— 这是「durable」的支点：
     * 客户端在轮询期间断开时，作业仍在服务端跑，恢复流程能凭 job_id 把它接回来。
     * （初版让适配器把 submit+poll 包在一起，job_id 拿不到，断线后就只能判定"从未提交"。）
     */
    private void parseAndPersist(long documentId, String contentHash, String title, byte[] pdf) {
        progress.step(documentId, DocumentProgress.SUBMITTING, DocumentProgress.STAGE_SUBMITTING);
        tracker.begin(documentId, IngestStep.SUBMITTED);
        String jobId = parser.submit(pdf, title + ".pdf", null, null);
        documents.markRunning(documentId, jobId);
        tracker.finish(documentId, IngestStep.SUBMITTED, "job=" + jobId);
        progress.step(documentId, DocumentProgress.PARSING, DocumentProgress.STAGE_PARSING);
        tracker.begin(documentId, IngestStep.PARSING);

        // 解析是 190 秒里的大头：轮询回调里写心跳，界面才能看到「还在动」（30 秒节流写在 tracker 里）
        ParsedDocument parsed = parser.fetchResult(jobId,
                status -> tracker.heartbeat(documentId, heartbeatDetail(status)));
        persistResult(documentId, contentHash, parsed);
    }

    /** 心跳文案：状态中文 + 服务端已跑秒数（用服务端秒数，两端时钟不必一致）。 */
    static String heartbeatDetail(ParserAdapter.ParseJobStatus status) {
        String stage = switch (status.status()) {
            case "queued" -> "排队中";
            case "running" -> "解析中";
            case "done" -> "解析完成";
            case "failed" -> "解析失败";
            default -> status.status();
        };
        long sec = Math.max(0, Math.round(status.elapsedSeconds()));
        return stage + " · 已用 " + (sec / 60) + "m" + (sec % 60) + "s";
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
            tracker.startOrResume(doc.documentId(), IngestFlow.RESUME);
            try {
                ParsedDocument parsed = parser.fetchResult(doc.jobId(),
                        status -> tracker.heartbeat(doc.documentId(), heartbeatDetail(status)));
                persistResult(doc.documentId(), doc.contentHash(), parsed);
                tracker.succeed(doc.documentId(),
                        IngestTracker.summary("pages", parsed.pageCount(), "resumed", true));
                log.info("document_id={} 已从作业 {} 恢复完成", doc.documentId(), doc.jobId());
            } catch (RuntimeException e) {
                tracker.fail(doc.documentId(), "恢复失败：" + e.getMessage());
                documents.markFailed(doc.documentId(), "恢复失败：" + e.getMessage());
                log.warn("document_id={} 恢复失败", doc.documentId(), e);
            }
        }
    }

    /** 产物写 COS 并更新 document。主流程与恢复流程共用。 */
    private void persistResult(long documentId, String contentHash, ParsedDocument parsed) {
        tracker.begin(documentId, IngestStep.PARSED);
        // key 里带 parser 版本：重解析写新对象，老产物不被覆盖（合并方案的代价缓解）
        String resultKey = "parsed/" + contentHash + "/" + parsed.parserName() + "-"
                + parsed.parserVersion() + "/normalized.json";
        storage.put(resultKey, parsed.rawJson().getBytes(StandardCharsets.UTF_8),
                "application/json");
        documents.markDone(documentId, resultKey, parsed.parserName(),
                parsed.parserVersion(), parsed.pageCount());
        // 报告期是「类型 + 年份」的纯函数，解析完成即可确定；阶段 5 靠它判定「本期证据」
        documents.backfillPeriod(documentId);
        // 解析完成只是「产物可读」；真正可检索要等切块 + 嵌入切到 ACTIVE 版本（进度 100）
        progress.step(documentId, DocumentProgress.PARSED, DocumentProgress.STAGE_PARSED);
        tracker.finish(documentId, IngestStep.PARSED,
                parsed.pageCount() + " 页 · " + parsed.parserName() + " " + parsed.parserVersion());
        log.info("解析完成 document_id={} 产物={}", documentId, resultKey);
    }

    /** 读回页数（成功摘要用）；读不到返回 null，{@code summary()} 会自动跳过 null 值。 */
    private Integer parsedPages(long documentId) {
        try {
            return documents.findParseState(documentId).pagesParsed();
        } catch (RuntimeException e) {
            return null;
        }
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
