package com.finagent.security;

import java.util.Optional;

/**
 * 请求级租户上下文。
 *
 * <p>写入方：{@link AuthenticationFilter}；清理方：同一个 Filter 的 finally。
 *
 * <p><b>Kafka listener 线程、CLI（ReindexRunner）、定时任务里没有上下文</b> ——
 * 这不是缺陷，而是边界：这些路径的 tenant 必须从「所属文档行」推导
 * （见 {@code ChunkPersister} / {@code TaskMessageRepository}），不能读 ThreadLocal。
 */
public final class TenantContext {

    private static final ThreadLocal<CurrentUser> CURRENT = new ThreadLocal<>();

    private TenantContext() {
    }

    public static void set(CurrentUser user) {
        CURRENT.set(user);
    }

    public static CurrentUser current() {
        return CURRENT.get();
    }

    public static Optional<CurrentUser> optional() {
        return Optional.ofNullable(CURRENT.get());
    }

    /** 需要身份的路径一律走这里，不要自己判 null —— 少了身份应该 401，而不是继续跑。 */
    public static CurrentUser require() {
        CurrentUser user = CURRENT.get();
        if (user == null) {
            throw new UnauthorizedException("AUTH_REQUIRED", "缺少认证上下文");
        }
        return user;
    }

    public static void clear() {
        CURRENT.remove();
    }
}
