package com.finagent.answer;

import com.finagent.citation.Citation;
import com.finagent.citation.CitationValidator;
import com.finagent.context.ContextBlock;
import com.finagent.context.ContextPackage;
import com.finagent.understanding.QueryType;
import com.finagent.understanding.QueryUnderstanding;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 答案生成：解析、降级、拒答、facts 截断。LLM 用假实现，不烧 token。 */
class AnswerServiceTest {

    private AnswerGenerator generator;
    private AnswerService service;

    @BeforeEach
    void setUp() {
        generator = mock(AnswerGenerator.class);
        var mapper = JsonMapper.builder().build();
        service = new AnswerService(generator, new AnswerParser(mapper),
                new CitationValidator(mapper), new FactGuard());
    }

    @Test
    @DisplayName("正常 JSON → 解析出 answer / facts / citations，confidence=high")
    void normalAnswer() {
        when(generator.complete(anyString(), anyString())).thenReturn("""
                {"sufficient":true,"answer":"2024年营业总收入 12,084,594,947.22 元 [E1]",
                 "facts":[{"metric":"营业总收入","period":"2024","value":"12,084,594,947.22",
                           "unit":"元","scope":"合并","evidence":"E1"}],
                 "usedEvidence":["E1"],"confidence":"high","note":""}
                """);

        AnswerResult r = service.answer("2024年营业总收入", qu(2024, 2024), pkg(block(1, 101, 38, 47, "TABLE")));

        assertThat(r.sufficient()).isTrue();
        assertThat(r.answer()).contains("12,084,594,947.22");
        assertThat(r.facts()).hasSize(1);
        assertThat(r.facts().get(0).source()).isEqualTo("PDF");
        assertThat(r.citations()).extracting(Citation::childChunkId).containsExactly(101L);
        assertThat(r.confidence()).isEqualTo("high");
        assertThat(r.warnings()).isEmpty();
    }

    @Test
    @DisplayName("正文里的跳号编号被重排成 1..n：模型写 [E2][E7]，citations 只有 2 条 → [E1][E2]")
    void renumbersSkippedCitationMarkers() {
        when(generator.complete(anyString(), anyString())).thenReturn("""
                {"sufficient":true,"answer":"营业总收入 12,084,594,947.22 元 [E2]，净利润 3,000 万元 [E7]",
                 "facts":[],"usedEvidence":["E2","E7"],"confidence":"high","note":""}
                """);

        AnswerResult r = service.answer("2024年营业总收入", qu(2024, 2024),
                pkg(block(1, 101, 38, 47, "TABLE"), block(2, 102, 38, 48, "TABLE"),
                        block(3, 103, 38, 49, "TABLE"), block(4, 104, 38, 50, "TABLE"),
                        block(5, 105, 38, 51, "TABLE"), block(6, 106, 38, 52, "TABLE"),
                        block(7, 107, 38, 53, "TABLE")));

        assertThat(r.citations()).extracting(Citation::childChunkId).containsExactly(102L, 107L);
        assertThat(r.answer()).contains("[E1]").contains("[E2]").doesNotContain("[E7]");
    }

    @Test
    @DisplayName("越界编号在正文里被删掉，不留点不开的死链")
    void dropsOutOfRangeMarker() {
        when(generator.complete(anyString(), anyString())).thenReturn("""
                {"sufficient":true,"answer":"营业总收入 12,084,594,947.22 元 [E1][E9]",
                 "facts":[],"usedEvidence":["E1","E9"],"confidence":"high","note":""}
                """);

        AnswerResult r = service.answer("2024年营业总收入", qu(2024, 2024),
                pkg(block(1, 101, 38, 47, "TABLE")));

        assertThat(r.citations()).hasSize(1);
        assertThat(r.answer()).contains("[E1]").doesNotContain("E9");
    }

