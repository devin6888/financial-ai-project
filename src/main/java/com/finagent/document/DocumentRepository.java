package com.finagent.document;

import com.finagent.scope.DocMeta;
import com.finagent.scope.SelectableDocument;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * document 表的仓储。
 *
 * <p>解析状态与解析结果都在这张表上（合并方案），没有独立的 parse_job 表。
 * 用 JdbcTemplate 而非 JPA —— 本项目 SQL 都是手写的，多一层 ORM 只是负担。
 */
@Repository
public class DocumentRepository {

    private final JdbcTemplate jdbc;

    public DocumentRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 幂等登记公司，返回 id。
     *
     * <p>tenantId 由调用方显式传入，**不读 ThreadLocal**：上传接口有 HTTP 上下文，
     * 而 CLI 与测试调用的 {@code parseAndStore} 没有 —— 让它猜只会变成「有时 401、有时写错租户」。
     * 唯一约束是 (tenant_id, code)：不同租户可以有同一个公司代码。
     */
    public long upsertCompany(String code, String name, String market, long tenantId) {
        Long id = jdbc.queryForObject("""
                INSERT INTO company (code, name, market, tenant_id)
                VALUES (?, ?, ?, ?)
                ON CONFLICT (tenant_id, code) DO UPDATE SET name = EXCLUDED.name
                RETURNING id
                """, Long.class, code, name, market, tenantId);
        return id == null ? -1L : id;
    }

    /**
     * 幂等登记文档：content_hash 已存在时复用该行。
     *
     * <p>注意会把 parse_status 重置为 PENDING 并清掉上一次的作业号 ——
     * 重解析是"替换"语义，不是"共存"（详见计划「追加变更 B」）。
     *
     * @return document id
     */
    public long upsertDocument(long companyId, String docType, Integer docYear, String title,
                               String objectKey, String contentHash) {
        Long id = jdbc.queryForObject("""
                INSERT INTO document (company_id, tenant_id, doc_type, doc_year, title, object_key,
                                      content_hash, parse_status)
                SELECT c.id, c.tenant_id, ?, ?, ?, ?, ?, 'PENDING'
                  FROM company c
                 WHERE c.id = ?
                ON CONFLICT (tenant_id, content_hash) DO UPDATE
                    SET object_key   = EXCLUDED.object_key,
                        title        = EXCLUDED.title,
                        parse_status = 'PENDING',
                        parse_job_id = NULL,
                        parse_error  = NULL
                RETURNING id
                """, Long.class, docType, docYear, title, objectKey, contentHash, companyId);
        return id == null ? -1L : id;
    }

    public void markRunning(long documentId, String jobId) {
        jdbc.update("""
                UPDATE document
                   SET parse_status = 'RUNNING', parse_job_id = ?,
                       parse_started_at = now(), parse_error = NULL
                 WHERE id = ?
                """, jobId, documentId);
    }

    public void markDone(long documentId, String parsedObjectKey, String parser,
                         String parserVersion, int pagesParsed) {
        jdbc.update("""
                UPDATE document
                   SET parse_status = 'DONE', parsed_object_key = ?, parser = ?,
                       parser_version = ?, pages_parsed = ?, parsed_at = now(),
                       parse_finished_at = now(), parse_error = NULL
                 WHERE id = ?
                """, parsedObjectKey, parser, parserVersion, pagesParsed, documentId);
    }

    public void markFailed(long documentId, String error) {
        jdbc.update("""
                UPDATE document
                   SET parse_status = 'FAILED', parse_error = ?, parse_finished_at = now()
                 WHERE id = ?
                """, error, documentId);
    }

    /**
     * 在途文档：应用启动时用它恢复。
     *
     * <p>走的是部分索引 idx_document_parse_active，只覆盖 PENDING/RUNNING 行。
     */
    public List<InFlightDocument> findInFlight() {
        return jdbc.query("""
                SELECT id, content_hash, parse_job_id
                  FROM document
                 WHERE parse_status IN ('PENDING', 'RUNNING')
                 ORDER BY id
                """, IN_FLIGHT_MAPPER);
    }

