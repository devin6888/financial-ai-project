package com.finagent.ingest;

import java.time.OffsetDateTime;

/**
 * 一次入库的 run（{@code document_ingest_run} 的投影）。
 *
 * @param attempt     第几次入库（1 起）：重试、重新解析各算一次
 * @param flow        PARSE / REINDEX / RESUME
 * @param totalSteps  流程模板的步数，创建时写死
 * @param currentStep 已开始的最后一步序号（0 表示还没开始）
 * @param summaryJson 成功后的摘要（块数、版本号），DONE 时前端只看它
 */
public record IngestRun(long id, long documentId, int attempt, String flow, int totalSteps,
                        int currentStep, String status, String summaryJson, String error,
                        OffsetDateTime startedAt, OffsetDateTime finishedAt) {

    public IngestFlow flowEnum() {
        return IngestFlow.valueOf(flow);
    }
}
