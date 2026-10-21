package com.finagent;

import com.finagent.document.DocumentParseService;
import com.finagent.document.DocumentRepository;
import com.finagent.document.ParserProperties;
import com.finagent.storage.ObjectStorage;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 端到端：COS -> Java 编排 -> Python/Docling -> 产物写 COS -> 更新 document。
 *
 * <p>这条链路才是生产路径。初版计划把全量解析放在 Python 脚本里、Java 只测 3 页，
 * 等于**测了一条生产中不存在的路** —— 本用例是那个缺口的修复。
 *
 * <p>默认被 surefire 排除（181 页约 25 分钟）。手动运行：
 * <pre>
 *   mvn -Dsurefire.excludedGroups= -Dgroups=e2e -Dtest=FullDocumentPipelineTest test
 * </pre>
 */
@SpringBootTest
@ActiveProfiles("test")
@Tag("e2e")
@EnabledIfEnvironmentVariable(named = "FINAGENT_COS_SECRET_ID", matches = ".+")
class FullDocumentPipelineTest {

    private static final String SOURCE_KEY = "samples/300059-2025-annual-report.pdf";
    private static final String COMPANY_CODE = "300059";

    @Autowired
    private ObjectStorage storage;

    @Autowired
    private DocumentParseService parseService;

    @Autowired
    private DocumentRepository documents;

    @Autowired
    private ParserProperties parserProperties;

    @Test
    void cosToParserToCos() {
        assumeTrue(reachable(), "解析服务未启动，跳过（先跑 uvicorn）");
        assumeTrue(storage.exists(SOURCE_KEY), "COS 上还没有年报，先上传 " + SOURCE_KEY);

        // 1) 从 COS 取 PDF
        byte[] pdf = storage.get(SOURCE_KEY);
        assertThat(pdf).startsWith("%PDF".getBytes(StandardCharsets.US_ASCII));

        // 2) 走完整编排：登记 -> 提交作业 -> 轮询 -> 产物写 COS -> 更新 document
        long t0 = System.currentTimeMillis();
        long documentId = parseService.parseAndStore(
                COMPANY_CODE, "东方财富", "SZ",
                "ANNUAL", 2025, "东方财富2025年年度报告", SOURCE_KEY, pdf);
        long elapsedMs = System.currentTimeMillis() - t0;

        // 3) 查库确认状态与产物指针
        DocumentRepository.ParseState st = documents.findParseState(documentId);

        System.out.println("""
                ===== 端到端结果 =====
                document_id   : %d
                parse_status  : %s
                parser        : %s %s
                pages_parsed  : %s
                产物          : %s
                耗时          : %.1f 分钟
                ======================
                """.formatted(documentId, st.parseStatus(), st.parser(), st.parserVersion(),
                String.valueOf(st.pagesParsed()), st.parsedObjectKey(), elapsedMs / 60000.0));

        assertThat(st.parseStatus()).isEqualTo("DONE");
        assertThat(st.parseError()).isNull();
        assertThat(st.parsedObjectKey()).isNotBlank();
        assertThat(st.pagesParsed()).isEqualTo(181);

        // 4) 产物必须真的在 COS 上
        assertThat(storage.exists(st.parsedObjectKey())).isTrue();

        // 5) 没有卡在途的行
        assertThat(documents.findInFlight()).isEmpty();
    }

    /** 用**配置里**的 baseUrl 判断服务可达性，不硬编码端口。 */
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
