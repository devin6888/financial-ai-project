package com.finagent.understanding;

import com.finagent.document.DocumentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.time.Year;
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
        QueryUnderstanding cleaned = validate(raw, query);
        if (cleaned.docType() == null) {
            // LLM 没给类型时用措辞补：这是确定性信息（「半年报」三个字就在问题里），
            // 不该因为模型漏填而丢掉一条真过滤条件。
            String byKeyword = DocTypes.detect(query);
            if (byKeyword != null) {
                cleaned = new QueryUnderstanding(cleaned.queryType(), cleaned.normalizedQuery(),
                        cleaned.companyIds(), cleaned.yearRange(), cleaned.metrics(),
                        cleaned.needMultiQuery(), cleaned.needDecompose(), byKeyword,
                        cleaned.degraded(), cleaned.route());
            }
        }

        // 第 3 层续：字段完整性交叉验证（只对 ExactFact 生效）
        if (cleaned.queryType() == QueryType.EXACT_FACT && !isExactFactEvidenceSufficient(cleaned)) {
            log.info("判为 ExactFact 但三要素不全，降级为 SemanticExplanation：{}", query);
            return new QueryUnderstanding(QueryType.SEMANTIC_EXPLANATION, cleaned.normalizedQuery(),
                    cleaned.companyIds(), cleaned.yearRange(), cleaned.metrics(),
                    cleaned.needMultiQuery(), cleaned.needDecompose(), true, "DEGRADED:weak_evidence");
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

        List<String> companyIds = raw.companyIds() == null ? List.of()
                : raw.companyIds().stream().filter(documents::companyExists).toList();

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
                companyIds, years,
                raw.metrics() == null ? List.of() : raw.metrics(),
                raw.needMultiQuery(), raw.needDecompose(), docType,
                raw.degraded(), raw.route());
    }

    static boolean isExactFactEvidenceSufficient(QueryUnderstanding u) {
        return u.companyIds() != null && !u.companyIds().isEmpty()
                && u.yearRange() != null
                && u.metrics() != null && !u.metrics().isEmpty();
    }

    /** 兜底：保守策略 + degraded 标记。 */
    static QueryUnderstanding degraded(String query, String reason) {
        return new QueryUnderstanding(QueryType.SEMANTIC_EXPLANATION, query,
                List.of(), null, List.of(), false, false, true, "DEGRADED:" + reason);
    }
}