    /** 读回某个文档的解析状态与产物指针，用于验收断言。 */
    public ParseState findParseState(long documentId) {
        return jdbc.queryForObject("""
                SELECT id, parse_status, parsed_object_key, parser, parser_version,
                       pages_parsed, parse_error, title
                  FROM document
                 WHERE id = ?
                """, (rs, n) -> new ParseState(
                        rs.getLong("id"),
                        rs.getString("parse_status"),
                        rs.getString("parsed_object_key"),
                        rs.getString("parser"),
                        rs.getString("parser_version"),
                        (Integer) rs.getObject("pages_parsed"),
                        rs.getString("parse_error"),
                        rs.getString("title")), documentId);
    }

    /**
     * 读回异步解析所需的入参：PDF 对象键、内容哈希、标题。
     *
     * <p>阶段 7 把「登记 + 同步解析」拆成「登记（同步）+ 投递事件（异步）」，
     * worker 收到事件后只有 documentId，要靠本方法把 PDF 从 COS 读回来再解析。
     */
    public ParseInput findParseInput(long documentId) {
        return jdbc.queryForObject("""
                SELECT id, object_key, content_hash, title
                  FROM document
                 WHERE id = ?
                """, (rs, n) -> new ParseInput(
                        rs.getLong("id"),
                        rs.getString("object_key"),
                        rs.getString("content_hash"),
                        rs.getString("title")), documentId);
    }

    /**
     * 按 content_hash 找「已完成入库」的文档（DONE 且有 ACTIVE chunk 版本）。
     *
     * <p>阶段 7 上传链路的幂等快路径：同一份 PDF 重复上传时，若已解析且已入库，
     * 直接返回已有 id，不再重新解析（181 页要 50 分钟，重复解析纯属浪费）。
     *
     * @return 命中时返回 document id，否则 {@link Optional#empty()}
     */
    public Optional<Long> findCompletedIdByContentHash(String contentHash) {
        List<Long> ids = jdbc.query("""
                SELECT id FROM document
                 WHERE content_hash = ?
                   AND parse_status = 'DONE'
                   AND active_chunk_version IS NOT NULL
                 ORDER BY id
                """, (rs, n) -> rs.getLong("id"), contentHash);
        return ids.isEmpty() ? Optional.empty() : Optional.of(ids.get(0));
    }

