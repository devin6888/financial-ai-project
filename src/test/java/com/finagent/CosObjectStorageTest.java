package com.finagent;

import com.finagent.storage.ObjectStorage;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 腾讯云 COS 连通性测试。
 *
 * <p>未配置 COS 凭据时整个类被跳过（不会创建 Spring 上下文），
 * 因此它不会影响没有凭据的机器。
 */
@Tag("db")
@SpringBootTest
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named = "FINAGENT_COS_SECRET_ID", matches = ".+")
class CosObjectStorageTest {

    @Autowired
    private ObjectStorage objectStorage;

    @Test
    void putGetExistsDeleteRoundTrip() {
        String key = "test/" + UUID.randomUUID() + ".txt";
        String content = "金融研报 Agent COS 连通性测试";

        objectStorage.put(key, content.getBytes(StandardCharsets.UTF_8), "text/plain; charset=utf-8");
        assertThat(objectStorage.exists(key)).isTrue();
        assertThat(new String(objectStorage.get(key), StandardCharsets.UTF_8)).isEqualTo(content);

        String url = objectStorage.presignedUrl(key, Duration.ofMinutes(10));
        assertThat(url).startsWith("http");

        objectStorage.delete(key);
        assertThat(objectStorage.exists(key)).isFalse();
    }
}
