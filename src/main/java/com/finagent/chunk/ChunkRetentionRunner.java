package com.finagent.chunk;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 旧 chunk 版本的清理入口（运维用，默认关闭）。
 *
 * <p>用法（先干跑，再加 --apply）：</p>
 * <pre>
 *   mvn spring-boot:run -Dspring-boot.run.arguments="--finagent.retention.enabled=true --finagent.retention.document-id=7"
 *   # 确认计划无误后把 --finagent.retention.apply=true 加上
 * </pre>
 *
 * <p><b>为什么挂在 ApplicationRunner 上而不是定时任务</b>：删除是破坏性动作，不应该「自己悄悄开始」；
 * 由人显式触发、先看计划再执行，出问题还能在日志里看到当时保留了哪些版本。
 * 这与 ReindexRunner 是同一个套路。
 */
@Component
@ConditionalOnProperty(prefix = "finagent.retention", name = "enabled", havingValue = "true")
public class ChunkRetentionRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(ChunkRetentionRunner.class);

    private final ChunkRetentionService retention;
    private final JdbcTemplate jdbc;
    private final boolean apply;
    private final long documentId;

    public ChunkRetentionRunner(ChunkRetentionService retention, JdbcTemplate jdbc,
                                @org.springframework.beans.factory.annotation.Value(
                                        "${finagent.retention.apply:false}") boolean apply,
                                @org.springframework.beans.factory.annotation.Value(
                                        "${finagent.retention.document-id:0}") long documentId) {
        this.retention = retention;
        this.jdbc = jdbc;
        this.apply = apply;
        this.documentId = documentId;
    }

    @Override
    public void run(ApplicationArguments args) {
        for (long id : targets()) {
            RetentionPlan plan = retention.plan(id);
            log.info("清理计划：{}", plan.summary());
            if (!apply) {
                log.info("干跑模式（finagent.retention.apply=false），未删除任何行");
                continue;
            }
            int deleted = retention.apply(plan);
            log.info("已删除 document {} 的 {} 行旧 chunk", id, deleted);
        }
    }

    /** document-id > 0 只处理这一份；否则处理全部（运维批量清理）。 */
    private List<Long> targets() {
        if (documentId > 0) {
            return List.of(documentId);
        }
        List<Long> all = jdbc.queryForList(
                "SELECT DISTINCT document_id FROM document_chunk ORDER BY document_id", Long.class);
        log.info("未指定 document-id，将处理 {} 份文档", all.size());
        return all;
    }
}