    /**
     * 单文档的状态详情（GET /api/documents/{id} 用）。
     *
     * <p>LEFT JOIN company 带出公司代码/名称，前端据此展示「哪家公司的哪份报告、
     * 解析到哪一步」。company 不存在时 code/name 为 null。
     *
     * @return 文档不存在时返回 {@link Optional#empty()}
     */
    public Optional<DocumentDetail> findDetail(long documentId) {
        List<DocumentDetail> rows = jdbc.query("""
                SELECT d.id, d.parse_status, d.active_chunk_version, d.title, d.pages_parsed,
                       d.parse_error, d.parsed_object_key, d.parser, d.parser_version,
                       d.doc_year, d.doc_type, c.code, c.name
                  FROM document d
                  LEFT JOIN company c ON c.id = d.company_id
                 WHERE d.id = ?
                """, DETAIL_MAPPER, documentId);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    /**
     * 公司代码是否存在。
     *
     * <p>为什么需要它：§9.14 要求「问题里的公司必须在 company 表里命中」——
     * LLM 会编造不存在的代码，必须在检索前校验，否则它会变成永远命中 0 行的硬过滤条件，
     * 静默地把整个检索结果清空。
     *
     * <p>参数 {@code code} 是**证券代码**（{@code company.code}）；自 ST9 §16 起，
     * 代码一律由 {@code com.finagent.understanding.CompanyResolver} 查库得到，
     * 不再由模型直接产出（契约 C13）。
     */
    public boolean companyExists(String code) {
        if (code == null || code.isBlank()) {
            return false;
        }
        Integer n = jdbc.queryForObject(
                "SELECT count(*) FROM company WHERE code = ?", Integer.class, code.strip());
        return n != null && n > 0;
    }

    /**
     * 公司代码（如 300059）→ company.id。
     *
     * <p>为什么要这一步：QU 抽出来的是<b>股票代码</b>，而检索的硬过滤条件是
     * document.company_id。两者不是一回事，直接透传会得到「过滤条件永远不匹配」——
     * 不报错，只是静默返回 0 条。
     *
     * @return 不存在时返回 null，由调用方决定是否放弃该过滤条件
     */
    public Long findCompanyIdByCode(String code) {
        if (code == null || code.isBlank()) {
            return null;
        }
        java.util.List<Long> ids = jdbc.query("SELECT id FROM company WHERE code = ?",
                (rs, n) -> rs.getLong("id"), code.strip());
        return ids.isEmpty() ? null : ids.get(0);
    }

    /**
     * 按 doc_type + doc_year 推算报告期并回填（只在 period_start 为空时写）。
     *
     * <p>为什么不让调用方传 period：报告期是「类型 + 年份」的纯函数，让每个入口各传一次
     * 只会出现「有的入口忘了传」，而漏传的表现是 period 为 null —— 不报错，只是
     * 阶段 5 判定「本期证据」时静默失效。
     *
     * <p>为什么半年报从 1/1 起：A 股半年报的「本期」是 1-6 月，起点必须是 1/1，
     * 不能理解成「报告期内的 6 个月」。
     *
     * @return 受影响行数；0 表示 doc_year 为空或期间已存在
     */
    public int backfillPeriod(long documentId) {
        return jdbc.update("""
                UPDATE document
                   SET period_start = CASE doc_type
                           WHEN 'ANNUAL'      THEN make_date(doc_year, 1, 1)
                           WHEN 'SEMI_ANNUAL' THEN make_date(doc_year, 1, 1)
                           WHEN 'Q1'          THEN make_date(doc_year, 1, 1)
                           WHEN 'Q3'          THEN make_date(doc_year, 7, 1)
                       END,
                       period_end   = CASE doc_type
                           WHEN 'ANNUAL'      THEN make_date(doc_year, 12, 31)
                           WHEN 'SEMI_ANNUAL' THEN make_date(doc_year, 6, 30)
                           WHEN 'Q1'          THEN make_date(doc_year, 3, 31)
                           WHEN 'Q3'          THEN make_date(doc_year, 9, 30)
                       END
                 WHERE id = ?
                   AND doc_year IS NOT NULL
                   AND period_start IS NULL
                """, documentId);
    }

    /**
     * 可检索文档 = 解析完成（DONE）且有 active_chunk_version。
     *
     * <p>为什么必须过滤这两条而不是在检索时兜底：库里存在 parse_status=FAILED 的文档，
     * 它一条 chunk 都没有。放进作用域后检索会返回 0 条，错误信息指向「没找到证据」，
     * 而真实原因是「这份文档根本没解析成功」—— 用户没法自己修。
     *
     * @param ids          为空表示不限
     * @param companyCodes 为空表示不限
     * @param years        按 doc_year 精确匹配，为空表示不限
     * @param docTypes     为空表示不限
     * @param industries   为空表示不限
     */
    public List<DocMeta> findRetrievable(List<Long> ids, List<String> companyCodes,
                                         List<Integer> years, List<String> docTypes,
                                         List<String> industries) {
        StringBuilder sql = new StringBuilder("""
                SELECT d.id, d.company_id, c.code, c.name, d.doc_year, d.doc_type,
                       d.period_start, d.period_end, d.active_chunk_version, d.title
                  FROM document d
                  LEFT JOIN company c ON c.id = d.company_id
                 WHERE d.parse_status = 'DONE'
                   AND d.active_chunk_version IS NOT NULL
                """);
        List<Object> args = new ArrayList<>();
        appendIn(sql, args, "d.id", ids);
        appendIn(sql, args, "c.code", companyCodes);
        appendIn(sql, args, "d.doc_year", years);
        appendIn(sql, args, "d.doc_type", docTypes);
        appendIn(sql, args, "c.industry", industries);
        sql.append(" ORDER BY d.doc_year DESC NULLS LAST, d.id");
        return jdbc.query(sql.toString(), DOC_META_MAPPER, args.toArray());
    }

    /**
     * 按 id 查解析状态 —— 只用于「为什么这份文档没进作用域」的诊断。
     *
     * <p>分开一个方法而不是给 findRetrievable 加参数：可检索与不可检索是两种语义，
     * 混在一个查询里迟早会有人忘了加 WHERE。
     */
    /**
     * 前端「选择文档」列表：只列可检索的（解析完成且有 ACTIVE 块版本）。
     *
     * <p>chunkCount 只数 CHILD 的 ACTIVE 行：Parent 也是行，算进去会让用户以为文档大了一倍。
     */
    public List<SelectableDocument> findSelectableDocuments() {
        return jdbc.query("""
                SELECT d.id, d.title, c.code AS company_code, c.name AS company_name,
                       d.doc_year, d.doc_type, d.pages_parsed,
                       (SELECT count(*) FROM document_chunk dc
                         WHERE dc.document_id = d.id
                           AND dc.chunk_level = 'CHILD'
                           AND dc.status = 'ACTIVE'
                           AND dc.chunk_version = d.active_chunk_version) AS chunk_count
                  FROM document d
                  LEFT JOIN company c ON c.id = d.company_id
                 WHERE d.parse_status = 'DONE' AND d.active_chunk_version IS NOT NULL
                 ORDER BY d.doc_year DESC NULLS LAST, d.id
                """, (rs, i) -> new SelectableDocument(
                rs.getLong("id"),
                rs.getString("title"),
                rs.getString("company_code"),
                rs.getString("company_name"),
                rs.getObject("doc_year", Integer.class),
                rs.getString("doc_type"),
                rs.getObject("pages_parsed", Integer.class),
                rs.getLong("chunk_count")));
    }

    /**
     * 全部文档（含未解析完成、解析失败的），阶段 10 文档页用。
     *
     * <p>为什么不能复用 {@link #findSelectableDocuments()}：那个方法按「DONE + 有 ACTIVE
     * 块版本」过滤，语义是「能检索的文档」。文档页要看的是「我传了哪些、卡在哪一步」——
     * 用可检索列表会让 PENDING / PARSING / FAILED 的文档在界面上直接消失，用户既不知道
     * 在等什么，也不知道该不该重传。
     *
     * <p>chunkCount 同样只数 CHILD 的 ACTIVE 行（理由见 findSelectableDocuments）。
     * 未解析完成的文档 active_chunk_version 为 null，子查询自然得 0。
     *
     * <p>租户边界由 RLS 保证（V8 的 p_document_tenant），这里不写 tenant_id 条件 ——
     * 写了会变成「应用层以为在隔离、实际另一条路径忘了写就漏」的两套口径。
     */
    public List<DocumentRow> listDocuments(int limit) {
        int safe = limit <= 0 ? 50 : Math.min(limit, 200);
        return jdbc.query("""
                SELECT d.id, d.title, c.code AS company_code, c.name AS company_name,
                       d.doc_year, d.doc_type, d.parse_status, d.pages_parsed, d.parse_error,
                       d.active_chunk_version, d.created_at,
                       (SELECT count(*) FROM document_chunk dc
                         WHERE dc.document_id = d.id
                           AND dc.chunk_level = 'CHILD'
                           AND dc.status = 'ACTIVE'
                           AND dc.chunk_version = d.active_chunk_version) AS chunk_count
                  FROM document d
                  LEFT JOIN company c ON c.id = d.company_id
                 ORDER BY d.created_at DESC NULLS LAST, d.id DESC
                 LIMIT ?
                """, (rs, i) -> new DocumentRow(
                rs.getLong("id"),
                rs.getString("title"),
                rs.getString("company_code"),
                rs.getString("company_name"),
                rs.getObject("doc_year", Integer.class),
                rs.getString("doc_type"),
                rs.getString("parse_status"),
                rs.getObject("pages_parsed", Integer.class),
                rs.getString("parse_error"),
                rs.getObject("active_chunk_version", Integer.class),
                rs.getLong("chunk_count"),
                rs.getObject("created_at", OffsetDateTime.class)), safe);
    }

    public List<DocStatus> findStatusByIds(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        String sql = "SELECT id, parse_status, active_chunk_version, title FROM document WHERE id IN ("
                + String.join(",", Collections.nCopies(ids.size(), "?")) + ")";
        return jdbc.query(sql, (rs, n) -> new DocStatus(
                rs.getLong("id"),
                rs.getString("parse_status"),
                (Integer) rs.getObject("active_chunk_version"),
                rs.getString("title")), ids.toArray());
    }

    /** 空的筛选条件不加任何条件（= 不限），避免拼出 IN () 的语法错误。 */
    private static void appendIn(StringBuilder sql, List<Object> args, String column, List<?> values) {
        if (values == null || values.isEmpty()) {
            return;
        }
        sql.append(" AND ").append(column).append(" IN (")
                .append(String.join(",", Collections.nCopies(values.size(), "?")))
                .append(")");
        args.addAll(values);
    }

    private static final RowMapper<DocMeta> DOC_META_MAPPER = (rs, n) -> new DocMeta(
            rs.getLong("id"),
            (Long) rs.getObject("company_id"),
            rs.getString("code"),
            rs.getString("name"),
            (Integer) rs.getObject("doc_year"),
            rs.getString("doc_type"),
            rs.getObject("period_start", LocalDate.class),
            rs.getObject("period_end", LocalDate.class),
            rs.getInt("active_chunk_version"),
            rs.getString("title"));

    /** 文档的解析状态投影（诊断用）。 */
    public record DocStatus(long id, String parseStatus, Integer activeChunkVersion, String title) {
        public boolean retrievable() {
            return "DONE".equals(parseStatus) && activeChunkVersion != null;
        }
    }

    /** 解析状态与产物指针。 */
    public record ParseState(long documentId, String parseStatus, String parsedObjectKey,
                             String parser, String parserVersion, Integer pagesParsed,
                             String parseError,
                             /** 文档标题。阶段 3 拼接 embedding_text 需要它（加在末尾，不影响既有调用）。 */
                             String title) {
    }

    private static final RowMapper<InFlightDocument> IN_FLIGHT_MAPPER = (rs, n) ->
            new InFlightDocument(rs.getLong("id"), rs.getString("content_hash"),
                    rs.getString("parse_job_id"));

    /** 在途文档的最小投影。 */
    public record InFlightDocument(long documentId, String contentHash, String jobId) {
    }

    /** 异步解析入参：PDF 对象键、内容哈希、标题（阶段 7 worker 用）。 */
    public record ParseInput(long documentId, String objectKey, String contentHash,
                             String title) {
    }

    /**
     * 文档列表行（GET /api/documents 用）。
     *
     * @param parseStatus PENDING / PARSING / DONE / FAILED —— 前端四态各有各的显示
     * @param chunkCount  ACTIVE 版本的 CHILD 块数；未解析完成时为 0
     */
    public record DocumentRow(long id, String title, String companyCode, String companyName,
                              Integer docYear, String docType, String parseStatus,
                              Integer pagesParsed, String parseError,
                              Integer activeChunkVersion, long chunkCount,
                              OffsetDateTime createdAt) {
    }

    /** 单文档的状态详情投影（GET /api/documents/{id} 用）。 */
    public record DocumentDetail(long id, String parseStatus, Integer activeChunkVersion,
                                 String title, Integer pagesParsed, String parseError,
                                 String parsedObjectKey, String parser, String parserVersion,
                                 Integer docYear, String docType, String companyCode,
                                 String companyName) {
    }

    private static final RowMapper<DocumentDetail> DETAIL_MAPPER = (rs, n) -> new DocumentDetail(
            rs.getLong("id"),
            rs.getString("parse_status"),
            (Integer) rs.getObject("active_chunk_version"),
            rs.getString("title"),
            (Integer) rs.getObject("pages_parsed"),
            rs.getString("parse_error"),
            rs.getString("parsed_object_key"),
            rs.getString("parser"),
            rs.getString("parser_version"),
            (Integer) rs.getObject("doc_year"),
            rs.getString("doc_type"),
            rs.getString("code"),
            rs.getString("name"));
}
