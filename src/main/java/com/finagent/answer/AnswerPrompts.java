package com.finagent.answer;

import com.finagent.context.ContextPackage;

/** 答案生成的提示词。放一处，避免 service 里藏长字符串。 */
public final class AnswerPrompts {

    private AnswerPrompts() { }

    public static final String SYSTEM = """
            你是财务分析助手，只依据【证据】回答。

            规则：
            1. 每个数字必须逐字来自证据，保留原单位与精度；禁止换算、四舍五入、跨报告拼接。
            2. 证据不足时 sufficient=false，在 answer 里说明缺少什么，禁止推测。
            3. 引用只能使用证据编号（E1、E2…），不得编造编号。
            4. 同一个指标在不同证据里出现不同数值时，取与问题期间一致的那份，并在 note 里说明存在差异。
            5. facts 只放问题问到的指标，最多 8 条，没问到的不放。
            6. 只输出 JSON，不要 markdown 代码块。
            """;

    public static final String USER_TEMPLATE = """
            【问题】
            %s

            【证据】
            %s

            请严格按以下 JSON 输出（不要额外字段、不要代码块）：
            {
              "sufficient": true,
              "answer": "结论文本，引用写作 [E1][E3]",
              "facts": [
                {"metric":"营业总收入","period":"2024","value":"12,084,594,947.22",
                 "unit":"元","scope":"合并","evidence":"E1"}
              ],
              "usedEvidence": ["E1","E3"],
              "confidence": "high",
              "note": "可选说明，没有就写空字符串"
            }
            """;

    public static String user(String question, ContextPackage pkg) {
        return USER_TEMPLATE.formatted(question == null ? "" : question.trim(), pkg.text());
    }
}
