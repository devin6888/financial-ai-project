package com.finagent.retrieval;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * PostgreSQL 实现（FTS + zhparser / 精确子串）。
 *
 * <p><b>定位是「召回」，不是「排序」</b> —— 实测 ts_rank 无 IDF、与 BM25 不等价，
 * 且 zhparser 会把「营业收入」切成「营业」+「收入」。
 * 所以这一路只负责把候选捞出来，排序交给 RRF + Rerank。
 */
@Component
public class PgSparseRetriever implements SparseRetriever {

    private static final Logger log = LoggerFactory.getLogger(PgSparseRetriever.class);

    private static final String BASE_SELECT = """
            SELECT dc.id, coalesce(dc.parent_id, 0) AS parent_id, dc.document_id,
                   dc.chunk_type, dc.section_path, dc.title, dc.content,
                   dc.page_start, dc.page_end, dc.source_block_ids
              FROM document_chunk dc
              JOIN document d ON d.id = dc.document_id
             WHERE dc.chunk_level = 'CHILD'
               AND dc.status = 'ACTIVE'
               AND dc.chunk_version = coalesce(?, d.active_chunk_version)
            """;

    private final JdbcTemplate jdbc;

    public PgSparseRetriever(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<RetrievedChunk> retrieve(String query, List<String> terms, SparseMode mode,
                                         RetrievalFilters f, int topK) {
        return mode == SparseMode.EXACT ? exact(terms, f, topK) : ftsWithFallback(query, f, topK);
    }

    // ---------------- EXACT：精确子串 ----------------

    /** 实体命中在标题/章节路径里的额外分（比正文命中更可信：整块都在讲这件事）。 */
    private static final int TITLE_BONUS = 3;

    /** 位置哨兵：strpos 找不到返回 0，用最大整数参与 LEAST，等价于「排到最后」。 */
    private static final int NO_POS = Integer.MAX_VALUE;

    /**
     * 任一实体命中即可（OR），按<b>加权命中分</b>降序，再按<b>首个命中位置</b>升序。
     *
     * <p><b>为什么不再只数命中个数</b>（阶段 20 技术债 #1）：实测「每股收益」这类查询，
     * 命中的 17 条里有 16 条只命中 1 个词，分数全是 1 → {@code ORDER BY score DESC, id ASC}
     * 退化成按主键排序，等于没排序；命中数超过 topSparse 时还会按 id 截断，把正确的块截掉。
     *
     * <p>现在的三档信号，全部是 SQL 里一次算完（不额外查库、不调模型）：
     * <ol>
     *   <li><b>实体长度</b>：{@code char_length(term)} 当权重 —— 「每股收益」(4) 比「每股」(2) 更具体，
     *       这是没有 IDF 时最廉价的「具体程度」代理；</li>
     *   <li><b>标题/章节命中</b>：title 或 section_path 里出现的，每个实体加 {@link #TITLE_BONUS} 分 —— 整块都在讲这件事，
     *       比正文里顺带提一句更相关；</li>
     *   <li><b>首次出现位置</b>：所有命中词里最早出现的那个字符位置（{@code LEAST(strpos...)})，
     *       越靠前越可能是「表头/首段」，同分时用它兜底。</li>
     * </ol>
     *
     * <p>为什么用 ILIKE 而不是全文检索：这条路径的全部价值就是<b>不过分词器</b>。
     */
    List<RetrievedChunk> exact(List<String> terms, RetrievalFilters f, int topK) {
        if (terms == null || terms.isEmpty()) {
            return List.of();
        }
        List<String> effective = terms.stream()
                .filter(t -> t != null && !t.isBlank())
                .map(String::strip)
                .distinct()
                .toList();
        if (effective.isEmpty()) {
            return List.of();
        }

        StringBuilder where = new StringBuilder(" AND (");
        StringBuilder score = new StringBuilder("(");
        StringBuilder pos = new StringBuilder("LEAST(");
        for (int i = 0; i < effective.size(); i++) {
            if (i > 0) {
                where.append(" OR ");
                score.append(" + ");
                pos.append(", ");
            }
            where.append("dc.content ILIKE ?");
            // 每命中一个实体，加「实体长度」分；命中标题/章节路径再加 TITLE_BONUS
            score.append("(CASE WHEN dc.content ILIKE ? THEN char_length(?) ELSE 0 END)");
            score.append(" + (CASE WHEN (coalesce(dc.title, '') || ' ' || coalesce(dc.section_path, '')) ILIKE ?")
                 .append(" THEN ").append(TITLE_BONUS).append(" ELSE 0 END)");
            pos.append("CASE WHEN strpos(dc.content, ?) > 0 THEN strpos(dc.content, ?) ELSE ")
                 .append(NO_POS).append(" END");
        }
        where.append(")");
        score.append(")");
        pos.append(")");

        StringBuilder sql = new StringBuilder(BASE_SELECT);
        List<Object> args = new ArrayList<>();
        args.add(f.chunkVersion());
        sql.append(where);
        // 1) WHERE 里的占位符先绑定
        for (String t : effective) {
            args.add("%" + t + "%");
        }

        DenseRetriever.appendFilters(sql, args, f);

        sql.append(" ORDER BY ").append(score).append(" DESC, ").append(pos).append(" ASC, dc.id ASC LIMIT ?");
        // 2) ORDER BY 里的占位符后绑定（顺序必须与 SQL 文本一致：score 的 3n 个，再 pos 的 2n 个）
        for (String t : effective) {
            args.add("%" + t + "%");
            args.add(t);
            args.add("%" + t + "%");
        }
        for (String t : effective) {
            args.add(t);
            args.add(t);
        }
        args.add(topK);

        List<RetrievedChunk> out = mapRows(sql.toString(), args.toArray());
        log.debug("Sparse EXACT 召回 {} 条（terms={}）", out.size(), effective);
        return out;
    }

    // ---------------- FTS_OR：AND 优先，OR 回退 ----------------

    /**
     * 先试 AND（精度高），命中 0 条再退到 OR（保召回）。
     *
     * <p>实测：「2025年公司面临的主要风险有哪些」
     * 用 AND 命中 <b>0 条</b>，用 OR 命中 372 条（共 654）。
     * 渐进放松是 IR 里的标准手法，成本几乎为零。
     */
    List<RetrievedChunk> ftsWithFallback(String query, RetrievalFilters f, int topK) {
        List<RetrievedChunk> and = runFts(query, f, topK, false);
        if (!and.isEmpty()) {
            return and;
        }
        List<RetrievedChunk> or = runFts(query, f, topK, true);
        if (!or.isEmpty()) {
            log.debug("Sparse AND 为空，已回退到 OR（命中 {} 条）", or.size());
        }
        return or;
    }

    /**
     * 跑一次 FTS。包级可见是为了让测试能<b>分别验证 AND 与 OR 两种语义</b> ——
     * 只测 {@link #retrieve} 的话，两者都返回 topK 条，看不出回退是否真的发生。
     */
    List<RetrievedChunk> runFts(String query, RetrievalFilters f, int topK, boolean orMode) {
        // OR 模式：把分词结果用 | 连成 tsquery；AND 模式直接用 plainto_tsquery
        String tsExpr = orMode
                ? "to_tsquery('chinese_zh', array_to_string("
                        + "tsvector_to_array(to_tsvector('chinese_zh', ?)), ' | '))"
                : "plainto_tsquery('chinese_zh', ?)";

        StringBuilder sql = new StringBuilder(BASE_SELECT);
        List<Object> args = new ArrayList<>();
        args.add(f.chunkVersion());
        sql.append(" AND dc.content_tsv @@ ").append(tsExpr);
        args.add(query);

        DenseRetriever.appendFilters(sql, args, f);

        sql.append(" ORDER BY ts_rank(dc.content_tsv, ").append(tsExpr).append(") DESC");
        args.add(query);
        sql.append(" LIMIT ?");
        args.add(topK);

        return mapRows(sql.toString(), args.toArray());
    }

    private List<RetrievedChunk> mapRows(String sql, Object[] args) {
        List<RetrievedChunk> out = new ArrayList<>();
        int[] rank = {0};
        jdbc.query(sql, rs -> {
            rank[0]++;
            out.add(new RetrievedChunk(
                    rs.getLong("id"), rs.getLong("parent_id"), rs.getLong("document_id"),
                    rs.getString("chunk_type"), rs.getString("section_path"),
                    rs.getString("title"), rs.getString("content"),
                    rs.getInt("page_start"), rs.getInt("page_end"),
                    rs.getString("source_block_ids"),
                    null, rank[0], 0.0, null));
        }, args);
        return out;
    }
}
