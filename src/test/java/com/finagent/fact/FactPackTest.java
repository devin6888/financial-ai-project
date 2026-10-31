package com.finagent.fact;

import com.finagent.answer.AnswerResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class FactPackTest {

    private static Map<String, Object> row(int no, String metric, String period, String value, String unit,
                                           String evidence, String origin, String formula, String display,
                                           String note) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("no", no);
        row.put("metric", metric);
        row.put("period", period);
        row.put("value", value);
        row.put("unit", unit);
        row.put("scope", "");
        row.put("evidence", evidence);
        row.put("origin", origin);
        row.put("formula", formula);
        row.put("display", display);
        row.put("note", note);
        return row;
    }

    private static List<Map<String, Object>> sampleRows() {
        return List.of(
                // 故意乱序：no 决定渲染顺序
                row(9, "营业总收入同比增长率", "2025vs2024", "38.46", "%", "E2",
                        AnswerResult.ORIGIN_COMPUTED,
                        "(16,067,741,039.51 − 11,604,343,090.16) / 11,604,343,090.16 × 100",
                        "", "与证据披露的 38.46% 一致"),
                row(1, "营业总收入", "2025", "16067741039.51", "元", "E2",
                        AnswerResult.ORIGIN_REPORTED, "", "160.68 亿元", ""));
    }

    @Test
    @DisplayName("渲染出两段：已报事实带换算值与原编号，已计算事实带公式与「系统计算」")
    void rendersBothSections() {
        FactPack pack = FactPack.from(sampleRows());

        assertThat(pack.prompt()).contains("【已报事实】").contains("【已计算事实】");
        assertThat(pack.prompt()).contains("160.68 亿元").contains("〔E2〕");
        assertThat(pack.prompt()).contains("系统计算").contains("11,604,343,090.16");
        // 已报事实在已计算事实之前
        assertThat(pack.prompt().indexOf("【已报事实】")).isLessThan(pack.prompt().indexOf("【已计算事实】"));
    }

    @Test
    @DisplayName("白名单里是去千分位的数字串：正文写 160.68 或 16067741039.51 都能对上")
    void allowedValuesAreNormalised() {
        FactPack pack = FactPack.from(sampleRows());

        assertThat(pack.allowedValues()).contains("160.68", "16067741039.51", "38.46");
    }

    @Test
    @DisplayName("还原成 AnswerResult.facts：origin 与 formula 都带过去")
    void restoresFactRows() {
        FactPack pack = FactPack.from(sampleRows());

        assertThat(pack.rows()).hasSize(2);
        // 状态里 no 小的在前：1 是已报事实，9 是计算事实
        assertThat(pack.rows().get(0).origin()).isEqualTo(AnswerResult.ORIGIN_REPORTED);
        assertThat(pack.rows().get(1).origin()).isEqualTo(AnswerResult.ORIGIN_COMPUTED);
        assertThat(pack.rows().get(1).formula()).contains("× 100");
    }

    @Test
    @DisplayName("空状态 → 空包：提示词里不出现空标题")
    void emptyRowsYieldEmptyPack() {
        assertThat(FactPack.from(List.of())).isSameAs(FactPack.EMPTY);
        assertThat(FactPack.EMPTY.prompt()).isEmpty();
        assertThat(FactPack.EMPTY.isEmpty()).isTrue();
    }
}
