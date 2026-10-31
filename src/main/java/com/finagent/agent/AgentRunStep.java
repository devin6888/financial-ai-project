package com.finagent.agent;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Agent 的一步流水（表 {@code agent_run_step}），只追加不改。
 *
 * <p>{@code summary} 是给时间线显示的一句话，不是完整状态 —— 完整状态在检查点里。
 */
public record AgentRunStep(
        long id,
        long tenantId,
        UUID runId,
        int seq,
        String node,
        String status,
        String summary,
        Integer durationMs,
        OffsetDateTime createdAt) {

    public static final String STATUS_OK = "OK";
    public static final String STATUS_FAILED = "FAILED";
    public static final String STATUS_SKIPPED = "SKIPPED";
}