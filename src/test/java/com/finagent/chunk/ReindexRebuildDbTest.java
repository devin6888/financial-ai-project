package com.finagent.chunk;

import com.finagent.document.DocumentRepository;
import com.finagent.embedding.EmbeddingService;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * RETRIEVAL_ONLY 的真库验证（不调模型、不花钱）。
 *
 * <p>这一条测的不是「能跑」，而是<b>「零成本」</b>：把库里的块搬回内存再按 C1 拼一遍
 * embedding_text，算出的 hash 必须与库里存的那一列逐条相等 —— 否则「只重建检索数据」
 * 会退化成「654 块全量重嵌」，那是 60 秒 + 真金白银。
 *
 * <p>为什么风险这么高还要专门测：section_path 在库里是 {@code " § "} 连接、拼 embedding_text
 * 时是 {@code " > "} 连接，拆回去只要差一个字符，hash 就全不匹配，而<b>这个过程不会报任何错</b>。
 */
@Tag("db")
@SpringBootTest
@ActiveProfiles("test")
class ReindexRebuildDbTest {

    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private ChunkRepository chunks;
    @Autowired
    private DocumentRepository documents;
    @Autowired
    private EmbeddingReuseService reuseService;
    @Autowired
    private ReindexService reindexService;

    @Test
    void rebuildOfActiveVersionReusesEveryVector() {
        long documentId = pickDocument();
        int version = chunks.activeVersion(documentId);
        String title = documents.findParseState(documentId).title();

        List<ChunkRepository.StoredChunk> rows = chunks.findVersionChunks(documentId, version);
        ReindexService.RebuildPlan plan = ReindexService.group(rows);

        List<String> texts = WritePlan.embeddingTexts(plan.children(), title);
        List<String> hashes = texts.stream().map(WritePlan::sha256).toList();
        List<ChunkRepository.ChildEmbedding> old =
                chunks.findActiveChildEmbeddings(documentId, version);

        var reuse = reuseService.plan(old, texts, hashes,
                EmbeddingService.MODEL, EmbeddingService.VERSION);

        // 1) 块数与库里一致（没有漏、没有多）
        assertThat(plan.parents()).hasSize(
                chunks.countByVersion(documentId, version, "PARENT"));
        assertThat(texts).hasSize(chunks.countByVersion(documentId, version, "CHILD"));
        // 2) 一条都不用重嵌 —— 这是本模式「零 API 调用」的全部依据
        assertThat(reuse.toEmbedTexts())
                .as("重建后的 embedding_text 必须与库里逐条一致，否则会全量重嵌")
                .isEmpty();
        // 3) 每个「不同」的 hash 都在旧版本里找到了同 model/version 的向量。
        //    注意这里比较的是去重后的 hash 数：实测 v11 的 654 个 CHILD 只有 652 个不同 hash
        //    （有一组 3 个块 embedding_text 完全相同，向量也只有一个），
        //    复用表本来就是 hash → 向量，重复的块天然共用一个向量，不能按块数断言。
        assertThat(reuse.reusableByHash()).hasSize(new java.util.HashSet<>(hashes).size());
    }

    @Test
    @Transactional
    void retrievalOnlyWritesNewVersionAndSwitches() {
        long documentId = pickDocument();
        int before = chunks.activeVersion(documentId);
        int children = chunks.countByVersion(documentId, before, "CHILD");

        int after = reindexService.reindex(documentId, ReindexMode.RETRIEVAL_ONLY);

        assertThat(after).isEqualTo(before + 1);
        assertThat(chunks.countByVersion(documentId, after, "PARENT"))
                .isEqualTo(chunks.countByVersion(documentId, before, "PARENT"));
        assertThat(chunks.countByVersion(documentId, after, "CHILD")).isEqualTo(children);
        // 新版本里不能有「没有向量」的块（persistAndSwitch 的校验，这里再断言一次口径）
        assertThat(chunks.countChildrenWithoutEmbedding(documentId, after)).isZero();
        // 事务回滚，版本号不会被这次测试消耗
    }

    /** 挑一个块最多的文档：样本够大，hash 全等的结论才有说服力。 */
    private long pickDocument() {
        Long id = jdbc.queryForObject("""
                SELECT d.id FROM document d
                 WHERE d.active_chunk_version > 0
                 ORDER BY (SELECT count(*) FROM document_chunk c
                            WHERE c.document_id = d.id
                              AND c.chunk_version = d.active_chunk_version) DESC,
                          d.id
                 LIMIT 1
                """, Long.class);
        assertThat(id).as("库里至少要有一个已切块的文档").isNotNull();
        return id;
    }
}
