package com.finagent.chat;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.OffsetDateTime;

/**
 * trace 落盘：一行一条 JSON，便于离线复盘「这条答案为什么这么答」。
 *
 * <p>为什么不上数据库表：阶段 5 还没有 conversation/message 表，落文件足够；
 * 阶段 9 有了会话表再迁移，不提前背 schema 债。
 */
@Component
public class ChatTraceWriter {

    private static final Logger log = LoggerFactory.getLogger(ChatTraceWriter.class);

    private final ObjectMapper objectMapper;
    private final Path path;

    public ChatTraceWriter(ObjectMapper objectMapper,
                           @Value("${finagent.chat.trace-file:logs/chat-trace.jsonl}") String traceFile) {
        this.objectMapper = objectMapper;
        this.path = (traceFile == null || traceFile.isBlank()) ? null : Path.of(traceFile);
    }

    /** 写失败只告警：trace 是观测手段，不能让它把正常回答搞挂。 */
    public void write(ChatTrace trace) {
        if (path == null || trace == null) {
            return;
        }
        try {
            if (path.getParent() != null) {
                Files.createDirectories(path.getParent());
            }
            String line = objectMapper.writeValueAsString(new Line(OffsetDateTime.now().toString(), trace)) + "\n";
            Files.writeString(path, line, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException | RuntimeException e) {
            log.warn("chat trace 落盘失败：{}", e.getMessage());
        }
    }

    private record Line(String at, ChatTrace trace) { }
}
