package com.finagent.agent;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Agent 路径的配置（{@code finagent.agent.*}）。
 *
 * @param enabled                总开关；false 时 {@link AgentRunService} 直接拒绝
 * @param minEvidence            verify 判「证据够」的最少命中条数
 * @param maxSubQuestions        plan 拆子问题的上限（防一条问题被切成十几个）
 * @param recursionLimit         图的最大步数（库默认 25，正常路径最多 8 步）
 * @param hitl                   复杂问题是否在 answer 前等人批
 * @param approvalTimeoutMinutes 人批超时（分钟）；超时只标失败，<b>不会自动通过</b>
 */
@ConfigurationProperties(prefix = "finagent.agent")
public record AgentProperties(
        @DefaultValue("false") boolean enabled,
        @DefaultValue("2") int minEvidence,
        @DefaultValue("4") int maxSubQuestions,
        @DefaultValue("25") int recursionLimit,
        @DefaultValue("true") boolean hitl,
        @DefaultValue("30") int approvalTimeoutMinutes) {
}
