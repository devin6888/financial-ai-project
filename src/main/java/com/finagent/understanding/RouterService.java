package com.finagent.understanding;

import com.finagent.document.DocumentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.time.Year;
import java.util.ArrayList;
import java.util.List;

/**
 * Query Understanding 编排 —— §9.14 的四层防线。
 *
 * <ol>
 *   <li><b>规则快路径</b>：代码 + 年份 + 指标俱全 → 直接 ExactFact，不调 LLM</li>
 *   <li><b>受约束的结构化输出</b>：queryType 被限定为 4 个枚举</li>
 *   <li><b>字段完整性交叉验证</b>：判为 ExactFact 但三要素不全 → 降级</li>
 *   <li><b>兜底降级</b>：LLM 失败/超时 → 保守策略 + degraded</li>
 * </ol>
 *
 * <p><b>为什么降级选 SemanticExplanation 而不是 ExactFact</b>（§9.14 原文的设计结论）：
 * 四类误判的代价不对称 ——
 * 把 Semantic 误判成 Exact 会「只召回一个孤立段落、答不出因果关系、任务直接失败」（高）；
 * 反过来只是多召回、多花 Token，答案一般仍可接受（低）。
 * 降级必须往<b>误判代价最小</b>的方向走。
 *
 * <p><b>两个入口</b>：{@link #understand(String)} 单轮（阶段 4 起就没有变过语义）；
 * {@link #understand(String, TurnContext)} 多轮，用会话上下文把「那净利润呢」补全成可独立检索的问题。
 * 上下文为空时后者直接委托前者 —— 「无会话」与「阶段 8 的行为」必须是同一条代码路径。
 */
@Service
public class RouterService {

    private static final Logger log = LoggerFactory.getLogger(RouterService.class);

    /** 年份下限：财经数据早于 1990 无意义。 */
    private static final int MIN_YEAR = 1990;

    private final RuleBasedRouter rule;
    private final ObjectProvider<LlmRouter> llm;
    private final DocumentRepository documents;

    public RouterService(RuleBasedRouter rule, ObjectProvider<LlmRouter> llm,
                         DocumentRepository documents) {
        this.rule = rule;
        this.llm = llm;
        this.documents = documents;
    }

    public QueryUnderstanding understand(String query) {
        // 第 1 层：规则快路径
        QueryUnderstanding byRule = rule.tryRoute(query);
        if (byRule != null) {
            return byRule;
        }

        // 第 2 层：LLM 结构化输出（失败不在这里处理，统一走第 4 层）
        QueryUnderstanding raw;
        try {
            LlmRouter router = llm.getIfAvailable();
            if (router == null) {
                return degraded(query, "llm_disabled");
            }
            raw = router.route(query);
        } catch (RuntimeException e) {
            log.warn("Query Understanding 失败，降级：{}", e.toString());
            return degraded(query, "llm_failed");
        }

        // 第 3 层：逐字段校验 —— 丢弃非法字段，而不是丢弃整个结果
        QueryUnderstanding cleaned = withKeywordDocType(validate(raw, query), query);

        // 第 3 层续：字段完整性交叉验证（只对 ExactFact 生效）
        if (cleaned.queryType() == QueryType.EXACT_FACT && !isExactFactEvidenceSufficient(cleaned)) {
            log.info("判为 ExactFact 但三要素不全，降级为 SemanticExplanation：{}", query);
            return weakEvidence(cleaned);
        }
        return cleaned;
    }

