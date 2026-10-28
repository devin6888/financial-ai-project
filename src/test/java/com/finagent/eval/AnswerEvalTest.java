package com.finagent.eval;

import com.finagent.chat.ChatRequest;
import com.finagent.chat.ChatResponse;
import com.finagent.chat.ChatService;
import com.finagent.citation.Citation;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 端到端评测（Task 3）：真模型、真数据库，32 题全跑一遍。
 *
 * <p>判分口径写在 ST6 §2，这里只做三件机器能做的事：
 * <ol>
 *   <li>引用对不对：文档在期望集合里，且被引块正文里确有期望数值；</li>
 *   <li>数值答没答：期望数值是否出现在 answer 或 facts 里（去千分位后比）；</li>
 *   <li>陷阱题有没有拒答。</li>
 * </ol>
 * 「答得好不好」交给 {@link HumanReviewExporter} 生成的表人工看。
 */
@SpringBootTest
@ActiveProfiles("test")
@Tag("e2e")
class AnswerEvalTest {

    private static final Pattern YEAR = Pattern.compile("(?<!\\d)(19|20)\\d{2}(?!\\d)");
    private static final Path ROWS = Path.of("target/eval-rows.jsonl");

    @Autowired
    private ChatService chatService;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    @DisplayName("32 题端到端跑通，打印逐题判定并落盘汇总")
    void evaluateAnswers() throws IOException {
        List<GoldenSet.Item> items = GoldenSet.load();
        List<AnswerMetrics.Row> rows = new ArrayList<>();
        List<HumanReviewExporter.Row> table = new ArrayList<>();
        List<String> problems = new ArrayList<>();

        // 评测很贵（32 题 × 真模型 ≈ 4 分钟），中途被杀不该从第一题重来。
        // -Deval.resume=true 时读回已完成的题，只补没跑过的。
        Set<String> done = new HashSet<>();
        if (Boolean.getBoolean("eval.resume") && Files.exists(ROWS)) {
            for (EvalRow old : loadRows()) {
                done.add(old.id());
                rows.add(old.metric());
                table.add(old.review());
            }
            System.out.println("EVAL-RESUME|已完成 " + done.size() + " 题，跳过");
        }

        for (GoldenSet.Item item : items) {
            if (done.contains(item.id())) {
                continue;
            }
            long t0 = System.currentTimeMillis();
            ChatResponse r = chatService.ask(new ChatRequest(item.question(), item.toChatScope(), "NEVER"));
            long ms = System.currentTimeMillis() - t0;

            int valid = 0;
            List<String> citeTrace = new ArrayList<>();
            for (Citation c : r.citations()) {
                String content = citedContent(c);
                boolean docOk = item.expectedDocIds().contains(c.documentId());
                boolean valueOk = item.expectedValues().isEmpty() || expectedIn(content, item.expectedValues());
                if (docOk && valueOk) {
                    valid++;
                }
                citeTrace.add("#" + c.childChunkId() + " doc=" + c.documentId()
                        + (docOk ? "" : "!doc") + (valueOk ? "" : "!value"));
            }
            double citationAccuracy = r.citations().isEmpty() ? -1 : (double) valid / r.citations().size();
            boolean valuesOk = item.expectedValues().isEmpty() || answerHasValues(r, item.expectedValues());

            Integer questionYear = singleYear(item.question());
            boolean periodExpected = false;
            if (questionYear != null) {
                for (Citation c : r.citations()) {
                    Integer docYear = jdbc.query("SELECT doc_year FROM document WHERE id = ?",
                            rs -> rs.next() ? (Integer) rs.getObject(1) : null, c.documentId());
                    if (docYear != null && !docYear.equals(questionYear)) {
                        periodExpected = true;
                    }
                }
            }
            boolean periodFlagged = r.warnings().stream().anyMatch(w -> w.startsWith("evidence_period_mismatch:"));

            AnswerMetrics.Row metric = new AnswerMetrics.Row(item.id(), item.type(), r.sufficient(),
                    r.confidence(), valuesOk, r.citations().size(), citationAccuracy, periodExpected,
                    periodFlagged, ms);
            HumanReviewExporter.Row review = new HumanReviewExporter.Row(item.id(), item.type(), item.question(),
                    r.answer(), r.confidence(), valid, r.citations().size(), valuesOk,
                    periodExpected ? (periodFlagged ? "已标注" : "缺标注") : null, ms);
            rows.add(metric);
            table.add(review);
            appendRow(new EvalRow(item.id(), item.type(), item.question(), r.answer(), r.confidence(),
                    r.sufficient(), valuesOk, r.citations().size(), valid, periodExpected, periodFlagged, ms));

            System.out.printf("EVAL-ANS|%s|%s|sufficient=%s|conf=%s|values=%s|cite=%d/%d|period=%s|ms=%d|warn=%s|citeTrace=%s%n",
                    item.id(), item.type(), r.sufficient(), r.confidence(), valuesOk ? "ok" : "MISS",
                    valid, r.citations().size(), periodExpected ? (periodFlagged ? "flagged" : "UNFLAGGED") : "-",
                    ms, r.warnings(), citeTrace);

            // 硬不变量：引用必须能反解析到真实行；陷阱题不能编出引用
            for (Citation c : r.citations()) {
                Integer n = jdbc.queryForObject(
                        "SELECT count(*) FROM document_chunk WHERE id = ? AND document_id = ?",
                        Integer.class, c.childChunkId(), c.documentId());
                if (n == null || n == 0) {
                    problems.add(item.id() + " 引用不可反解析：" + c.childChunkId());
                }
            }
        }

        AnswerMetrics.Summary summary = AnswerMetrics.summarize(rows);
        printSummary(rows, summary);
        persist(rows, table, summary);

        assertThat(problems).as("引用必须能反解析到 document_chunk").isEmpty();
        assertThat(rows).hasSize(32);
    }

