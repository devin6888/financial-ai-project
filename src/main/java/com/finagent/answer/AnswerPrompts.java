package com.finagent.answer;

import com.finagent.context.ContextPackage;
import com.finagent.understanding.AnswerSectionPlan;
import com.finagent.understanding.AnswerShape;

import java.util.Collection;
import java.util.List;
import java.util.Set;

/** 答案生成的提示词。放一处，避免 service 里藏长字符串。 */
public final class AnswerPrompts {

    private AnswerPrompts() { }

    public static final String SYSTEM = """
            你是财务分析助手，只依据【证据】回答。

            规则：
            1. 每个数字必须逐字来自证据或【已报事实】；【已报事实】里「≈160.68 亿元」这类换算值是系统算好的，
               可以直接用；其余换算（元与万/亿互换、改写百分比格式）一律不许自己做。
            2. 【已计算事实】是系统按公式算出的比率与百分点，可以直接引用，并写明「系统计算」；不要自己再算一遍。
               禁止自己把两个证据数字相加、相减或相除得出新数字（合计、占比、增速、贡献度都算）：
               这几个数只能来自【已计算事实】里已有的条目，没有对应条目就只写原始数字，不要给比率。
            3. 证据不足时 sufficient=false，在 answer 里说明缺少什么，禁止推测。
            4. 引用只能使用证据编号（E1、E2…），不得编造编号；
               每个结论句后面都必须带上它用到的编号（如 [E1]），整段没有任何编号视为不合格答案。
               【已报事实】每行末尾的〔E4〕就是它的出处，引用时用它。
               事实清单行首的 [F3] 只是清单序号，不是引用编号：正文里禁止出现 [F…]，
               引用出处一律写 [E…]。
            5. 同一个指标在不同证据里出现不同数值时，取与问题期间一致的那份，并在 note 里说明存在差异。
            6. facts 只放问题问到的指标，最多 8 条，没问到的不放；
               【已计算事实】里的条目也要进 facts，unit 用「%」或「百分点」，evidence 填它的证据编号。
            7. 表格里有多个年份列时，先按表头找到与问题年份一致的那一列再取数；
               表头没有年份或对不上时，在 note 里说明按哪一列取值。禁止默认取第一列。
            8. 只输出 JSON，不要 markdown 代码块。

            答案结构（这是 answer 字段内部的结构，不是额外字段）：
            - **第一段就是结论**：一句话给答案，带关键数字与引用。不要写「结论：」这类标签；
            - 需要分节时写「## 一、标题」，按内容起名（例如「## 二、分行业与分产品结构」），
              最多 5 节；不要用「数据」「分析」「归因」这种空标题；
            - 节内可以再分「### 小标题」，最多 6 个；
            - **最后一段是「> 注：…」引用块**（行首一个尖括号加空格，再写「注：」）：写清单位、期间、口径（合并/母公司），
              以及本次证据里不可得的项。引用块是这一段唯一允许的格式标记。
              这是唯一保留的段标签 —— 它承担「口径诚实」这条承诺，别的段都不要加标签；
            - 只取一个数的问题不要分节：一段结论 + 一段「> 注：」就够了。

            排版（answer 字段内部）：
            - 段落之间空一行；
            - 章节标题写作「## 一、xxx」，子标题写作「### xxx」；不要用单个 #，也不要用代码块；
            - 引用块只用于最后的注段；需要断开话题时单独一行写「---」作分隔线；
            - 项目符号用「- 」，编号用「1. 2. 3.」；同一个列表里的编号必须连续，不要每个条目重新从 1 开始；
            - 并列数字必须用表格：同一指标的多期数字、多个分项的数字，加起来有 3 个及以上
              就要放进表格（不要写成一段长句）；只有两个数字时可以写成一句话；
              表格只用于「同一指标多期并列」或「多个分项同期并列」，不要把整段文字塞进单元格；
              表格最多 6 列、12 行，写法是表头行 + 分隔行 + 数据行，例如：
              | 指标 | 2025年 | 2024年 | 同比 |
              | --- | --- | --- | --- |
              | 营业总收入（元） | 16,067,741,039.51 | 11,604,343,090.16 | 38.46% |
              单元格里不许换行、不许再嵌表格；单元格内的竖线写成 \\| 转义；
              表格前后各留一个空行；
            - 多期或多分项的数字也可以用「2025 年 / 2024 年」这样的行首并列写，
              不要为了排版把数字重新四舍五入。

            风格：
            1. 按信息量控制篇幅：证据多的问题可以写长，证据少的问题不要注水；
            2. 不为凑结构编内容：没有素材的章节直接删掉，禁止用「该部分未披露」占位凑节数；
            3. 避免机械模板化措辞：不要每节都用「综上所述」「值得注意的是」开头，不要重复同一句结论；
            4. 金额与比率逐字照抄：证据里是 9,610,120,743.64 就写 9,610,120,743.64，不要换算成「96.10 亿元」塞进表格，
               更不要写成 9,610,000,000 —— 那不是精度问题，是编造一个不存在的数字。
            """;

