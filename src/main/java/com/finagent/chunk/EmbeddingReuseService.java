package com.finagent.chunk;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 增量重嵌入（契约 C10）。
 *
 * <p>目的：重切文档时，绝大多数块的内容其实没变（典型情况是改了一个表格的渲染、或只重跑了
 * 切块参数），把 654 块全部重新送 embedding API 既慢（~60 秒）又费钱。
 *
 * <p>复用键是 {@code embedding_text_hash}（C1 拼法的 SHA-256）：
 * <ul>
 *   <li>hash 相同 → 送进模型的文本一模一样 → 向量必然可复用</li>
 *   <li>hash 不同 → 文本变了 → 必须重嵌</li>
 *   <li>hash 相同但 {@code embedding_model} / {@code embedding_version} 变了 → 也必须重嵌
 *       （换了模型或改了调用参数，旧向量与新模型不在同一个空间）</li>
 * </ul>
 *
 * <p>本类只做「分类」，不做 IO、不调 API —— 这样「哪些复用、哪些重嵌」可以纯函数测试。
 */
@Service
public class EmbeddingReuseService {

    /**
     * 分类结果。
     *
     * @param reusableByHash 可复用向量（hash → 旧向量）
     * @param toEmbedTexts   需要重新嵌入的文本，顺序与调用方传入的新块顺序一致
     */
    public record ReuseResult(Map<String, float[]> reusableByHash, List<String> toEmbedTexts) {
    }

    /**
     * 在旧版本 CHILD 里建复用索引，把新块分成「复用」与「待嵌入」。
     *
     * @param oldChildren 旧 ACTIVE 版本的 CHILD 向量
     * @param newTexts    新版本的 embedding_text（顺序与 children 一致）
     * @param newHashes   新版本的 embedding_text_hash（与 newTexts 一一对应）
     * @param model       当前 embedding_model（复用条件之一）
     * @param version     当前 embedding_version（复用条件之一）
     */
    public ReuseResult plan(List<ChunkRepository.ChildEmbedding> oldChildren,
                            List<String> newTexts, List<String> newHashes,
                            String model, String version) {
        Map<String, float[]> reusable = new HashMap<>();
        for (ChunkRepository.ChildEmbedding c : oldChildren) {
            // 三个条件缺一不可：hash 相同、模型相同、版本相同；向量为空也不能算可复用
            if (model.equals(c.embeddingModel()) && version.equals(c.embeddingVersion())
                    && c.embedding() != null) {
                reusable.putIfAbsent(c.embeddingTextHash(), c.embedding());
            }
        }
        List<String> toEmbed = new ArrayList<>();
        for (int i = 0; i < newTexts.size(); i++) {
            if (!reusable.containsKey(newHashes.get(i))) {
                toEmbed.add(newTexts.get(i));
            }
        }
        return new ReuseResult(reusable, toEmbed);
    }

    /**
     * 把「待嵌入」的新向量按原顺序填回，可复用的位置用旧向量。
     *
     * @param freshVectors 与 {@link ReuseResult#toEmbedTexts()} 等长等序
     * @return 与 newHashes 等长的向量列表，顺序与 chunk 收集顺序一致
     */
    public List<float[]> assemble(ReuseResult reuse, List<String> newHashes,
                                  List<float[]> freshVectors) {
        List<float[]> out = new ArrayList<>(newHashes.size());
        int cursor = 0;
        for (String hash : newHashes) {
            float[] v = reuse.reusableByHash().get(hash);
            if (v != null) {
                out.add(v);
            } else {
                out.add(freshVectors.get(cursor++));
            }
        }
        if (cursor != freshVectors.size()) {
            throw new IllegalStateException(
                    "待嵌入向量数量与分类结果不符：分类 " + cursor + " 条，实际 " + freshVectors.size() + " 条");
        }
        return out;
    }
}
