package com.finagent.ingest;

import com.finagent.document.DocumentProgress;
import com.finagent.document.DocumentRepository;
import com.finagent.scope.ScopeAuthorizer;
import com.finagent.task.DocumentTaskProducer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * 入库执行记录与失败重试端点（阶段 19）。
 *
 * <p>两个端点放在同一个控制器：它们共用「这条文档现在处于什么状态」的判断，
 * 拆开就会有两份对 {@code parse_status} 的解释。
 *
 * <p>不跟随 {@link com.finagent.document.DocumentController} 的 COS 条件装配：
 * 「看到卡在哪一步」与 COS 凭据无关，缺凭据时更应该能看。
 */
@RestController
public class DocumentIngestController {

    private static final Logger log = LoggerFactory.getLogger(DocumentIngestController.class);

    private final IngestTracker tracker;
    private final DocumentRepository documents;
    private final DocumentTaskProducer producer;
    private final ScopeAuthorizer authorizer;
    private final DocumentProgress progress;
    private final ObjectMapper json;

    public DocumentIngestController(IngestTracker tracker, DocumentRepository documents,
                                    DocumentTaskProducer producer, ScopeAuthorizer authorizer,
                                    DocumentProgress progress, ObjectMapper json) {
        this.tracker = tracker;
        this.documents = documents;
        this.producer = producer;
        this.authorizer = authorizer;
        this.progress = progress;
        this.json = json;
    }

    /**
     * 某文档的执行记录。
     *
     * <p>返回形状随状态变化（前端因此只有两条分支，而不是四种）：
     * <ul>
     *   <li>RUNNING / FAILED —— 回<b>完整模板</b>（含还没开始的步骤，状态 PENDING），
     *       这样「第 3/8 步」与「后面 5 步未执行」能画在同一张表里；</li>
     *   <li>DONE —— 只回摘要 + 空 steps：已完成的任务没人关心中间步骤（用户 m17943 的要求）；</li>
     *   <li>NONE —— 没有任何记录（阶段 19 之前的存量文档），前端退回「按 document 状态展示」。</li>
     * </ul>
     */
    @GetMapping("/api/documents/{id}/ingest")
    public ResponseEntity<?> ingest(@PathVariable long id) {
        Optional<DocumentRepository.ParseState> doc = findDocument(id);
        if (doc.isEmpty() || !authorizer.filterReadable(List.of(id)).contains(id)) {
            return notFound(id);
        }
        Optional<IngestRun> latest = tracker.latest(id);
        if (latest.isEmpty()) {
            return ResponseEntity.ok(new IngestView("NONE", null, null, 0, 0, null, null,
                    doc.get().parseError(), Map.of(), List.of()));
        }
        IngestRun run = latest.get();
        boolean done = "DONE".equals(run.status());
        List<IngestStepRow> rows = tracker.steps(run.id());
        List<StepView> steps = done ? List.of() : template(run, rows);
        Map<String, Object> summary = done ? summary(run, rows, doc.get()) : Map.of();
        return ResponseEntity.ok(new IngestView(run.status(), run.attempt(), run.flow(),
                run.currentStep(), run.totalSteps(),
                iso(run.startedAt()), iso(run.finishedAt()), run.error(), summary, steps));
    }

