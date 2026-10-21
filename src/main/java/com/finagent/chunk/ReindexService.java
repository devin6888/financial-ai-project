package com.finagent.chunk;

import com.finagent.document.DocumentModel;
import com.finagent.document.DocumentRepository;
import com.finagent.document.NormalizedDocumentLoader;
import com.finagent.chunk.model.ParentGroup;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 重索引编排。
 *
 * <p>刻意<b>不</b>写第二套切块/嵌入逻辑 —— 复用 {@link ParentChunker}、
 * {@link ChildChunker}、{@link ChunkWriter}。重索引与首次索引走同一条链路，
 * 两套实现迟早会不一致。
 */
@Service
public class ReindexService {

    private static final Logger log = LoggerFactory.getLogger(ReindexService.class);

    private final DocumentRepository documents;
    private final NormalizedDocumentLoader loader;
    private final ChunkWriter writer;

    public ReindexService(DocumentRepository documents, NormalizedDocumentLoader loader,
                          ChunkWriter writer) {
        this.documents = documents;
        this.loader = loader;
        this.writer = writer;
    }

    /**
     * @return 新的 chunk 版本号
     * @throws UnsupportedOperationException 本阶段未实现的模式
     */
    public int reindex(long documentId, ReindexMode mode) {
        if (mode != ReindexMode.RECHUNK) {
            // 显式失败，而不是静默什么都不做（评审 Y2 / M1）
            throw new UnsupportedOperationException(
                    mode + " 尚未实现：RETRIEVAL_ONLY 依赖阶段 4 的检索参数，"
                            + "REEMBED 依赖换模型场景，FULL_REPARSE 依赖调用解析服务。"
                            + "本阶段只交付 RECHUNK。");
        }

        DocumentRepository.ParseState st = documents.findParseState(documentId);
        if (!"DONE".equals(st.parseStatus())) {
            throw new IllegalStateException(
                    "文档 " + documentId + " 尚未解析完成（状态 " + st.parseStatus() + "），无法重切");
        }

        DocumentModel model = loader.load(st.parsedObjectKey());
        List<ParentGroup> groups = ParentChunker.split(model);
        List<List<com.finagent.chunk.model.Chunk>> children =
                groups.stream().map(ChildChunker::splitAll).toList();

        String parserVersion = model.meta() == null
                ? st.parserVersion() : model.meta().parser_version();

        log.info("开始重切 document_id={}：Parent {} / Child {}",
                documentId, groups.size(), children.stream().mapToInt(List::size).sum());
        return writer.writeVersion(documentId, st.title(), groups, children, parserVersion);
    }
}
