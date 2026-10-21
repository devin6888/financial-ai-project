package com.finagent.understanding;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * LLM 结构化输出（§9.14 第二层防线）。
 *
 * <p>把「开放生成」变成「有限选择」：queryType 被约束为 4 个枚举值，大幅降低跑偏。
 *
 * <p><b>失败时抛异常，不在这里降级</b> —— 降级由 {@link RouterService} 统一处理，
 * 因为只有编排层知道 trace 该怎么标、以及该往哪个方向降。
 *
 * <p>已用 javap 确认：Spring AI 2.0 的 {@code CallResponseSpec} 有
 * {@code entity(Class<T>)}，可直接把 JSON 反序列化成 DTO。
 */
@Component
@ConditionalOnProperty(name = "finagent.router.llm-enabled", havingValue = "true",
        matchIfMissing = true)
public class LlmRouter {

    private static final String SYSTEM = """
            你是金融研报检索的查询理解模块。把用户问题解析成结构化对象，只输出 JSON。

            queryType 只能是以下四个之一：
            - EXACT_FACT：答案是一个数值或一条定义（如「2025年营业收入是多少」）
            - SEMANTIC_EXPLANATION：答案需要跨段落理解（如「营收增长的原因是什么」）
            - COMPARISON：证据分布在多个年份或多个指标（如「比较2023到2025年收入和利润」）
            - COMPLEX_ANALYSIS：需要多步推理才能确定查什么

            字段说明：
            - companyIds：证券代码（6 位数字），不确定就留空数组
            - yearFrom / yearTo：年份整数；只问一年则两者相同
            - metrics：财务指标名（如营业收入、净利润）
            - docType：报告类型，只能取 ANNUAL（年报）、SEMI_ANNUAL（半年报）、
              Q1（一季报）、Q3（三季报）；问题里没提就留空字符串
            - needMultiQuery：是否需要把一个问题拆成多条互补查询

            判为 EXACT_FACT 时，companyIds、yearFrom/yearTo、metrics 必须同时非空；
            任一项无法确定，就判为 SEMANTIC_EXPLANATION（误判代价更小的方向）。
            """;

    private final ChatClient chat;

    public LlmRouter(ChatClient.Builder builder) {
        this.chat = builder.build();
    }

    public QueryUnderstanding route(String query) {
        RouterDto dto = chat.prompt().system(SYSTEM).user(query).call().entity(RouterDto.class);
        if (dto == null) {
            throw new IllegalStateException("LLM 返回空结果");
        }
        return new QueryUnderstanding(
                QueryType.parse(dto.queryType()),
                dto.normalizedQuery() == null || dto.normalizedQuery().isBlank()
                        ? query : dto.normalizedQuery(),
                dto.companyIds(),
                toRange(dto.yearFrom(), dto.yearTo()),
                dto.metrics(),
                dto.needMultiQuery(),
                dto.needDecompose(),
                dto.docType(),
                false, "LLM");
    }

    /** 把 from/to 规整成有序区间；都为 null 则返回 null（交给调用方丢弃该字段）。 */
    static int[] toRange(Integer from, Integer to) {
        if (from == null && to == null) {
            return null;
        }
        int f = from == null ? to : from;
        int t = to == null ? f : to;
        return new int[]{Math.min(f, t), Math.max(f, t)};
    }

    /** LLM 返回的原始 DTO —— 字段全可空，便于逐字段校验。 */
    public record RouterDto(
            String queryType,
            String normalizedQuery,
            List<String> companyIds,
            Integer yearFrom,
            Integer yearTo,
            List<String> metrics,
            boolean needMultiQuery,
            boolean needDecompose,
            String docType) {
    }
}
