package com.finagent.understanding;

import com.finagent.context.ContextBlock;
import com.finagent.context.EvidenceProfile;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 章节计划：这道题「按什么结构写」（阶段 17，ST17 Task 1）。
 *
 * <p><b>要解决的问题</b>：阶段 16 的答案有固定四段（结论/数据/归因/口径），
 * 所有问题长得一样 —— 用户的原话是「我们的回答好像就限定好了」。
 * 这份计划把「章节」从常量变成<b>按形态 + 证据算出来的候选集</b>：
 * 同一个提示词模板，既能让「分析营收情况」出现 5 节，也能让「同比增长多少」只有一段话。
 *
 * <p><b>为什么章节要闸门</b>：只有「问题问了」还不够，还要「证据里有」。
 * 证据里没有费用表却给一节「成本与费用」，模型只能写「该部分未披露」占位 ——
 * 那是拿结构骗人。所以每条闸门都对应一个可断言的证据信号（{@link EvidenceProfile}）。
 *
 * <p><b>这是一份建议，不是强制</b>：提示词里明确写「证据里没有素材的章节删掉」，
 * verify 也只判「章节数 ≥ min(3, N)」，不判标题 —— 否则模型换个说法就被判失败。
 *
 * @param sections    按建议顺序的章节（0~5 个）
 * @param requirement 写进【回答要求】的那段话；空计划时为空串
 */
public record AnswerSectionPlan(List<Section> sections, String requirement) {

