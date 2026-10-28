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
 *
 * <p>两个入口：{@link #route(String)} 单轮；{@link #route(String, String)} 带对话背景（指代消解）。
 * 后者复用<b>同一次</b>模型调用同时产出结构化字段与 normalizedQuery（设计 §11.11），
 * 不为一个字段多开一次往返。
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
            - needLongTermMemory：问题是否依赖用户<b>长期偏好/习惯</b>（「按我以前的格式」「以后都先给结论」
              「我之前说过什么」）。纯财务事实问答一律 false —— 这一项决定要不要多调一次检索，
              误判为 true 会白花一次向量化调用

            判为 EXACT_FACT 时，companyIds、yearFrom/yearTo、metrics 必须同时非空；
            任一项无法确定，就判为 SEMANTIC_EXPLANATION（误判代价更小的方向）。
            """;

    /** 多轮追加段：只加「怎么用背景」，原有字段约束一字不改。 */
    private static final String CONTEXT_RULES = """

            用户可能是在追问上一轮。你还会看到【对话背景】（会话已确认的公司/年份/指标 + 最近几轮）。
            遇到追问（如「那净利润呢」「它现在的现金流怎么样」「比去年增长多少」）时，
            请把上文的信息补进结构化字段，并额外输出两个字段：
            - normalizedQuery：脱离上文也能独立检索的问题，必须含公司名或代码 + 年份 + 指标；
              用户这一轮已经说全了，就原样返回
            - resolved：本轮是否用了上文补全（true=用了，false=没有）

            上下文不足以补全时，原样返回并把 resolved 置为 false。
            绝不编造公司或年份 —— 编造的年份会变成真实过滤条件，把正确答案过滤掉。
            """;

    private final ChatClient chat;

    public LlmRouter(ChatClient.Builder builder) {
        this.chat = builder.build();
    }

    /** 单轮：只喂问题。 */
    public QueryUnderstanding route(String query) {
        RouterDto dto = chat.prompt().system(SYSTEM).user(query).call().entity(RouterDto.class);
        return toUnderstanding(dto, query, false);
    }

    /** 多轮：背景块放在用户消息里（system 段保持稳定，便于复用提示词缓存）。 */
    public QueryUnderstanding route(String query, String contextBlock) {
        if (contextBlock == null || contextBlock.isBlank()) {
            return route(query);
        }
        String user = "【对话背景】\n" + contextBlock + "\n【本轮问题】\n" + query;
        RouterDto dto = chat.prompt().system(SYSTEM + CONTEXT_RULES).user(user)
                .call().entity(RouterDto.class);
        return toUnderstanding(dto, query, true);
    }

    private static QueryUnderstanding toUnderstanding(RouterDto dto, String query, boolean fromContext) {
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
                Boolean.TRUE.equals(dto.needMultiQuery()),
                Boolean.TRUE.equals(dto.needDecompose()),
                dto.docType(),
                false, "LLM",
                fromContext && Boolean.TRUE.equals(dto.resolved()),
                Boolean.TRUE.equals(dto.needLongTermMemory()));
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

    /**
     * LLM 返回的原始 DTO —— 字段全可空，便于逐字段校验。
     *
     * <p>布尔字段用包装类型而不是 {@code boolean}：模型偶尔漏字段，
     * 缺省的 {@code Boolean} 是 null（当 false 处理），而缺省的 {@code boolean} 会让
     * Jackson 直接解析失败 —— 一个可容忍的漏字段不该让整个 QU 降级。
     */
    public record RouterDto(
            String queryType,
            String normalizedQuery,
            List<String> companyIds,
            Integer yearFrom,
            Integer yearTo,
            List<String> metrics,
            Boolean needMultiQuery,
            Boolean needDecompose,
            String docType,
            Boolean resolved,
            Boolean needLongTermMemory) {
    }
}
