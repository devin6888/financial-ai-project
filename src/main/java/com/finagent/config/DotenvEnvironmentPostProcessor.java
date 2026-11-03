package com.finagent.config;

import org.apache.commons.logging.Log;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.logging.DeferredLogFactory;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 把项目根目录的 `.env` 读进 {@link org.springframework.core.env.Environment}。
 *
 * <p><b>为什么需要它</b>：命令行跑之前要先 `. .\scripts\load-env.ps1`，凭据才进进程环境；
 * 而在 IDEA 里直接点「运行 FinAgentApplication」不会经过那个脚本，于是
 * `spring.ai.openai.api-key` 为空，Spring AI 2.0 的 `OpenAiEmbeddingModel` 在装配阶段就抛：
 * <pre>
 * At least one credential source must be specified: credential (apiKey),
 * workloadIdentity, or adminApiKey
 * </pre>
 * 这个报错只说「缺凭据」，不说「缺哪个变量、去哪配」，第一次遇到很难定位。
 *
 * <p><b>三条设计约束</b>：
 * <ol>
 *   <li><b>优先级最低</b>：用 {@code addLast} 追加，真实环境变量（CI / 容器注入）与
 *       `application.yml` 都优先于 `.env` —— 提交在仓库里的配置不该被本地文件悄悄覆盖；</li>
 *   <li><b>按环境变量的规则解析</b>：{@link SystemEnvironmentPropertySource} 会把
 *       `spring.ai.openai.api-key` 的查找翻译成 `SPRING_AI_OPENAI_API_KEY`，
 *       所以 `.env` 里写的是环境变量名，Spring 里读的是标准属性名，两者不必改写法；</li>
 *   <li><b>没有文件就静默跳过</b>：生产部署不依赖 `.env`，缺文件不是错误。</li>
 * </ol>
 *
 * <p>注册方式：`src/main/resources/META-INF/spring.factories` 里的
 * `org.springframework.boot.EnvironmentPostProcessor`。注意 Boot 4 有两个同名接口：
 * `org.springframework.boot.EnvironmentPostProcessor`（本类实现的这个）与
 * `org.springframework.boot.env.EnvironmentPostProcessor`（旧的，仍存在但属兼容层）。
 * 工厂键名与接口全限定名必须一致，否则 Boot 会抛
 * `Class [...] is not assignable to factory type [...]`。
 */
public class DotenvEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

    /** 属性源名（排查冲突时会在日志里看到）。 */
    public static final String SOURCE_NAME = "dotenvFile";

    /** 允许显式指定文件位置，主要给测试用；不指定时按 {@link #locate()} 的规则找。 */
    public static final String PATH_PROPERTY = "finagent.dotenv.path";

    private static final String FILE_NAME = ".env";

    /**
     * 必须是 {@link DeferredLogFactory} 给的 Log，不能自己 {@code LoggerFactory.getLogger(...)}：
     * EnvironmentPostProcessor 跑在 LoggingApplicationListener **之前**，此时日志系统还没初始化，
     * 直接打 INFO 会被丢弃（实测：真启动时那条「已从 … 加载 N 个变量」一条都看不到）。
     * DeferredLog 会先把日志缓存住，等日志系统初始化后统一回放。
     *
     * <p>同一个原因，这个类**只能有这一个构造器**：{@code Instantiator} 按构造器参数类型注入，
     * 多一个无参构造器就可能被它选中，于是拿到一个不会回放的普通 Log。
     */
    public DotenvEnvironmentPostProcessor(DeferredLogFactory logFactory) {
        this.log = logFactory.getLog(DotenvEnvironmentPostProcessor.class);
    }

    private final Log log;

    /** 最后一个执行：要等 ConfigData 把 application.yml 装进 Environment，才谈得上「谁优先」。 */
    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        Path file = locate();
        if (file == null) {
            log.debug("未找到 " + FILE_NAME + "，跳过（生产环境请用真实环境变量注入凭据）");
            return;
        }
        Map<String, Object> values;
        try {
            values = parse(Files.readAllLines(file, StandardCharsets.UTF_8));
        } catch (IOException e) {
            log.warn("读取 " + file + " 失败，跳过：" + e);
            return;
        }
        if (values.isEmpty()) {
            return;
        }

        environment.getPropertySources()
                .addLast(new SystemEnvironmentPropertySource(SOURCE_NAME, values));
        log.info("已从 " + file.toAbsolutePath().normalize() + " 加载 " + values.size()
                + " 个变量（优先级最低：真实环境变量与 application.yml 仍然优先）");

        if (isBlank(environment.getProperty("spring.ai.openai.api-key"))) {
            // 提前把「该配哪个键」说清楚，别让用户去读 Spring AI 的装配异常
            log.warn("spring.ai.openai.api-key 为空：请在 " + file.toAbsolutePath().normalize()
                    + " 里写 SPRING_AI_OPENAI_API_KEY=sk-...，或用环境变量 / 启动参数提供");
        }
    }

    /** 找 `.env`：优先 `finagent.dotenv.path`，其次工作目录，再退到上一级（IDE 有时以模块目录为工作目录）。 */
    static Path locate() {
        String explicit = System.getProperty(PATH_PROPERTY);
        if (explicit != null && !explicit.isBlank()) {
            Path p = Paths.get(explicit.trim());
            return Files.isReadable(p) ? p : null;
        }
        Path cwd = Paths.get("").toAbsolutePath();
        for (Path dir : List.of(cwd, cwd.getParent() == null ? cwd : cwd.getParent())) {
            Path candidate = dir.resolve(FILE_NAME);
            if (Files.isReadable(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    /**
     * 解析规则与 {@code scripts/load-env.ps1} 保持一致，避免「命令行能跑、IDE 不能跑」：
     * 跳过空行与 `#` 注释；按**第一个** `=` 切分（值里可以再有 `=`）；去掉成对引号与首尾空白。
     */
    static Map<String, Object> parse(List<String> lines) {
        Map<String, Object> values = new LinkedHashMap<>();
        for (String raw : lines) {
            if (raw == null) {
                continue;
            }
            String line = raw.strip();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            int idx = line.indexOf('=');
            if (idx < 1) {
                continue;
            }
            String name = line.substring(0, idx).strip();
            String value = unquote(line.substring(idx + 1).strip());
            if (!name.isEmpty()) {
                values.put(name, value);
            }
        }
        return values;
    }

    private static String unquote(String value) {
        if (value.length() >= 2) {
            char first = value.charAt(0);
            char last = value.charAt(value.length() - 1);
            if ((first == '"' && last == '"') || (first == '\'' && last == '\'')) {
                return value.substring(1, value.length() - 1);
            }
        }
        return value;
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
