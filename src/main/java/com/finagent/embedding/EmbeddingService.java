package com.finagent.embedding;

import com.finagent.chunk.model.Chunk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 向量化服务。
 *
 * <p><b>唯一职责是「控批量」</b> —— Spring AI 的 {@code EmbeddingModel.embed(List)} 会把整个列表
 * 一次性发出去，而百炼 text-embedding-v4 单请求上限 10 行。不切批就会直接 400。
 *
 * <p>输入用拼接后的 embedding_text（含标题与章节路径），但<b>不改写 chunk.content</b> ——
 * 拼接模板是「给模型看的 Prompt」，原始内容才是事实源（§8.5）。
 */
@Service
public class EmbeddingService {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingService.class);

    /** 百炼 text-embedding-v4 的单请求行数上限（路线图已核实） */
    public static final int MAX_ROWS_PER_REQUEST = 10;

    /** 服务商模型名 —— 写进 embedding_model */
    public static final String MODEL = "text-embedding-v4";

    /**
     * 本项目的接入版本 —— 写进 embedding_version。
     *
     * <p>语义与 {@link #MODEL} 分开（评审 C9）：模型没换、但我们改了调用参数
     *（dimensions、encoding_format 等）时升这个号。初版把两者写反了，
     * 会让 idx_chunk_embedding_meta 误判重嵌入范围。
     */
    public static final String VERSION = "v1";

    private final EmbeddingModel embeddingModel;

    public EmbeddingService(EmbeddingModel embeddingModel) {
        this.embeddingModel = embeddingModel;
    }

    public String modelName() {
        return MODEL;
    }

    public String modelVersion() {
        return VERSION;
    }

    /**
     * 批量向量化，返回与输入<b>等长等序</b>的向量列表。
     *
     * @throws IllegalStateException 返回数量与输入不符（宁可失败也不要错位入库）
     */
    public List<float[]> embedAll(List<String> texts) {
        if (texts == null || texts.isEmpty()) {
            return List.of();
        }
        List<float[]> result = new ArrayList<>(texts.size());
        for (int i = 0; i < texts.size(); i += MAX_ROWS_PER_REQUEST) {
            int end = Math.min(i + MAX_ROWS_PER_REQUEST, texts.size());
            List<String> batch = texts.subList(i, end);
            List<float[]> vectors = embeddingModel.embed(batch);
            if (vectors == null || vectors.size() != batch.size()) {
                throw new IllegalStateException(
                        "向量数量与输入不符：输入 " + batch.size() + " 条，返回 "
                                + (vectors == null ? 0 : vectors.size()) + " 条");
            }
            result.addAll(vectors);
            log.debug("已向量化 {}/{}", end, texts.size());
        }
        return result;
    }

    /**
     * 构造送进模型的文本。
     *
     * <p>模板变了要重嵌入，所以入库时同时记 embedding_text_hash —— 它能精确圈出
     * 「哪些 chunk 的 embedding_text 变了」（§8.6）。
     */
    public static String embeddingText(String documentTitle, Chunk chunk) {
        StringBuilder sb = new StringBuilder();
        if (documentTitle != null && !documentTitle.isBlank()) {
            sb.append(documentTitle.strip()).append('\n');
        }
        if (chunk.sectionPath() != null && !chunk.sectionPath().isEmpty()) {
            sb.append(String.join(" > ", chunk.sectionPath())).append('\n');
        }
        if (chunk.caption() != null && !chunk.caption().isBlank()) {
            sb.append(chunk.caption().strip()).append('\n');
        }
        sb.append(chunk.content());
        return sb.toString();
    }
}
