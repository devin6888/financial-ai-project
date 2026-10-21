package com.finagent.chat;

import com.finagent.answer.AnswerResult;
import com.finagent.citation.Citation;
import com.finagent.scope.ChatScope;
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
 * 阶段 5 端到端：真模型 + 真库，验四条结构性不变量。
 *
 * <p>为什么不断言「答案对不对」：LLM 的措辞不稳定，断言自然语言会把测试变成脆皮。
 * 但这四条一旦破了，答案一定不可信：
 * <ol>
 *   <li>每份被引用的 chunk 都能反解析回库里真实存在、且文档 id 一致；</li>
 *   <li>引用不越出本轮作用域（这是跨文档评测里最严重的问题「混文档」的防线）；</li>
 *   <li>每个 fact 的数值逐字出现在某条引用证据的正文里（不采信模型算术）；</li>
 *   <li>答案非空且 confidence 是已知取值。</li>
 * </ol>
 */
@SpringBootTest
@ActiveProfiles("test")
@Tag("e2e")
class ChatE2ETest {

    private static final long DOC_2025 = 7L;
    private static final long DOC_2024 = 38L;

    @Autowired
    private ChatService chatService;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    @DisplayName("12 道单文档指标题 + 2 道陷阱题：引用可反解析、不越界、数字逐字来自证据")
    void endToEnd() {
        List<Case> cases = List.of(
                new Case(1, "2024年营业总收入是多少？", List.of(DOC_2024)),
                new Case(2, "2024年归属于母公司股东的净利润是多少？", List.of(DOC_2024)),
                new Case(3, "2024年基本每股收益是多少？", List.of(DOC_2024)),
                new Case(4, "2024年末资产总额是多少？", List.of(DOC_2024)),
                new Case(5, "2024年加权平均净资产收益率是多少？", List.of(DOC_2024)),
                new Case(6, "2024年经营活动产生的现金流量净额是多少？", List.of(DOC_2024)),
                new Case(7, "2025年营业总收入是多少？", List.of(DOC_2025)),
                new Case(8, "2025年归属于母公司股东的净利润是多少？", List.of(DOC_2025)),
                new Case(9, "2025年基本每股收益是多少？", List.of(DOC_2025)),
                new Case(10, "2025年末资产总额是多少？", List.of(DOC_2025)),
                new Case(11, "2025年加权平均净资产收益率是多少？", List.of(DOC_2025)),
                new Case(12, "2025年经营活动产生的现金流量净额是多少？", List.of(DOC_2025)),
                new Case(13, "2023年营业总收入是多少？", List.of(DOC_2024)),
                new Case(14, "2024年和2025年营业总收入对比如何？", List.of(DOC_2024, DOC_2025)));

        List<String> violations = new ArrayList<>();
        for (Case c : cases) {
            ChatResponse r = chatService.ask(new ChatRequest(c.question(), ChatScope.documents(c.docs()), "NEVER"));
            check(c, r, violations);
            System.out.println(line(c, r));
        }
        assertThat(violations).as("结构性不变量被破坏：\n" + String.join("\n", violations)).isEmpty();
    }

    private void check(Case c, ChatResponse r, List<String> violations) {
        if (r == null) {
            violations.add("Q" + c.no() + " 响应为空");
            return;
        }
        if (r.answer() == null || r.answer().isBlank()) {
            violations.add("Q" + c.no() + " 答案为空");
        }
        if (!List.of("high", "medium", "low", "insufficient", "error").contains(r.confidence())) {
            violations.add("Q" + c.no() + " confidence 非法：" + r.confidence());
        }
        if ("error".equals(r.confidence())) {
            violations.add("Q" + c.no() + " 答案生成失败（confidence=error）");
            return;
        }
        for (Citation cite : r.citations()) {
            if (cite.documentId() != c.docs().get(0) && !c.docs().contains(cite.documentId())) {
                violations.add("Q" + c.no() + " 引用越出作用域：doc=" + cite.documentId() + " scope=" + c.docs());
            }
            Integer found = jdbc.queryForObject(
                    "SELECT count(*) FROM document_chunk WHERE id = ? AND document_id = ?",
                    Integer.class, cite.childChunkId(), cite.documentId());
            if (found == null || found == 0) {
                violations.add("Q" + c.no() + " 引用反解析失败：chunk=" + cite.childChunkId()
                        + " doc=" + cite.documentId());
            }
            if (cite.pageStart() == null) {
                violations.add("Q" + c.no() + " 引用没有页码：chunk=" + cite.childChunkId());
            }
        }
        // 数字必须逐字出现在被引用的证据里
        List<String> contents = r.citations().isEmpty() ? List.of()
                : jdbc.queryForList("SELECT content FROM document_chunk WHERE id IN ("
                        + String.join(",", r.citations().stream().map(x -> String.valueOf(x.childChunkId())).toList())
                        + ")", String.class);
        String joined = String.join("\n", contents);
        for (AnswerResult.Fact f : r.facts()) {
            String raw = f.value() == null ? "" : f.value();
            String digits = raw.replace(",", "").replace(" ", "");
            if (!raw.isEmpty() && !joined.contains(raw) && !joined.contains(digits)) {
                violations.add("Q" + c.no() + " fact 数值不在引用证据里：" + f.metric() + "=" + raw);
            }
        }
    }

    private static String line(Case c, ChatResponse r) {
        String first = r.citations().isEmpty() ? "-"
                : r.citations().get(0).childChunkId() + ":" + r.citations().get(0).pageStart();
        String facts = r.facts().isEmpty() ? "-" : r.facts().get(0).metric() + "=" + r.facts().get(0).value();
        return "CHATE2E|Q" + c.no() + "|docs=" + r.scope().resolvedDocumentIds()
                + "|conf=" + r.confidence() + "|cit=" + r.citations().size()
                + "|blocks=" + r.trace().contextBlocks() + "|tokens=" + r.trace().contextTokens()
                + "|ms=" + r.trace().totalMs() + "|first=" + first + "|fact=" + facts
                + "|warn=" + r.warnings()
                + "|q=" + c.question();
    }

    private record Case(int no, String question, List<Long> docs) { }
}
