package com.finagent.agent;

import java.util.UUID;

/** 运行不存在、或不属于当前用户 —— 对外一律 404（不确认存在性，契约 C11）。 */
public class AgentRunNotFoundException extends RuntimeException {

    public AgentRunNotFoundException(UUID runId) {
        super("Agent 运行不存在：" + runId);
    }
}