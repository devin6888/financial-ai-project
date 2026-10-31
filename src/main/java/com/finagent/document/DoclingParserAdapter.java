package com.finagent.document;

// Spring Boot 4 默认使用 Jackson 3（tools.jackson），自动配置的 ObjectMapper 也是它；
// Jackson 2（com.fasterxml.jackson）只是被 Spring AI 传递进来的，没有对应 Bean。
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.function.Consumer;

/**
 * 通过 HTTP 调用 Python 解析服务。
 *
 * <p>采用「提交作业 + 轮询」而不是一个长同步请求（A 方案）：
 * 181 页约 25 分钟，同步请求在故障时会让全部算力白费，而且客户端看不到任何进度。
 * 作业化之后单个请求都很短，读超时从 45 分钟降到 5 分钟。
 */
@Component
public class DoclingParserAdapter implements ParserAdapter {

    private static final Logger log = LoggerFactory.getLogger(DoclingParserAdapter.class);

    private final RestClient client;
    private final ObjectMapper mapper;
    private final ParserProperties props;

    public DoclingParserAdapter(ParserProperties props, ObjectMapper mapper) {
        this.props = props;
        this.mapper = mapper;
        // RestClient.Builder 只有 requestFactory(ClientHttpRequestFactory) 这一个重载
        //（javap spring-web-7.0.9 确认，无 Consumer/Function 版本），所以必须先构造实例。
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(10));
        factory.setReadTimeout(Duration.ofMinutes(props.readTimeoutMinutes()));
        this.client = RestClient.builder()
                .baseUrl(props.baseUrl())
                .requestFactory(factory)
                .build();
    }

    @Override
    public ParsedDocument fetchResult(String jobId) {
        return toParsedDocument(awaitResult(jobId, null));
    }

    @Override
    public ParsedDocument fetchResult(String jobId, Consumer<ParseJobStatus> onPoll) {
        return toParsedDocument(awaitResult(jobId, onPoll));
    }

    @Override
    public String name() {
        return "docling";
    }

    @Override
    public String submit(byte[] pdf, String sourceName, Integer pageStart, Integer pageEnd) {
        MultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
        form.add("file", new ByteArrayResource(pdf) {
            @Override
            public String getFilename() {
                return sourceName;
            }
        });
        if (pageStart != null && pageEnd != null) {
            form.add("page_start", String.valueOf(pageStart));
            form.add("page_end", String.valueOf(pageEnd));
        }

        String body = client.post()
                .uri("/jobs")
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(form)
                .retrieve()
                .body(String.class);
        try {
            String jobId = mapper.readTree(body).path("job_id").asText();
            if (jobId.isBlank()) {
                throw new IllegalStateException("解析服务未返回 job_id: " + body);
            }
            log.info("已提交解析作业 {}: {} ({} 字节)", jobId, sourceName, pdf.length);
            return jobId;
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("提交作业失败，返回的不是合法 JSON: " + body, e);
        }
    }

    /**
     * 轮询直到作业完成，返回 result 节点。
     *
     * @param onPoll 每次轮询后的回调（可空）。回调异常被吞掉：埋点是旁路，
     *               不能因为写流水失败而把一次 190 秒的解析打断（阶段 19）
     */
    private JsonNode awaitResult(String jobId, Consumer<ParseJobStatus> onPoll) {
        long deadline = System.nanoTime()
                + Duration.ofMinutes(props.jobTimeoutMinutes()).toNanos();
        String lastStatus = null;

        while (System.nanoTime() < deadline) {
            JsonNode job = queryJob(jobId);
            String status = job.path("status").asText();

            if (!status.equals(lastStatus)) {
                log.info("解析作业 {} 状态: {} (已用 {}s)", jobId, status,
                        job.path("elapsed_seconds").asDouble());
                lastStatus = status;
            }
            if ("done".equals(status)) {
                return job.path("result");
            }
            if ("failed".equals(status)) {
                throw new IllegalStateException("解析作业失败: " + job.path("error").asText());
            }
            if (onPoll != null) {
                try {
                    onPoll.accept(new ParseJobStatus(status, job.path("elapsed_seconds").asDouble()));
                } catch (RuntimeException e) {
                    log.debug("轮询回调失败（忽略）：{}", e.toString());
                }
            }
            sleep(props.pollIntervalSeconds());
        }
        throw new IllegalStateException("解析作业超时（" + props.jobTimeoutMinutes() + " 分钟）: "
                + jobId + " —— 作业可能仍在服务端运行，可用 GET /jobs/" + jobId + " 继续查");
    }

    private JsonNode queryJob(String jobId) {
        String body;
        try {
            body = client.get().uri("/jobs/{id}", jobId).retrieve().body(String.class);
        } catch (HttpClientErrorException.NotFound e) {
            // 解析服务重启后作业状态在内存里丢失 —— 恢复流程要能区分这种情况
            throw new IllegalStateException("作业不存在（解析服务可能已重启）: " + jobId, e);
        }
        try {
            return mapper.readTree(body);
        } catch (Exception e) {
            throw new IllegalStateException("查询作业返回的不是合法 JSON: " + body, e);
        }
    }

    private static void sleep(int seconds) {
        try {
            Thread.sleep(Duration.ofSeconds(seconds).toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("轮询被中断，作业仍在服务端运行", e);
        }
    }

    private ParsedDocument toParsedDocument(JsonNode root) {
        int headings = root.path("headings").size();
        int anomalies = 0;
        for (JsonNode h : root.path("headings")) {
            if (h.hasNonNull("anomaly")) {
                anomalies++;
            }
        }
        return new ParsedDocument(
                root.path("meta").path("content_hash").asText(),
                root.path("meta").path("parser").asText(name()),
                root.path("meta").path("parser_version").asText(),
                root.path("meta").path("page_count").asInt(),
                root.path("blocks").size(),
                root.path("tables").size(),
                headings,
                anomalies,
                root.toString());
    }
}
