package com.finagent.memory;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/** 显式命令的规则解析：宁可漏，不可误写（ST9 D2）。 */
class MemoryCommandParserTest {

    private final MemoryCommandParser parser = new MemoryCommandParser();

    @Test
    @DisplayName("「以后回答先给结论」→ INSTRUCTION / answer.style")
    void parsesStyleCommand() {
        MemoryCommand cmd = parse("以后回答先给结论");
        assertThat(cmd.memoryType()).isEqualTo(LongTermMemory.TYPE_INSTRUCTION);
        assertThat(cmd.memoryKey()).isEqualTo("answer.style");
        assertThat(cmd.content()).isEqualTo("先给结论");
        assertThat(cmd.sourceRef()).isEqualTo("以后回答先给结论");
    }

    @Test
    @DisplayName("「以后都用中文回答」→ answer.language")
    void parsesLanguageCommand() {
        MemoryCommand cmd = parse("以后都用中文回答");
        assertThat(cmd.memoryKey()).isEqualTo("answer.language");
        assertThat(cmd.content()).isEqualTo("用中文回答");
    }

    @Test
    @DisplayName("「以后每个数字都要给出处」→ analysis.must_cite")
    void parsesCitationCommand() {
        MemoryCommand cmd = parse("以后每个数字都要给出处");
        assertThat(cmd.memoryKey()).isEqualTo("analysis.must_cite");
        assertThat(cmd.content()).contains("出处");
    }

    @Test
    @DisplayName("「记住：…」→ instruction.general，且去掉命令词前缀")
    void parsesRememberCommand() {
        MemoryCommand cmd = parse("记住：我更关心经营性现金流");
        assertThat(cmd.memoryKey()).isEqualTo("instruction.general");
        assertThat(cmd.content()).isEqualTo("我更关心经营性现金流");
    }

    @Test
    @DisplayName("「以后不要再用表格」→ instruction.negative，内容保留「不要」")
    void parsesNegativeCommand() {
        MemoryCommand cmd = parse("以后不要再用表格");
        assertThat(cmd.memoryKey()).isEqualTo("instruction.negative");
        assertThat(cmd.content()).isEqualTo("不要再用表格");
    }

    @Test
    @DisplayName("「以后重点关注300059」→ WATCHLIST，key 带代码")
    void parsesWatchlistCommand() {
        MemoryCommand cmd = parse("以后重点关注300059");
        assertThat(cmd.memoryType()).isEqualTo(LongTermMemory.TYPE_WATCHLIST);
        assertThat(cmd.memoryKey()).isEqualTo("focus.company.300059");
    }

    @Test
    @DisplayName("不是命令句就什么都不写：「我记得东方财富去年营收」")
    void ignoresOrdinarySentence() {
        assertThat(parser.parse("我记得东方财富去年营收是多少")).isEmpty();
        assertThat(parser.parse("东方财富2025年营业收入")).isEmpty();
        assertThat(parser.parse("  ")).isEmpty();
    }

    @Test
    @DisplayName("命令在第二句也能识别（按句子切分后逐句匹配）")
    void findsCommandInLaterClause() {
        MemoryCommand cmd = parse("2025年营收多少。以后回答先给结论");
        assertThat(cmd.memoryKey()).isEqualTo("answer.style");
    }

    private MemoryCommand parse(String question) {
        Optional<MemoryCommand> parsed = parser.parse(question);
        assertThat(parsed).as("应命中：" + question).isPresent();
        return parsed.get();
    }
}
