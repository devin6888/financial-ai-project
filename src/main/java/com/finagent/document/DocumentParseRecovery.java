package com.finagent.document;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 启动时在**后台线程**恢复在途解析作业。
 *
 * <p>刻意不阻塞启动：181 页要 25 分钟，不能让应用起不来去等它。
 * 逻辑留在 {@link DocumentParseService} 里，本类只负责挑线程 —— 这样恢复逻辑好测。
 */
@Component
// 依赖 DocumentParseService，条件必须与它一致，否则会因缺 Bean 而启动失败
@ConditionalOnProperty(prefix = "finagent.cos",
        name = {"secret-id", "secret-key", "region", "bucket"})
public class DocumentParseRecovery implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DocumentParseRecovery.class);

    private final DocumentParseService parseService;

    public DocumentParseRecovery(DocumentParseService parseService) {
        this.parseService = parseService;
    }

    @Override
    public void run(ApplicationArguments args) {
        Thread thread = new Thread(() -> {
            try {
                parseService.recoverInFlight();
            } catch (RuntimeException e) {
                log.warn("在途作业恢复过程出错（不影响应用启动）", e);
            }
        }, "parse-recovery");
        thread.setDaemon(true);
        thread.start();
    }
}
