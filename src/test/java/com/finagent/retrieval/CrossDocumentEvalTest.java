package com.finagent.retrieval;

import com.finagent.understanding.QueryUnderstanding;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 跨文档评测：同一批问题分别指向 2024 / 2025 两份年报，看引用是否指对年份。
 *
 * <p><b>为什么这个评测必须有</b>：2025 年报的「主要会计数据」表里同时有 2024 年那一列，
 * 所以「2024 年营业收入」这个数字在<b>两份报告</b>里都能找到。数值对不代表答对，
 * 真正要验的是「引用到了哪一份」—— 这正是评测里唯一有区分度的指标。
 *
 * <p>因此本类的主指标是 <b>cite@1 / cite@8</b>（top-1 / top-8 是否来自期望文档），
 * 次指标是 <b>val@1 / val@8</b>（期望数值是否出现在 top-1 / top-8 的正文里）。
 * 后者在两份报告都能命中，只用来发现「引对了文档但取错了行」。
 *
 * <p>跑法（默认被 pom 的 excludedGroups 排除）：
 *
 * <pre>
 * mvn -B "-Dsurefire.excludedGroups=" "-Dgroups=e2e" "-Dtest=CrossDocumentEvalTest" test
 * </pre>
 */
@SpringBootTest
@ActiveProfiles("test")
@Tag("e2e")
class CrossDocumentEvalTest {

    /** 东方财富 2025 年年度报告（181 页）。 */
    static final long DOC_2025 = 7L;
    /** 东方财富 2024 年年度报告（177 页）。 */
    static final long DOC_2024 = 38L;

    /**
     * 一条用例。
     *
     * @param expectDoc 期望引用的文档 id；null 表示「本题不设文档预期」（陷阱题）
     * @param value     期望数值；null 表示不做数值检查
     * @param note      人工备注，打印在结果里便于回看
     */
    record Qa(int no, String question, Long expectDoc, String value, String note) {
    }

    static final List<Qa> CASES = List.of(
            new Qa(1, "东方财富2024年营业总收入是多少？", DOC_2024, "11,604,343,090.16", "2025 年报对比列也有这个数"),
            new Qa(2, "东方财富2025年营业总收入是多少？", DOC_2025, "16,067,741,039.51", "只在 2025 年报"),
            new Qa(3, "东方财富2024年归属于上市公司股东的净利润是多少？", DOC_2024, "9,610,120,743.64", "2025 年报对比列也有"),
            new Qa(4, "东方财富2025年归属于上市公司股东的净利润是多少？", DOC_2025, "12,084,594,947.22", "只在 2025 年报"),
            new Qa(5, "东方财富2024年基本每股收益是多少？", DOC_2024, "0.6087", "两份都在（2025 年报对比列）"),
            new Qa(6, "东方财富2025年基本每股收益是多少？", DOC_2025, "0.7650", "只在 2025 年报"),
            new Qa(7, "300059 2024年资产总额是多少？", DOC_2024, "306,369,357,347.30", "带代码，应走 RULE 快路径"),
            new Qa(8, "300059 2025年资产总额是多少？", DOC_2025, "392,933,744,976.20", "带代码，应走 RULE 快路径"),
            new Qa(9, "东方财富2024年加权平均净资产收益率是多少？", DOC_2024, "12.64%", "2025 年报对比列也有"),
            new Qa(10, "东方财富2025年加权平均净资产收益率是多少？", DOC_2025, "14.03%", "只在 2025 年报"),
            new Qa(11, "东方财富2024年经营活动产生的现金流量净额是多少？", DOC_2024, "44,587,872,891.43", "2025 年报对比列也有"),
            new Qa(12, "东方财富2025年经营活动产生的现金流量净额是多少？", DOC_2025, "-10,222,245,645.14", "负数，看金额符号是否取对"),
            new Qa(13, "东方财富2023年营业总收入是多少？", null, "11,081,439,282.37", "陷阱：库内没有 2023 年报，只有对比列"),
            new Qa(14, "东方财富2024年和2025年营业收入对比", null, null, "陷阱：多年份，QU 不应做硬过滤")
    );

