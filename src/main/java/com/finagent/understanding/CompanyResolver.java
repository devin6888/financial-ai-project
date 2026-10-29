package com.finagent.understanding;

import com.finagent.scope.DocMeta;
import com.finagent.scope.ResolvedScope;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 公司名 / 代码 → 作用域内公司（ST9 §16）。
 *
 * <p>为什么需要它：模型只被允许<b>抄原文</b>（抽取出「东方财富」这几个字），把公司名对到
 * {@code company} 行必须由权威表完成 —— 让模型把名字翻译成代码，等于让它凭训练记忆背答案，
 * 实测真的会背错（e2e 抓到它把东方财富翻译成 600519 贵州茅台）。
 *
 * <p>纯函数、无 Spring 依赖：单测不需要上下文，也便于在 trace 里复现判定过程。
 */
public final class CompanyResolver {

    private CompanyResolver() {
    }

    /** 匹配到的一家公司。{@code code} 是证券代码，{@code companyId} 是数据库主键，两者不是一回事。 */
    public record Match(String code, String name, long companyId) {
    }

    /**
     * 解析结果。
     *
     * @param matches    命中的公司（0~n 家，多家用于对比题）
     * @param unresolved 提到但作用域里没有的公司（「像公司名」的才算，代词和泛称不算）
     * @param warnings   不影响作答的提示（如「提了个泛称，已忽略」）
     */
    public record Resolution(List<Match> matches, List<String> unresolved, List<String> warnings) {

        public static Resolution empty() {
            return new Resolution(List.of(), List.of(), List.of());
        }

        public boolean hasMatch() {
            return !matches.isEmpty();
        }

        /** 是否应该拒答：提到了范围外的公司，且一家都没匹配上。 */
        public boolean blocked() {
            return matches.isEmpty() && !unresolved.isEmpty();
        }

        public List<String> codes() {
            return matches.stream().map(Match::code).toList();
        }

        public List<String> names() {
            return matches.stream().map(Match::name).toList();
        }
    }

    /** 名称归一化时剥掉的公司后缀（长的在前，逐次剥）。 */
    private static final List<String> SUFFIXES = List.of(
            "股份有限公司", "有限责任公司", "有限公司", "股份公司", "集团", "控股", "信息", "科技", "股份");

    /** 含这些词就认为「像公司名」（用于判断未匹配的 mention 该不该触发拒答）。 */
    private static final List<String> COMPANY_WORDS = List.of(
            "公司", "股份", "集团", "银行", "证券", "保险", "控股", "实业", "科技", "药业",
            "能源", "汽车", "地产", "电力", "传媒", "基金", "信托", "航空", "钢铁", "煤炭",
            "化工", "医药", "食品", "家电");

    /** 代词/泛称：这些词即使没匹配上也不拒答（它们不指向某家公司）。 */
    private static final Set<String> PRONOUNS = Set.of(
            "该公司", "上述公司", "本公司", "母公司", "子公司", "公司", "企业", "行业", "市场",
            "这些公司", "上市公司", "其他公司", "同行业");

    /**
     * @param codes    模型给的证券代码（已在 {@link RouterService#validateLlmUnderstand} 做过「必须在原文出现」校验）
     * @param mentions 模型抽的公司名（已在 validate 做过「必须是原文子串」校验）
     * @param scope    当前作用域；只在这里面找 —— 范围外公司就是要拒答的那种
     */
    public static Resolution resolve(List<String> codes, List<String> mentions, ResolvedScope scope) {
        List<DocMeta> docs = scope == null || scope.documents() == null ? List.of() : scope.documents();
        if (docs.isEmpty()) {
            return Resolution.empty();
        }
        List<Match> matches = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        List<String> unresolved = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        // 1) 代码优先：用户字面写了代码（或上下文补全）时，直接精确比对，不需要模糊匹配
        for (String code : safe(codes)) {
            DocMeta hit = docs.stream()
                    .filter(d -> code.equals(d.companyCode()))
                    .findFirst().orElse(null);
            if (hit == null) {
                if (looksLikeCompany(code)) {
                    unresolved.add(code);
                }
            } else {
                add(matches, seen, hit);
            }
        }

        // 2) 公司名：归一化后双向 contains（「东方财富信息」也能命中「东方财富」）
        for (String mention : safe(mentions)) {
            List<DocMeta> hits = docs.stream()
                    .filter(d -> nameMatches(mention, d.companyName()))
                    .toList();
            if (hits.isEmpty()) {
                if (looksLikeCompany(mention)) {
                    unresolved.add(mention);
                } else if (!PRONOUNS.contains(mention.trim())) {
                    // 泛称（如「行业」）：不拒答，但要留痕，便于排查「为什么没加公司约束」
                    warnings.add("company_mention_ignored");
                }
            } else {
                hits.forEach(d -> add(matches, seen, d));
            }
        }
        return new Resolution(List.copyOf(matches), List.copyOf(unresolved), List.copyOf(warnings));
    }

    private static void add(List<Match> matches, Set<String> seen, DocMeta doc) {
        String code = doc.companyCode();
        if (code == null || code.isBlank() || !seen.add(code)) {
            return;
        }
        matches.add(new Match(code, doc.companyName(), doc.companyId() == null ? 0L : doc.companyId()));
    }

    /** 名称匹配：先逐字包含，再归一化后双向包含。 */
    static boolean nameMatches(String mention, String companyName) {
        if (mention == null || companyName == null) {
            return false;
        }
        String m = mention.trim();
        String n = companyName.trim();
        if (m.isEmpty() || n.isEmpty()) {
            return false;
        }
        if (m.contains(n) || n.contains(m)) {
            return true;
        }
        String nm = normalize(m);
        String nn = normalize(n);
        return !nm.isEmpty() && !nn.isEmpty() && (nm.contains(nn) || nn.contains(nm));
    }

    /** 去掉空白、括号内容与公司后缀，用于「东方财富信息股份有限公司」≈「东方财富」。 */
    static String normalize(String raw) {
        String s = raw == null ? "" : raw.replaceAll("\\s+", "")
                .replaceAll("[（(].*?[）)]", "");
        boolean changed = true;
        while (changed) {
            changed = false;
            for (String suffix : SUFFIXES) {
                if (s.length() > suffix.length() && s.endsWith(suffix)) {
                    s = s.substring(0, s.length() - suffix.length());
                    changed = true;
                }
            }
        }
        return s;
    }

    /**
     * 「像公司名」判定：长度 ≥ 3，或含公司类后缀词。
     *
     * <p>为什么需要这道判定：mention 只保证是原文子串，模型完全可能抽到「报告」「今年」这种词。
     * 直接拿未匹配的 mention 拒答，会把正常问题误伤成「范围外公司」。
     */
    static boolean looksLikeCompany(String raw) {
        String s = raw == null ? "" : raw.trim();
        if (s.isEmpty() || PRONOUNS.contains(s)) {
            return false;
        }
        if (s.length() >= 3) {
            return true;
        }
        return COMPANY_WORDS.stream().anyMatch(s::contains);
    }

    private static List<String> safe(List<String> values) {
        return values == null ? List.of() : values;
    }
}
