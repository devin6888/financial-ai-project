package com.finagent.answer;

import com.finagent.citation.CitationValidator;
import com.finagent.context.ContextBlock;
import com.finagent.context.ContextPackage;
import com.finagent.understanding.QueryType;
import com.finagent.understanding.QueryUnderstanding;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 流式答案：与 {@link AnswerService#answer} 共用同一套校验，只多了一条增量回调。
 */
class AnswerServiceStreamingTest {

    private static final String JSON = """
            {"sufficient":true,"answer":"2025年营业总收入 16,067,741,039.51 元 [E1]",
             "facts":[{"metric":"营业总收入","period":"2025","value":"16,067,741,039.51",
                        "unit":"元","scope":"合并","evidence":"E1"}],
             "usedEvidence":["E1"],"confidence":"high","note":""}
            """;

    private final JsonMapper mapper = JsonMapper.builder().build();

    private AnswerService service(AnswerGenerator generator) {
        return new AnswerService(generator, new AnswerParser(mapper), new CitationValidator(mapper), new FactGuard());
    }

    @Test
    @DisplayName("分三段吐出 JSON → delta 拼接等于 answer，且校验结果与非流式一致")
    void deltasJoinToAnswer() {
        List<String> chunks = List.of(JSON.substring(0, 30), JSON.substring(30, 90), JSON.substring(90));
        ChunkedGenerator generator = new ChunkedGenerator(chunks);
        List<String> deltas = new ArrayList<>();

        AnswerResult r = service(generator).answerStreaming("2025年营业总收入", qu(), pkg(block()), null, deltas::add);

        assertThat(generator.streamed).isTrue();
        assertThat(String.join("", deltas)).isEqualTo("2025年营业总收入 16,067,741,039.51 元 [E1]");
        assertThat(r.answer()).isEqualTo(String.join("", deltas));
        assertThat(r.citations()).hasSize(1);
        assertThat(r.confidence()).isEqualTo("high");
    }

    @Test
    @DisplayName("空证据包 → 不调模型、没有 delta，直接拒答")
    void refusesWithoutCallingModel() {
        ChunkedGenerator generator = new ChunkedGenerator(List.of(JSON));
        List<String> deltas = new ArrayList<>();

        AnswerResult r = service(generator).answerStreaming("q", null, ContextPackage.empty(), null, deltas::add);

        assertThat(generator.streamed).isFalse();
        assertThat(deltas).isEmpty();
        assertThat(r.sufficient()).isFalse();
        assertThat(r.warnings()).contains("NO_EVIDENCE_IN_SCOPE");
    }

    @Test
    @DisplayName("模型输出不是 JSON → 降级为 error，不抛异常")
    void unparsableFallsBackToError() {
        ChunkedGenerator generator = new ChunkedGenerator(List.of("抱歉，", "我无法回答。"));
        List<String> deltas = new ArrayList<>();

        AnswerResult r = service(generator).answerStreaming("q", null, pkg(block()), null, deltas::add);

        assertThat(r.confidence()).isEqualTo("error");
        assertThat(r.warnings()).contains("answer_generation_failed");
        assertThat(r.citations()).isEmpty();
    }

    // ------------------------------------------------------------ helpers

    /** 只实现 stream，complete 一旦被调用就失败 —— 保证测的是流式那条路径。 */
    private static final class ChunkedGenerator implements AnswerGenerator {
        private final List<String> chunks;
        private boolean streamed;

        private ChunkedGenerator(List<String> chunks) {
            this.chunks = chunks;
        }

        @Override
        public String complete(String systemPrompt, String userPrompt) {
            throw new AssertionError("流式路径不应该调用 complete");
        }

        @Override
        public void stream(String systemPrompt, String userPrompt, Consumer<String> onDelta) {
            streamed = true;
            chunks.forEach(onDelta);
        }
    }

    private static QueryUnderstanding qu() {
        return new QueryUnderstanding(QueryType.EXACT_FACT, "q", List.of("300059"), new int[]{2025, 2025},
                List.of("营业总收入"), false, false, null, false, "TEST");
    }

    private static ContextPackage pkg(ContextBlock... blocks) {
        List<ContextBlock> list = new ArrayList<>(List.of(blocks));
        int tokens = list.stream().mapToInt(ContextBlock::tokenCount).sum();
        return new ContextPackage("证据文本", List.copyOf(list), tokens, List.of(), 1, 0);
    }

    private static ContextBlock block() {
        return new ContextBlock(1, 101L, 500L, 7L, "东方财富2025年年度报告", "第二节 > 六、主要会计数据",
                "六、主要会计数据", "营业总收入 16,067,741,039.51", 7, 7, "TABLE", "主要会计数据", "元",
                null, null, 0.9, 10, 100, ContextBlock.ROLE_HIT);
    }
}