    /**
     * 多轮：先看规则命中（问题本身已经带了实体，不需要消解），否则让 LLM 带着对话背景解析。
     *
     * <p>为什么规则命中就<b>完全不做消解</b>：「600519 2025年营业收入」这种问题已经自足，
     * 再做一次消解只增加一次模型调用与误补的风险 —— 规则路径的价值就是快且确定。
     */
    public QueryUnderstanding understand(String query, TurnContext ctx) {
        if (ctx == null || ctx.isEmpty()) {
            return understand(query);
        }

        QueryUnderstanding byRule = rule.tryRoute(query);
        if (byRule != null) {
            return byRule;
        }

        QueryUnderstanding raw;
        try {
            LlmRouter router = llm.getIfAvailable();
            if (router == null) {
                // 降级也要把上下文里确定的公司/年份补上：这比「什么都不补」好，
                // 又比「让模型自由发挥」安全（设计对降级路径的要求）。
                return fillFromContext(degraded(query, "llm_disabled"), ctx);
            }
            raw = router.route(query, ctx.render());
        } catch (RuntimeException e) {
            log.warn("带上下文的 Query Understanding 失败，降级：{}", e.toString());
            return fillFromContext(degraded(query, "llm_failed"), ctx);
        }

        QueryUnderstanding cleaned = withKeywordDocType(validate(raw, query), query);
        // 模型漏填公司/年份时用上下文补齐（只补槽位，不补指标 —— 指标是语义判断，补错会带偏检索）
        cleaned = fillFromContext(cleaned, ctx);

        if (cleaned.queryType() == QueryType.EXACT_FACT && !isExactFactEvidenceSufficient(cleaned)) {
            log.info("判为 ExactFact 但三要素不全，降级为 SemanticExplanation：{}", query);
            return weakEvidence(cleaned);
        }
        return cleaned;
    }

    /**
     * §9.14：单字段校验失败时丢弃该字段，而不是丢弃整个结果。
     *
     * <p>注意 queryType 是唯一"不能丢"的 —— 丢了就不知道该怎么检索，所以回落到保守值。
     */
    QueryUnderstanding validate(QueryUnderstanding raw, String original) {
        QueryType type = raw.queryType() == null ? QueryType.SEMANTIC_EXPLANATION : raw.queryType();

        // 代码校验两道（ST9 §16.3）：① 6 位数字 ② <b>必须字面出现在问题原文里</b>。
        // 第 ② 道是根治「模型编造公司代码」的那一刀：e2e 抓到它把「东方财富」翻译成 600519。
        // 注意：这不等于「丢弃库里没有的公司」—— 原代码 000858（五粮液）本来就写字用户话里，
        // 会通过校验，再由作用域层给出「范围内没有五粮液」的明确拒答（阶段 6 的教训）。
        List<String> companyCodes = raw.companyCodes() == null ? List.of()
                : raw.companyCodes().stream()
                        .filter(c -> c != null && c.length() == 6 && c.chars().allMatch(Character::isDigit))
                        .filter(c -> original != null && original.contains(c))
                        .distinct()
                        .toList();

        // 公司名只做一道校验：必须是问题原文的**逐字子串**。
        // 模型可以抽错（抽到「报告」这种词），但抽不出原文里没有的字符串 —— 这就把「编造」变成不可能。
        List<String> companyMentions = raw.companyMentions() == null ? List.of()
                : raw.companyMentions().stream()
                        .filter(m -> m != null && !m.isBlank())
                        .map(String::trim)
                        .filter(m -> original != null && original.contains(m))
                        .distinct()
                        .toList();

        int currentYear = Year.now().getValue();
        int[] years = raw.yearRange();
        if (years != null && (years[0] < MIN_YEAR || years[1] > currentYear)) {
            years = null;    // 越界 → 丢弃该字段，不影响其余
        }

        // docType 只接受四个合法值；LLM 编了别的值就丢弃（§9.14 单字段丢弃）。
        // 丢弃后不再用规则补：规则补只应发生在 LLM 没给的时候，不该覆盖它的错误判断 ——
        // 一个不存在的类型说明模型没理解问题，此时用关键词猜反而可能猜错方向。
        String docType = DocTypes.isValid(raw.docType()) ? raw.docType() : null;

        return new QueryUnderstanding(type,
                raw.normalizedQuery() == null || raw.normalizedQuery().isBlank()
                        ? original : raw.normalizedQuery(),
                companyCodes, years,
                raw.metrics() == null ? List.of() : raw.metrics(),
                raw.needMultiQuery(), raw.needDecompose(), docType,
                raw.degraded(), raw.route(), raw.inferredFromContext(),
                raw.needLongTermMemory(), companyMentions);
    }

