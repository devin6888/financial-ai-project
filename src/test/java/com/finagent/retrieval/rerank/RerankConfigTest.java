package com.finagent.retrieval.rerank;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.StandardEnvironment;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 装配的唯一性验证 —— 无论有没有 key、key 是不是空的，都必须<b>恰好一个</b> RerankModel。
 *
 * <p>用 ApplicationContextRunner 而不是启动整个应用：这是 Spring Boot 官方推荐的
 * 「只测这块自动配置」的姿势，不需要数据库、几毫秒一个用例。
 */
class RerankConfigTest {

    private final ApplicationContextRunner runner =
            new ApplicationContextRunner().withUserConfiguration(RerankConfig.class);

    /**
     * 去掉系统环境变量和系统属性。
     *
     * <p><b>必须这么做</b>：本机 .env 加载后 FINAGENT_RERANK_API_KEY 就在进程环境里，
     * 而 ApplicationContextRunner 的环境包含 systemEnvironment —— 不隔离的话
     * 「没有 key」这一格会被环境变量悄悄填上，测试变成假绿（第一次就是这么翻车的）。
     */
    private ApplicationContextRunner isolated() {
        return runner.withInitializer(ctx -> {
            MutablePropertySources sources = ctx.getEnvironment().getPropertySources();
            sources.remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
            sources.remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
        });
    }

    @Test
    void withoutApiKeyTheFallbackIsNoop() {
        isolated().run(ctx -> {
            assertThat(ctx).hasSingleBean(RerankModel.class);
            assertThat(ctx.getBean(RerankModel.class)).isInstanceOf(NoopRerankModel.class);
        });
    }

    @Test
    void withApiKeyTheRealModelIsUsed() {
        isolated().withPropertyValues("finagent.rerank.api-key=sk-test")
                .run(ctx -> {
                    assertThat(ctx).hasSingleBean(RerankModel.class);
                    assertThat(ctx.getBean(RerankModel.class)).isInstanceOf(DashScopeRerankModel.class);
                    assertThat(ctx.getBean(RerankModel.class).modelName()).isEqualTo("qwen3-rerank");
                });
    }

    @Test
    void blankApiKeyCountsAsNotConfigured() {
        // 这一格正是「成对 ConditionalOnProperty」会翻车的地方：两个条件同时命中 -> 2 个 Bean
        isolated().withPropertyValues("finagent.rerank.api-key=")
                .run(ctx -> {
                    assertThat(ctx).hasSingleBean(RerankModel.class);
                    assertThat(ctx.getBean(RerankModel.class)).isInstanceOf(NoopRerankModel.class);
                });
    }

    @Test
    void overridesAreBindable() {
        isolated().withPropertyValues(
                        "finagent.rerank.api-key=sk-test",
                        "finagent.rerank.model=my-rerank",
                        "finagent.rerank.base-url=https://example.invalid",
                        "finagent.rerank.read-timeout=3s")
                .run(ctx -> {
                    assertThat(ctx.getBean(RerankModel.class).modelName()).isEqualTo("my-rerank");
                    RerankProperties props = ctx.getBean(RerankProperties.class);
                    assertThat(props.getBaseUrl()).isEqualTo("https://example.invalid");
                    assertThat(props.getReadTimeout()).isEqualTo(Duration.ofSeconds(3));
                });
    }
}