    @Test
    @DisplayName("sufficient=false → 直接返回，不做 facts 校验、不带引用")
    void insufficientAnswer() {
        when(generator.complete(anyString(), anyString())).thenReturn("""
                {"sufficient":false,"answer":"证据里没有 2023 年的数据。","facts":[],
                 "usedEvidence":[],"confidence":"high","note":"缺 2023 年报"}
                """);

        AnswerResult r = service.answer("2023年营业收入", qu(2023, 2023), pkg(block(1, 101, 38, 47, "TEXT")));

        assertThat(r.sufficient()).isFalse();
        assertThat(r.facts()).isEmpty();
        assertThat(r.citations()).isEmpty();
        assertThat(r.confidence()).isEqualTo("insufficient");
        assertThat(r.note()).isEqualTo("缺 2023 年报");
    }

    @Test
    @DisplayName("模型带 markdown 围栏 → 仍能解析")
    void lenientParsing() {
        when(generator.complete(anyString(), anyString())).thenReturn("""
                ```json
                {"sufficient":true,"answer":"见证据 [E1]","facts":[],"usedEvidence":["E1"],
                 "confidence":"high","note":""}
                ```
                """);

        AnswerResult r = service.answer("q", null, pkg(block(1, 101, 38, 47, "TEXT")));

        assertThat(r.confidence()).isEqualTo("high");
        assertThat(r.citations()).hasSize(1);
    }

    @Test
    @DisplayName("非 JSON → confidence=error + answer_generation_failed，不抛异常")
    void unparsableAnswer() {
        when(generator.complete(anyString(), anyString())).thenReturn("抱歉，我无法回答这个问题。");

        AnswerResult r = service.answer("q", null, pkg(block(1, 101, 38, 47, "TEXT")));

        assertThat(r.sufficient()).isFalse();
        assertThat(r.confidence()).isEqualTo("error");
        assertThat(r.warnings()).contains("answer_generation_failed");
        assertThat(r.citations()).isEmpty();
    }

    @Test
    @DisplayName("引用编号越界 → 被剔除，confidence 降为 medium")
    void outOfRangeCitationDowngrades() {
        when(generator.complete(anyString(), anyString())).thenReturn("""
                {"sufficient":true,"answer":"见证据 [E1][E9]","facts":[],"usedEvidence":["E1","E9"],
                 "confidence":"high","note":""}
                """);

        AnswerResult r = service.answer("q", null, pkg(block(1, 101, 38, 47, "TEXT")));

        assertThat(r.citations()).hasSize(1);
        assertThat(r.confidence()).isEqualTo("medium");
        assertThat(r.warnings()).contains("answer_citations_dropped");
    }

    @Test
    @DisplayName("同一指标在两份报告里数值不同 → low + 两条 warning")
    void multiDocConflict() {
        when(generator.complete(anyString(), anyString())).thenReturn("""
                {"sufficient":true,"answer":"合并营业总收入 见 [E1][E2]","facts":[
                   {"metric":"营业总收入","period":"2024","value":"12,084,594,947.22","unit":"元","scope":"合并","evidence":"E1"},
                   {"metric":"营业总收入","period":"2024","value":"12,000,000,000.00","unit":"元","scope":"合并","evidence":"E2"}],
                 "usedEvidence":["E1","E2"],"confidence":"high","note":""}
                """);

        AnswerResult r = service.answer("2024年营业总收入", null,
                pkg(block(1, 101, 38, 47, "TEXT"),
                        block(2, 102, 7, 47, "TEXT", "营业总收入 12,000,000,000.00")));

        assertThat(r.confidence()).isEqualTo("low");
        assertThat(r.warnings()).contains("fact_multi_doc_conflict", "fact_value_mismatch");
        assertThat(r.note()).contains("不一致");
    }

