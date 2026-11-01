package com.finagent.chunk;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 旧 chunk 版本的保留策略（阶段 20 技术债 #3，契约 C32）。
 *
 * <p>为什么需要它：一份年报重切 10 次就留下 10 份 chunk。实测 document 7 有
 * <b>11 个版本 8338 行</b>，当前版本只有 758 行 —— 91% 的行永远不会被检索到，
 * 却一直占着 vector(1024) 那 4100 字节/行的空间。
 *
 * <p>为什么不能一删了之：历史答案的 conversation_message.citations_json 里存着 chunkId，
 * 前端点引用会去取原文；financial_fact.chunk_id 还有外键直指 chunk。
 * 所以删除语句带 <b>NOT IN (被引用的 chunk)</b>，保护到「行」这一级。
 *
 * <p><b>为什么保护到行而不是整版</b>：如果「一条被引用就整版保留」，实测 document 7 的 11 个版本
 * 几乎全都要留（引用散落在很多版本里），等于什么都没清。只留被引用那几行，既能点开原文，
 * 又能清掉同版本的其余行。
 *
 * <p>只做「算 + 删」两件事，不做定时调度：什么时候清理由运维决定（见 {@link ChunkRetentionRunner}）。
 */
@Service
public class ChunkRetentionService {

    private static final Logger log = LoggerFactory.getLogger(ChunkRetentionService.class);

    /**
     * 被引用到的 chunk id：历史消息里的子块、历史消息里的父块、事实表指向的块。
     *
     * <p>citations_json 是 JSONB，可能是 NULL、可能是对象（老数据），所以先判 jsonb_typeof ——
     * 直接 jsonb_array_elements 对非数组会报错，一条脏数据就能让整个清理任务失败。
     */
    private static final String CITED_CTE = """
            WITH cited AS (
                SELECT (c->>'childChunkId')::bigint AS id
                  FROM conversation_message m
                  CROSS JOIN LATERAL jsonb_array_elements(
                       CASE WHEN jsonb_typeof(m.citations_json) = 'array'
                            THEN m.citations_json ELSE '[]'::jsonb END) c
                 WHERE c->>'childChunkId' IS NOT NULL
                UNION
                SELECT (c->>'parentChunkId')::bigint
                  FROM conversation_message m
                  CROSS JOIN LATERAL jsonb_array_elements(
                       CASE WHEN jsonb_typeof(m.citations_json) = 'array'
                            THEN m.citations_json ELSE '[]'::jsonb END) c
                 WHERE c->>'parentChunkId' IS NOT NULL
                UNION
                SELECT chunk_id FROM financial_fact WHERE chunk_id IS NOT NULL
            )
            """;

    private final JdbcTemplate jdbc;
    private final int keepVersions;

    public ChunkRetentionService(JdbcTemplate jdbc,
                                 @Value("${finagent.retention.keep-versions:3}") int keepVersions) {
        this.jdbc = jdbc;
        this.keepVersions = keepVersions <= 0 ? 3 : keepVersions;
    }

    /** 算一份清理计划，<b>不写库</b>。 */
    public RetentionPlan plan(long documentId) {
        Integer activeVersion = jdbc.query(
                "SELECT active_chunk_version FROM document WHERE id = ?",
                rs -> rs.next() ? (Integer) rs.getObject(1) : null, documentId);

        List<VersionStat> versions = jdbc.query(
                "SELECT chunk_version, count(*) AS rows FROM document_chunk "
                        + "WHERE document_id = ? AND chunk_version IS NOT NULL "
                        + "GROUP BY chunk_version ORDER BY chunk_version DESC",
                (rs, i) -> new VersionStat(rs.getInt(1), rs.getLong(2)), documentId);

        Set<Integer> keep = keepSet(versions.stream().map(VersionStat::version).toList(),
                activeVersion, keepVersions);

        List<Integer> deleteVersions = versions.stream()
                .map(VersionStat::version)
                .filter(v -> !keep.contains(v))
                .sorted()
                .toList();

        long rows = versions.stream()
                .filter(v -> deleteVersions.contains(v.version()))
                .mapToLong(VersionStat::rows)
                .sum();

        long spared = deleteVersions.isEmpty() ? 0 : jdbc.queryForObject(
                CITED_CTE + " SELECT count(*) FROM document_chunk dc JOIN cited ON cited.id = dc.id "
                        + "WHERE dc.document_id = ? AND dc.chunk_version = ANY(?)",
                Long.class, documentId, deleteVersions.toArray(Integer[]::new));

        return new RetentionPlan(documentId, activeVersion,
                keep.stream().sorted(Comparator.reverseOrder()).toList(),
                deleteVersions, rows, spared);
    }

    /** 真删。返回删掉的行数；只删计划里列出的版本，且跳过被引用的行。 */
    public int apply(RetentionPlan plan) {
        if (plan.deleteVersions().isEmpty()) {
            return 0;
        }
        int deleted = jdbc.update(CITED_CTE
                + " DELETE FROM document_chunk dc"
                + " WHERE dc.document_id = ?"
                + "   AND dc.chunk_version = ANY(?)"
                + "   AND dc.id NOT IN (SELECT id FROM cited)",
                plan.documentId(), plan.deleteVersions().toArray(Integer[]::new));
        log.info("清理 document {} 的旧 chunk：{} 个版本 / 删除 {} 行 / 保留被引用 {} 行",
                plan.documentId(), plan.deleteVersions().size(), deleted, plan.rowsSpared());
        return deleted;
    }

    /**
     * 保留集 = 当前版本 ∪ 版本号最大的 N 个。
     *
     * <p><b>被引用的版本不进保留集</b>：保护被引用的是「行」，由 DELETE 的 NOT IN 子句负责。
     * 两个来源缺一不可：没有 active 会删掉正在用的行；没有「最近 N 个」会让刚被取代的版本
     * 立刻消失（想回滚就没有退路）。
     */
    static Set<Integer> keepSet(List<Integer> versionsDesc, Integer activeVersion, int keepN) {
        Set<Integer> keep = new LinkedHashSet<>();
        if (activeVersion != null) {
            keep.add(activeVersion);
        }
        for (int i = 0; i < Math.min(keepN, versionsDesc.size()); i++) {
            keep.add(versionsDesc.get(i));
        }
        return keep;
    }

    /** 一个版本的规模（只用于估算能省多少行）。 */
    public record VersionStat(int version, long rows) {
    }
}
