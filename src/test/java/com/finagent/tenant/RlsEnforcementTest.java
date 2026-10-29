package com.finagent.tenant;

import com.finagent.security.CurrentUser;
import com.finagent.security.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 行级安全（RLS）是否真的生效。
 *
 * <p>为什么必须单独测：应用层的 SQL 里到处都写着 tenant_id 条件，即使把 RLS 关掉、
 * 甚至把策略写错，那些查询也照样返回「正确」结果 —— 于是 RLS 变成一层没人验证过的装饰。
 * 这里的每一条 SQL 都**故意不写** WHERE tenant_id，让数据库自己决定能看到什么。
 *
 * <p>rls profile 的两个前提（见 application-rls.yml）：
 * <ul>
 *   <li>运行时用户是 finagent_app（非 owner），否则 owner 会绕过 RLS；</li>
 *   <li>fallback-tenant-id = 0，让「没有上下文」真的返回 0 行。</li>
 * </ul>
 */
@Tag("db")
@SpringBootTest
@ActiveProfiles("rls")
class RlsEnforcementTest {

    @Autowired
    private JdbcTemplate jdbc;

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    @Test
    void runtimeUserIsNotTableOwner() {
        String currentUser = jdbc.queryForObject("SELECT current_user", String.class);
        // 如果这条断言炸了，说明 rls profile 没生效，下面的用例会全部变成假绿
        assertNotEquals("finagent", currentUser, "运行时连接用了 owner，RLS 会被绕过");
    }

    @Test
    void tenantOneSeesItsOwnDocuments() {
        asTenant(1L);
        // 故意不写 WHERE tenant_id
        Integer count = jdbc.queryForObject("SELECT count(*) FROM document", Integer.class);
        assertTrue(count != null && count >= 2, "租户 1 至少有 2 份文档，实际 " + count);
    }

    @Test
    void otherTenantSeesNothing() {
        asTenant(2L);
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM document", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM document_chunk", Integer.class));
    }

    @Test
    void noContextFailsClosed() {
        TenantContext.clear();
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM document", Integer.class));
    }

    @Test
    void cannotInsertIntoAnotherTenant() {
        asTenant(2L);
        // WITH CHECK 的作用：不是「看不见」别人，而是「连写都写不进去」
        assertThrows(DataAccessException.class, () -> jdbc.update("""
                INSERT INTO task_message (event_id, topic, message_key, document_id, tenant_id)
                VALUES (?, 'document.parse', '7', 7, 1)
                """, UUID.randomUUID().toString()));
    }

    private void asTenant(long tenantId) {
        TenantContext.set(new CurrentUser(99L, tenantId, "probe", "探测用户", Set.of("ADMIN")));
    }
}
