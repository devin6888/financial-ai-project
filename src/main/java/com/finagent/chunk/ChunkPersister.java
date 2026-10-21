package com.finagent.chunk;

import com.finagent.chunk.WritePlan.PlannedChild;
import com.finagent.chunk.WritePlan.PlannedGroup;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 只负责「写库 + 校验 + 原子切换」，**不含任何网络调用**（评审 C2）。
 *
 * <p>为什么与 {@link ChunkWriter} 拆成两个 Bean：{@code @Transactional} 靠代理生效，
 * **同类内部调用不会开事务**。把事务方法放在另一个 Bean 里，才能被正确代理。
 */
@Component
public class ChunkPersister {

    private static final Logger log = LoggerFactory.getLogger(ChunkPersister.class);

    private final JdbcTemplate jdbc;
    private final ChunkRepository chunks;

    public ChunkPersister(JdbcTemplate jdbc, ChunkRepository chunks) {
        this.jdbc = jdbc;
        this.chunks = chunks;
    }

    /**
     * 写入新版本 → 校验 → 原子切换 → 旧版本退役，**全部在一个事务内**。
     *
     * <p><b>原子性怎么保证</b>：新版本的行以 {@code chunk_version=newVersion} 写入并标 ACTIVE，
     * 但检索只读 {@code document.active_chunk_version} 指向的那一版 ——
     * 所以在事务提交前，新版本对检索<b>不可见</b>；提交时指针与旧版 SUPERSEDED 一起生效。
     *
     * <p><b>失败时</b>：整个事务回滚，新版本的行不会残留，{@code active_chunk_version} 不变，
     * 旧版本继续可用（§8.6 硬要求）。
     *
     * <p><b>并发时</b>（评审 C1）：入口就 {@code SELECT ... FOR UPDATE} 锁住 document 行，
     * 第二个重索引会阻塞到第一个提交，从而读到正确的 base 版本。
     * 另有 {@code uq_chunk_version_index} 唯一索引兜底。
     */
    @Transactional
    public int persistAndSwitch(long documentId, WritePlan plan, String parserVersion,
                                String embeddingModel, String embeddingVersion) {
        // 1) 行锁 + 读当前版本（锁持有到本事务提交）
        Integer base = jdbc.queryForObject(
                "SELECT coalesce(active_chunk_version, 0) FROM document WHERE id = ? FOR UPDATE",
                Integer.class, documentId);
        int newVersion = (base == null ? 0 : base) + 1;

        // 2) 写入：每个 group 插完 Parent 立刻用返回的 id 插它的 Child。
        //    这个两段式写法天然不需要暂存 parent id —— 初版用实例字段暂存是错的（评审 B5）。
        for (PlannedGroup g : plan.groups()) {
            long parentId = chunks.insertParent(documentId, newVersion, g.parent(), parserVersion);
            for (PlannedChild c : g.children()) {
                chunks.insertChild(documentId, parentId, newVersion, c.chunk(),
                        c.embedding(), c.embeddingTextHash(),
                        embeddingModel, embeddingVersion, parserVersion);
            }
        }

        // 3) 校验：数量 + 向量完整性。任何一条不满足都抛异常 → 整体回滚
        int parentsWritten = chunks.countByVersion(documentId, newVersion, "PARENT");
        int childrenWritten = chunks.countByVersion(documentId, newVersion, "CHILD");
        if (parentsWritten != plan.parentCount()) {
            throw new IllegalStateException(
                    "Parent 数量不符：期望 " + plan.parentCount() + "，实际 " + parentsWritten);
        }
        if (childrenWritten != plan.childCount()) {
            throw new IllegalStateException(
                    "Child 数量不符：期望 " + plan.childCount() + "，实际 " + childrenWritten);
        }
        int missing = chunks.countChildrenWithoutEmbedding(documentId, newVersion);
        if (missing != 0) {
            throw new IllegalStateException("有 " + missing + " 个 CHILD 没有向量，拒绝切换");
        }

        // 4) 原子切换 + 旧版本退役
        chunks.switchActiveVersion(documentId, newVersion);
        int superseded = chunks.supersedeOtherVersions(documentId, newVersion);

        log.info("文档 {} 已切到 chunk 版本 {}（Parent {} / Child {}，旧版本退役 {} 行）",
                documentId, newVersion, parentsWritten, childrenWritten, superseded);
        return newVersion;
    }
}