    /**
     * LLM 没给报告类型时用措辞补：「半年报」三个字就在问题里，这是确定性信息，
     * 不该因为模型漏填而丢掉一条真过滤条件。
     */
    private static QueryUnderstanding withKeywordDocType(QueryUnderstanding u, String query) {
        if (u.docType() != null) {
            return u;
        }
        String byKeyword = DocTypes.detect(query);
        if (byKeyword == null) {
            return u;
        }
        return new QueryUnderstanding(u.queryType(), u.normalizedQuery(), u.companyCodes(),
                u.yearRange(), u.metrics(), u.needMultiQuery(), u.needDecompose(), byKeyword,
                u.degraded(), u.route(), u.inferredFromContext(), u.needLongTermMemory(),
                u.companyMentions());
    }

    /**
     * 用会话上下文补齐「槽位」（公司 / 年份），并把 normalizedQuery 改写成可独立检索的形态。
     *
     * <p>补全只发生在字段<b>缺失</b>时：模型已经给出的值一律不动 ——
     * 用户这一轮明确说的，比上文推断的更可信。
     */
    static QueryUnderstanding fillFromContext(QueryUnderstanding base, TurnContext ctx) {
        if (ctx == null || ctx.isEmpty()) {
            return base;
        }
        boolean inferred = base.inferredFromContext();

        List<String> codes = base.companyCodes();
        if ((codes == null || codes.isEmpty()) && ctx.companyCode() != null) {
            codes = List.of(ctx.companyCode());
            inferred = true;
        }

        int[] years = base.yearRange();
        if (years == null && ctx.yearFrom() != null) {
            int to = ctx.yearTo() == null ? ctx.yearFrom() : ctx.yearTo();
            years = new int[]{Math.min(ctx.yearFrom(), to), Math.max(ctx.yearFrom(), to)};
            inferred = true;
        }

        String normalized = base.normalizedQuery();
        if (inferred) {
            normalized = selfContained(ctx, normalized);
        }
        return new QueryUnderstanding(base.queryType(), normalized, codes, years, base.metrics(),
                base.needMultiQuery(), base.needDecompose(), base.docType(), base.degraded(),
                base.route(), inferred, base.needLongTermMemory(), base.companyMentions());
    }

    /**
     * 把「那净利润呢」变成「东方财富（300059） 2025年 那净利润呢」。
     *
     * <p>只补公司与时年两个前缀，不动问题本身 —— 检索（dense + sparse）拿到的是
     * 「实体齐全的原话」，而不是模型改写的句子，语义漂移最小。
     */
    static String selfContained(TurnContext ctx, String query) {
        String q = query == null ? "" : query;
        List<String> parts = new ArrayList<>();
        String company = ctx.companyLabel();
        // 名字或代码任一出现就当作「已经说了这家公司」：用户写「东方财富的净利润」
        // 时不该再被加上「东方财富（300059）」前缀（重复实体只会稀释稀疏检索的命中）。
        boolean companyPresent = (ctx.companyName() != null && q.contains(ctx.companyName()))
                || (ctx.companyCode() != null && q.contains(ctx.companyCode()));
        if (company != null && !companyPresent) {
            parts.add(company);
        }
        String year = ctx.yearLabel();
        if (year != null && !q.contains(year)) {
            parts.add(year);
        }
        if (parts.isEmpty()) {
            return q;
        }
        return String.join(" ", parts) + " " + q;
    }

    /** 判为 ExactFact 但三要素不全 → 降级（保留已经解析出的字段与 inferred 标记）。 */
    private static QueryUnderstanding weakEvidence(QueryUnderstanding u) {
        return new QueryUnderstanding(QueryType.SEMANTIC_EXPLANATION, u.normalizedQuery(),
                u.companyCodes(), u.yearRange(), u.metrics(), u.needMultiQuery(), u.needDecompose(),
                u.docType(), true, "DEGRADED:weak_evidence", u.inferredFromContext(),
                u.needLongTermMemory(), u.companyMentions());
    }

    static boolean isExactFactEvidenceSufficient(QueryUnderstanding u) {
        return u.mentionsCompany()
                && u.yearRange() != null
                && u.metrics() != null && !u.metrics().isEmpty();
    }

    /** 兜底：保守策略 + degraded 标记。 */
    static QueryUnderstanding degraded(String query, String reason) {
        return new QueryUnderstanding(QueryType.SEMANTIC_EXPLANATION, query,
                List.of(), null, List.of(), false, false, null, true, "DEGRADED:" + reason);
    }
}