    private record EvalRow(String id, String type, String question, String answer, String confidence,
                           boolean sufficient, boolean valuesOk, int citations, int valid,
                           boolean periodExpected, boolean periodFlagged, long ms) {

        AnswerMetrics.Row metric() {
            return new AnswerMetrics.Row(id, type, sufficient, confidence, valuesOk, citations,
                    citations == 0 ? -1 : (double) valid / citations, periodExpected, periodFlagged, ms);
        }

        HumanReviewExporter.Row review() {
            return new HumanReviewExporter.Row(id, type, question, answer, confidence, valid, citations,
                    valuesOk, periodExpected ? (periodFlagged ? "已标注" : "缺标注") : null, ms);
        }

        String toJson() {
            return String.format("{\"id\":\"%s\",\"type\":\"%s\",\"question\":\"%s\",\"answer\":\"%s\","
                            + "\"confidence\":\"%s\",\"sufficient\":%s,\"valuesOk\":%s,\"citations\":%d,"
                            + "\"valid\":%d,\"periodExpected\":%s,\"periodFlagged\":%s,\"ms\":%d}",
                    id, type, esc(question), esc(answer), confidence, sufficient, valuesOk, citations, valid,
                    periodExpected, periodFlagged, ms);
        }
    }

    private static String esc(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
    }

    private static void appendRow(EvalRow row) throws IOException {
        Files.createDirectories(Path.of("target"));
        Files.writeString(ROWS, row.toJson() + "\n", java.nio.file.StandardOpenOption.CREATE,
                java.nio.file.StandardOpenOption.APPEND);
    }

    private static List<EvalRow> loadRows() throws IOException {
        List<EvalRow> out = new ArrayList<>();
        for (String line : Files.readAllLines(ROWS)) {
            if (line.isBlank()) {
                continue;
            }
            out.add(new EvalRow(str(line, "id"), str(line, "type"), str(line, "question"), str(line, "answer"),
                    str(line, "confidence"), bool(line, "sufficient"), bool(line, "valuesOk"),
                    Integer.parseInt(num(line, "citations")), Integer.parseInt(num(line, "valid")),
                    bool(line, "periodExpected"), bool(line, "periodFlagged"), Long.parseLong(num(line, "ms"))));
        }
        return out;
    }

    private static String str(String json, String key) {
        Matcher m = Pattern.compile("\"" + key + "\":\"((?:[^\"\\\\]|\\\\.)*)\"").matcher(json);
        return m.find() ? m.group(1).replace("\\n", "\n").replace("\\\"", "\"") : "";
    }

