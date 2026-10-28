package com.finagent.security;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * token → 用户。
 *
 * <p>库里只存 SHA-256(token) 的十六进制串，不存明文：即使库被拖走，也拿不到可用的 token。
 * 一条 JOIN 把用户与角色一起拿出来，避免「先查用户再查角色」的两次往返。
 */
@Repository
public class ApiTokenRepository {

    private static final String SQL = """
            SELECT u.id AS user_id, u.tenant_id, u.username, u.display_name, u.status, r.code
              FROM app_user u
              LEFT JOIN user_role ur ON ur.user_id = u.id
              LEFT JOIN role r ON r.id = ur.role_id
             WHERE u.api_token_hash = ?
            """;

    private final JdbcTemplate jdbc;

    public ApiTokenRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 查不到、或用户已被停用，都返回 empty（对外一律 AUTH_INVALID，不泄漏「用户存在」）。 */
    public Optional<CurrentUser> findByTokenHash(String hexHash) {
        if (hexHash == null || hexHash.isBlank()) {
            return Optional.empty();
        }
        List<Row> rows = jdbc.query(SQL, (rs, i) -> new Row(
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

    /** 一次查询的中间结果：一个用户有几行（每个角色一行）。 */
    private record Row(long userId, long tenantId, String username, String displayName,
                       String status, String roleCode) {
    }

    /** 给测试与「签发 token」用：与库里的存储口径保持一致（UTF-8 字节的 SHA-256 十六进制）。 */
    public static String sha256Hex(String raw) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(Objects.requireNonNull(raw, "raw").getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("JDK 必须支持 SHA-256", e);
        }
    }
}
