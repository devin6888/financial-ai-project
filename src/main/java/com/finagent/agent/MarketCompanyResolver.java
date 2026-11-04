package com.finagent.agent;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import com.finagent.market.MarketGateway;
import com.finagent.security.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * 行情问题里把「东方财富」变成 300059 的三级解析。
 *
 * <p>顺序是有讲究的 —— 越靠前越可信：
 * <ol>
 *   <li>问题里直接写的 6 位代码：用户意图最明确，不需要任何猜测。</li>
 *   <li>租户 {@code company} 表 + QU 抽出的公司名 / 问题原文子串：覆盖「已入库公司」的常规问法。</li>
 *   <li>MCP {@code search_stock} 兜底：未入库公司（例如只问行情、没上传过年报）也能答，
 *       但<b>只有唯一候选</b>才采用，多候选一律要求用户给代码 —— 猜错公司比答不出更糟。</li>
 * </ol>
 */
@Component
public class MarketCompanyResolver {

    private static final Logger LOG = LoggerFactory.getLogger(MarketCompanyResolver.class);

    /** 解析结果。{@code source} 只用于 trace 与日志，不展示给用户。 */
    public record MarketCompany(String code, String name, String source) {
    }

    private final JdbcClient jdbc;
    private final MarketGateway gateway;

    public MarketCompanyResolver(JdbcClient jdbc, MarketGateway gateway) {
        this.jdbc = jdbc;
        this.gateway = gateway;
    }

    public Optional<MarketCompany> resolve(String question, List<String> codes, List<String> mentions) {
        String inline = MarketIntentDetector.codeInQuestion(question);
        if (inline != null) {
            return Optional.of(new MarketCompany(inline, nameOf(inline).orElse(""), "question"));
        }
        if (codes != null) {
            for (String code : codes) {
                if (code != null && code.matches("\\d{6}")) {
                    return Optional.of(new MarketCompany(code, nameOf(code).orElse(""), "understanding"));
                }
            }
        }
        return byName(question, mentions);
    }

    private Optional<MarketCompany> byName(String question, List<String> mentions) {
        List<MarketCompany> known = tenantCompanies();
        if (mentions != null) {
            for (String mention : mentions) {
                if (mention == null || mention.isBlank()) {
                    continue;
                }
                Optional<MarketCompany> hit = match(known, mention);
                if (hit.isPresent()) {
                    return hit;
                }
            }
        }
        if (question != null && !question.isBlank()) {
            // 长名字优先：「东方财富证券」不该先命中「东方财富」之外的碎片
            List<MarketCompany> byLength = new ArrayList<>(known);
            byLength.sort(Comparator.comparingInt((MarketCompany c) -> c.name().length()).reversed());
            for (MarketCompany company : byLength) {
                String name = normalize(company.name());
                if (name.length() >= 2 && normalize(question).contains(name)) {
                    return Optional.of(company);
                }
            }
        }
        return bySearch(mentions);
    }

    private Optional<MarketCompany> bySearch(List<String> mentions) {
        if (mentions == null) {
            return Optional.empty();
        }
        for (String mention : mentions) {
            if (mention == null || mention.isBlank() || normalize(mention).length() < 2) {
                continue;
            }
            try {
                List<MarketGateway.Hit> hits = gateway.search(mention.strip());
                if (hits.size() == 1) {
                    MarketGateway.Hit hit = hits.get(0);
                    return Optional.of(new MarketCompany(hit.code(), hit.name(), "mcp_search"));
                }
                if (hits.size() > 1) {
                    LOG.info("行情公司名「{}」有 {} 个候选，按歧义处理", mention, hits.size());
                }
            } catch (RuntimeException e) {
                LOG.warn("行情公司名解析失败：mention={} reason={}", mention, e.getMessage());
            }
        }
        return Optional.empty();
    }

    private Optional<MarketCompany> match(List<MarketCompany> known, String mention) {
        String target = normalize(mention);
        if (target.isEmpty()) {
            return Optional.empty();
        }
        for (MarketCompany company : known) {
            if (normalize(company.name()).equals(target)) {
                return Optional.of(company);
            }
        }
        for (MarketCompany company : known) {
            String name = normalize(company.name());
            if (name.length() >= 2 && (target.contains(name) || name.contains(target))) {
                return Optional.of(company);
            }
        }
        return Optional.empty();
    }

    private Optional<String> nameOf(String code) {
        for (MarketCompany company : tenantCompanies()) {
            if (company.code().equals(code)) {
                return Optional.of(company.name());
            }
        }
        return Optional.empty();
    }

    /** 一次读完本租户的公司（几十行量级），避免按名字做 LIKE 造成误匹配。 */
    private List<MarketCompany> tenantCompanies() {
        Long tenantId = tenantId();
        if (tenantId == null) {
            return List.of();
        }
        return jdbc.sql("SELECT code, name FROM company WHERE tenant_id = ?")
                .param(tenantId)
                .query((rs, rowNum) -> new MarketCompany(rs.getString("code"), rs.getString("name"), "company"))
                .list();
    }

    private static Long tenantId() {
        return TenantContext.optional().map(user -> user.tenantId()).orElse(null);
    }

    private static String normalize(String text) {
        if (text == null) {
            return "";
        }
        String value = text.strip().toLowerCase(Locale.ROOT)
                .replace("股份有限公司", "")
                .replace("有限责任公司", "")
                .replace("集团", "")
                .replace("证券", "")
                .replace("控股", "")
                .replace(" ", "")
                .replace("（", "").replace("）", "")
                .replace("(", "").replace(")", "");
        return value;
    }
}
