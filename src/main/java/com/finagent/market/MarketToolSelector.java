package com.finagent.market;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * 让模型自己选工具、自己填参数，<b>但不由框架执行</b>。
 *
 * <p>为什么不直接用 Spring AI 的自动工具执行：执行点在 {@code ToolCallingAdvisor} 里，
 * 它拿到 {@code toolCalls} 会立刻调用工具并把结果再喂回模型，一次往返变成两次，
 * 行情数字还要经过一次模型转述 —— 数字必须逐字准确，所以我们自己执行、自己渲染。
 * 这里直接调 {@link ChatModel}（不经过 {@code ChatClient}），模型返回的 toolCalls 原样留给我们。
 *
 * <p>工具清单以服务端为准（{@link MarketGateway#tools()}），只在两种情况下用内置定义：
 * 服务端还没握手成功、服务端没声明可用工具。内置定义与 Python/Java 两个 MCP Server 的
 * 签名保持一致，但不再承担「事实来源」的职责。
 *
 * <p>选不出来（返回空、参数非法、模型报错）都返回 {@link Optional#empty()}，
 * 由调用方回落到规则调用 —— 模型只是增强路径，不能是唯一路径。
 */
@Component
public class MarketToolSelector {

    private static final Logger LOG = LoggerFactory.getLogger(MarketToolSelector.class);
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private static final String SYSTEM = """
            你是行情查询的调度器。根据用户问题，从给定工具中选择一个并填好参数。
            规则：
            1. 只查 A 股；用户没给股票代码时不要编造代码。
            2. 问最新价、涨跌幅、市值、市盈率、市净率、换手率、成交额 → get_quote。
            3. 问最近走势、连涨连跌、近 N 天表现、K 线 → get_history（days 取用户说的天数，未说则不填）。
            4. 只调一个工具，不要解释。
            """;

    private static final Map<String, String> BUILTIN_DESCRIPTIONS = Map.of(
            "get_quote", "查一只 A 股的实时快照：最新价、涨跌额/涨跌幅、今开/昨收/最高/最低、总市值与流通市值（元）、市盈率 TTM、市净率、换手率、成交额。",
            "get_history", "查一只 A 股最近 N 个交易日的前复权日线（默认 20 天，最多 120 天）：日期、开高低收、成交量、成交额、涨跌幅、换手率。");

    private static final Map<String, String> BUILTIN_SCHEMAS = Map.of(
            "get_quote", """
                    {"type":"object","properties":{"code":{"type":"string","description":"6 位数字股票代码，如 300059"}},"required":["code"]}
                    """,
            "get_history", """
                    {"type":"object","properties":{"code":{"type":"string","description":"6 位数字股票代码，如 300059"},"days":{"type":"integer","description":"返回最近多少个交易日，1~120","minimum":1,"maximum":120}},"required":["code"]}
                    """);

    /** 服务端不可用时的兜底清单；顺序固定，便于断言。 */
    private static final List<ToolCallback> BUILTIN = List.of(
            definitionsOf("get_quote", BUILTIN_DESCRIPTIONS.get("get_quote"), BUILTIN_SCHEMAS.get("get_quote")),
            definitionsOf("get_history", BUILTIN_DESCRIPTIONS.get("get_history"), BUILTIN_SCHEMAS.get("get_history")));

    private final ChatModel chatModel;
    private final ObjectMapper mapper;

    public MarketToolSelector(ChatModel chatModel, ObjectMapper mapper) {
        this.chatModel = chatModel;
        this.mapper = mapper;
    }

    /** 模型选出来的调用。{@code arguments} 已从 tool_call 的 JSON 字符串解析成 Map。 */
    public record Picked(String tool, Map<String, Object> arguments) {
    }

    /** 没有服务端清单时用内置定义。 */
    public Optional<Picked> pick(String question, String knownCode) {
        return pick(question, knownCode, List.of());
    }

    public Optional<Picked> pick(String question, String knownCode, List<MarketToolSpec> specs) {
        if (question == null || question.isBlank()) {
            return Optional.empty();
        }
        String user = knownCode == null || knownCode.isBlank()
                ? question
                : question + "\n（已知股票代码：" + knownCode + "，可直接使用）";
        try {
            ChatResponse response = chatModel.call(new Prompt(
                    List.of(new SystemMessage(SYSTEM), new UserMessage(user)),
                    options(definitions(specs))));
            if (response == null || response.getResult() == null) {
                return Optional.empty();
            }
            AssistantMessage message = response.getResult().getOutput();
            if (message == null || !message.hasToolCalls()) {
                LOG.debug("模型没有选择行情工具，回落规则调用");
                return Optional.empty();
            }
            for (AssistantMessage.ToolCall call : message.getToolCalls()) {
                Optional<Picked> picked = toPicked(call);
                if (picked.isPresent()) {
                    return picked;
                }
            }
            return Optional.empty();
        } catch (RuntimeException e) {
            LOG.warn("模型选工具失败，回落规则调用：{}", e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * 服务端清单 → 模型能看到的工具定义。
     *
     * <p>两条过滤：只留 {@code get_quote}/{@code get_history}（{@code search_stock} 是公司解析的内部步骤，
     * 不该让模型选）；schema 为空的丢掉（模型看不到参数就只会瞎填）。全被滤掉时用内置清单，
     * 不能让模型面对一个空工具列表。
     */
    static List<ToolCallback> definitions(List<MarketToolSpec> specs) {
        if (specs == null || specs.isEmpty()) {
            return BUILTIN;
        }
        List<ToolCallback> callbacks = new ArrayList<>(Math.min(specs.size(), 2));
        for (MarketToolSpec spec : specs) {
            if (spec == null || !isSelectable(spec.name())) {
                continue;
            }
            String schema = spec.inputSchema() == null ? "" : spec.inputSchema().strip();
            if (schema.isEmpty()) {
                LOG.debug("服务端工具 {} 没有 inputSchema，忽略", spec.name());
                continue;
            }
            String description = spec.description() == null || spec.description().isBlank()
                    ? BUILTIN_DESCRIPTIONS.getOrDefault(spec.name(), "")
                    : spec.description();
            callbacks.add(definitionsOf(spec.name(), description, schema));
        }
        return callbacks.isEmpty() ? BUILTIN : List.copyOf(callbacks);
    }

    /** 只有这两个工具允许模型选；白名单与 {@code McpMarketGateway} 的取数白名单对齐（那边是私有方法，别写 {@code @link}）。 */
    private static boolean isSelectable(String name) {
        return "get_quote".equals(name) || "get_history".equals(name);
    }

    /**
     * 必须从<b>模型自己的 options</b> 派生：OpenAI 客户端的 {@code call()} 会把 options 强转成
     * {@code OpenAiChatOptions}，直接传通用的 {@code DefaultToolCallingChatOptions} 会抛
     * {@code ClassCastException}（冒烟时就是这么回落到规则调用的）。
     *
     * <p>取的是 {@code ChatModel#getOptions()}；它的旧名 {@code getDefaultOptions()} 在 Spring AI 2.0.1
     * 已标 {@code @Deprecated(forRemoval = true)}，语义相同。
     */
    private ChatOptions options(List<ToolCallback> callbacks) {
        ChatOptions defaults = chatModel.getOptions();
        if (defaults instanceof ToolCallingChatOptions toolOptions) {
            return toolOptions.mutate()
                    .toolCallbacks(callbacks)
                    .temperature(0.0)
                    .build();
        }
        return ToolCallingChatOptions.builder()
                .toolCallbacks(callbacks)
                .temperature(0.0)
                .build();
    }

    private Optional<Picked> toPicked(AssistantMessage.ToolCall call) {
        String name = call.name();
        if (name == null || !(name.equals("get_quote") || name.equals("get_history"))) {
            return Optional.empty();
        }
        Map<String, Object> args = new LinkedHashMap<>();
        String raw = call.arguments();
        if (raw != null && !raw.isBlank()) {
            try {
                Map<String, Object> parsed = mapper.readValue(raw, MAP_TYPE);
                if (parsed != null) {
                    args.putAll(parsed);
                }
            } catch (RuntimeException e) {
                LOG.debug("tool_call 参数不是合法 JSON：{}", raw);
                return Optional.empty();
            }
        }
        Object code = args.get("code");
        if (!(code instanceof String text) || !text.matches("\\d{6}")) {
            return Optional.empty();
        }
        args.put("code", text);
        if ("get_history".equals(name)) {
            Object days = args.get("days");
            int value = days instanceof Number number ? number.intValue() : 20;
            args.put("days", McpMarketGateway.clampDays(value));
        } else {
            args.remove("days");
        }
        return Optional.of(new Picked(name, args));
    }

    /**
     * 只带定义、不实现调用的 {@link ToolCallback}：它的唯一用途是让模型看到工具清单。
     * 真调用由 {@link MarketGateway#execute} 走 MCP 完成。
     */
    private static ToolCallback definitionsOf(String name, String description, String schema) {
        ToolDefinition definition = ToolDefinition.builder()
                .name(name)
                .description(description)
                .inputSchema(schema.strip())
                .build();
        return new ToolCallback() {
            @Override
            public ToolDefinition getToolDefinition() {
                return definition;
            }

            @Override
            public String call(String toolInput) {
                throw new UnsupportedOperationException("行情工具由 MarketGateway 执行，不经过框架");
            }
        };
    }
}