    /**
     * 失败文档重试：复位状态 → 开新 attempt → 重新投递解析事件。
     *
     * <p>只允许 FAILED：DONE 重跑要花 190 秒并产生新的 ACTIVE 版本（推翻用户正在看的结果），
     * RUNNING 重跑会让两个 worker 同时写同一个文档 —— 两者都该由运维显式触发，不放在按钮上。
     */
    @PostMapping("/api/documents/{id}/retry")
    public ResponseEntity<?> retry(@PathVariable long id) {
        Optional<DocumentRepository.ParseState> doc = findDocument(id);
        if (doc.isEmpty() || !authorizer.filterReadable(List.of(id)).contains(id)) {
            return notFound(id);
        }
        if (!"FAILED".equals(doc.get().parseStatus())) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                    "error", "NOT_RETRYABLE",
                    "message", "只有解析失败的文档可以重试，当前状态 " + doc.get().parseStatus()));
        }
        documents.markPending(id);
        progress.step(id, DocumentProgress.QUEUED, DocumentProgress.STAGE_QUEUED);
        long runId = tracker.start(id, IngestFlow.PARSE);
        producer.sendParse(id);
        log.info("文档 {} 重试已投递，runId={}", id, runId);
        return ResponseEntity.ok(Map.of(
                "documentId", id,
                "parseStatus", "PENDING",
                "attempt", runId > 0 ? latestAttempt(id) : 0));
    }

    /** 完整模板：有行用行，没行补 PENDING（失败之后那几步、以及还没走到的步骤）。 */
    static List<StepView> template(IngestRun run, List<IngestStepRow> rows) {
        Map<Integer, IngestStepRow> bySeq = new LinkedHashMap<>();
        for (IngestStepRow row : rows) {
            bySeq.put(row.seq(), row);
        }
        List<StepView> steps = new ArrayList<>(run.totalSteps());
        for (IngestStep step : run.flowEnum().steps()) {
            int seq = run.flowEnum().seqOf(step);
            IngestStepRow row = bySeq.get(seq);
            if (row == null) {
                steps.add(new StepView(seq, step.name(), step.label(), "PENDING",
                        null, 0, null, null, null));
            } else {
                steps.add(new StepView(row.seq(), row.step(), step.label(), row.status(),
                        row.detail(), row.retry(), iso(row.startedAt()), iso(row.finishedAt()),
                        row.durationMs()));
            }
        }
        return steps;
    }

    /**
     * 成功摘要：块数/版本来自 summary_json，耗时按 steps 现算。
     *
     * <p>耗时不在写库时存：steps 已经带了每步的 duration_ms，再存一份就有两个真相，
     * 而「总耗时」与「各阶段耗时」必须能对上账。
     */
    private Map<String, Object> summary(IngestRun run, List<IngestStepRow> rows,
                                        DocumentRepository.ParseState doc) {
        Map<String, Object> summary = new LinkedHashMap<>();
        if (run.startedAt() != null && run.finishedAt() != null) {
            summary.put("totalMs", Duration.between(run.startedAt(), run.finishedAt()).toMillis());
        }
        summary.put("parseMs", durationOf(rows, IngestStep.PARSING));
        summary.put("chunkMs", durationOf(rows, IngestStep.CHUNKING));
        summary.put("embedMs", durationOf(rows, IngestStep.EMBEDDING));
        summary.put("pages", doc.pagesParsed());
        summary.putAll(parseSummaryJson(run.summaryJson()));
        summary.values().removeIf(Objects::isNull);
        return summary;
    }

    private static Long durationOf(List<IngestStepRow> rows, IngestStep step) {
        long total = 0L;
        boolean any = false;
        for (IngestStepRow row : rows) {
            if (step.name().equals(row.step()) && row.durationMs() != null) {
                total += row.durationMs();
                any = true;
            }
        }
        return any ? total : null;
    }

    private Map<String, Object> parseSummaryJson(String raw) {
        if (raw == null || raw.isBlank()) {
            return Map.of();
        }
        try {
            return json.readValue(raw, new TypeReference<LinkedHashMap<String, Object>>() {
            });
        } catch (RuntimeException e) {
            log.warn("summary_json 解析失败，忽略：{}", e.toString());
            return Map.of();
        }
    }

    private int latestAttempt(long documentId) {
        return tracker.latest(documentId).map(IngestRun::attempt).orElse(0);
    }

    private Optional<DocumentRepository.ParseState> findDocument(long id) {
        try {
            return Optional.of(documents.findParseState(id));
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    private static ResponseEntity<Map<String, Object>> notFound(long id) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of(
                "error", "NOT_FOUND", "message", "文档不存在 id=" + id));
    }

    private static String iso(OffsetDateTime time) {
        return time == null ? null : time.toString();
    }

    /**
     * 给前端的执行记录视图。
     *
     * <p>{@code state} 是 run 的状态（RUNNING/DONE/FAILED/NONE），不是 document 的 parse_status ——
     * 两者在「切块中」会不一致（document 已 DONE、run 还在跑），前端按 run 状态决定是否显示动效。
     */
    public record IngestView(String state, Integer attempt, String flow, int currentStep,
                             int totalSteps, String startedAt, String finishedAt, String error,
                             Map<String, Object> summary, List<StepView> steps) {
    }

    /** 一步：label 由后端给（中文文案只有一份）。status 多了 PENDING，表示「还没执行」。 */
    public record StepView(int seq, String step, String label, String status, String detail,
                           int retry, String startedAt, String finishedAt, Integer durationMs) {
    }
}
