package com.finagent.memory;

import java.time.OffsetDateTime;

/**
 * 一条长期记忆（对应 long_term_memory 表的一行）。
 *
 * <p>同 (tenant, subject, type, key) 只允许一条 ACTIVE：改写不覆盖，而是旧行
 * SUPERSEDED 保留、新行 version + 1（「三月说过什么、五月改成什么」不可重算）。
 */
public record LongTermMemory(
        long id,
        long tenantId,
        String subjectType,
        long subjectId,
        String memoryType,
        String memoryKey,
        String content,
        String valueJson,
        String sourceType,
        String sourceRef,
        double confidence,
        int importance,
        String status,
        int version,
        OffsetDateTime expiresAt,
        OffsetDateTime lastAccessedAt,
        int retrievalCount,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {

    public static final String SUBJECT_USER = "USER";
    public static final String SUBJECT_ORG = "ORG";

    /** 用户显式规则：进独立预算，只合并压缩不丢弃（ST9 D10）。 */
    public static final String TYPE_INSTRUCTION = "INSTRUCTION";
    /** 情境类记忆：进 5% 配额。 */
    public static final String TYPE_CONTEXTUAL = "CONTEXTUAL";
    /** 长期关注对象（公司），key 形如 focus.company.300059。 */
    public static final String TYPE_WATCHLIST = "WATCHLIST";

    public static final String SOURCE_COMMAND = "EXPLICIT_COMMAND";
    public static final String SOURCE_API = "USER_API";

    public static final String STATUS_ACTIVE = "ACTIVE";
    public static final String STATUS_SUPERSEDED = "SUPERSEDED";
    public static final String STATUS_DELETED = "DELETED";

    public boolean isActive() {
        return STATUS_ACTIVE.equals(status);
    }

    /** 过期判断只在召回/展示时用：写入不拦，行照留。 */
    public boolean isExpired(OffsetDateTime now) {
        return expiresAt != null && !expiresAt.isAfter(now);
    }
}
