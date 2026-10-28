package com.finagent.eval;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 评测集自检：不是测系统，是测「考题本身有没有错」。
 *
 * <p>三类会毁掉整份评测的错误：
 * <ol>
 *   <li>evidenceChunkIds 指向不存在的块 / 已被 SUPERSEDED 的块；</li>
 *   <li>expectedValues 在标注的证据块里根本找不到（抄错数字）；</li>
 *   <li>陷阱题标了「该拒答」却其实有证据。</li>
 * </ol>
 */
@SpringBootTest
@ActiveProfiles("test")
@Tag("e2e")
class GoldenSetTest {

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    @DisplayName("32 题齐全，四类各占预期数量")
    void hasAllTypes() {
        List<GoldenSet.Item> items = GoldenSet.load();

        assertThat(items).hasSize(32);
        assertThat(items).extracting(GoldenSet.Item::id).doesNotHaveDuplicates();
        assertThat(items.stream().filter(i -> i.type().equals("METRIC")).count()).isEqualTo(14);
        assertThat(items.stream().filter(i -> i.type().equals("NARRATIVE")).count()).isEqualTo(7);
        assertThat(items.stream().filter(i -> i.type().equals("COMPARISON")).count()).isEqualTo(6);
        assertThat(items.stream().filter(i -> i.type().equals("TRAP")).count()).isEqualTo(5);
    }

    @Test
    @DisplayName("标注的证据块都真实存在、属于 ACTIVE CHILD、且确实含期望数值")
    void evidenceIsGrounded() {
        List<String> problems = new ArrayList<>();
        for (GoldenSet.Item item : GoldenSet.load()) {
            if (!item.answerable()) {
                if (!item.evidenceChunkIds().isEmpty()) {
                    problems.add(item.id() + " 陷阱题不该标证据");
                }
                continue;
            }
            if (item.evidenceChunkIds().isEmpty()) {
                problems.add(item.id() + " 可答题没有证据块");
                continue;
            }
            List<String> contents = new ArrayList<>();
            for (Long id : item.evidenceChunkIds()) {
                // 阶段 7 之后重切是常规操作：chunk id 会变，用 embedding_text_hash 做稳定锚点
                String hash = EvidenceResolver.hashOf(jdbc, id);
                if (hash == null || hash.isBlank()) {
                    problems.add(item.id() + " 证据块不存在：" + id);
                    continue;
                }
                List<String> rows = jdbc.queryForList(
                        "SELECT content FROM document_chunk"
                                + " WHERE embedding_text_hash = ? AND status = 'ACTIVE' AND chunk_level = 'CHILD'",
                        String.class, hash);
                if (rows.isEmpty()) {
                    problems.add(item.id() + " 证据内容已不在 ACTIVE 版本里：id=" + id);
                } else {
                    contents.addAll(rows);
                }
            }
            String joined = String.join("\n", contents);
            for (String value : item.expectedValues()) {
                if (!joined.contains(value) && !joined.contains(value.replace(",", ""))) {
                    problems.add(item.id() + " 证据块里找不到期望数值：" + value);
                }
            }
            for (Long docId : item.expectedDocIds()) {
                Integer n = jdbc.queryForObject("SELECT count(*) FROM document WHERE id = ?", Integer.class, docId);
                if (n == null || n == 0) {
                    problems.add(item.id() + " 期望文档不存在：" + docId);
                }
            }
        }
        assertThat(problems).as("评测集自身有问题：\n" + String.join("\n", problems)).isEmpty();
    }

    @Test
    @DisplayName("作用域都指向真实存在的文档")
    void scopesExist() {
        for (GoldenSet.Item item : GoldenSet.load()) {
            for (Long id : item.documentIds()) {
                Integer n = jdbc.queryForObject("SELECT count(*) FROM document WHERE id = ?", Integer.class, id);
                assertThat(n).as(item.id() + " 作用域文档不存在：" + id).isNotNull().isPositive();
            }
        }
    }
}
