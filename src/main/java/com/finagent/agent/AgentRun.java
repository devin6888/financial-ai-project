package com.finagent.agent;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * 一次 Agent 运行（表 {@code agent_run}）。
 *
 * <p>它是「给人看的状态」，LangGraph4j 自己的存档在 {@code LG4JThread} / {@code LG4JCheckpoint}；
 * 两者的对应关系是 {@code AgentRun.id = LG4JThread.thread_name}（都是 UUID，不可猜）。
 *
 * @param status 见 {@link #STATUS_RUNNING} 等常量；不是枚举，因为库里是 VARCHAR + CHECK
 */
public record AgentRun(
        UUID id,
        long tenantId,
        long userId,
        Long conversationId,
        String question,
        String status,
        String currentNode,
        String planJson,
        String decision,
        String decisionNote,
        String error,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {

    public static final String STATUS_RUNNING = "RUNNING";
    public static final String STATUS_WAITING_APPROVAL = "WAITING_APPROVAL";
    public static final String STATUS_DONE = "DONE";
    public static final String STATUS_FAILED = "FAILED";
    public static final String STATUS_REJECTED = "REJECTED";

    public static final String DECISION_APPROVE = "APPROVE";
    public static final String DECISION_EDIT = "EDIT";
    public static final String DECISION_REJECT = "REJECT";

    /** 等人审批：前端据此显示审批卡，而不是「转圈」。 */
    public boolean waitingForApproval() {
        return STATUS_WAITING_APPROVAL.equals(status);
    }

    /** 已经结束（无论成功、失败、被驳回）—— 结束的运行不能再 resume。 */
    public boolean finished() {
        return STATUS_DONE.equals(status) || STATUS_FAILED.equals(status) || STATUS_REJECTED.equals(status);
    }
}