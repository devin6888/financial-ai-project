package com.finagent.security;

import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;

/**
 * 登录失败节流：同一「用户名 + 来源 IP」在窗口内失败太多次就直接拒绝。
 *
 * <p>放在内存里而不是库里，是因为它保护的是「暴力猜密码」这类高频攻击：
 * 攻击者本来就在打数据库，把计数也写库等于帮他放大负载。代价是重启后计数清零、
 * 多副本之间不共享 —— 对一个单机部署的演示系统可以接受，写进已知限制。
 */
@Component
public class LoginThrottle {

    private record Window(int failures, long resetAtMillis) {
    }

    private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();
    private final AuthProperties props;

    public LoginThrottle(AuthProperties props) {
        this.props = props;
    }

    public static String key(String username, String ip) {
        return (username == null ? "" : username.trim().toLowerCase()) + "|" + (ip == null ? "" : ip);
    }

    public boolean isBlocked(String key) {
        Window window = windows.get(key);
        if (window == null) {
            return false;
        }
        if (System.currentTimeMillis() >= window.resetAtMillis()) {
            windows.remove(key, window);
            return false;
        }
        return window.failures() >= props.maxLoginFailures();
    }

    public void recordFailure(String key) {
        long now = System.currentTimeMillis();
        long resetAt = now + props.failureWindowMinutes() * 60_000L;
        windows.compute(key, (k, old) -> old == null || now >= old.resetAtMillis()
                ? new Window(1, resetAt)
                : new Window(old.failures() + 1, old.resetAtMillis()));
    }

    /** 登录成功要清掉：否则「输错几次后终于输对」会被前面的失败拖住。 */
    public void reset(String key) {
        windows.remove(key);
    }

    /** 仅供测试与排查：当前在窗口内的 key 数量。 */
    int trackedKeys() {
        return windows.size();
    }
}
