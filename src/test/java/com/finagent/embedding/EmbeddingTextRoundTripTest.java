package com.finagent.embedding;

import com.finagent.chunk.model.Chunk;
import com.finagent.chunk.model.ChunkLevel;
import com.finagent.chunk.model.ChunkType;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 验证「库里的向量 == 把该 chunk 的 embedding_text 送进模型得到的向量」。
 *
 * <p>为什么值得专门测：这是整条链路最容易出现静默偏差的地方 ——
 * 如果拼接顺序、分隔符、或字段来源在入库与查询之间不一致，
 * 向量与文本就会「错位」，而检索只会表现为「结果有点不准」，不会报错。
 *
 * <p>{@code embedding_text} 不是数据库字段，它是入库时临时拼出来的：
 * {@code 文档标题 + section_path + 题注 + content}（§8.5）。
 * 库里只存它的指纹 {@code embedding_text_hash}，不存原文。
 */
@Tag("db")
@SpringBootTest
@ActiveProfiles("test")
class EmbeddingTextRoundTripTest {

    /** 阶段 3 入库的一个 TEXT 子块。 */
    private static final long CHUNK_ID = 805L;

    @Autowired private JdbcTemplate jdbc;
    @Autowired private EmbeddingService embeddingService;

    @Test
    void storedVectorEqualsEmbeddingOfReconstructedText() {
        Map<String, Object> row = jdbc.queryForMap("""
                SELECT dc.id, dc.content, dc.title, dc.section_path, dc.caption,
                       dc.embedding::text AS vec, d.title AS doc_title
                  FROM document_chunk dc
                  JOIN document d ON d.id = dc.document_id
                 WHERE dc.id = ?
                """, CHUNK_ID);

        String sectionPath = (String) row.get("section_path");
        Chunk chunk = new Chunk(
                ChunkLevel.CHILD, ChunkType.TEXT,
                sectionPath == null ? List.of() : Arrays.asList(sectionPath.split(" § ")),
                (String) row.get("title"),
                (String) row.get("content"),
                0, 0, null, null, 0, 0,
                (String) row.get("caption"), null, null, null, null, null);

        // 1) 重建 embedding_text —— 与入库时用的是同一个方法
        String text = EmbeddingService.embeddingText((String) row.get("doc_title"), chunk);

        // 2) 重新嵌入
        float[] fresh = embeddingService.embedAll(List.of(text)).get(0);

        // 3) 与库里存的向量比
        float[] stored = parseVector((String) row.get("vec"));
        assertThat(stored).hasSize(1024);
        assertThat(fresh).hasSize(1024);

        double cos = cosine(stored, fresh);
        System.out.printf("chunk %d 的向量一致性（余弦相似度）: %.6f%n", CHUNK_ID, cos);

        // 同一文本 + 同一模型 -> 应该几乎完全一致
        // 留一点余量是因为 embedding 服务本身可能有浮点级的不确定性
        assertThat(cos).isGreaterThan(0.999);
    }

    /** 解析 pgvector 的文本表示 "[0.1,0.2,...]"。 */
    static float[] parseVector(String literal) {
        String body = literal.trim();
        if (body.startsWith("[")) {
            body = body.substring(1);
        }
        if (body.endsWith("]")) {
            body = body.substring(0, body.length() - 1);
        }
        String[] parts = body.split(",");
        float[] out = new float[parts.length];
        for (int i = 0; i < parts.length; i++) {
            out[i] = Float.parseFloat(parts[i].trim());
        }
        return out;
    }

    static double cosine(float[] a, float[] b) {
        double dot = 0, na = 0, nb = 0;
        for (int i = 0; i < a.length; i++) {
            dot += (double) a[i] * b[i];
            na += (double) a[i] * a[i];
            nb += (double) b[i] * b[i];
        }
        return dot / (Math.sqrt(na) * Math.sqrt(nb));
    }
}