    @Test
    @DisplayName("答案里的数字在证据里找不到 → 拒答（模型编造员工总数）")
    void refusesFabricatedNumber() {
        when(generator.complete(anyString(), anyString())).thenReturn("""
                {"sufficient":true,"answer":"2024年员工总数为5,560人 [E1]",
                 "facts":[{"metric":"员工总数","period":"2024","value":"5,560","unit":"人",
                           "scope":"合并","evidence":"E1"}],
                 "usedEvidence":["E1"],"confidence":"high","note":""}
                """);

        AnswerResult r = service.answer("2024年员工总数", null, pkg(block(1, 101, 7, 47, "TABLE")));

        assertThat(r.sufficient()).isFalse();
        assertThat(r.confidence()).isEqualTo("insufficient");
        assertThat(r.warnings()).anyMatch(w -> w.startsWith("answer_value_not_in_evidence"));
        assertThat(r.facts()).isEmpty();
    }

    @Test
    @DisplayName("模型自己算的增长率不算编造：只校验报告式数字")
    void allowsComputedRatio() {
        when(generator.complete(anyString(), anyString())).thenReturn("""
                {"sufficient":true,"answer":"营业总收入 12,084,594,947.22 元，同比增长 38.4% [E1]",
                 "facts":[],"usedEvidence":["E1"],"confidence":"high","note":""}
                """);

        AnswerResult r = service.answer("2024年营业总收入", qu(2024, 2024), pkg(block(1, 101, 38, 47, "TABLE")));

        assertThat(r.sufficient()).isTrue();
        assertThat(r.confidence()).isEqualTo("high");
    }

    @Test
    @DisplayName("空证据包 → 拒答，一次都不调模型")
    void refusesWithoutCallingModel() {
        AnswerResult r = service.answer("q", null, ContextPackage.empty());

        assertThat(r.sufficient()).isFalse();
        assertThat(r.confidence()).isEqualTo("insufficient");
        assertThat(r.warnings()).contains("NO_EVIDENCE_IN_SCOPE");
        verify(generator, never()).complete(anyString(), anyString());
    }

    @Test
    @DisplayName("facts 超过 8 条 → 截断前 8 条 + facts_truncated")
    void truncatesFacts() {
        StringBuilder facts = new StringBuilder();
        for (int i = 1; i <= 10; i++) {
            if (i > 1) {
                facts.append(',');
            }
            facts.append("{\"metric\":\"指标").append(i).append("\",\"period\":\"2024\",\"value\":\"")
                    .append(i).append("\",\"unit\":\"元\",\"scope\":\"合并\",\"evidence\":\"E1\"}");
        }
        String json = "{\"sufficient\":true,\"answer\":\"见 [E1]\",\"facts\":[" + facts
                + "],\"usedEvidence\":[\"E1\"],\"confidence\":\"high\",\"note\":\"\"}";
        when(generator.complete(anyString(), anyString())).thenReturn(json);

        AnswerResult r = service.answer("q", null, pkg(block(1, 101, 38, 47, "TEXT")));

        assertThat(r.facts()).hasSize(8);
        assertThat(r.warnings()).contains("facts_truncated");
    }

    // ------------------------------------------------------------ helpers

    private static QueryUnderstanding qu(int from, int to) {
        return new QueryUnderstanding(QueryType.EXACT_FACT, "q", List.of("300059"), new int[]{from, to},
                List.of("营业总收入"), false, false, null, false, "TEST");
    }

    private static ContextPackage pkg(ContextBlock... blocks) {
        List<ContextBlock> list = new ArrayList<>(List.of(blocks));
        int tokens = list.stream().mapToInt(ContextBlock::tokenCount).sum();
        return new ContextPackage("证据文本", List.copyOf(list), tokens, List.of(), 1, 0);
    }

    private static ContextBlock block(int no, long id, long docId, int page, String type) {
        return block(no, id, docId, page, type, "营业总收入 12,084,594,947.22");
    }

    private static ContextBlock block(int no, long id, long docId, int page, String type, String content) {
        return new ContextBlock(no, id, 500L, docId, "东方财富2025年年度报告", "第二节 > 六、主要会计数据",
                "六、主要会计数据", content, page, page, type, "主要会计数据", "元",
                null, null, 0.9, 10, 100, ContextBlock.ROLE_HIT);
    }
}
