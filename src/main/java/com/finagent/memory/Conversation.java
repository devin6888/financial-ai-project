package com.finagent.memory;

import com.finagent.scope.ChatScope;
import com.finagent.scope.ScopeKind;

import java.time.OffsetDateTime;

/**
 * 一个会话（阶段 9 引入）。
 *
 * <p>{@code scope} 是<b>会话级作用域</b>：用户开一个会话时选定的研究范围。
 * 单轮请求可以不传 scope，就用它；传了 scope 则以单轮为准（只影响这一轮，不改会话配置）。
 *
 * <p>{@code scopeKind} 与 {@code scope.kind()} 冗余：库里 {scope_kind} 单独成列，
 * 便于按类型建索引与统计，也避免 scope_json 解析失败时连种类都丢了。
 */
public record Conversation(long id, long tenantId, long ownerUserId, String title,
                           ScopeKind scopeKind, ChatScope scope, String status,
                           OffsetDateTime createdAt, OffsetDateTime updatedAt) {

    public static final String STATUS_ACTIVE = "ACTIVE";
    public static final String STATUS_DELETED = "DELETED";

    public boolean deleted() {
        return STATUS_DELETED.equals(status);
    }
}
