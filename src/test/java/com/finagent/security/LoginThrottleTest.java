package com.finagent.security;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 纯内存计数：不启动 Spring，也不碰数据库。 */
class LoginThrottleTest {

    private static LoginThrottle throttle(int maxFailures, int windowMinutes) {
        return new LoginThrottle(new AuthProperties(168, maxFailures, windowMinutes));
    }

    @Test
    void blockedOnlyAfterReachingTheLimit() {
        LoginThrottle t = throttle(3, 15);
        String key = LoginThrottle.key("demo", "127.0.0.1");

        assertFalse(t.isBlocked(key));
        t.recordFailure(key);
        t.recordFailure(key);
        assertFalse(t.isBlocked(key), "还没到上限不应拦");
        t.recordFailure(key);
        assertTrue(t.isBlocked(key), "达到上限即拦（含第 N 次）");
    }

    @Test
    void successResetsTheCounter() {
        LoginThrottle t = throttle(2, 15);
        String key = LoginThrottle.key("demo", "127.0.0.1");

        t.recordFailure(key);
        t.recordFailure(key);
        assertTrue(t.isBlocked(key));
        t.reset(key);
        assertFalse(t.isBlocked(key), "输对一次要能立刻解除，否则用户被自己前面的错误拖住");
    }

    @Test
    void expiredWindowDoesNotBlock() throws Exception {
        // 窗口设为 0 分钟：记录后立刻过期，等价于「窗口已滑过去」
        LoginThrottle t = throttle(1, 0);
        String key = LoginThrottle.key("demo", "127.0.0.1");

        t.recordFailure(key);
        assertFalse(t.isBlocked(key));
    }

    @Test
    void keysAreScopedByUsernameAndIp() {
        LoginThrottle t = throttle(1, 15);
        t.recordFailure(LoginThrottle.key("demo", "10.0.0.1"));

        assertTrue(t.isBlocked(LoginThrottle.key("demo", "10.0.0.1")));
        assertFalse(t.isBlocked(LoginThrottle.key("demo", "10.0.0.2")), "换 IP 不应受限（同一账号仍受限）");
        assertFalse(t.isBlocked(LoginThrottle.key("other", "10.0.0.1")), "换账号不应受限");
        assertEquals(1, t.trackedKeys());
    }

    @Test
    void usernameComparisonIsCaseInsensitive() {
        LoginThrottle t = throttle(1, 15);
        t.recordFailure(LoginThrottle.key("DEMO", "127.0.0.1"));

        assertTrue(t.isBlocked(LoginThrottle.key("demo", "127.0.0.1")), "大小写不能绕过节流");
    }
}