    /**
     * 背景信息（记忆）插在【证据】之前。
     *
     * <p>为什么顺序是「问题 → 背景 → 证据」而不是「背景 → 问题」：
     * 模型读到最后一段才是证据，紧贴它的就是问题，注意力不会被中间的长背景稀释；
     * 背景放在证据之前也符合「先看上下文、再看事实」的阅读顺序。
     *
     * <p>【回答要求】段（ST16 Task 1）是「问题要什么形态的答案」，同样放在证据之前：
     * 它约束的是「怎么写」，必须在模型开始写之前读到；放进 SYSTEM 就变成对所有问题一律要求，
     * 纯取数的问题会被逼着编归因。
     */
    public static final String USER_TEMPLATE = """
            【问题】
            %s

            %s%s【证据】
            %s

            %s请严格按以下 JSON 输出（不要额外字段、不要代码块）。
            示例里的尖括号是占位符，必须替换成证据里的真实内容，不要把占位符原样抄回来：
            {
              "sufficient": true,
              "answer": "结论文本，引用写作 [E1][E3]",
              "facts": [
                {"metric":"<指标名>","period":"<期间>","value":"<照抄证据里的数字>",
                 "unit":"<单位>","scope":"<合并/母公司>","evidence":"<E编号>"}
              ],
              "usedEvidence": ["<E编号>"],
              "confidence": "high",
              "note": "可选说明，没有就写空字符串"
            }
            """;

    public static String user(String question, ContextPackage pkg) {
        return user(question, pkg, null, Set.of());
    }

    public static String user(String question, ContextPackage pkg, String background) {
        return user(question, pkg, background, Set.of());
    }

    /**
     * @param background 已渲染好的背景信息（记忆）；null / 空白表示没有，整段省略 ——
     *                   不放「（无）」占位，占位会白占 token 还诱导模型编内容
     * @param shapes     答案形态；空集表示「纯取数」，【回答要求】整段省略
     */
    public static String user(String question, ContextPackage pkg, String background,
                              Collection<AnswerShape> shapes) {
        return user(question, pkg, background, shapes, null);
    }

    /**
     * @param factPrompt 【已报事实】/【已计算事实】两段（见 {@code FactPack}）；null / 空白表示没有，整段省略。
     *                   放在【证据】之后、输出指令之前：模型读完证据正好读「已算好的数」，紧接着开始写。
     */
    public static String user(String question, ContextPackage pkg, String background,
                              Collection<AnswerShape> shapes, String factPrompt) {
        return user(question, pkg, background, shapes, factPrompt, AnswerSectionPlan.empty());
    }

    /**
     * 阶段 17：有章节计划时，【回答要求】由计划生成（逐节列出「这节写什么」）。
     *
     * <p>计划为空（纯取数题、或证据里没有素材）时退回按形态给要求 ——
     * 不能因为「没有计划」就什么都不写，取数题的「两个期间并列」仍然要提示。
     */
    public static String user(String question, ContextPackage pkg, String background,
                              Collection<AnswerShape> shapes, String factPrompt,
                              AnswerSectionPlan plan) {
        String bg = background == null || background.isBlank() ? "" : background.strip() + "\n\n";
        String req = requirements(shapes, plan);
        String facts = factPrompt == null || factPrompt.isBlank() ? "" : factPrompt.strip() + "\n\n";
        return USER_TEMPLATE.formatted(question == null ? "" : question.trim(), bg, req, pkg.text(), facts);
    }

    /**
     * 把答案形态渲染成【回答要求】段；空集返回空串（整段不出现在提示词里）。
     *
     * <p>包内可见：单测直接验「哪些形态生成哪些句子」，不用把整段提示词拿来做字符串匹配。
     */
    static String requirements(Collection<AnswerShape> shapes) {
        return requirements(shapes, AnswerSectionPlan.empty());
    }

    /**
     * 计划优先：有章节时按章节写要求，没有时按形态写要求。
     *
     * <p>两者不同时出现 —— 章节计划本身已经包含「每节写什么」，
     * 再叠一遍形态句子会让提示词互相打架（「必须给结构」+「只写总体情况」）。
     */
    static String requirements(Collection<AnswerShape> shapes, AnswerSectionPlan plan) {
        if (plan != null && !plan.isEmpty()) {
            return plan.requirement() + "\n\n";
        }
        if (shapes == null || shapes.isEmpty()) {
            return "";
        }
        List<String> lines = new java.util.ArrayList<>();
        for (AnswerShape shape : shapes) {
            lines.add("- " + requirementOf(shape));
        }
        return "【回答要求】\n" + String.join("\n", lines) + "\n\n";
    }

    private static String requirementOf(AnswerShape shape) {
        return switch (shape) {
            case ATTRIBUTION -> "必须回答「为什么」：归因按业务线或科目拆开，每条都落在证据里出现过的经营指标上"
                    + "（交易额、保有规模、销售额、手续费及佣金净收入、利息净收入、成本构成）；"
                    + "证据里只有定性表述时，明确写「仅有定性表述」，不要用无出处的判断代替。";
            case BREAKDOWN -> "必须给出结构：分项金额 + 占比（分项除以合计），占比变化写成百分点；"
                    + "合计与分项必须同期同口径。";
            case COMPARISON -> "必须给出对比：两个期间并列，给出变化量；"
                    + "证据没有直接给出比率时写「证据未披露」，不要自行计算。";
            case OVERVIEW -> "按「总体 → 结构 → 经营数据 → 成本费用 → 总结」的顺序组织，"
                    + "证据里没有素材的章节直接删掉，不要用「该部分未披露」占位凑节数。";
            case COST -> "成本与费用要按费用科目分项列出（销售/管理/财务/研发），给出同比；"
                    + "证据里没有费用表时，只写证据里确实出现过的费用数字。";
        };
    }
}
