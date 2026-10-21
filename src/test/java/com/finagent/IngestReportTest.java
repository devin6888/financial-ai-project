package com.finagent;

import com.finagent.chunk.ChildChunker;
import com.finagent.chunk.ChunkRepository;
import com.finagent.chunk.ChunkWriter;
import com.finagent.chunk.ParentChunker;
import com.finagent.chunk.model.Chunk;
import com.finagent.chunk.model.ParentGroup;
import com.finagent.document.DocumentModel;
import com.finagent.document.DocumentParseService;
import com.finagent.document.DocumentRepository;
import com.finagent.document.NormalizedDocumentLoader;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 把一份**本地 PDF** 完整跑一遍：上传 COS -> 解析 -> 切块 -> 向量化 -> 原子切换。
 *
 * <p>与 {@link FullDocumentPipelineTest} 的区别：那份只解析、且 PDF 必须已在 COS 上；
 * 本用例从本地文件起步，一条命令就能新增一份语料，参数全部走 -D，便于重复使用：
 * <pre>
 *   mvn -B "-Dsurefire.excludedGroups=" "-Dgroups=e2e" "-Dtest=IngestReportTest" test ^
 *       -Dingest.pdf=docs/东方财富2024年年度报告.pdf -Dingest.year=2024 ^
 *       -Dingest.title=东方财富2024年年度报告
 * </pre>
 *
 * <p>默认排除在常规测试之外：181 页解析约 50 分钟。
 */
@SpringBootTest
@ActiveProfiles("test")
@Tag("e2e")
@EnabledIfEnvironmentVariable(named = "FINAGENT_COS_SECRET_ID", matches = ".+")
class IngestReportTest {

    @Autowired private ObjectStorage storage;
    @Autowired private DocumentParseService parseService;
    @Autowired private DocumentRepository documents;
    @Autowired private NormalizedDocumentLoader loader;
    @Autowired private ChunkWriter writer;
    @Autowired private ChunkRepository chunks;
    @Autowired private ParserProperties parserProperties;

    private static String prop(String key, String fallback) {
        String v = System.getProperty(key);
        return (v == null || v.isBlank()) ? fallback : v;
    }

    @Test
    void ingestLocalPdf() throws Exception {
        String pdfPath = prop("ingest.pdf", "docs/东方财富2024年年度报告.pdf");
        String code = prop("ingest.code", "300059");
        String name = prop("ingest.name", "东方财富");
        String market = prop("ingest.market", "SZ");
        String docType = prop("ingest.type", "ANNUAL");
        int year = Integer.parseInt(prop("ingest.year", "2024"));
        String title = prop("ingest.title", "东方财富2024年年度报告");

        assumeTrue(reachable(), "解析服务未启动，跳过（先跑 uvicorn）");

        byte[] pdf = Files.readAllBytes(Path.of(pdfPath));
        assertThat(pdf).startsWith("%PDF".getBytes(java.nio.charset.StandardCharsets.US_ASCII));

        // 源文件先落 COS：document.pdf_object_key 指向它，事后可追溯
        String sourceKey = "samples/" + code + "-" + year + "-" + docType.toLowerCase() + ".pdf";
        if (!storage.exists(sourceKey)) {
            storage.put(sourceKey, pdf, "application/pdf");
            System.out.println("已上传源文件到 COS：" + sourceKey);
        }

        // ---------- 1) 解析（这一步最慢） ----------
        long t0 = System.currentTimeMillis();
        long docId = parseService.parseAndStore(code, name, market, docType, year, title, sourceKey, pdf);
        long tParse = System.currentTimeMillis() - t0;

        var st = documents.findParseState(docId);
        System.out.printf("""
                
                ================= 解析完成 =================
                document_id  : %d
                parse_status : %s
                parser       : %s %s
                pages_parsed : %s
                产物          : %s
                解析耗时      : %.1f 分钟
                ============================================
                """.formatted(docId, st.parseStatus(), st.parser(), st.parserVersion(),
                String.valueOf(st.pagesParsed()), st.parsedObjectKey(), tParse / 60000.0));

        assertThat(st.parseStatus()).isEqualTo("DONE");
        assertThat(st.parseError()).isNull();

        // ---------- 2) 切块 ----------
        t0 = System.currentTimeMillis();
        DocumentModel model = loader.load(st.parsedObjectKey());
        List<ParentGroup> groups = ParentChunker.split(model);
        List<List<Chunk>> children = groups.stream().map(ChildChunker::splitAll).toList();
        long tChunk = System.currentTimeMillis() - t0;

        // ---------- 3) 向量化 + 入库 ----------
        t0 = System.currentTimeMillis();
        int version = writer.writeVersion(docId, st.title(), groups, children,
                model.meta().parser_version());
        long tWrite = System.currentTimeMillis() - t0;

        var stats = chunks.statsByVersion(docId, version);
        int parents = chunks.countByVersion(docId, version, "PARENT");
        int kids = chunks.countByVersion(docId, version, "CHILD");
        int noVector = chunks.countChildrenWithoutEmbedding(docId, version);

        System.out.printf("""
                
                ================= 切块与入库 =================
                文档            : #%d %s（%s 页）
                切块            : %d ms   (Parent %d / Child %d)
                向量化 + 入库   : %d ms
                -------------------------------------------------
                库里 Parent     : %d
                库里 Child      : %d
                无向量的 Child  : %d
                token 分布      : min=%d  p50=%d  p90=%d  max=%d
                <=700 的比例    : %.1f%%
                >=150 的比例    : %.1f%%
                =================================================
                """,
                docId, st.title(), st.pagesParsed(), tChunk, groups.size(), children.stream().mapToInt(List::size).sum(),
                tWrite, parents, kids, noVector,
                stats.min(), stats.p50(), stats.p90(), stats.max(),
                stats.pctAtMost700(), stats.pctAtLeast150());

        // 与阶段 3 同一套硬口径：向量 100%、原子切换、表格行之外不得超 700
        assertThat(noVector).isZero();
        assertThat(chunks.activeVersion(docId)).isEqualTo(version);
        assertThat(chunks.countWithFullContext(docId, version)).isEqualTo(kids);
        int overAll = chunks.countOverToken(docId, version, "CHILD", 700);
        int overTable = chunks.countOverTokenByType(docId, version, "CHILD", "TABLE", 700);
        assertThat(overAll - overTable).isZero();
    }

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
