package com.finagent.eval;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 门槛（Task 8）：把 ST6 §2 的五个数字变成会红的断言。
 *
 * <p>为什么读文件而不是自己再跑一遍：评测一次要四五分钟且花钱，
 * 每跑一次回答都不同；门槛必须落在「同一份结果」上，不能取两次运行的混合值。
 * 顺序由文件名保证（AnswerEvalTest 先于 EvalGateTest），Maven 也按类名排序执行。
 */
@Tag("e2e")
class EvalGateTest {

    private static final Path ANSWER_SUMMARY = Path.of("target/eval-answers.json");
    private static final Path RETRIEVAL_SUMMARY = Path.of("target/eval-retrieval.json");

    @Test
    @DisplayName("检索：Recall@10 ≥ 0.90、DocRecall@10 ≥ 0.90")
    void retrievalGate() throws IOException {
        String json = read(RETRIEVAL_SUMMARY, "先跑 RetrievalEvalTest");
        double recall10 = number(json, "recallAt10");
        double docRecall10 = number(json, "docRecall10");

        assertThat(recall10).as("Recall@10").isGreaterThanOrEqualTo(0.90);
        assertThat(docRecall10).as("DocRecall@10").isGreaterThanOrEqualTo(0.90);
    }

    @Test
    @DisplayName("答案：引用准确率 ≥ 0.90、数值准确率 ≥ 0.90、拒答 100%、期间标注 100%")
    void answerGate() throws IOException {
        String json = read(ANSWER_SUMMARY, "先跑 AnswerEvalTest");
        String summary = json.substring(0, json.indexOf(",\"rows\"") + 1);

        assertThat(number(summary, "citationAccuracy")).as("引用准确率").isGreaterThanOrEqualTo(0.90);
        assertThat(number(summary, "valueAccuracy")).as("数值准确率").isGreaterThanOrEqualTo(0.90);
        assertThat(number(summary, "refusalAccuracy")).as("拒答正确率").isEqualTo(1.0);
        assertThat(number(summary, "periodSamples")).as("期间标注样本数").isPositive();
        assertThat(number(summary, "periodRate")).as("期间标注率").isEqualTo(1.0);
    }

    private static String read(Path path, String hint) throws IOException {
        if (!Files.exists(path)) {
            throw new AssertionError("找不到 " + path + " —— " + hint);
        }
        return Files.readString(path);
    }

    private static double number(String json, String key) {
        Matcher m = Pattern.compile("\"" + key + "\":(-?\\d+(?:\\.\\d+)?)").matcher(json);
        assertThat(m.find()).as("汇总里找不到字段 " + key).isTrue();
        return Double.parseDouble(m.group(1));
    }
}
