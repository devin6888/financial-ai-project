package com.finagent.chunk;

import com.finagent.chunk.model.Chunk;
import com.finagent.chunk.model.ParentGroup;

import java.util.ArrayList;
import java.util.List;

/**
 * 一次写入计划的完整描述：Parent + 它的 Child + 每个 Child 的向量。
 *
 * <p>为什么要有这个中间结构：{@link ChunkPersister} 只该关心「写什么」，
 * 不该知道 embedding 的存在；{@link ChunkWriter} 负责把向量贴到正确的位置上。
 */
public record WritePlan(List<PlannedGroup> groups) {

    public record PlannedGroup(Chunk parent, List<PlannedChild> children) {
    }

    public record PlannedChild(Chunk chunk, float[] embedding, String embeddingTextHash) {
    }

    public int parentCount() {
        return groups.size();
    }

    public int childCount() {
        return groups.stream().mapToInt(g -> g.children().size()).sum();
    }

    /**
     * 把向量按**与收集时相同的顺序**贴回 Child。
     *
     * <p>顺序一致性是硬要求：错位会把 A 的向量写到 B 上，而这种错误**不会报任何异常**。
     * 所以收集与装配必须走同一个遍历顺序（见 {@link #embeddingTexts}）。
     */
    public static WritePlan assemble(List<ParentGroup> parents, List<List<Chunk>> childrenPerGroup,
                                     List<String> embeddingTexts, List<float[]> vectors) {
        int total = parents.size();
        List<PlannedGroup> groups = new ArrayList<>(total);
        int cursor = 0;
        for (int g = 0; g < total; g++) {
            List<Chunk> children = childrenPerGroup.get(g);
            List<PlannedChild> planned = new ArrayList<>(children.size());
            for (Chunk c : children) {
                // 重编号：chunk_index 在版本内全局唯一（见 Chunk#withChunkIndex 的说明）。
                // 不做这一步会撞 uq_chunk_version_index —— 端到端跑真实数据时才会暴露。
                planned.add(new PlannedChild(c.withChunkIndex(cursor), vectors.get(cursor),
                        sha256(embeddingTexts.get(cursor))));
                cursor++;
            }
            groups.add(new PlannedGroup(parents.get(g).parent(), planned));
        }
        if (cursor != vectors.size()) {
            throw new IllegalStateException(
                    "向量数量与 chunk 数不符：chunk " + cursor + "，向量 " + vectors.size());
        }
        return new WritePlan(groups);
    }

    /**
     * 记录的是 embedding_text 的 hash 而不是原文。
     *
     * <p>为什么（§8.6）：模板或模型变了，需要精确圈出「哪些 chunk 的 embedding_text 变了」。
     * 存原文会撑大表，存 hash 就够比对。
     */
    static String sha256(String text) {
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("计算 SHA-256 失败", e);
        }
    }

    /** 与 {@link #assemble} 必须使用完全相同的遍历顺序。 */
    public static List<String> embeddingTexts(List<List<Chunk>> childrenPerGroup,
                                              String documentTitle) {
        List<String> out = new ArrayList<>();
        for (List<Chunk> children : childrenPerGroup) {
            for (Chunk c : children) {
                out.add(com.finagent.embedding.EmbeddingService.embeddingText(documentTitle, c));
            }
        }
        return out;
    }
}