    @Autowired private HybridRetriever retriever;
    @Autowired private com.finagent.understanding.RouterService router;

    @Test
    void compareCitationsAcrossReports() {
        System.out.println("=== XDOC BEGIN ===");
        List<int[]> qu = new ArrayList<>();
        List<int[]> noQu = new ArrayList<>();

        probeExplicitYearFilter();
        for (Qa qa : CASES) {
            runOne(qa, true, qu);
            runOne(qa, false, noQu);
        }

        summarize("QU   ", qu);
        summarize("NO-QU", noQu);
        System.out.println("=== XDOC END ===");
    }

    /**
     * 直接验 SQL 层的年份过滤：显式给一个库里没有的年份，必须<b>一条都不返</b>。
     *
     * <p>这一步是为了把「QU 抽对了年份」和「过滤条件真的拼进了 SQL」分开验。
     * 只看着检索结果，两者永远混在一起 —— 结果对不出错时，你分不清是过滤生效了，
     * 还是模型本来就能选对文档。
     */
    private void probeExplicitYearFilter() {
        var f2023 = new RetrievalFilters(null, null, 2023, null, null, null);
        var r = retriever.retrieve(RetrievalRequest.of("营业收入", f2023),
                (QueryUnderstanding) null);
        System.out.println("XDOC-PROBE|doc_year=2023(库里没有)|returned=" + r.chunks().size()
                + "|warn=" + r.trace().warnings());
        assertThat(r.chunks()).as("显式年份过滤不该被回退逻辑放过").isEmpty();

        var f2024 = new RetrievalFilters(null, null, 2024, null, null, null);
        var r24 = retriever.retrieve(RetrievalRequest.of("营业收入", f2024),
                (QueryUnderstanding) null);
        System.out.println("XDOC-PROBE|doc_year=2024|returned=" + r24.chunks().size()
                + "|docs=" + r24.chunks().stream().map(RetrievedChunk::documentId).distinct().toList());
        assertThat(r24.chunks()).isNotEmpty();
        assertThat(r24.chunks()).allSatisfy(c -> assertThat(c.documentId()).isEqualTo(DOC_2024));
    }

