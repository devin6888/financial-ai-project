package com.finagent.security;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import jakarta.servlet.FilterChain;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 不启动 Spring：直接 new 出 Filter，用模拟请求验证四种分支。 */
class AuthenticationFilterTest {

    private final ApiTokenRepository tokens = mock(ApiTokenRepository.class);
    private final AuthenticationFilter filter = new AuthenticationFilter(tokens);

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    @Test
    void missingHeaderIs401() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean chained = new AtomicBoolean(false);

        filter.doFilter(Get("/api/chat"), response, (req, res) -> chained.set(true));

        assertEquals(401, response.getStatus());
        assertTrue(response.getContentAsString().contains("AUTH_REQUIRED"));
        assertTrue(!chained.get(), "没有凭证时不应该进入业务逻辑");
    }

    @Test
    void invalidTokenIs401() throws Exception {
        when(tokens.findByTokenHash(anyString())).thenReturn(Optional.empty());
        MockHttpServletRequest request = Get("/api/chat");
        request.addHeader("Authorization", "Bearer garbage");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (req, res) -> {
            throw new AssertionError("不应该放行");
        });

        assertEquals(401, response.getStatus());
        assertTrue(response.getContentAsString().contains("AUTH_INVALID"));
    }

    @Test
    void validTokenPopulatesContextAndClearsAfterwards() throws Exception {
        CurrentUser user = new CurrentUser(2L, 1L, "analyst", "演示分析师", Set.of("ANALYST"));
        when(tokens.findByTokenHash(ApiTokenRepository.sha256Hex("dev-analyst-token")))
                .thenReturn(Optional.of(user));
        MockHttpServletRequest request = Get("/api/chat");
        request.addHeader("Authorization", "Bearer dev-analyst-token");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> seen = new AtomicReference<>();

        filter.doFilter(request, response, (req, res) -> seen.set(TenantContext.require().username()));

        assertEquals("analyst", seen.get());
        assertNull(TenantContext.current(), "请求结束后必须清空，否则线程复用会串身份");
    }

    @Test
    void contextIsClearedEvenWhenChainThrows() {
        CurrentUser user = new CurrentUser(2L, 1L, "analyst", "演示分析师", Set.of("ANALYST"));
        when(tokens.findByTokenHash(anyString())).thenReturn(Optional.of(user));
        MockHttpServletRequest request = Get("/api/chat");
        request.addHeader("Authorization", "Bearer dev-analyst-token");

        FilterChain boom = (req, res) -> {
            throw new IllegalStateException("下游炸了");
        };
        assertThrows(IllegalStateException.class, () -> filter.doFilter(request, new MockHttpServletResponse(), boom));
        assertNull(TenantContext.current());
    }

    private static MockHttpServletRequest Get(String uri) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", uri);
        request.setRequestURI(uri);
        return request;
    }
}
