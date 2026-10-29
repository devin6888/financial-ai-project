package com.finagent.security;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 真库测试：token 哈希必须与 V8 种子里存的值一致。
 *
 * <p>这里同时是一道「迁移与代码口径一致」的守卫：如果哪天改了哈希算法（例如加盐、换编码），
 * 库里 64 位十六进制就对不上，三个 demo token 会全部登录失败 —— 这条测试会先炸。
 */
@Tag("db")
@SpringBootTest
@ActiveProfiles("test")
class ApiTokenRepositoryTest {

    @Autowired
    private ApiTokenRepository tokens;

    @Test
    void knownTokenResolvesToUserWithRoles() {
        Optional<CurrentUser> user = tokens.findByTokenHash(ApiTokenRepository.sha256Hex("dev-analyst-token"));
        assertTrue(user.isPresent(), "dev-analyst-token 应该能在库里查到");
        assertEquals("analyst", user.get().username());
        assertTrue(user.get().tenantId() > 0);
        assertEquals(Set.of("ANALYST"), user.get().roles());
        assertFalse(user.get().isAdmin());
    }

    @Test
    void unknownTokenResolvesToEmpty() {
        assertTrue(tokens.findByTokenHash(ApiTokenRepository.sha256Hex("not-a-real-token")).isEmpty());
        assertTrue(tokens.findByTokenHash("").isEmpty());
        assertTrue(tokens.findByTokenHash(null).isEmpty());
    }
}
