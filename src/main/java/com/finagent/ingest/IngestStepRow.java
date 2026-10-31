package com.finagent.ingest;

import java.time.OffsetDateTime;

/**
 * run 里的一步（{@code document_ingest_step} 的投影）。
 *
 * @param retry 同一步的重试次数；重试不新增行，否则总步数会漂移
 */
public record IngestStepRow(int seq, String step, String status, String detail, int retry,
                            OffsetDateTime startedAt, OffsetDateTime finishedAt,
                            Integer durationMs) {

    public IngestStep stepEnum() {
        return IngestStep.valueOf(step);
    }
}
