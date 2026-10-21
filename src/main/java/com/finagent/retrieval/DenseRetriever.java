package com.finagent.retrieval;

import com.finagent.chunk.ChunkRepository;
import com.finagent.embedding.EmbeddingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 向量召回（§9.3 第 3 步 / §9.13：Top-30）。
 *
 * <p>用 pgvector 的余弦距离 {@code <=>}（值越小越相似），对外暴露的名次从 1 起。
 */
@Component
public class DenseRetriever {

    private static final Logger log = LoggerFactory.getLogger(DenseRetriever.class);

    private final JdbcTemplate jdbc;
    private final EmbeddingService embeddingService;

    public DenseRetriever(JdbcTemplate jdbc, EmbeddingService embeddingService) {
        this.jdbc = jdbc;
        this.embeddingService = embeddingService;
    }

    public List<RetrievedChunk> retrieve(String query, RetrievalFilters f, int topK) {
        // 1) 把 query 向量化。复用 EmbeddingService，保证与入库时同一个模型/维度
        List<float[]> vec = embeddingService.embedAll(List.of(query));
        String literal = ChunkRepository.toVectorLiteral(vec.get(0));

        // 2) 过滤条件必须与 Sparse 路完全一致（见 appendFilters 的说明）
        StringBuilder sql = new StringBuilder("""
                SELECT dc.id, coalesce(dc.parent_id, 0) AS parent_id, dc.document_id,
                       dc.chunk_type, dc.section_path, dc.title, dc.content,
                       dc.page_start, dc.page_end, dc.source_block_ids
                  FROM document_chunk dc
                  JOIN document d ON d.id = dc.document_id
                 WHERE dc.chunk_level = 'CHILD'
                   AND dc.status = 'ACTIVE'
                   AND dc.embedding IS NOT NULL
                   AND dc.chunk_version = coalesce(?, d.active_chunk_version)
                """);
        List<Object> args = new ArrayList<>();
        args.add(f.chunkVersion());
        appendFilters(sql, args, f);
        sql.append(" ORDER BY dc.embedding <=> ?::vector LIMIT ?");
        args.add(literal);
        args.add(topK);

        // 3) 名次在 Java 里按返回顺序赋 1..N。
        //    为什么不用 row_number() OVER(...)：窗口函数会对整个过滤集排序再截断，
        //    而 ORDER BY + LIMIT 能让 pgvector 直接走 HNSW 索引。
        List<RetrievedChunk> out = new ArrayList<>(topK);
        int[] rank = {0};
        jdbc.query(sql.toString(), rs -> {
            rank[0]++;
            out.add(new RetrievedChunk(
                    rs.getLong("id"), rs.getLong("parent_id"), rs.getLong("document_id"),
                    rs.getString("chunk_type"), rs.getString("section_path"),
                    rs.getString("title"), rs.getString("content"),
                    rs.getInt("page_start"), rs.getInt("page_end"),
                    rs.getString("source_block_ids"),
                    rank[0], null, 0.0, null));
        }, args.toArray());

        log.debug("Dense 召回 {} 条（query={}）", out.size(), query);
        return out;
    }

    /**
     * 把业务过滤条件拼成 SQL 片段 —— <b>Dense 与 Sparse 共用</b>。
     *
     * <p>为什么必须共用：两路如果各写一份，候选集就不同源，RRF 融合会引入偏差，
     * 而这种 bug 不报错、只会让检索质量悄悄变差。
     *
     * <p>约定：片段以 {@code " AND ..."} 开头，参数按调用顺序追加到 {@code args}。
     */
    static void appendFilters(StringBuilder sql, List<Object> args, RetrievalFilters f) {
        if (f.documentIds() != null && !f.documentIds().isEmpty()) {
            // 实测不能用 ANY(?) 直接绑 List：PG 驱动报
            // 「无法推测实例 java.util.ImmutableCollections$ListN 的 SQL 类型」（见 ST5 偏差登记）。
            // 要绑数组得用 setObject(i, list, Types.ARRAY) 或 createArrayOf，JdbcTemplate 的
            // 变参 API 拿不到那个入口 —— 所以退回占位符拼接。参数值仍是预编译的 ?，没有注入风险。
            sql.append(" AND dc.document_id IN (")
                    .append(String.join(",", Collections.nCopies(f.documentIds().size(), "?")))
                    .append(")");
            args.addAll(f.documentIds());
        }
        if (f.companyId() != null) {
            sql.append(" AND d.company_id = ?");
            args.add(f.companyId());
        }
        if (f.docYear() != null) {
            sql.append(" AND d.doc_year = ?");
            args.add(f.docYear());
        }
        if (f.docType() != null && !f.docType().isBlank()) {
            sql.append(" AND d.doc_type = ?");
            args.add(f.docType());
        }
        if (f.sectionPathPrefix() != null && !f.sectionPathPrefix().isBlank()) {
            sql.append(" AND dc.section_path LIKE ?");
            args.add(f.sectionPathPrefix().strip() + "%");
        }
    }
}