    /**
     * 跑一条并打印一行结果。
     *
     * <p>结果向量按固定顺序累加，避免两遍统计口径漂移：
     * [0] 用例数 [1] cite@1 [2] cite@8 [3] val@1 [4] val@8 [5] 报错数
     */
    private void runOne(Qa qa, boolean useQu, List<int[]> acc) {
        RetrievalRequest req = RetrievalRequest.of(qa.question());
        HybridRetriever.Result r = useQu
                ? retriever.retrieve(req)
                : retriever.retrieve(req, (QueryUnderstanding) null);

        if (useQu) {
            // 把 QU 的结构化输出单独打出来：年份过滤有没有生效，看这一行
            var u = router.understand(qa.question());
            var w = new java.util.ArrayList<String>();
            var eff = retriever.mergeFilters(RetrievalFilters.none(), u, null, w);
            System.out.println("XDOC-QU|" + qa.no()
                    + "|codes=" + u.companyIds() + "|years=" + (u.yearRange() == null ? "-"
                    : u.yearRange()[0] + ".." + u.yearRange()[1])
                    + "|metrics=" + u.metrics() + "|route=" + u.route()
                    + "|eff.docYear=" + eff.filters().docYear()
                    + "|eff.companyId=" + eff.filters().companyId()
                    + "|warn=" + w);
        }
        List<RetrievedChunk> chunks = r.chunks();
        // trace 与结果必须自洽 —— 这条是硬不变量
        assertThat(r.trace().returned()).isEqualTo(chunks.size());
        if (chunks.isEmpty()) {
            // 空结果不中断评测：陷阱题（库内没有对应年份）本来就可能返回空，
            // 让它记一笔继续跑，比中途抛断言把后面的题全吞掉有用
            System.out.println("XDOC|" + qa.no() + "|" + (useQu ? "QU   " : "NO-QU")
                    + "|EMPTY|warn=" + r.trace().warnings() + "|" + qa.question());
            acc.add(new int[]{qa.expectDoc() != null ? 1 : 0, 0, 0,
                    qa.value() != null ? 1 : 0, 0, 0});
            return;
        }

        Long top1Doc = chunks.get(0).documentId();
        boolean cite1 = qa.expectDoc() != null && qa.expectDoc().equals(top1Doc);
        boolean cite8 = qa.expectDoc() != null && chunks.stream()
                .anyMatch(c -> qa.expectDoc().equals(c.documentId()));
        boolean val1 = qa.value() != null && hasValue(chunks.get(0), qa.value());
        boolean val8 = qa.value() != null && chunks.stream().anyMatch(c -> hasValue(c, qa.value()));

        // 计分口径：cite 只在设了文档预期的题上计（陷阱题不进分母），
        // val 只在设了期望数值的题上计 —— 两者的分母不同，不能混算
        boolean scored = qa.expectDoc() != null;
        acc.add(new int[]{scored ? 1 : 0, cite1 ? 1 : 0, cite8 ? 1 : 0,
                qa.value() != null ? 1 : 0, val1 ? 1 : 0, val8 ? 1 : 0});

        String mode = useQu ? "QU   " : "NO-QU";
        // 结果里出现了几份文档：年份过滤若真的生效，只可能是一份
        long distinctDocs = chunks.stream().map(RetrievedChunk::documentId).distinct().count();
        // 混进来的「另一份报告」有几条 —— 这是无 QU 时真正的风险，比 cite@1 更能说明问题
        long wrong = qa.expectDoc() == null ? -1
                : chunks.stream().filter(c -> !qa.expectDoc().equals(c.documentId())).count();
        System.out.println("XDOC|" + qa.no() + "|" + mode
                + "|cite1=" + (scored ? (cite1 ? "Y" : "N") : "-")
                + "|cite8=" + (scored ? (cite8 ? "Y" : "N") : "-")
                + "|val1=" + (qa.value() == null ? "-" : (val1 ? "Y" : "N"))
                + "|val8=" + (qa.value() == null ? "-" : (val8 ? "Y" : "N"))
                + "|top1Doc=" + top1Doc
                + "|" + r.trace().queryType() + "/" + r.trace().quRoute()
                + "|warn=" + r.trace().warnings()
                + "|docs=" + distinctDocs
                + "|wrong=" + (wrong < 0 ? "-" : wrong)
                + "|top1=" + chunks.get(0).chunkId() + ":" + head(chunks.get(0).sectionPath())
                + "|" + qa.question());
    }

    private static void summarize(String mode, List<int[]> acc) {
        int citeN = 0, c1 = 0, c8 = 0, valN = 0, v1 = 0, v8 = 0;
        for (int[] v : acc) {
            citeN += v[0];
            c1 += v[1];
            c8 += v[2];
            valN += v[3];
            v1 += v[4];
            v8 += v[5];
        }
        System.out.println("XDOC-SUM|" + mode + "|citeN=" + citeN + "|cite@1=" + c1 + "|cite@8=" + c8
                + "|valN=" + valN + "|val@1=" + v1 + "|val@8=" + v8 + "|cases=" + acc.size());
    }

    /**
     * 数值是否出现在 chunk 的正文里（章节路径与标题也算，因为数值只会出现在正文）。
     *
     * <p>先做符号归一：PDF 抽取出来的负号可能是 U+2212 或全角，直接 contains 会漏判。
     */
    static boolean hasValue(RetrievedChunk c, String value) {
        String target = normalize(value);
        String text = normalize((c.content() == null ? "" : c.content()));
        return text.contains(target);
    }

    static String normalize(String s) {
        return s.replace('\u2212', '-').replace('\u2010', '-').replace('\u2011', '-')
                .replace('\u2013', '-').replace('\uFF0C', ',').replace(" ", "");
    }

    static String head(String s) {
        if (s == null) {
            return "";
        }
        return s.length() > 40 ? s.substring(0, 40) : s;
    }
}
