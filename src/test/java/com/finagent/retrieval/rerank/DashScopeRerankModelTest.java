package com.finagent.retrieval.rerank;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * 百炼 Rerank 的线上格式验证。
 *
 * <p>关键点：<b>测试夹具里的响应是 curl 实测抓下来的原文</b>，不是我照着文档编的。
 * 计划里专门警告过「不要把文档里的 JSON 当成事实」，这个类就是那句话的落地。
 *
 * <p>Authorization 头在这里测不到（RestClient 由测试注入，头是生产路径 buildClient() 加的），
 * 由 {@link DashScopeRerankModelLiveTest} 打真接口覆盖。
 */
class DashScopeRerankModelTest {

    private static final String BASE_URL = "https://dashscope.aliyuncs.com";
    private static final String URL = BASE_URL + DashScopeRerankModel.PATH;

    /** curl 实测的真实响应（2026-10-20），一字未改。 */
    private static final String REAL_OK = """
            {"output":{"results":[{"index":2,"relevance_score":0.6092122030118452},
                                  {"index":0,"relevance_score":0.5670480230390897},
                                  {"index":1,"relevance_score":0.2694444844149849}]},
             "usage":{"total_tokens":175},
             "request_id":"120c1d1a-eab6-9788-8754-073a5ff68c9f"}
            """;

    /** 实测的空 query 报错体。 */
    private static final String REAL_ERROR = "{\"request_id\":\"15b38d86\",\"code\":\"InvalidParameter\","
            + "\"message\":\"<400> InternalError.Algo.InvalidParameter: Value error, "
            + "query should not be empty.: input.query\"}";

    private MockRestServiceServer server;
    private DashScopeRerankModel model;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE_URL);
        server = MockRestServiceServer.bindTo(builder).build();
        RerankProperties props = new RerankProperties();
        props.setApiKey("test-key");
        model = new DashScopeRerankModel(props, builder.build());
    }

    @Test
    void sendsDocumentedPayloadAndParsesRealResponse() {
        server.expect(requestTo(URL))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(content().json("""
                        {"model":"qwen3-rerank",
                         "input":{"query":"东方财富2025年营业收入是多少",
                                  "documents":["doc-a","doc-b","doc-c"]},
                         "parameters":{"return_documents":false,"top_n":3}}
                        """))
                .andRespond(withSuccess(REAL_OK, MediaType.APPLICATION_JSON));

        List<RerankResult> results =
                model.rerank("东方财富2025年营业收入是多少", List.of("doc-a", "doc-b", "doc-c"), 3);

        server.verify();
        assertThat(results).extracting(RerankResult::index).containsExactly(2, 0, 1);
        assertThat(results).extracting(RerankResult::relevanceScore)
                .containsExactly(0.6092122030118452, 0.5670480230390897, 0.2694444844149849);
    }

    @Test
    void sortsByScoreEvenWhenApiReturnsUnordered() {
        // 实测响应本来就是降序，但那是服务端细节，不是契约 —— 依赖它会静默拿到乱序结果
        server.expect(requestTo(URL)).andRespond(withSuccess("""
                {"output":{"results":[{"index":0,"relevance_score":0.1},
                                      {"index":1,"relevance_score":0.9}]}}
                """, MediaType.APPLICATION_JSON));

        assertThat(model.rerank("q", List.of("a", "b"), 5))
                .extracting(RerankResult::index)
                .containsExactly(1, 0);
    }

    @Test
    void clampsTopNToCandidateCount() {
        // 实测 top_n 大于候选数不报错；但我们不该把无意义的数字发出去
        server.expect(requestTo(URL))
                .andExpect(content().json("{\"parameters\":{\"top_n\":2}}"))
                .andRespond(withSuccess("""
                        {"output":{"results":[{"index":1,"relevance_score":0.8},
                                              {"index":0,"relevance_score":0.2}]}}
                        """, MediaType.APPLICATION_JSON));

        model.rerank("q", List.of("a", "b"), 10);
        server.verify();
    }

    @Test
    void truncatesWhenProviderReturnsMoreThanRequested() {
        server.expect(requestTo(URL)).andRespond(withSuccess(REAL_OK, MediaType.APPLICATION_JSON));

        assertThat(model.rerank("q", List.of("a", "b", "c"), 2)).hasSize(2);
    }

    @Test
    void blankQueryFailsWithoutHttpCall() {
        assertThatThrownBy(() -> model.rerank("   ", List.of("a"), 1))
                .isInstanceOf(RerankException.class)
                .hasMessageContaining("query");
        server.verify();
    }

    @Test
    void emptyCandidatesReturnEmptyWithoutHttpCall() {
        assertThat(model.rerank("q", List.of(), 3)).isEmpty();
        assertThat(model.rerank("q", null, 3)).isEmpty();
        server.verify();
    }

    @Test
    void tooManyCandidatesFailBeforeHttpCall() {
        List<String> docs = new ArrayList<>();
        for (int i = 0; i <= DashScopeRerankModel.MAX_DOCUMENTS; i++) {
            docs.add("doc-" + i);
        }
        assertThatThrownBy(() -> model.rerank("q", docs, 5))
                .isInstanceOf(RerankException.class)
                .hasMessageContaining("500");
        server.verify();
    }

    @Test
    void httpErrorBecomesRerankExceptionCarryingProviderMessage() {
        server.expect(requestTo(URL))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(REAL_ERROR));

        assertThatThrownBy(() -> model.rerank("营业收入", List.of("a"), 1))
                .isInstanceOf(RerankException.class)
                .hasMessageContaining("400")
                .hasMessageContaining("InvalidParameter");
    }

    @Test
    void responseWithoutResultsIsRejected() {
        // 错误响应没有 output 字段 —— 不先分流 HTTP 状态就会 NPE 而不是给出可读错误
        server.expect(requestTo(URL))
                .andRespond(withSuccess("{\"request_id\":\"x\"}", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> model.rerank("q", List.of("a"), 1))
                .isInstanceOf(RerankException.class)
                .hasMessageContaining("output.results");
    }

    @Test
    void outOfRangeIndexIsRejected() {
        server.expect(requestTo(URL)).andRespond(withSuccess("""
                {"output":{"results":[{"index":7,"relevance_score":0.5}]}}
                """, MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> model.rerank("q", List.of("a", "b"), 1))
                .isInstanceOf(RerankException.class)
                .hasMessageContaining("越界");
    }

    @Test
    void modelNameComesFromProperties() {
        RerankProperties props = new RerankProperties();
        props.setApiKey("k");
        props.setModel("custom-rerank");
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE_URL);
        MockRestServiceServer.bindTo(builder).build();

        assertThat(new DashScopeRerankModel(props, builder.build()).modelName())
                .isEqualTo("custom-rerank");
    }
}
