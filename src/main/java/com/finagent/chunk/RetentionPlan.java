package com.finagent.chunk;

import java.util.List;

/**
 * 一份旧版本清理计划（干跑结果）。<b>只描述要做什么，不含动作</b>。
 *
 * @param documentId     目标文档
 * @param activeVersion  当前检索用的版本（null = 文档还没有任何 chunk）
 * @param keepVersions   保留的版本号（降序）
 * @param deleteVersions 计划删除的版本号（升序）
 * @param rowsToDelete   待删版本的总行数（含被引用、最终会留下的行）
 * @param rowsSpared     待删版本里被历史消息或事实表引用、最终留下的行数
 */
public record RetentionPlan(
        long documentId,
        Integer activeVersion,
        List<Integer> keepVersions,
        List<Integer> deleteVersions,
        long rowsToDelete,
        long rowsSpared
) {
    public RetentionPlan {
        keepVersions = keepVersions == null ? List.of() : List.copyOf(keepVersions);
        deleteVersions = deleteVersions == null ? List.of() : List.copyOf(deleteVersions);
    }

    /** 实际会删掉的行数。 */
    public long rowsToFree() {
        return rowsToDelete - rowsSpared;
    }

    /** 一行摘要，给运维日志看。 */
    public String summary() {
        return "document=" + documentId + " active=v" + activeVersion
                + " keep=" + keepVersions + " delete=" + deleteVersions
                + " rows=" + rowsToDelete + " spared=" + rowsSpared
                + " willDelete=" + rowsToFree();
    }
}
