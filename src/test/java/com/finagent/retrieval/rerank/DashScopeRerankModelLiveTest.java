package com.finagent.retrieval.rerank;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.util.Comparator;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 打真接口的冒烟：覆盖 MockRestServiceServer 测不到的那部分 ——
 * buildClient() 里的 base-url、Authorization 头、超时设置，以及真实的 JSON 序列化。
 *
 * <p>环境里没有 FINAGENT_RERANK_API_KEY 时自动跳过（沿用 VectorStoreSmokeTest 的做法）。
 * 不需要 Spring 上下文，也不连数据库。
 */
@EnabledIfEnvironmentVariable(named = "FINAGENT_RERANK_API_KEY", matches = ".+")
class DashScopeRerankModelLiveTest {

    @Test
    void ranksRelevantFinancialTextFirst() {
        RerankProperties props = new RerankProperties();
        props.setApiKey(System.getenv("FINAGENT_RERANK_API_KEY"));
        DashScopeRerankModel model = new DashScopeRerankModel(props);

        List<String> docs = List.of(
                "第四节 公司治理 公司设有董事会、监事会，独立董事占比三分之一。",
                "第二节 公司简介 主要会计数据 营业收入 1,162,800.00 万元，上年同期 1,008,470.00 万元。",
                "第三节 管理层讨论与分析 主营业务分析 报告期内，公司实现营业总收入 116.28 亿元，同比增长 15.3%。");

        List<RerankResult> results = model.rerank("东方财富2025年营业收入是多少", docs, 3);

        assertThat(results).hasSize(3);
        assertThat(results).extracting(RerankResult::index).doesNotContainNull();
        // 公司治理那一段与问题无关，必须排最后
        assertThat(results.get(results.size() - 1).index()).isEqualTo(0);
        // 分数必须降序
        assertThat(results).extracting(RerankResult::relevanceScore)
                .isSortedAccordingTo(Comparator.reverseOrder());
        assertThat(model.modelName()).isEqualTo("qwen3-rerank");
    }
}
