package com.finagent.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.logging.DeferredLogFactory;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * `.env` 自动加载的行为约定。
 *
 * <p>三个必须被证明的点，否则这个类只是「看起来能跑」：
 * <ol>
 *   <li><b>注册生效</b>：`META-INF/spring.factories` 的条目真的会被 Boot 4 读出来并执行 ——
 *       所以有一条用例走真实的 {@link SpringApplicationBuilder}；</li>
 *   <li><b>名字能对上</b>：`.env` 写的是环境变量名（`SPRING_AI_OPENAI_API_KEY`），
 *       代码读的是标准属性名（`spring.ai.openai.api-key`），靠
 *       {@code SystemEnvironmentPropertySource} 的宽松转换接上；</li>
 *   <li><b>优先级最低</b>：真实环境变量与命令行参数必须压过 `.env`。</li>
 * </ol>
 *
 * <p>注意：凡是断言「`.env` 的值生效」的用例，都不能用跑着 surefire 的那个 JVM 的真实环境变量 ——
 * 本机按 README 先执行了 `load-env.ps1`，`SPRING_AI_OPENAI_API_KEY` 已经在 SYSTEM_ENVIRONMENT 里，
 * 会合法地压过 `.env`。所以这类用例用干净的 {@link StandardEnvironment} 直接调用 EPP。
 */
class DotenvEnvironmentPostProcessorTest {

    @TempDir
    Path dir;

    @Configuration
    static class Noop {
    }

    /** 生产里由 Boot 注入；单测直接给一个「立刻输出」的实现。 */
    private static final DeferredLogFactory LOGS = supplier -> supplier.get();

    @Test
    void parsesLikeThePowerShellLoader() {
        Map<String, Object> values = DotenvEnvironmentPostProcessor.parse(List.of(
                "# 注释",
                "",
                "   ",
                "A=1",
                "B =  2  ",
                "C=\"quoted value\"",
                "D='single'",
                "E=has=equals=inside",
                "F=",
                "G=dev/",
                "no-equals-line",
                "=nameless"));

        assertThat(values)
                .containsEntry("A", "1")
                .containsEntry("B", "2")
                .containsEntry("C", "quoted value")
                .containsEntry("D", "single")
                .containsEntry("E", "has=equals=inside")
                .containsEntry("F", "")
                .containsEntry("G", "dev/");
        assertThat(values).doesNotContainKeys("no-equals-line", "", "nameless");
    }

    @Test
    void handlesWindowsLineEndings() {
        // Files.readAllLines 会吃掉 CRLF，这里直接喂带 \r 的行，确认 strip 生效
        Map<String, Object> values = DotenvEnvironmentPostProcessor.parse(List.of("KEY=v\r"));
        assertThat(values).containsEntry("KEY", "v");
    }

    @Test
    void exposesEnvNamesAsSpringPropertyNames() {
        withPath(write("""
                SPRING_AI_OPENAI_API_KEY=sk-test-key
                FINAGENT_RERANK_API_KEY=rerank-key
                FINAGENT_COS_KEY_PREFIX=dev/
                """));
        try {
            StandardEnvironment env = cleanEnvironment();
            new DotenvEnvironmentPostProcessor(LOGS).postProcessEnvironment(env, null);

            assertThat(env.getProperty("spring.ai.openai.api-key")).isEqualTo("sk-test-key");
            assertThat(env.getProperty("finagent.rerank.api-key")).isEqualTo("rerank-key");
            assertThat(env.getProperty("finagent.cos.key-prefix")).isEqualTo("dev/");
            // 最低优先级：追加在最后，而不是插到最前
            PropertySource<?> last = env.getPropertySources().stream()
                    .reduce((first, second) -> second)
                    .orElseThrow();
            assertThat(last.getName()).isEqualTo(DotenvEnvironmentPostProcessor.SOURCE_NAME);
        } finally {
            clearPath();
        }
    }

    @Test
    void neverOverridesHigherPrecedenceSources() {
        withPath(write("""
                SPRING_AI_OPENAI_API_KEY=from-dotenv
                """));
        try {
            StandardEnvironment env = cleanEnvironment();
            env.getPropertySources().addFirst(new MapPropertySource("cliArgs",
                    Map.of("spring.ai.openai.api-key", "from-cli")));
            new DotenvEnvironmentPostProcessor(LOGS).postProcessEnvironment(env, null);

            assertThat(env.getProperty("spring.ai.openai.api-key")).isEqualTo("from-cli");
        } finally {
            clearPath();
        }
    }

    @Test
    void missingFileIsNotAnError() {
        withPath(dir.resolve("nope.env"));
        try {
            StandardEnvironment env = cleanEnvironment();
            new DotenvEnvironmentPostProcessor(LOGS).postProcessEnvironment(env, null);

            assertThat(env.getPropertySources().contains(DotenvEnvironmentPostProcessor.SOURCE_NAME)).isFalse();
        } finally {
            clearPath();
        }
    }

    /** 唯一一条走真实 SpringApplication 的用例：证明 spring.factories 注册被 Boot 4 认。 */
    @Test
    void isRegisteredThroughSpringFactories() {
        withPath(write("""
                FINAGENT_DOTENV_TEST_KEY=hello-from-dotenv
                """));
        try (ConfigurableApplicationContext ctx = new SpringApplicationBuilder(Noop.class)
                .web(WebApplicationType.NONE)
                .run()) {
            assertThat(ctx.getEnvironment().getPropertySources()
                    .contains(DotenvEnvironmentPostProcessor.SOURCE_NAME)).isTrue();
            assertThat(ctx.getEnvironment().getProperty("finagent.dotenv.test-key"))
                    .isEqualTo("hello-from-dotenv");
        } finally {
            clearPath();
        }
    }

    /**
     * 去掉两个系统属性源，得到「什么凭据都没有」的干净环境：
     * 跑测试的这个 JVM 里 `SPRING_AI_OPENAI_API_KEY` 早已由 load-env.ps1 注入，
     * 它会合法地压过 `.env`，让断言变成假绿/假红。
     */
    private static StandardEnvironment cleanEnvironment() {
        StandardEnvironment env = new StandardEnvironment();
        env.getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        env.getPropertySources().remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
        return env;
    }

    private void withPath(Path file) {
        System.setProperty(DotenvEnvironmentPostProcessor.PATH_PROPERTY, file.toString());
    }

    private void clearPath() {
        System.clearProperty(DotenvEnvironmentPostProcessor.PATH_PROPERTY);
    }

    private Path write(String content) {
        try {
            Path file = dir.resolve(".env");
            Files.writeString(file, content, StandardCharsets.UTF_8);
            return file;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
