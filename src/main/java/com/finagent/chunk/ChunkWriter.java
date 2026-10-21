package com.finagent.chunk;

import com.finagent.chunk.model.Chunk;
import com.finagent.chunk.model.ParentGroup;
import com.finagent.embedding.EmbeddingService;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 编排：切好的 chunk -> 向量 -> 入库（短事务）。
 *
 * <p><b>为什么把算向量放在事务外</b>（评审 C2）：532 个 Child 约 54 次 HTTP，
 * 若包在事务里，会一直持有着一个上千行插入的事务，既占连接也拉长锁窗口。
 */
@Service
public class ChunkWriter {

    private final ChunkPersister persister;
    private final EmbeddingService embeddingService;

    public ChunkWriter(ChunkPersister persister, EmbeddingService embeddingService) {
        this.persister = persister;
        this.embeddingService = embeddingService;
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
        // 1) 事务外：算向量
        List<String> texts = WritePlan.embeddingTexts(childrenPerGroup, documentTitle);
        List<float[]> vectors = embeddingService.embedAll(texts);

        // 2) 组装（顺序与收集时一致，错位不会报错所以必须由同一个方法保证）
        WritePlan plan = WritePlan.assemble(parents, childrenPerGroup, texts, vectors);

        // 3) 事务内：写 -> 校验 -> 原子切换
        return persister.persistAndSwitch(documentId, plan, parserVersion,
                embeddingService.modelName(), embeddingService.modelVersion());
    }
}
