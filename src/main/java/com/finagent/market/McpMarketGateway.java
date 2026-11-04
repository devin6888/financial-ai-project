package com.finagent.market;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * 真调 MCP 子进程的实现。
 *
 * <p>三件事必须做对，否则线上就是「偶发查不到行情」：
 * <ol>
 *   <li><b>懒初始化</b>：{@code initialized=false}（见 application.yml），首次调用才 handshake。
 *       冷启动实测 3.6 秒，放启动路径会拖慢整个应用。</li>
 *   <li><b>失败冷却</b>：子进程起不来时每次请求都去 spawn 一遍，会把机器拖死。
 *       失败后 60 秒内直接抛 {@link MarketUnavailableException}。</li>
 *   <li><b>错误分流</b>：传输层失败 = 服务不可用（技术细节不能给用户看）；
 *       业务失败（{@code ok:false}）= 可以把服务端原话给用户看。</li>
 * </ol>
 */
public class McpMarketGateway implements MarketGateway {

    private static final Logger LOG = LoggerFactory.getLogger(McpMarketGateway.class);

    private static final ZoneId CN = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter ISO = DateTimeFormatter.ISO_OFFSET_DATE_TIME;
    private static final long COOLDOWN_MS = 60_000L;
    /** 工具清单缓存：服务端工具签名不会频繁变，但也不能永久缓存（换了 jar 之后进程还在跑）。 */
    private static final long TOOLS_TTL_MS = 300_000L;
    /** 拿不到清单时的短缓存，避免每个请求都往子进程走一趟 listTools。 */
    private static final long TOOLS_RETRY_MS = 60_000L;
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final McpSyncClient client;
    private final MarketProperties props;
    private final ObjectMapper mapper;

    /** 传输层失败后的冷却截止时间（毫秒时间戳）。 */
    private volatile long cooldownUntil;

    /** 上次成功拉到的工具清单；失败时保留旧值，宁可给旧的也不给空的。 */
    private volatile List<MarketToolSpec> toolsCache = List.of();
    private volatile long toolsExpireAt;

    public McpMarketGateway(McpSyncClient client, MarketProperties props, ObjectMapper mapper) {
        this.client = client;
        this.props = props;
        this.mapper = mapper;
    }

    /** 模型可选的工具白名单：{@code search_stock} 是内部工具，不在此列。 */
    private static final java.util.Set<String> SELECTABLE_TOOLS = java.util.Set.of("get_quote", "get_history");

    @Override
    public Snapshot call(String tool, String code, int days) {
        return execute(tool, arguments(tool, code, days), days);
    }

    @Override
    public Snapshot execute(String tool, Map<String, Object> arguments, int days) {
        if (!isKnownTool(tool)) {
            throw new MarketApiException("不支持的行情工具：" + tool);
        }
        ensureReady();
        McpSchema.CallToolResult result;
        try {
            result = client.callTool(new McpSchema.CallToolRequest(tool, arguments));
        } catch (RuntimeException e) {
            cooldown("调用行情工具失败：" + e.getMessage(), e);
            throw new MarketUnavailableException("行情服务调用失败：" + e.getMessage(), e);
        }
        Map<String, Object> body = payload(result, tool);
        return new Snapshot(body, asOf(body, tool));
    }

