package com.finagent.chunk;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * 重索引的命令行入口 —— 让 {@link ReindexService} 真的有人调用（评审 M1）。
 *
 * <p>用法：
 * <pre>{@code
 * mvn spring-boot:run -Dspring-boot.run.arguments="--finagent.reindex.document-id=7"
 * }</pre>
 *
 * <p>为什么用 ApplicationRunner 而不是 REST 接口：本阶段还没有 Web 层需求，
 * 加一个 controller 会引入权限、审计等本阶段不处理的关注点。阶段 4 再开接口。
 */
@Component
@ConditionalOnProperty(name = "finagent.reindex.document-id")
public class ReindexRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(ReindexRunner.class);

    private final ReindexService reindexService;
    private final long documentId;

    public ReindexRunner(ReindexService reindexService, Environment env) {
        this.reindexService = reindexService;
        this.documentId = Long.parseLong(env.getProperty("finagent.reindex.document-id", "0"));
    }

    @Override
    public void run(ApplicationArguments args) {
        int version = reindexService.reindex(documentId, ReindexMode.RECHUNK);
        log.info("重切完成：document_id={} 新 chunk 版本={}", documentId, version);
    }
}
