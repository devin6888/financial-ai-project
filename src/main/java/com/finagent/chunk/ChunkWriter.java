package com.finagent.chunk;

import com.finagent.chunk.model.Chunk;
import com.finagent.chunk.model.ParentGroup;
import com.finagent.document.DocumentProgress;
import com.finagent.embedding.EmbeddingService;
import com.finagent.ingest.IngestStep;
import com.finagent.ingest.IngestTracker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 编排：切好的 chunk -> 向量 -> 入库（短事务）。
 *
 * <p><b>为什么把算向量放在事务外</b>（评审 C2）：532 个 Child 约 54 次 HTTP，
 * 若包在事务里，会一直持有着一个上千行插入的事务，既占连接也拉长锁窗口。
 *
 * <p><b>增量重嵌</b>（阶段 7，契约 C10）：写新版本前先按 {@code embedding_text_hash}
 * 去旧 ACTIVE 版本找同 hash 且同 model/version 的块，直接复用其向量；只对内容真变了的块
 * 调 embedding API。首次索引（无旧版本）退化为全量嵌入，行为与阶段 3 一致。
 */
@Service
public class ChunkWriter {

    private static final Logger log = LoggerFactory.getLogger(ChunkWriter.class);

    private final ChunkPersister persister;
    private final ChunkRepository chunks;
    private final EmbeddingService embeddingService;
    private final EmbeddingReuseService reuseService;
    private final DocumentProgress progress;
    private final IngestTracker tracker;

    public ChunkWriter(ChunkPersister persister, ChunkRepository chunks,
                       EmbeddingService embeddingService, EmbeddingReuseService reuseService,
                       DocumentProgress progress, IngestTracker tracker) {
        this.persister = persister;
        this.chunks = chunks;
        this.embeddingService = embeddingService;
        this.reuseService = reuseService;
        this.progress = progress;
        this.tracker = tracker;
    }

    /**
     * @param parents           按顺序的 Parent（含成员）
     * @param childrenPerGroup  与 parents 一一对应的 Child 列表
     * @param parserVersion     写进 chunk 供追溯
     * @return 新的 chunk 版本号
     */
    public int writeVersion(long documentId, String documentTitle,
                            List<ParentGroup> parents, List<List<Chunk>> childrenPerGroup,
                            String parserVersion) {
        // 1) 事务外：算 embedding_text 及其 hash（复用键）
        List<String> texts = WritePlan.embeddingTexts(childrenPerGroup, documentTitle);
        List<String> hashes = new ArrayList<>(texts.size());
        for (String t : texts) {
            hashes.add(WritePlan.sha256(t));
        }

        // 2) 读旧 ACTIVE 版本的 CHILD 向量作为复用源（首次索引没有旧版本 → 空）
        int oldVersion = chunks.activeVersion(documentId);
        List<ChunkRepository.ChildEmbedding> oldChildren = oldVersion == 0
                ? List.of()
                : chunks.findActiveChildEmbeddings(documentId, oldVersion);

        // 3) 分类：哪些能复用、哪些必须重嵌
        EmbeddingReuseService.ReuseResult reuse = reuseService.plan(
                oldChildren, texts, hashes, embeddingService.modelName(),
                embeddingService.modelVersion());

        // 4) 只对「待嵌入」调 API —— 未变的块零 API 调用（验收口径）
        //    进度：55%「切块完成」→ 95%「向量化 x/y」，按<b>条数</b>插值（最后一批常不满 10 条）
        List<String> toEmbed = reuse.toEmbedTexts();
        progress.step(documentId, DocumentProgress.CHUNKED, DocumentProgress.STAGE_CHUNKED);
        int totalChildren = childrenPerGroup.stream().mapToInt(List::size).sum();
        tracker.begin(documentId, IngestStep.CHUNKED);
        tracker.finish(documentId, IngestStep.CHUNKED,
                "Parent " + parents.size() + " / Child " + totalChildren);
        tracker.begin(documentId, IngestStep.EMBEDDING);
        tracker.heartbeatNow(documentId, "已嵌入 0/" + toEmbed.size());
        List<float[]> fresh = embeddingService.embedAll(toEmbed, done -> {
            progress.embeddingStep(documentId, done, toEmbed.size());
            // 批次只改 detail，不新增行 —— 否则「第 7/8 步」会随着批次变成第 20/8 步
            tracker.heartbeat(documentId, "已嵌入 " + done + "/" + toEmbed.size());
        });
        tracker.finish(documentId, IngestStep.EMBEDDING,
                "新嵌 " + fresh.size() + " / 复用 " + (texts.size() - fresh.size()));
        log.info("文档 {} 增量重嵌：复用 {} / 新嵌 {}", documentId,
                texts.size() - fresh.size(), fresh.size());

        // 5) 按原顺序填回向量（复用的直接拷贝旧向量）
        List<float[]> vectors = reuseService.assemble(reuse, hashes, fresh);

        // 6) 组装（顺序与收集时一致，错位不会报错所以必须由同一个方法保证）
        WritePlan plan = WritePlan.assemble(parents, childrenPerGroup, texts, vectors);

        // 7) 事务内：写 -> 校验 -> 原子切换
        int version = persister.persistAndSwitch(documentId, plan, parserVersion,
                embeddingService.modelName(), embeddingService.modelVersion());
        // 切换成功才算「入库完成」：进度 100 与 active_chunk_version 可见性同时成立
        progress.step(documentId, DocumentProgress.DONE, DocumentProgress.STAGE_DONE);
        tracker.begin(documentId, IngestStep.SWITCHED);
        tracker.finish(documentId, IngestStep.SWITCHED, "v" + version);
        tracker.succeed(documentId, IngestTracker.summary(
                "chunks", totalChildren, "version", version,
                "embedded", fresh.size(), "reused", texts.size() - fresh.size()));
        return version;
    }
}
