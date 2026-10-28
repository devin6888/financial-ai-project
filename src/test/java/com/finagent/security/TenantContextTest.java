package com.finagent.security;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** TenantContext 的三条不变量：没设就抛 401、设了能取回、清掉后干净。 */
class TenantContextTest {

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    @Test
    void requireWithoutContextThrows401() {
        UnauthorizedException e = assertThrows(UnauthorizedException.class, TenantContext::require);
        assertEquals("AUTH_REQUIRED", e.code());
    }

    @Test
    void setThenRequireReturnsSameUser() {
        CurrentUser u = new CurrentUser(2L, 1L, "analyst", "演示分析师", Set.of("ANALYST"));
        TenantContext.set(u);
        assertSame(u, TenantContext.require());
        assertTrue(TenantContext.optional().isPresent());
        assertTrue(u.hasRole("ANALYST"));
        assertEquals(false, u.isAdmin());
    }

    @Test
    void clearRemovesContext() {
        TenantContext.set(new CurrentUser(2L, 1L, "analyst", "演示分析师", Set.of("ANALYST")));
        TenantContext.clear();
        assertNull(TenantContext.current());
        assertTrue(TenantContext.optional().isEmpty());
    }
}
