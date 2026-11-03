package com.finagent.agent;

import com.finagent.scope.ChatScope;
import com.finagent.scope.DocMeta;
import com.finagent.scope.ResolvedScope;
import com.finagent.scope.ScopeResolver;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * 「这个问题需要先问清人吗」的判定（ST14 P3）。
 *
 * <p>为什么不是「每个问题都弹一次审批」：阶段 11 把审批点放在 approve 节点，
 * 于是普通问题也要点一下「批准」才能拿到答案 —— 用户原话（m10194）：
 * 「走图不一定是全部要人批的，一般是不明确的问题才需要人去审核」。
 *
 * <p>判定只用**可确定性计算**的信号，不调模型：
 * 作用域里有几家公司、几个年度、问题里有没有点到具体主体/年份。
 * 拿不准的（例如「经营情况怎么样」）不算歧义 —— 宁可少弹，也不要让确认卡变成收费站。
 *
 * <p>为什么不用 QU 的 needDecompose/量子句判断：那要等 understand 节点跑完（已经花掉 3~4 秒），
 * 而 {@code interruptsBefore} 是<em>编译期</em>配置（C17 §17.2），必须开跑前就决定走哪张图。
 */
@Component
public class AmbiguityDetector {

    private static final Pattern YEAR = Pattern.compile("(?:19|20)\\d{2}");

    private final ScopeResolver scopes;

    public AmbiguityDetector(ScopeResolver scopes) {
        this.scopes = scopes;
    }

    /**
     * @param ambiguous 是否含糊不清的、模棱两可的
     * @param reasons 机器可读的原因码（company_not_specified / year_not_specified），空表示不歧义
     */
    public record Decision(boolean ambiguous, List<String> reasons) {
        public static Decision confident() {
            return new Decision(false, List.of());
        }
    }

    /**
     * 检测、识别； 用于识别是否需要用于人工审核
     * @throws com.finagent.scope.ScopeEmptyException 作用域里一份可读文档都没有（不是「歧义」，是拒答）
     */
    public Decision detect(ChatScope scope, String question) {
        ResolvedScope resolved = scopes.resolve(scope);
        List<String> reasons = reasons(resolved.documents(), question);
        return reasons.isEmpty() ? Decision.confident() : new Decision(true, reasons);
    }

    /**
     * 纯函数版：understand 节点已经解析好了作用域，不必再查一次。
     *
     * @return 原因码，空表示不歧义
     */
    public static List<String> reasons(List<DocMeta> documents, String question) {
        String text = question == null ? "" : question;
        Set<String> companies = new LinkedHashSet<>();
        Set<Integer> years = new LinkedHashSet<>();
        for (DocMeta doc : documents) {
            if (doc.companyCode() != null && !doc.companyCode().isBlank()) {
                companies.add(doc.companyCode());
            }
            if (doc.docYear() != null) {
                years.add(doc.docYear());
            }
        }

        List<String> reasons = new ArrayList<>();
        // 作用域里有多家公司，而问题既没点代码也没点公司名 → 模型只能自己挑一家，必须问
        if (companies.size() > 1 && !mentionsCompany(documents, text)) {
            reasons.add("company_not_specified");
        }
        // 同一家公司有多份年报，而问题没点年份 → 「净利润是多少」到底哪一年，必须问
        if (years.size() > 1 && !YEAR.matcher(text).find()) {
            reasons.add("year_not_specified");
        }
        return List.copyOf(reasons);
    }

    /**
     * 审批卡上可点的候选（阶段 22）。
     *
     * <p>只给「真的存在多个取值」的维度：只有一份年报时不会出现年份候选，否则卡片上会多出无意义的选择。
     * 年份候选按年份升序；公司候选按文档里出现的顺序。
     *
     * @return 每项 {@code {kind, value, label}}；{@code kind} ∈ YEAR / ALL_YEARS / COMPANY
     */
    public static List<Map<String, Object>> candidates(List<DocMeta> documents) {
        List<Map<String, Object>> out = new ArrayList<>();
        Map<Integer, Integer> docsByYear = new TreeMap<>();
        Map<String, String> companies = new LinkedHashMap<>();
        Map<String, Integer> docsByCompany = new HashMap<>();
        for (DocMeta doc : documents) {
            if (doc.docYear() != null) {
                docsByYear.merge(doc.docYear(), 1, Integer::sum);
            }
            String code = doc.companyCode();
            if (code != null && !code.isBlank()) {
                String name = doc.companyName() == null ? "" : doc.companyName();
                companies.putIfAbsent(code, name);
                docsByCompany.merge(code, 1, Integer::sum);
            }
        }
        if (docsByYear.size() > 1) {
            for (Map.Entry<Integer, Integer> e : docsByYear.entrySet()) {
                out.add(candidate("YEAR", e.getKey(),
                        e.getKey() + " 年报（" + e.getValue() + " 份）"));
            }
            String span = docsByYear.keySet().stream().map(String::valueOf)
                    .reduce((a, b) -> a + " + " + b).orElse("");
            out.add(candidate("ALL_YEARS", "", "两份都看（" + span + "）"));
        }
        if (companies.size() > 1) {
            for (Map.Entry<String, String> e : companies.entrySet()) {
                String label = e.getValue().isBlank() ? e.getKey() : e.getValue() + "（" + e.getKey() + "）";
                out.add(candidate("COMPANY", e.getKey(),
                        label + " · " + docsByCompany.getOrDefault(e.getKey(), 0) + " 份"));
            }
        }
        return List.copyOf(out);
    }

    private static Map<String, Object> candidate(String kind, Object value, String label) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("kind", kind);
        item.put("value", value);
        item.put("label", label);
        return item;
    }

    private static boolean mentionsCompany(List<DocMeta> documents, String text) {
        for (DocMeta doc : documents) {
            if (doc.companyCode() != null && !doc.companyCode().isBlank() && text.contains(doc.companyCode())) {
                return true;
            }
            String name = doc.companyName();
            if (name != null && !name.isBlank() && text.contains(name)) {
                return true;
            }
        }
        return false;
    }
}
