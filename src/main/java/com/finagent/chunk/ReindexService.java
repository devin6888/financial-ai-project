package com.finagent.chunk;

import com.finagent.chunk.model.Chunk;
import com.finagent.chunk.model.ChunkLevel;
import com.finagent.chunk.model.ChunkType;
import com.finagent.chunk.model.ParentGroup;
import com.finagent.document.DocumentModel;
import com.finagent.document.DocumentRepository;
import com.finagent.document.NormalizedDocumentLoader;
import com.finagent.ingest.IngestFlow;
import com.finagent.ingest.IngestStep;
import com.finagent.ingest.IngestTracker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 重索引编排 —— 三种模式共用同一条「写新版本 + 原子切换」的出口。
 *
 * <p>刻意<b>不</b>写第二套切块/嵌入逻辑 —— 复用 {@link ParentChunker}、
 * {@link ChildChunker}、{@link ChunkWriter}。重索引与首次索引走同一条链路，
 * 两套实现迟早会不一致。
 *
 * <h2>三种模式的区别只在「上游给什么」</h2>
 * <ul>
 *   <li>{@link ReindexMode#RECHUNK}：上游是 PDF 解析产物 → 重新切块（会变块数）。</li>
 *   <li>{@link ReindexMode#RETRIEVAL_ONLY}：上游是<b>库里现有的块</b> → 块与文本一字不动，
 *       只重建检索侧派生的两样东西：{@code content_tsv}（生成列，插入时按当前分词配置重算）
 *       与 {@code embedding}（按 C10 的 hash 复用，模型没换就零 API 调用）。</li>
 *   <li>{@link ReindexMode#REEMBED}：同 RETRIEVAL_ONLY，但强制不用复用、全部重嵌。</li>
 * </ul>
 *
 * <p>为什么 RETRIEVAL_ONLY 值得有：换了分词器（zhparser 词典/映射）之后，{@code content_tsv}
 * 是 {@code GENERATED ALWAYS AS ... STORED} —— 改配置不会让已有行重算，只能重新插入一遍。
 * 而重新插入最省的做法就是「沿用现有切块 + 复用向量」，这正是本模式。
 */
@Service
public class ReindexService {

    private static final Logger log = LoggerFactory.getLogger(ReindexService.class);

    /** section_path 的存储分隔符（见 {@code Chunk#sectionPathText}）。 */
    private static final Pattern PATH_SEP = Pattern.compile(Pattern.quote(" § "));

    private final DocumentRepository documents;
    private final NormalizedDocumentLoader loader;
    private final ChunkWriter writer;
    private final ChunkRepository chunks;
    private final IngestTracker tracker;

    public ReindexService(DocumentRepository documents, NormalizedDocumentLoader loader,
                          ChunkWriter writer, ChunkRepository chunks, IngestTracker tracker) {
        this.documents = documents;
        this.loader = loader;
        this.writer = writer;
        this.chunks = chunks;
        this.tracker = tracker;
    }

    /**
     * @return 新的 chunk 版本号
     * @throws UnsupportedOperationException {@link ReindexMode#FULL_REPARSE}（它不走重索引，走解析入库）
     * @throws IllegalStateException         文档未解析完成 / 还没有切块版本 / 库里块关系不完整
     */
    public int reindex(long documentId, ReindexMode mode) {
        return switch (mode) {
            case RECHUNK -> rechunk(documentId);
            case RETRIEVAL_ONLY -> rebuildRetrieval(documentId, false);
            case REEMBED -> rebuildRetrieval(documentId, true);
            case FULL_REPARSE -> throw new UnsupportedOperationException(
                    "FULL_REPARSE 尚未实现：它要从解析开始重来（调 Python 解析服务），"
                            + "入口是上传 / document.parse，不走重索引。");
        };
    }

    /** 重新切块 + 增量重嵌（块数可能变化）。 */
    private int rechunk(long documentId) {
        DocumentRepository.ParseState st = requireParsed(documentId);
        tracker.start(documentId, IngestFlow.REINDEX);
        tracker.begin(documentId, IngestStep.CHUNKING);
        try {
            DocumentModel model = loader.load(st.parsedObjectKey());
            List<ParentGroup> groups = ParentChunker.split(model);
            List<List<Chunk>> children = groups.stream().map(ChildChunker::splitAll).toList();

            String parserVersion = model.meta() == null
                    ? st.parserVersion() : model.meta().parser_version();

            log.info("开始重切 document_id={}：Parent {} / Child {}",
                    documentId, groups.size(), children.stream().mapToInt(List::size).sum());
            return writer.writeVersion(documentId, st.title(), groups, children, parserVersion);
        } catch (RuntimeException e) {
            tracker.fail(documentId, e.getClass().getSimpleName() + ": " + e.getMessage());
            throw e;
        }
    }

    /**
     * 不重新切块：把当前 ACTIVE 版本的块原样搬进新版本，重算 tsv 与向量。
     *
     * @param forceReembed true = 忽略复用源全部重嵌（REEMBED）
     */
    private int rebuildRetrieval(long documentId, boolean forceReembed) {
        DocumentRepository.ParseState st = requireParsed(documentId);
        int active = chunks.activeVersion(documentId);
        if (active <= 0) {
            throw new IllegalStateException(
                    "文档 " + documentId + " 还没有任何切块版本，无法只重建检索数据（请用 RECHUNK）");
        }
        List<ChunkRepository.StoredChunk> rows = chunks.findVersionChunks(documentId, active);
        if (rows.isEmpty()) {
            throw new IllegalStateException(
                    "文档 " + documentId + " 的 v" + active + " 里没有任何块，无法重建检索数据");
        }

        tracker.start(documentId, IngestFlow.RETRIEVAL);
        try {
            RebuildPlan plan = group(rows);
            String parserVersion = plan.parserVersion() == null || plan.parserVersion().isBlank()
                    ? st.parserVersion() : plan.parserVersion();
            log.info("只重建检索数据 document_id={} 沿用 v{}：Parent {} / Child {}（强制重嵌={}）",
                    documentId, active, plan.parents().size(),
                    plan.children().stream().mapToInt(List::size).sum(), forceReembed);
            return writer.writeVersion(documentId, st.title(), plan.parents(), plan.children(),
                    parserVersion, IngestStep.REUSED, forceReembed);
        } catch (RuntimeException e) {
            tracker.fail(documentId, e.getClass().getSimpleName() + ": " + e.getMessage());
            throw e;
        }
    }

    private DocumentRepository.ParseState requireParsed(long documentId) {
        DocumentRepository.ParseState st = documents.findParseState(documentId);
        if (!"DONE".equals(st.parseStatus())) {
            throw new IllegalStateException(
                    "文档 " + documentId + " 尚未解析完成（状态 " + st.parseStatus() + "），无法重切");
        }
        return st;
    }

    /**
     * 把库里的行还原成切块链路的中间产物（Parent + 各自的 Child）。
     *
     * <p>section_path 用 {@code " § "} 存、embedding_text 用 {@code " > "} 拼，
     * 这里必须精确拆回去 —— 拆错一个字，hash 就变，复用全失效。
     */
    static RebuildPlan group(List<ChunkRepository.StoredChunk> rows) {
        List<Chunk> parents = new ArrayList<>();
        Map<Integer, List<Chunk>> childrenByParent = new LinkedHashMap<>();
        int childRows = 0;
        String parserVersion = null;

        for (ChunkRepository.StoredChunk r : rows) {
            if (parserVersion == null && r.parserVersion() != null && !r.parserVersion().isBlank()) {
                parserVersion = r.parserVersion();
            }
            Chunk c = toChunk(r);
            if (ChunkLevel.PARENT.name().equals(r.level())) {
                parents.add(c);
            } else {
                childrenByParent.computeIfAbsent(r.parentIndex(), k -> new ArrayList<>()).add(c);
                childRows++;
            }
        }

        List<ParentGroup> groups = new ArrayList<>(parents.size());
        List<List<Chunk>> children = new ArrayList<>(parents.size());
        int attached = 0;
        for (Chunk p : parents) {
            List<Chunk> kids = childrenByParent.getOrDefault(p.chunkIndex(), List.of());
            groups.add(new ParentGroup(p, List.of(), List.of()));
            children.add(kids);
            attached += kids.size();
        }
        // 关系不完整时宁可失败：丢子块 = 检索里凭空少证据，而且不会报错
        if (attached != childRows) {
            throw new IllegalStateException(
                    "有 " + (childRows - attached) + " 个 CHILD 找不到所属 PARENT（parent_id 指向的父块不在同一版本），拒绝重建");
        }
        return new RebuildPlan(groups, children, parserVersion);
    }

    private static Chunk toChunk(ChunkRepository.StoredChunk r) {
        return new Chunk(
                ChunkLevel.valueOf(r.level()),
                ChunkType.valueOf(r.type()),
                sectionPath(r.sectionPath()),
                r.title(),
                r.content(),
                r.pageStart(),
                r.pageEnd(),
                r.sourceBlockIds(),
                r.bboxJson(),
                r.tokenCount(),
                r.chunkIndex(),
                r.caption(),
                r.unit(),
                r.currency(),
                r.headersJson(),
                r.relatedFootnoteIds(),
                null);
    }

    private static List<String> sectionPath(String stored) {
        if (stored == null || stored.isBlank()) {
            return List.of();
        }
        return List.of(PATH_SEP.split(stored));
    }

    /** 重建所需的三样东西：父组、每组的孩子、产物里的 parser_version。 */
    record RebuildPlan(List<ParentGroup> parents, List<List<Chunk>> children, String parserVersion) {
    }
}