    private static String num(String json, String key) {
        Matcher m = Pattern.compile("\"" + key + "\":(-?\\d+)").matcher(json);
        return m.find() ? m.group(1) : "0";
    }

    private static boolean bool(String json, String key) {
        Matcher m = Pattern.compile("\"" + key + "\":(true|false)").matcher(json);
        return m.find() && Boolean.parseBoolean(m.group(1));
    }

    private static void printSummary(List<AnswerMetrics.Row> rows, AnswerMetrics.Summary s) {
        System.out.println("EVAL-ANS-SUM|" + s.line());
        for (String type : List.of("METRIC", "NARRATIVE", "COMPARISON", "TRAP")) {
            List<AnswerMetrics.Row> sub = rows.stream().filter(r -> r.type().equals(type)).toList();
            System.out.println("EVAL-ANS-SUM|" + type + "|" + AnswerMetrics.summarize(sub).line());
        }
        for (AnswerMetrics.Row r : rows) {
            if (!r.valuesOk() && !r.type().equals("TRAP")) {
                System.out.println("EVAL-ANS-MISS|" + r.id() + "|" + r.confidence());
            }
            if (r.type().equals("TRAP") && r.sufficient()) {
                System.out.println("EVAL-ANS-BADTRAP|" + r.id() + "|" + r.confidence());
            }
            if (r.periodExpected() && !r.periodFlagged()) {
                System.out.println("EVAL-ANS-UNFLAGGED|" + r.id());
            }
        }
    }

    private static void persist(List<AnswerMetrics.Row> rows, List<HumanReviewExporter.Row> table,
                               AnswerMetrics.Summary s) throws IOException {
        StringBuilder sb = new StringBuilder("{\"summary\":").append(s.toJson()).append(",\"rows\":[");
        for (int i = 0; i < rows.size(); i++) {
            AnswerMetrics.Row r = rows.get(i);
            if (i > 0) {
                sb.append(',');
            }
            sb.append(String.format("{\"id\":\"%s\",\"type\":\"%s\",\"sufficient\":%s,\"confidence\":\"%s\","
                            + "\"valuesOk\":%s,\"citations\":%d,\"citationAccuracy\":%.4f,"
                            + "\"periodExpected\":%s,\"periodFlagged\":%s,\"ms\":%d}",
                    r.id(), r.type(), r.sufficient(), r.confidence(), r.valuesOk(), r.citations(),
                    r.citationAccuracy(), r.periodExpected(), r.periodFlagged(), r.ms()));
        }
        sb.append("]}");
        Files.createDirectories(Path.of("target"));
        Files.writeString(Path.of("target/eval-answers.json"), sb.toString());
        Files.createDirectories(Path.of("target"));
        Files.writeString(Path.of("target/eval-summary.json"), s.toJson());
        HumanReviewExporter.write(Path.of("docs/阶段6-人工核验表.md"), table, s);
    }

    private String citedContent(Citation c) {
        List<String> rows = jdbc.queryForList("SELECT content FROM document_chunk WHERE id = ?",
                String.class, c.childChunkId());
        return rows.isEmpty() ? "" : rows.get(0);
    }

    private static boolean expectedIn(String content, List<String> expectedValues) {
        String norm = normalize(content);
        return expectedValues.stream().anyMatch(v -> norm.contains(normalize(v)));
    }

    private static boolean answerHasValues(ChatResponse r, List<String> expectedValues) {
        StringBuilder sb = new StringBuilder(r.answer() == null ? "" : r.answer());
        r.facts().forEach(f -> sb.append(' ').append(f.value()));
        String norm = normalize(sb.toString());
        return expectedValues.stream().allMatch(v -> norm.contains(normalize(v)));
    }

    private static String normalize(String s) {
        return s == null ? "" : s.replace(",", "").replace(" ", "").replace("\\", "");
    }

    private static Integer singleYear(String question) {
        Matcher m = YEAR.matcher(question);
        Integer found = null;
        while (m.find()) {
            int y = Integer.parseInt(m.group());
            if (found != null && found != y) {
                return null;
            }
            found = y;
        }
        return found;
    }
}