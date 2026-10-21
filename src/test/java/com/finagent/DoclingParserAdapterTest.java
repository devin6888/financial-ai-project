package com.finagent;

import com.finagent.document.ParsedDocument;
import com.finagent.document.ParserAdapter;
import com.finagent.document.ParserProperties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Java Adapter 与 Python 解析服务的联通测试。
 *
 * <p>解析服务没启动时跳过（不失败）—— 它是个独立进程，不应让 mvn test 强依赖它。
 * 真正的端到端（含 COS 与落库）在 FullDocumentPipelineTest。
 */
@SpringBootTest
@ActiveProfiles("test")
class DoclingParserAdapterTest {

    private static final Path PDF = Path.of("docs/东方财富2025年年度报告.pdf");

    @Autowired
    private ParserAdapter parserAdapter;

    @Autowired
    private ParserProperties parserProperties;

    @Test
    void parsesFirstPagesThroughHttpService() throws Exception {
        assumeTrue(Files.exists(PDF), "样本 PDF 不在仓库中");
        assumeTrue(reachable(), "解析服务未启动，跳过（先跑 uvicorn）");

        byte[] pdf = Files.readAllBytes(PDF);
        // 刻意走 submit + fetchResult 两步（与编排层一致）—— job_id 必须能被拿到并落库
        String jobId = parserAdapter.submit(pdf, PDF.getFileName().toString(), 1, 3);
        assertThat(jobId).isNotBlank();
        ParsedDocument doc = parserAdapter.fetchResult(jobId);

        assertThat(doc.parserName()).isEqualTo("docling");
        assertThat(doc.parserVersion()).isNotBlank();
        assertThat(doc.pageCount()).isEqualTo(3);
        assertThat(doc.blockCount()).isGreaterThan(5);
        assertThat(doc.contentHash()).hasSize(64);

        // rawJson 是原样的 NormalizedDocument
        assertThat(doc.rawJson()).contains("\"headings\"").contains("\"section_path\"");
    }

    /** 用**配置里**的 baseUrl 判断服务是否可达，而不是硬编码端口（评审 M9）。 */
    private boolean reachable() {
        try {
            URI uri = URI.create(parserProperties.baseUrl());
            int port = uri.getPort() > 0 ? uri.getPort() : 80;
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress(uri.getHost(), port), 500);
                return true;
            }
        } catch (Exception e) {
            return false;
        }
    }
}
