package com.finagent.security;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 会话 token：登录签发、每次请求校验、登出撤销。
 *
 * <p>与 {@link ApiTokenRepository}（预置 token）分开两张表，是因为两者生命周期不同：
 * 预置 token 是「人肉写进库里的长期凭证」，会话 token 是「登录一次、可撤销、会过期」的。
 * 合在一列会丢掉「什么时候签发的 / 什么时候撤销的」这两条审计信息。
 *
 * <p>库里只存 SHA-256：明文只在签发那一刻存在内存与响应体里。
 */
@Repository
public class ApiSessionRepository {

    private static final SecureRandom RANDOM = new SecureRandom();

    private static final String FIND_SQL = """
            SELECT u.id AS user_id, u.tenant_id, u.username, u.display_name, u.status, r.code
              FROM api_session s
              JOIN app_user u ON u.id = s.user_id
              LEFT JOIN user_role ur ON ur.user_id = u.id
              LEFT JOIN role r ON r.id = ur.role_id
             WHERE s.token_hash = ?
               AND s.revoked_at IS NULL
               AND s.expires_at > now()
            """;

    private final JdbcTemplate jdbc;

    public ApiSessionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 签发结果：明文 token 只在这里出现一次，调用方必须立刻返回给客户端。 */
    public record IssuedSession(String token, OffsetDateTime expiresAt) {
    }

    /** 32 字节随机 → base64url（无填充）→ 43 个字符；熵足够，不需要再加用户 id 前缀。 */
    public IssuedSession issue(long userId, long tenantId, String label, int ttlHours) {
        byte[] raw = new byte[32];
        RANDOM.nextBytes(raw);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        OffsetDateTime expiresAt = jdbc.queryForObject("""
                INSERT INTO api_session (user_id, tenant_id, token_hash, label, expires_at)
                VALUES (?, ?, ?, ?, now() + make_interval(hours => ?))
                RETURNING expires_at
                """, OffsetDateTime.class,
                userId, tenantId, ApiTokenRepository.sha256Hex(token), label, ttlHours);
        return new IssuedSession(token, expiresAt);
    }

    /** 查不到、已撤销、已过期、用户被停用 —— 全部返回 empty（对外统一 AUTH_INVALID）。 */
    public Optional<CurrentUser> findByTokenHash(String hexHash) {
        if (hexHash == null || hexHash.isBlank()) {
            return Optional.empty();
        }
        List<Row> rows = jdbc.query(FIND_SQL, (rs, i) -> new Row(
                rs.getLong("user_id"),
                rs.getLong("tenant_id"),
                rs.getString("username"),
                rs.getString("display_name"),
                rs.getString("status"),
                rs.getString("code")), hexHash);
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        Row first = rows.get(0);
        if (!"ACTIVE".equals(first.status())) {
            return Optional.empty();
        }
        Set<String> roles = new LinkedHashSet<>();
        for (Row row : rows) {
            if (row.roleCode() != null) {
                roles.add(row.roleCode());
            }
        }
        return Optional.of(new CurrentUser(first.userId(), first.tenantId(), first.username(), first.displayName(), roles));
    }

    /**
     * 记录「这个 token 刚被用过」。
     *
     * <p>每 5 分钟才真正写一次：每个请求一条 UPDATE 对演示库无所谓，但这是没有收益的写放大，
     * 而 last_used_at 的用途只是「排查这个 token 还在不在用」，分钟级精度足够。
     */
    public void touch(String hexHash) {
        jdbc.update("""
                UPDATE api_session SET last_used_at = now()
                 WHERE token_hash = ?
                   AND (last_used_at IS NULL OR last_used_at < now() - interval '5 minutes')
                """, hexHash);
    }

    /** 登出：软撤销，不物理删（保留行才能回答「这个 token 什么时候被撤销的」）。 */
    public int revoke(String hexHash) {
        return jdbc.update("""
                UPDATE api_session SET revoked_at = now()
                 WHERE token_hash = ? AND revoked_at IS NULL
                """, hexHash);
    }

    /** 一次查询的中间结果：一个用户有几行（每个角色一行）。 */
    private record Row(long userId, long tenantId, String username, String displayName,
                       String status, String roleCode) {
    }
}
