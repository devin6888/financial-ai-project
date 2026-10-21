package com.finagent.chunk.model;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Chunk 的唯一带逻辑的方法：sectionPathText()。 */
class ChunkTest {

    private static Chunk chunkWithPath(List<String> path) {
        return new Chunk(ChunkLevel.CHILD, ChunkType.TEXT, path, "标题",
                "内容", 1, 1, "b0", null, 10, 0,
                null, null, null, null, null, null);
    }

    @Test
    void joinsPathWithSectionSign() {
        assertThat(chunkWithPath(List.of("第一节 重要提示", "一、风险")).sectionPathText())
                .isEqualTo("第一节 重要提示 § 一、风险");
    }

    @Test
    void singleElementPathHasNoSeparator() {
        assertThat(chunkWithPath(List.of("第一节")).sectionPathText()).isEqualTo("第一节");
    }

    @Test
    void nullOrEmptyPathYieldsNull() {
        // 封面/前置页没有章节路径 —— 存 null 而不是空串，便于 SQL 里用 IS NULL 区分
        assertThat(chunkWithPath(null).sectionPathText()).isNull();
        assertThat(chunkWithPath(List.of()).sectionPathText()).isNull();
    }

    @Test
    void sectionSignSurvivesTitlesContainingAngleBracket() {
        // 章节标题里可能含 >，用 § 分隔才不会被误解析
        assertThat(chunkWithPath(List.of("A > B 类业务", "（一）明细")).sectionPathText())
                .isEqualTo("A > B 类业务 § （一）明细");
    }
}
