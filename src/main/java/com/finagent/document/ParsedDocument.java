package com.finagent.document;

/**
 * 解析结果的最小骨架。
 *
 * <p>只保留本阶段 Java 侧真正会用到的字段；完整结构以 Python 服务的
 * NormalizedDocument 为准，原样落在 COS 里。不做全字段映射 —— 映射用不到的
 * 字段是纯粹的维护负担（YAGNI）。
 */
public record ParsedDocument(
        String contentHash,
        String parserName,
        String parserVersion,
        int pageCount,
        int blockCount,
        int tableCount,
        int headingCount,
        int anomalyCount,
        String rawJson
) {
}