    /**
     * 一个候选章节。
     *
     * @param id          稳定标识（overview / structure / operation / cost / profit / drivers / compare / detail / summary）
     * @param title       渲染用的中文标题，答案里写成「## 一、xxx」
     * @param purpose     给模型的一句「这节写什么」
     * @param layout      建议版式：paragraph / table / mixed（建议，不强制）
     * @param evidenceNos 本节优先看哪几条证据（软提示，不裁剪上下文）
     */
    public record Section(String id, String title, String purpose, String layout,
                          List<String> evidenceNos) {
        public Section {
            evidenceNos = evidenceNos == null ? List.of() : List.copyOf(evidenceNos);
        }

        /** 状态里只放 JDK 类型（C17 硬规则），所以状态/轨迹里用它。 */
        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", id);
            m.put("title", title);
            m.put("purpose", purpose);
            m.put("layout", layout);
            m.put("evidenceNos", evidenceNos);
            return m;
        }
    }

    public static final int MAX_SECTIONS = 5;

    /** 中文序号，最多五节（与 MAX_SECTIONS 对齐）。 */
    private static final String[] NUMERALS = {"一", "二", "三", "四", "五"};

    public AnswerSectionPlan {
        sections = sections == null ? List.of() : List.copyOf(sections);
        requirement = requirement == null ? "" : requirement;
    }

    public static AnswerSectionPlan empty() {
        return new AnswerSectionPlan(List.of(), "");
    }

    public boolean isEmpty() {
        return sections.isEmpty();
    }

    public List<String> titles() {
        return sections.stream().map(Section::title).toList();
    }

    /** 「一、xx」形式的编号标题，给提示词与测试用。 */
    public List<String> numberedTitles() {
        List<String> out = new ArrayList<>(sections.size());
        for (int i = 0; i < sections.size(); i++) {
            out.add(NUMERALS[i] + "、" + sections.get(i).title());
        }
        return out;
    }

    /**
     * 按形态与证据画像算候选章节。
     *
     * <p>闸门（写死在这里，可单测）：
     * <ol>
     *   <li>「成本与费用」只在 {@code profile.hasCostTable()} 时出现；</li>
     *   <li>「经营数据」只在 {@code profile.hasOperatingMetrics()} 时出现；</li>
     *   <li>「总结」只在已有 ≥3 节时出现；</li>
     *   <li>形态为空且画像为空 → 空计划（答案回到「一段话 + 注」）。</li>
     *   <li>问句里没有「分析意图」→ 空计划（「同比增长多少」这种只要一个数）。</li>
     * </ol>
     *
     * @param blocks 本轮证据；用于算 {@code evidenceNos}，null 视为没有
     */
    public static AnswerSectionPlan of(Set<AnswerShape> shapes, EvidenceProfile profile,
                                       List<ContextBlock> blocks) {
        return of(shapes, profile, blocks, null);
    }

    /**
     * 分析意图词（阶段 17 验收补的第 5 条闸门）。
     *
     * <p>为什么需要它：真链路里「2025年营业总收入同比增长多少」被 LLM 判成
     * SEMANTIC_EXPLANATION，形态里带 COMPARISON（因为问句有「同比」），于是排了
     * 「对比表 + 变化说明」两节，答案却只有一句话 —— verify 记了一条
     * {@code sections_missing:0/2}，等于每问一个单值问题都要被扣一次分。
     * 分节的依据是「这个问题要不要看结构」，不是「问句里有没有某个词」。
     */
    private static final Pattern ANALYSIS_INTENT = Pattern.compile(
            "分析|情况|原因|为什么|为何|结构|构成|表现|概览|概况|经营|拆解|驱动|对比|差异");

    /**
     * @param question 原始问题；用于判分析意图，null 表示不判（单测与历史调用）
     */
    public static AnswerSectionPlan of(Set<AnswerShape> shapes, EvidenceProfile profile,
                                       List<ContextBlock> blocks, String question) {
        EvidenceProfile p = profile == null ? EvidenceProfile.EMPTY : profile;
        Set<AnswerShape> s = shapes == null ? Set.of() : shapes;
        if (s.isEmpty() && p.isEmpty()) {
            return empty();
        }
        if (question != null && !ANALYSIS_INTENT.matcher(question).find()) {
            return empty();
        }

        boolean overview = s.contains(AnswerShape.OVERVIEW);
        boolean breakdown = s.contains(AnswerShape.BREAKDOWN);
        boolean attribution = s.contains(AnswerShape.ATTRIBUTION);
        boolean comparison = s.contains(AnswerShape.COMPARISON);

        // 顺序即建议顺序（ST17 §1.2 的形态→章节表）；同 id 只加一次，先到的标题胜出
        LinkedHashMap<String, Section> picked = new LinkedHashMap<>();
        if (overview) {
            add(picked, section("overview", "营业总收入总体情况", "一句话结论 + 营收与净利同比", "paragraph",
                    List.of("营业总收入", "营业收入", "净利润"), blocks));
            add(picked, section("structure", "分行业与分产品结构", "分行业、分产品、分地区的金额与占比", "table",
                    List.of("分行业", "分产品", "分地区", "占比"), blocks));
        }
        if (breakdown) {
            add(picked, section("structure", "结构与占比", "分项金额 + 占比，合计与分项同期同口径", "table",
                    List.of("分行业", "分产品", "占比", "构成"), blocks));
        }
        if (attribution) {
            add(picked, section("drivers", "增长驱动因素", "按业务线或科目拆开，每条落在证据里出现过的经营指标上",
                    "paragraph", List.of("驱动", "原因", "交易额", "手续费", "利息"), blocks));
        }
        // 「核心业务经营数据」只服务概览题与归因题；结构题要的是占比，不是交易额
        if ((overview || attribution) && p.hasOperatingMetrics()) {
            addOperation(picked, blocks);
        }
        // 「成本与费用」只在问到了概览 / 成本时出现，且必须有费用表（闸门 1）
        if (overview || s.contains(AnswerShape.COST)) {
            addCost(picked, p, blocks);
        }
        if (comparison) {
            add(picked, section("compare", "期间对比", "两个期间并列，给出变化量", "table",
                    List.of("同比", "增减", "变动"), blocks));
        }
        // 「分项说明」是结构题 / 对比题的收尾（挑变化最大的分项展开）；概览题用「总结」收尾
        if (breakdown || comparison) {
            add(picked, section("detail", "分项说明", "挑变化最大的分项展开，不要逐项复述表格", "paragraph",
                    List.of("同比", "增减"), blocks));
        }
        if (picked.size() >= 3) {
            add(picked, section("summary", "总结", "3 条以内的判断，每条能指回上面的数字", "paragraph",
                    List.of(), blocks));
        }

        List<Section> sections = picked.values().stream().limit(MAX_SECTIONS).toList();
        return new AnswerSectionPlan(sections, requirementOf(sections, s));
    }

    /**
     * 从状态里那份 {@code List<Map<String,Object>>} 还原（compute 写、answer 读）。
     *
     * <p>为什么不把 requirement 一起存进状态：requirement 是由「章节 + 形态」推出来的纯函数，
     * 存两份就有两个口径。状态里只存事实（章节），文本现场生成。
     */
    public static AnswerSectionPlan fromRows(List<Map<String, Object>> rows, Set<AnswerShape> shapes) {
        if (rows == null || rows.isEmpty()) {
            return empty();
        }
        List<Section> sections = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) {
            if (row == null || row.get("title") == null) {
                continue;
            }
            sections.add(new Section(
                    str(row.get("id")), str(row.get("title")), str(row.get("purpose")),
                    str(row.get("layout")), strings(row.get("evidenceNos"))));
        }
        Set<AnswerShape> s = shapes == null ? Set.of() : shapes;
        return new AnswerSectionPlan(sections, requirementOf(sections, s));
    }

    private static String str(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static List<String> strings(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        List<String> out = new ArrayList<>(list.size());
        for (Object item : list) {
            if (item != null) {
                out.add(String.valueOf(item));
            }
        }
        return List.copyOf(out);
    }

    // ---------------------------------------------------------------- 内部

    private static void addCost(LinkedHashMap<String, Section> picked, EvidenceProfile p,
                                List<ContextBlock> blocks) {
        if (!p.hasCostTable()) {
            return;
        }
        add(picked, section("cost", "成本与费用情况", "销售/管理/财务/研发费用及同比", "table",
                List.of("销售费用", "管理费用", "财务费用", "研发费用"), blocks));
    }

    private static void addOperation(LinkedHashMap<String, Section> picked, List<ContextBlock> blocks) {
        add(picked, section("operation", "核心业务经营数据", "交易额、保有规模、销售额、客户数（有几条写几条）",
                "mixed", List.of("交易额", "保有规模", "销售额", "客户数"), blocks));
    }

    /** 同 id 只加一次：多个形态都会想要「结构」节，不能出现两节同名。 */
    private static void add(LinkedHashMap<String, Section> picked, Section section) {
        picked.putIfAbsent(section.id(), section);
    }

    private static Section section(String id, String title, String purpose, String layout,
                                   List<String> keywords, List<ContextBlock> blocks) {
        return new Section(id, title, purpose, layout, evidenceNos(keywords, blocks));
    }

    /**
     * 在证据里找「本节该优先看哪几条」：标题 / 章节路径 / 正文前 120 字里出现关键词即算命中。
     *
     * <p>最多 3 条 —— 给多了等于没给提示。找不到就返回空列表，提示词里那一行整行省略。
     */
    static List<String> evidenceNos(List<String> keywords, List<ContextBlock> blocks) {
        if (keywords == null || keywords.isEmpty() || blocks == null || blocks.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<String> out = new LinkedHashSet<>();
        for (ContextBlock b : blocks) {
            if (out.size() >= 3) {
                break;
            }
            String head = ((b.title() == null ? "" : b.title()) + " "
                    + (b.sectionPath() == null ? "" : b.sectionPath()) + " "
                    + (b.caption() == null ? "" : b.caption()));
            String body = b.content() == null ? "" : b.content();
            String prefix = body.substring(0, Math.min(120, body.length()));
            for (String k : keywords) {
                if (head.contains(k) || prefix.contains(k)) {
                    out.add("E" + b.evidenceNo());
                    break;
                }
            }
        }
        return List.copyOf(out);
    }

    /**
     * 【回答要求】正文：先说是哪一类问题，再逐节列「标题：这节写什么（优先看 E#）」。
     *
     * <p>三条硬约束也写在这里（而不是 SYSTEM 里）：它们只对「有章节」的问题成立，
     * 纯取数题不该被要求分节。
     */
    static String requirementOf(List<Section> sections, Set<AnswerShape> shapes) {
        if (sections.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("【回答要求】\n");
        sb.append("本题属于「").append(kindLabel(shapes)).append("」类问题。")
                .append("建议按下面顺序分节（用 ## 一、二、三 编号），")
                .append("证据里没有对应素材的章节直接删掉，不要为了凑结构编内容：\n");
        List<String> numbered = new ArrayList<>();
        for (int i = 0; i < sections.size(); i++) {
            Section sec = sections.get(i);
            StringBuilder line = new StringBuilder(NUMERALS[i] + "、" + sec.title() + "：" + sec.purpose());
            if ("table".equals(sec.layout())) {
                // layout 是给渲染器的元数据，但模型看得懂「用表格呈现」这句话：
                // 不写这一句时，实测 Q1 的结构节只有 1 张表（该有表的那节写成了段落）
                line.append("（这一节用管道表格呈现）");
            }
            if (!sec.evidenceNos().isEmpty()) {
                line.append("（优先看 ").append(String.join("、", sec.evidenceNos())).append("）");
            }
            numbered.add(line.toString());
        }
        sb.append(String.join("\n", numbered)).append("\n");
        sb.append("每节先用一句话给结论，再给数字与表格；最后一段用「> 注：…」引用块写口径与缺口。");
        return sb.toString();
    }

    private static String kindLabel(Set<AnswerShape> shapes) {
        List<String> labels = new ArrayList<>();
        for (AnswerShape shape : shapes) {
            labels.add(shape.label());
        }
        return labels.isEmpty() ? "取数" : String.join(" + ", labels);
    }
}
