package com.finagent.retrieval.rerank;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 阿里云百炼 qwen3-rerank。
 *
 * <p>接口形态已用 curl <b>实测</b>（2026-10-20），与计划里的猜测一致：
 *
 * <pre>
 * POST {base-url}/api/v1/services/rerank/text-rerank/text-rerank
 * Authorization: Bearer KEY
 *
 * 请求：
 * {"model":"qwen3-rerank",
 *  "input":{"query":"东方财富2025年营业收入是多少",
 *           "documents":["第三节 ...","第四节 ...","第二节 ..."]},
 *  "parameters":{"return_documents":false,"top_n":3}}
 *
 * 200：
 * {"output":{"results":[{"index":2,"relevance_score":0.6092122030118452},
 *                       {"index":0,"relevance_score":0.5670480230390897},
 *                       {"index":1,"relevance_score":0.2694444844149849}]},
 *  "usage":{"total_tokens":175},
 *  "request_id":"120c1d1a-eab6-9788-8754-073a5ff68c9f"}
 * </pre>
 *
 * <p>实测出来的边界，都对应下面的一段校验：
 * <ul>
 *   <li>documents <b>最多 500 条</b>，空数组直接 400：
 *       "batch size of documents is invalid, it should not be empty or larger than 500"；</li>
 *   <li>query 为空直接 400："query should not be empty"；</li>
 *   <li>top_n 大于候选数<b>不报错</b>，按候选数原样返回；不给 top_n 则全量返回；</li>
 *   <li>单条文档超过约 4000 token 会<b>静默截断</b>：2 万汉字的文档返回
 *       usage.total_tokens = 3977，不报错。本项目 child 最大 1610 token，不触发；</li>
 *   <li>错误响应<b>没有 output 字段</b>，只有 code / message，所以必须先按 HTTP 状态分流，
 *       否则解析时会 NPE 而不是给出可读的错误。</li>
 * </ul>
 *
 * <p><b>本类不做降级</b> —— 失败一律抛 {@link RerankException}，由 HybridRetriever 捕获
 * 并回退到 RRF 结果（§9.7）。降级放在调用方，因为只有编排层知道降级后 trace 该怎么标。
 */
public class DashScopeRerankModel implements RerankModel {

    private static final Logger log = LoggerFactory.getLogger(DashScopeRerankModel.class);

    /** 相对 base-url 的接口路径 */
    static final String PATH = "/api/v1/services/rerank/text-rerank/text-rerank";

    /** 实测上限：超过 500 条服务端直接 400 */
    public static final int MAX_DOCUMENTS = 500;

    /** 错误体可能很长，截断后再进日志 */
    private static final int ERROR_BODY_LIMIT = 300;

    private final RestClient http;
    private final String model;

    /** 供装配用：自己建 RestClient（带超时）。 */
    public DashScopeRerankModel(RerankProperties props) {
        this(props, buildClient(props));
    }

    /** 供测试用：注入一个已被 MockRestServiceServer 接管的 RestClient。 */
    DashScopeRerankModel(RerankProperties props, RestClient http) {
        this.http = http;
        this.model = props.getModel();
    }

    private static RestClient buildClient(RerankProperties props) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(props.getConnectTimeout());
        factory.setReadTimeout(props.getReadTimeout());
        return RestClient.builder()
                .baseUrl(props.getBaseUrl())
                .defaultHeader("Authorization", "Bearer " + props.getApiKey())
                .requestFactory(factory)
                .build();
    }

    @Override
    public String modelName() {
        return model;
    }

    @Override
    public List<RerankResult> rerank(String query, List<String> documents, int topN) {
        if (query == null || query.isBlank()) {
            throw new RerankException("Rerank 的 query 不能为空（百炼会返回 400）");
        }
        if (documents == null || documents.isEmpty() || topN <= 0) {
            // 实测空 documents 直接 400。没有候选就没有可排的东西，本地返回空。
            return List.of();
        }
        if (documents.size() > MAX_DOCUMENTS) {
            throw new RerankException("Rerank 候选数 " + documents.size()
                    + " 超过百炼上限 " + MAX_DOCUMENTS);
        }
        int effectiveTopN = Math.min(topN, documents.size());

        Map<String, Object> request = new LinkedHashMap<>();
        request.put("model", model);
        request.put("input", Map.of("query", query, "documents", documents));
        request.put("parameters", Map.of("return_documents", false, "top_n", effectiveTopN));

        long start = System.currentTimeMillis();
        RerankResponse response;
        try {
            response = http.post()
                    .uri(PATH)
                    .body(request)
                    .retrieve()
                    .body(RerankResponse.class);
        } catch (RestClientResponseException e) {
            // 百炼的错误体：{"code":"InvalidParameter","message":"...","request_id":"..."}
            throw new RerankException("百炼 Rerank 返回 " + e.getStatusCode() + "："
                    + abbreviate(e.getResponseBodyAsString()), e);
        } catch (RestClientException e) {
            throw new RerankException("百炼 Rerank 调用失败：" + e.getMessage(), e);
        }
        long elapsed = System.currentTimeMillis() - start;

        List<RerankResult> results = toResults(response, documents.size(), effectiveTopN);
        if (results.isEmpty()) {
            throw new RerankException("百炼 Rerank 返回了空结果");
        }
        log.debug("Rerank 完成：候选 {} 条，返回 {} 条，用时 {} ms",
                documents.size(), results.size(), elapsed);
        return results;
    }

    /**
     * 校验下标 → 降序 → 截断。
     *
     * <p>为什么自己再排一次：实测响应已经是降序，但那是<b>服务端实现细节</b>，不是契约。
     * 依赖它会让我们在某次服务端调整后静默拿到乱序结果 —— 而这种错误几乎不可能被肉眼发现。
     */
    private static List<RerankResult> toResults(RerankResponse response, int documentCount, int limit) {
        if (response == null || response.output() == null || response.output().results() == null) {
            throw new RerankException("百炼 Rerank 响应缺少 output.results");
        }
        List<RerankResponse.Item> items = response.output().results();
        List<RerankResult> results = new ArrayList<>(items.size());
        for (RerankResponse.Item item : items) {
            if (item == null) {
                continue;
            }
            if (item.index() < 0 || item.index() >= documentCount) {
                throw new RerankException("Rerank 返回的下标 " + item.index()
                        + " 越界（候选共 " + documentCount + " 条）");
            }
            results.add(new RerankResult(item.index(), item.score()));
        }
        results.sort(Comparator.comparingDouble(RerankResult::relevanceScore).reversed());
        return results.size() <= limit ? results : List.copyOf(results.subList(0, limit));
    }

    private static String abbreviate(String body) {
        if (body == null) {
            return "";
        }
        String oneLine = body.replace('\n', ' ').replace('\r', ' ').strip();
        return oneLine.length() <= ERROR_BODY_LIMIT
                ? oneLine
                : oneLine.substring(0, ERROR_BODY_LIMIT) + "...";
    }

    /** 百炼响应体。只声明用得到的字段，其余靠 ignoreUnknown 丢弃。 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record RerankResponse(Output output) {

        @JsonIgnoreProperties(ignoreUnknown = true)
        record Output(List<Item> results) {
        }

        @JsonIgnoreProperties(ignoreUnknown = true)
        record Item(int index, @JsonProperty("relevance_score") double score) {
        }
    }
}