    @Override
    public List<Hit> search(String keyword) {
        if (keyword == null || keyword.isBlank()) {
            return List.of();
        }
        Snapshot snapshot;
        try {
            snapshot = execute("search_stock", Map.of("keyword", keyword.strip()), props.getDays());
        } catch (MarketApiException | MarketUnavailableException e) {
            LOG.warn("行情搜索失败（按查不到处理）：keyword={} reason={}", keyword, e.getMessage());
            return List.of();
        }
        Object items = snapshot.data().get("items");
        if (!(items instanceof List<?> list)) {
            return List.of();
        }
        List<Hit> hits = new ArrayList<>();
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> row)) {
                continue;
            }
            String code = string(row.get("code"), null);
            String name = string(row.get("name"), null);
            if (code != null && !code.isBlank()) {
                hits.add(new Hit(code, name == null ? "" : name));
            }
            if (hits.size() >= props.getSearchLimit()) {
                break;
            }
        }
        return hits;
    }

    /**
     * 从子进程拉工具签名，让 {@link MarketToolSelector} 用服务端的那一份。
     *
     * <p>为什么不缓存到启动时：握手是懒的（3.7 s），启动阶段可能还没就绪；放请求路径上又太贵，
     * 所以「首次用到时拉一次 + 5 分钟 TTL」。{@code search_stock} 在这里就被滤掉 ——
     * 模型不该看到它，那是 {@code com.finagent.agent.MarketCompanyResolver} 的内部步骤。
     */
    @Override
    public List<MarketToolSpec> tools() {
        long now = System.currentTimeMillis();
        List<MarketToolSpec> cached = toolsCache;
        if (!cached.isEmpty() && now < toolsExpireAt) {
            return cached;
        }
        try {
            ensureReady();
            McpSchema.ListToolsResult result = client.listTools();
            List<MarketToolSpec> specs = new ArrayList<>();
            if (result != null && result.tools() != null) {
                for (McpSchema.Tool tool : result.tools()) {
                    if (tool == null || !SELECTABLE_TOOLS.contains(tool.name())) {
                        continue;
                    }
                    specs.add(new MarketToolSpec(tool.name(), tool.description(), schemaOf(tool)));
                }
            }
            if (specs.isEmpty()) {
                LOG.debug("行情服务端没有声明可选工具，回落内置定义");
                toolsExpireAt = now + TOOLS_RETRY_MS;
                return cached.isEmpty() ? List.of() : cached;
            }
            toolsCache = List.copyOf(specs);
            toolsExpireAt = now + TOOLS_TTL_MS;
            LOG.debug("行情工具清单已刷新：{}", specs.stream().map(MarketToolSpec::name).toList());
            return toolsCache;
        } catch (RuntimeException e) {
            LOG.debug("拉取行情工具清单失败，沿用{}：{}", cached.isEmpty() ? "内置定义" : "上次结果", e.getMessage());
            toolsExpireAt = now + TOOLS_RETRY_MS;
            return cached;
        }
    }

    /** {@code inputSchema} 在 SDK 里是 Map，序列化成字符串就是 {@code ToolDefinition} 要的形态。 */
    private String schemaOf(McpSchema.Tool tool) {
        Map<String, Object> schema = tool.inputSchema();
        if (schema == null || schema.isEmpty()) {
            return "";
        }
        try {
            return mapper.writeValueAsString(schema);
        } catch (RuntimeException e) {
            LOG.debug("工具 {} 的 inputSchema 无法序列化：{}", tool.name(), e.getMessage());
            return "";
        }
    }

    private static boolean isKnownTool(String tool) {
        return "get_quote".equals(tool) || "get_history".equals(tool) || "search_stock".equals(tool);
    }

    private Map<String, Object> arguments(String tool, String code, int days) {
        if (tool == null || code == null || code.isBlank()) {
            throw new MarketApiException("缺少股票代码");
        }
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("code", code.strip());
        if ("get_history".equals(tool)) {
            args.put("days", clampDays(days));
        }
        return args;
    }

    /** 写死上限，避免模型给出 9999 天把上游接口打爆。 */
    static int clampDays(int days) {
        if (days <= 0) {
            return 20;
        }
        return Math.min(days, 120);
    }

    /**
     * 启动后异步握手：把 3.7 s 的冷启动挪出第一个用户请求。
     *
     * <p>失败只记 DEBUG —— 真失败时第一个请求会自己重试并走正常降级路径，
     * 启动日志里没必要打一条 ERROR 吓人。
     */
    @org.springframework.context.event.EventListener(org.springframework.boot.context.event.ApplicationReadyEvent.class)
    public void warmUp() {
        Thread thread = new Thread(() -> {
            try {
                ensureReady();
            } catch (RuntimeException e) {
                LOG.debug("预热行情 MCP 失败（等首个请求再试）：{}", e.getMessage());
            }
        }, "market-mcp-warmup");
        thread.setDaemon(true);
        thread.start();
    }

    private void ensureReady() {
        if (System.currentTimeMillis() < cooldownUntil) {
            throw new MarketUnavailableException("行情服务刚失败过，冷却中");
        }
        if (client.isInitialized()) {
            return;
        }
        synchronized (this) {
            if (client.isInitialized()) {
                return;
            }
            try {
                client.initialize();
                LOG.info("行情 MCP 子进程握手完成：{}", client.getServerInfo() == null ? "unknown" : client.getServerInfo().name());
            } catch (RuntimeException e) {
                cooldown("行情 MCP 初始化失败：" + e.getMessage(), e);
                throw new MarketUnavailableException("行情服务启动失败：" + e.getMessage(), e);
            }
        }
    }

    private void cooldown(String message, Throwable cause) {
        cooldownUntil = System.currentTimeMillis() + COOLDOWN_MS;
        LOG.error("{}（{} 秒内不再尝试）", message, COOLDOWN_MS / 1000, cause);
    }

    /**
     * 先看 {@code structuredContent}（MCP 有 outputSchema 时的结构化结果），
     * 再回落解析第一个 TextContent 的 JSON 文本。两条路都走不通才当失败。
     */
    private Map<String, Object> payload(McpSchema.CallToolResult result, String tool) {
        if (Boolean.TRUE.equals(result.isError())) {
            throw new MarketUnavailableException("行情工具执行失败：" + text(result));
        }
        Map<String, Object> body = null;
        Object structured = result.structuredContent();
        if (structured instanceof Map<?, ?> map) {
            body = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (entry.getKey() != null) {
                    body.put(String.valueOf(entry.getKey()), entry.getValue());
                }
            }
        }
        if (body == null || body.isEmpty()) {
            body = parse(text(result));
        }
        if (body == null) {
            throw new MarketUnavailableException("行情工具没有返回可解析的结果：" + tool);
        }
        if (Boolean.FALSE.equals(body.get("ok"))) {
            throw new MarketApiException(string(body.get("error"), "行情工具返回失败：" + tool));
        }
        return body;
    }

    private Map<String, Object> parse(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            return mapper.readValue(text, MAP_TYPE);
        } catch (RuntimeException e) {
            LOG.warn("行情工具返回的不是 JSON：{}", abbreviate(text));
            return null;
        }
    }

    private static String text(McpSchema.CallToolResult result) {
        if (result.content() == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (McpSchema.Content content : result.content()) {
            if (content instanceof McpSchema.TextContent textContent && textContent.text() != null) {
                if (!sb.isEmpty()) {
                    sb.append('\n');
                }
                sb.append(textContent.text());
            }
        }
        return sb.toString();
    }

    /**
     * 数据时间：快照用 {@code tradedAt}；历史用最后一根 K 线的日期（收盘 15:00）。
     * 都拿不到就按当前时间兜底并 WARN —— 答案里没有时间戳的行情是不可用的。
     */
    private String asOf(Map<String, Object> body, String tool) {
        if ("get_history".equals(tool)) {
            Object bars = body.get("bars");
            if (bars instanceof List<?> list && !list.isEmpty()) {
                Object last = list.get(list.size() - 1);
                if (last instanceof Map<?, ?> bar) {
                    String date = string(bar.get("date"), null);
                    if (date != null && !date.isBlank()) {
                        return date + "T15:00:00+08:00";
                    }
                }
            }
        }
        String tradedAt = string(body.get("tradedAt"), null);
        if (tradedAt != null && !tradedAt.isBlank()) {
            return tradedAt;
        }
        if (props.isWarnMissingAsOf()) {
            LOG.warn("行情返回缺少数据时间，使用当前时间兜底：tool={}", tool);
        }
        return OffsetDateTime.now(CN).format(ISO);
    }

    private static String string(Object value, String fallback) {
        if (value == null) {
            return fallback;
        }
        String text = String.valueOf(value);
        return text.isBlank() ? fallback : text;
    }

    private static String abbreviate(String text) {
        return text.length() <= 200 ? text : text.substring(0, 200) + "...";
    }
}
