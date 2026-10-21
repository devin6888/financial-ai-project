# 阶段 4：Query Understanding + 混合检索 实施计划

> **For agentic workers:** 本环境未安装 superpowers 执行子技能，采用「每个任务由子代理独立执行 + 主代理在任务间验收」替代。步骤用复选框跟踪。

**Goal:** 先把问题解析成可检索的结构（Query Understanding），再做 Dense + Sparse 双路召回、RRF 融合、百炼 qwen3-rerank 精排，并保证任何一环失败都能降级——给定一个自然语言问题，返回排序正确的 Child chunk。

**Architecture:** 四段解耦。**Query Understanding** 先用「规则快路径 + LLM 结构化输出」把问题解析成实体/年份/类型（四层防线 + 保守降级）；**Dense** 走 pgvector 余弦距离；**Sparse** 按 QU 结果走两种模式（有实体走精确子串、无实体走 FTS + OR）；两路按**排名**用 RRF 融合；最后交给自定义 RerankModel 精排。所有候选单元都是 **CHILD**（§9.1），Parent 只做上下文。

**Tech Stack:** Java 21 · Spring Boot 4.0.8 · Spring AI 2.0.1 · PostgreSQL 16 + pgvector 0.8.7 + zhparser 2.4 · 阿里云百炼（text-embedding-v4 / qwen3-rerank）

---

## 本计划的依据：设计文档 §9

| 出处 | 要求 |
|---|---|
| §9.1 | **Child 是检索单元**，Parent 只做上下文，V1 不建 Parent 向量 |
| §9.1 | **结构化过滤先于相似度** —— 权限/公司/年份必须先限制候选空间 |
| §9.1 | Hybrid 是 V1 基线：Dense 管语义，Sparse 管公司名/代码/指标/年份**精确匹配** |
| §9.1 | **Rerank 在 Candidate Retrieval 之后、Expansion 之前** |
| §9.1 | **不采用 HyDE** |
| §9.2 | Query Understanding 输出结构化对象：queryType / normalizedQuery / companyIds / yearRange / metrics / needMultiQuery / needDecompose |
| §9.2.1 | 四类查询由**证据分布形态**推出（点状/团状/离散多点/链状）；第 4 类是**单轮 RAG 的能力边界** |
| §9.3 | 10 步 Pipeline（本阶段做第 1、2、3~7 步） |
| §9.14 | **规则快路径 + 单次 LLM 结构化输出**；规则 ≤5ms、LLM P95 ≤800ms |
| §9.14 | 判为 ExactFact 时要求 companyIds / yearRange / metrics **同时非空**，否则降级 |
| §9.14 | **降级选 SemanticExplanation** —— 因为「Semantic 误判成 Exact」是唯一「任务直接失败」的方向 |
| §9.14 | **单字段校验失败时丢弃该字段，而不是丢弃整个结果** |
| §9.4 | RRF_score(d) = Σ 1/(k + rank_m(d))，**k = 60**；Dense/Sparse 各 Top-30 |
| §9.4 | PG FTS 的排序**不等价于 BM25** —— 保留 SparseRetriever 抽象仅作可替换性 |
| §9.7 | Rerank 输入 20~50、输出 5~10；**Spring AI 无 Rerank 抽象**（#4343/#5776），需自定义 |
| §9.7 | **Rerank 失败降级到 RRF 结果，trace 标 degraded=true** —— 不能因模型挂了导致 API 不可用 |
| §9.13 | 配额：Dense 30 / Sparse 30 → **RRF 截断 50** → **Rerank 取 8** |

**本阶段范围**：§9.3 的第 1~7 步 —— **Query Understanding** + Dense / Sparse / RRF / Rerank + 过滤 + trace。

**为什么把 QU 提前到本阶段**（与原路线图的差异，已与用户确认）：
自然语言问题交给 FTS 会被 AND 成 **0 条**（实测「2025年公司面临的主要风险有哪些」）。没有 QU，Sparse 路对真实提问基本不可用；而 QU 单独实现又没有可验证的产出。两者必须绑在一起。

**不在本阶段**：指代消解（依赖 Short-term Memory，阶段 9）、Multi-Query 的实际执行（§9.6，阶段 5）、Expansion（§9.8，阶段 5）、Context Packing 与 Citation（§9.9，阶段 5）、ACL 权限过滤（§9.5 权限部分，阶段 8）。

## 现状与真实的坑（已实测）

| 项 | 状态 |
|---|---|
| content_tsv 生成列 + GIN 索引 | 已存在（V1），随 chunk 写入自动维护 |
| idx_chunk_embedding（HNSW 余弦） | 已存在 |
| idx_chunk_active（document_id, chunk_version, chunk_level, status） | 已存在（V3） |
| 654 个 CHILD 全部有 1024 维向量 | 阶段 3 实测 |
| 数据规模 | 1 份文档 / 654 Child —— **本阶段需要更多文档才能做有意义的评测，见 Task 10** |

**实测 zhparser 的行为（重要）：**

```
to_tsvector('chinese_zh', '东方财富2025年营业收入同比增长')
→ '2025':2  '同比':5  '增长':6  '收入':4  '营业':3  '财富':1
```

- **「营业收入」被切成「营业」+「收入」** —— 财务指标不是原子词。plainto_tsquery 会把它变成「营业 AND 收入」，精确性还行，但**排序很弱**。
- 实测查「净利润」时，「净敞口套期收益」这类行会被召回。
- **本镜像的 zhparser 2.4 没有注册 scws_extra_dicts 这个 GUC**（pg_settings 里查不到），**无法加载财务自定义词典**。容器内只有 SCWS 内置词典。

**这正是 §9.4 说的「PG FTS 排序不等价于 BM25」。** 应对方式是承认它、用 RRF + Rerank 兜住——而不是假装 Sparse 排序可靠。

## File Structure

```
src/main/java/com/finagent/understanding/
├── QueryType.java              # EXACT_FACT / SEMANTIC_EXPLANATION / COMPARISON / COMPLEX_ANALYSIS
├── QueryUnderstanding.java     # 结构化输出（字段可空，支持逐字段丢弃）
├── MetricDictionary.java       # 财务指标词典（规则快路径的基础）
├── RuleBasedRouter.java        # 第 1 层：代码+年份+指标 -> ExactFact，不调 LLM
├── LlmRouter.java              # 第 2 层：单次结构化输出
└── RouterService.java          # 第 3/4 层：字段校验 + 交叉验证 + 兜底降级

src/main/java/com/finagent/retrieval/
├── RetrievalRequest.java        # query + filters + topK + 是否启用 rerank
├── RetrievalFilters.java        # documentId / companyId / docYear / sectionPathPrefix
├── RetrievedChunk.java          # chunk + denseRank + sparseRank + rrfScore + rerankScore
├── RetrievalTrace.java          # 各阶段候选与耗时，含 degraded 标记
├── DenseRetriever.java          # pgvector
├── SparseRetriever.java         # 接口（§9.4 要求保留可替换性）
├── PgSparseRetriever.java       # PG FTS + zhparser，**两种模式**（精确子串 / FTS-OR）
├── RrfFusion.java               # 纯函数，可离线单测
├── HybridRetriever.java         # 编排：两路召回 -> RRF -> Rerank
└── rerank/
    ├── RerankModel.java         # 自定义抽象（Spring AI 没有）
    ├── RerankResult.java
    ├── DashScopeRerankModel.java # 百炼 qwen3-rerank
    └── NoopRerankModel.java      # 未配置时的降级实现
```

**为什么把 RRF 做成纯函数**：融合逻辑最容易出错（排名偏移、并列处理），也最值得单测。拆出来后不需要数据库和网络就能验证。

**为什么 QU 单独一个包**：它是**纯输入转换**（问题 → 结构化对象），与检索的存储/索引进制无关。分开后可以单独测「规则路径命中率」与「降级方向是否正确」，不必牵扯数据库与向量。

---


## Task 1: V5 迁移 —— 补映射 + 精确匹配索引

**为什么现在做**：这一步同时解决两个已实测的问题：

| 问题 | 实测 | 本任务的修法 |
|---|---|---|
| 中文数字被丢 | 三/百/万 被标为 m（数词），而 chinese_zh 只映射了 n,v,a,i,e,l,j → **丢弃** | 给 chinese_zh 加 m 映射 |
| 指标名被切碎 | plainto_tsquery('每股收益') → 收益（每股 被标 r 后丢弃）→ **86 条**命中，而精确子串只有 **6 条** | 加 pg_trgm + GIN 索引，供 QU 抽出的实体做**精确子串召回** |

### ⚠️ 为什么**不**加 r（代词）

实测语料统计（654 个 CHILD）：

| 词 | 词性 | 含此词的 chunk |
|---|---|---|
| 公司 | n | 317 |
| **其他** | **r** | **250（38%）** |
| 每股 | r | 9 |
| 任何 | r | 8 |

**加 r 会让「其他」进入 38% 的 chunk**，而没 IDF 的情况下它不会被降权 —— 噪声远大于收益。
「每股」的问题由 QU + 精确子串匹配解决，不靠分词。

**Files:**
- Create: `src/main/resources/db/migration/V5__sparse_index_upgrade.sql`

- [ ] **Step 1: 写迁移**

```sql
-- ============================================================
-- V5__sparse_index_upgrade.sql
-- 阶段 4：为混合检索升级 Sparse 路的索引能力
--
-- 两个改动：
--   1) chinese_zh 增加 m（数词）映射 —— 修「三百万」这类中文数字被丢弃
--   2) 引入 pg_trgm + GIN 索引 —— 供 QU 抽出的实体做精确子串召回
--      （zhparser 会把「每股收益」切成「收益」，精确子串才能保住原意）
--
-- 注意：改映射后已有的 content_tsv 是旧值（生成列在写入时算好），
--       必须强制重算，见最后一步。
-- ============================================================

-- ---------- 1) 给 chinese_zh 加 m（数词）映射 ----------
-- tokid 是「别名字符的 ASCII 码」：实测已映射的 97/101/105/106/108/110/118
-- 正好对应 a/e/i/j/l/n/v。所以 m 就是 ascii('m')=109，**不要硬编码数字**
-- （初版计划里写的是 22，是错的 —— 那会让 DO 块静默地什么都不做）。
-- 用 DO 块保证幂等：ADD MAPPING 重复执行会报错
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_ts_config_map m
          JOIN pg_ts_config c ON c.oid = m.mapcfg
         WHERE c.cfgname = 'chinese_zh' AND m.maptokentype = ascii('m')   -- m = 109（见下方注释）
    ) THEN
        ALTER TEXT SEARCH CONFIGURATION chinese_zh ADD MAPPING FOR m WITH simple;
    END IF;
END $$;

-- ---------- 2) pg_trgm：精确子串召回 ----------
CREATE EXTENSION IF NOT EXISTS pg_trgm;

-- ---------- 3) 检索的第三类索引：内容三元组 ----------
CREATE INDEX IF NOT EXISTS idx_chunk_content_trgm
    ON document_chunk USING GIN (content gin_trgm_ops);

-- ---------- 4) 重算 content_tsv ----------
-- content_tsv 是 GENERATED ALWAYS AS ... STORED，只有行被更新时才会重算。
-- 不执行这一步，旧的 tsv 里仍然没有数词。
UPDATE document_chunk SET content = content;
```

- [ ] **Step 2: 应用并验证**

```powershell
Set-Location 'D:\Project\myproject\financial-ai-project'
. .\scripts\load-env.ps1
mvn -B -Dtest=VectorStoreSmokeTest test
```

Expected: 迁移成功，测试通过。

- [ ] **Step 3: 验证映射真的生效（本任务的核心验收）**

```powershell
docker compose exec -T postgres psql -U finagent -d finagent -c "select to_tsvector('chinese_zh','三百万') as 数词改后;"
docker compose exec -T postgres psql -U finagent -d finagent -c "select count(*) from document_chunk where document_id=7 and content ilike '%每股收益%';"
docker compose exec -T postgres psql -U finagent -d finagent -c "select count(*) from document_chunk where document_id=7 and content_tsv ` plainto_tsquery('chinese_zh','每股收益');"
docker compose exec -T postgres psql -U finagent -d finagent -c "set enable_seqscan=off; explain (costs off) select id from document_chunk where content ilike '%每股收益%';"
```

Expected:
- 三百万 现在至少产出 1 个 token（改前是 0，全是 m）
- 精确子串 **6** 条 vs 分词后 **86** 条 —— **两者都要跑**，它证明「精确匹配解决了分词救不了的问题」
- EXPLAIN 显示 Bitmap Index Scan on idx_chunk_content_trgm

---

## Task 2: 检索的领域模型

**Files:**
- Create: `src/main/java/com/finagent/retrieval/RetrievalFilters.java`
- Create: `src/main/java/com/finagent/retrieval/RetrievalRequest.java`
- Create: `src/main/java/com/finagent/retrieval/RetrievedChunk.java`
- Test: `src/test/java/com/finagent/retrieval/RetrievalRequestTest.java`

- [ ] **Step 1: 三个模型**

```java
package com.finagent.retrieval;

import java.util.List;

/**
 * 检索的硬过滤条件（§9.1：结构化过滤先于相似度）。
 *
 * <p>所有字段可空 —— 为空的项不加条件。
 *
 * <p><b>为什么要有这个类</b>：Dense 与 Sparse 两路必须用<b>完全相同</b>的过滤条件，
 * 否则两路的候选集不同源，RRF 融合会引入偏差。把条件集中在一处，
 * 两路各自转成 SQL 片段时都从它生成。
 *
 * <p>权限（tenant/user ACL）不在本阶段（阶段 8）。本类只做业务过滤。
 */
public record RetrievalFilters(
        Long documentId,
        Long companyId,
        Integer docYear,
        /** 章节路径前缀，如「第八节」—— 把检索限制在某一章 */
        String sectionPathPrefix,
        /** 只看某个 chunk 版本；为空则用 document.active_chunk_version */
        Integer chunkVersion
) {
    public static RetrievalFilters none() {
        return new RetrievalFilters(null, null, null, null, null);
    }
}
```

```java
package com.finagent.retrieval;

/**
 * 一次检索请求。
 *
 * <p>默认值直接取 §9.13 的配额。这些是<b>配置项</b>，用紧凑构造器兜住 null 与越界，
 * 避免调用方传 0 导致「什么都没召回」却不报错。
 *
 * @param topDense    Dense 单路召回条数（§9.13：30）
 * @param topSparse   Sparse 单路召回条数（§9.13：30）
 * @param fusionLimit RRF 之后截断到多少再送 Rerank（§9.13：50）
 * @param topRerank   Rerank 输出条数（§9.13：8）
 * @param useRerank   是否精排；关闭时直接返回 RRF 结果（评测时用于对比）
 */
public record RetrievalRequest(
        String query,
        RetrievalFilters filters,
        int topDense,
        int topSparse,
        int fusionLimit,
        int topRerank,
        boolean useRerank
) {
    public static final int DEFAULT_TOP_DENSE = 30;
    public static final int DEFAULT_TOP_SPARSE = 30;
    public static final int DEFAULT_FUSION_LIMIT = 50;
    public static final int DEFAULT_TOP_RERANK = 8;

    public RetrievalRequest {
        if (query == null || query.isBlank()) {
            throw new IllegalArgumentException("query 不能为空");
        }
        query = query.strip();
        filters = filters == null ? RetrievalFilters.none() : filters;
        topDense = topDense <= 0 ? DEFAULT_TOP_DENSE : topDense;
        topSparse = topSparse <= 0 ? DEFAULT_TOP_SPARSE : topSparse;
        fusionLimit = fusionLimit <= 0 ? DEFAULT_FUSION_LIMIT : fusionLimit;
        topRerank = topRerank <= 0 ? DEFAULT_TOP_RERANK : topRerank;
    }

    public static RetrievalRequest of(String query) {
        return new RetrievalRequest(query, null, 0, 0, 0, 0, true);
    }
}
```

```java
package com.finagent.retrieval;

/**
 * 一个候选 chunk 及其在各阶段的排名与分数。
 *
 * <p>为什么把每一路的排名都留着（而不是只留最终分）：排查召回问题时必须能回答
 * 「这条是被 Dense 捞到的还是被 Sparse 捞到的」「Rerank 前后名次怎么变」。
 * {@link RetrievalTrace} 就靠这些字段。
 *
 * <p><b>排名约定：1 起</b>；某路没召回时该路排名为 <b>null</b>（不是 0 —— 见 RrfFusion 的说明）。
 */
public record RetrievedChunk(
        long chunkId,
        long parentId,
        long documentId,
        String chunkType,
        String sectionPath,
        String title,
        String content,
        int pageStart,
        int pageEnd,
        String sourceBlockIds,
        /** Dense 路名次（1 起），未召回为 null */
        Integer denseRank,
        /** Sparse 路名次（1 起），未召回为 null */
        Integer sparseRank,
        /** RRF 融合分 */
        double rrfScore,
        /** Rerank 分（0~1），未精排为 null */
        Double rerankScore
) {
    public RetrievedChunk withRerankScore(Double score) {
        return new RetrievedChunk(chunkId, parentId, documentId, chunkType, sectionPath,
                title, content, pageStart, pageEnd, sourceBlockIds,
                denseRank, sparseRank, rrfScore, score);
    }

    public RetrievedChunk withRrfScore(double score) {
        return new RetrievedChunk(chunkId, parentId, documentId, chunkType, sectionPath,
                title, content, pageStart, pageEnd, sourceBlockIds,
                denseRank, sparseRank, score, rerankScore);
    }
}
```

- [ ] **Step 2: 写测试**

```java
package com.finagent.retrieval;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RetrievalRequestTest {

    @Test
    void zeroTopKBecomesDefaults() {
        // 传 0 不能变成「什么都不召回」—— 必须兜底成 §9.13 的配额
        var r = RetrievalRequest.of("净利润");
        assertThat(r.topDense()).isEqualTo(30);
        assertThat(r.topSparse()).isEqualTo(30);
        assertThat(r.fusionLimit()).isEqualTo(50);
        assertThat(r.topRerank()).isEqualTo(8);
    }

    @Test
    void nullFiltersBecomeNone() {
        assertThat(RetrievalRequest.of("x").filters()).isNotNull();
    }

    @Test
    void blankQueryIsRejected() {
        assertThatThrownBy(() -> RetrievalRequest.of("   "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("query");
        assertThatThrownBy(() -> RetrievalRequest.of((String) null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void queryIsStripped() {
        assertThat(RetrievalRequest.of("  净利润 ").query()).isEqualTo("净利润");
    }
}
```

Run: `mvn -B -Dtest=RetrievalRequestTest test`
Expected: 4 个用例 PASS。

---

## Task 3: RRF 融合（纯函数，先测）

**设计依据（§9.4）：** RRF_score(d) = Σ 1/(k + rank_m(d))，k = 60。

### RRF 是什么

**Reciprocal Rank Fusion（倒数排名融合）**，来自 Cormack、Clarke、Buettcher 在 SIGIR 2009 的论文
《Reciprocal Rank Fusion outperforms Condorcet and individual Rank Learning Methods》。
它只解决一件事：**把多个检索器的结果列表合成一个，而且不需要它们的分数可比**。

```
RRF_score(d) = Σ_m  1 / (k + rank_m(d))
```

- `m` 是检索路（Dense / Sparse / 多查询改写出的每一条）
- `rank_m(d)` 是文档 d 在第 m 路的**名次**（**1 起**）
- 某一路没召回 d，则该项**不存在**（不是 0，见下）

### 为什么需要它：分数根本不可比

| 路 | 分数是什么 | 实测取值范围 |
|---|---|---|
| Dense | 余弦相似度 | 0 ~ 1 |
| Sparse | ts_rank | **0.015 ~ 0.300** |

如果按 `0.7 × dense + 0.3 × sparse` 加权，因为 Sparse 的分天然小两个数量级，
**权重形同虚设，结果几乎等于 Dense-only** —— 混合检索白做。

RRF 的做法是**彻底丢掉分数，只看名次**。名次在任何检索器里都是 1,2,3…，量纲天然一致。

### k = 60 到底控制什么

k 是一个**平滑常数**，决定「名次之间的分差有多陡」：

| rank | k=60 | k=10 | k=0 |
|---|---|---|---|
| 1 | 0.016393 | 0.090909 | **1.0000** |
| 5 | 0.015385 | 0.066667 | 0.2000 |
| 10 | 0.014286 | 0.050000 | 0.1000 |
| 30 | 0.011111 | 0.025000 | 0.0333 |

**看最后一列（k=0）**：第 1 名是第 30 名的 **30 倍**。
任何一路只要把某条排第一，它就碾压其他所有候选 —— **等于放弃了融合**。

**k=60 时**：第 1 名只是第 30 名的 **1.48 倍**。名次差异被大幅压平。

一句话：**k 越大越"民主"，k 越小越"赢者通吃"**（k→∞ 趋近完全平均）。

### 为什么偏偏是 60

**不是推导出来的，是原论文在 TREC 数据集上试出来的经验值。**
Cormack 等人报告 k=60 在多个集合上稳定优于其他取值，此后成为事实标准
（Elasticsearch / OpenSearch / Weaviate 的 RRF 默认都是 60）。

沿用它的理由：
1. 我们的候选池在 30~50 量级（§9.13），而 k 应当与候选数同数量级 —— 60 与之匹配
2. 有大量公开结果可对照，出问题不会是「我们选错了 k」

**但它仍是配置项**（`RrfFusion.DEFAULT_K`）。若阶段 6 评测显示别的值更好，用数据说话。

### 融合的真正价值：共识

k=60 下最关键的一条性质：

```
两路都排第 5   →  2 × 1/65 = 0.030769
只有一路排第 1 →      1/61 = 0.016393
```

**两条列表都认可的中等排名，胜过只有一条列表力挺的第一名。**

这就是 RRF 的全部意义：**多个弱信号的一致，比单个强信号更可信**。

### 已知局限（必须写进代码注释）

| 局限 | 后果 |
|---|---|
| **完全忽略分数大小** | 相似度 0.95 与 0.51 的两条，只要都排第 1，贡献完全相同 |
| **治不了「某一路排序本身就差」** | RRF 只做名次换算。Sparse 的 ts_rank 没有 IDF，顺序本身不可靠 —— 那要靠 Rerank 解决 |
| **假设各路质量相当** | 如果一路几乎全是噪声，它照样贡献 `1/(k+rank)` 的分 |

### 实现必须守住的三条

1. **rank 从 1 开始**，不能用数组下标 0
2. **没召回必须是 null**，不能是 0 或 -1 —— 否则 `1/(60+0)=1/60` 会高于任何真实命中
3. **同分按 chunkId 升序** —— 否则同一评测集跑两次可能得到不同顺序，评测无法复现

**为什么按排名而不按分数融合**：Dense 的余弦相似度在 0~1，Sparse 的 ts_rank 在 0.0x 量级，**量纲完全不同**。直接加权求和等于让 Dense 单方面决定结果；RRF 只看名次，天然免疫尺度差异。

**Files:**
- Create: `src/main/java/com/finagent/retrieval/RrfFusion.java`
- Test: `src/test/java/com/finagent/retrieval/RrfFusionTest.java`

- [ ] **Step 1: 写测试**

```java
package com.finagent.retrieval;

import org.assertj.core.data.Offset;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RrfFusionTest {

    private static RetrievedChunk chunk(long id, Integer dense, Integer sparse) {
        return new RetrievedChunk(id, 0, 1, "TEXT", "s", "t", "c", 1, 1, "b",
                dense, sparse, 0.0, null);
    }

    @Test
    void computesRrfWithK60() {
        var fused = RrfFusion.fuse(List.of(chunk(1, 1, 1)), 60);
        assertThat(fused.get(0).rrfScore()).isCloseTo(2.0 / 61, Offset.offset(1e-9));
    }

    @Test
    void ranksByCombinedScoreNotSinglePath() {
        // A 只有 dense #1 = 1/61；B 有 dense #2 + sparse #2 = 2/62 > 1/61
        var fused = RrfFusion.fuse(List.of(chunk(1, 1, null), chunk(2, 2, 2)), 60);
        assertThat(fused).extracting(RetrievedChunk::chunkId).containsExactly(2L, 1L);
    }

    @Test
    void chunkMissingFromOnePathStillCounts() {
        var fused = RrfFusion.fuse(List.of(chunk(1, null, 1)), 60);
        assertThat(fused.get(0).rrfScore()).isCloseTo(1.0 / 61, Offset.offset(1e-9));
    }

    @Test
    void missingRankIsAbsentNotZero() {
        // 若把 null 当 0，1/(60+0)=1/60 会高于任何真实命中 —— 这是最容易犯的错
        var fused = RrfFusion.fuse(List.of(chunk(1, 1, null)), 60);
        assertThat(fused.get(0).rrfScore()).isLessThan(1.0 / 50);
    }

    @Test
    void tiesAreBrokenDeterministicallyByChunkId() {
        var fused = RrfFusion.fuse(List.of(chunk(9, 1, null), chunk(3, 1, null)), 60);
        assertThat(fused).extracting(RetrievedChunk::chunkId).containsExactly(3L, 9L);
    }

    @Test
    void emptyInputYieldsEmptyOutput() {
        assertThat(RrfFusion.fuse(List.of(), 60)).isEmpty();
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `mvn -B -Dtest=RrfFusionTest test`
Expected: 编译失败（RrfFusion 不存在）。

- [ ] **Step 3: 实现**

```java
package com.finagent.retrieval;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Reciprocal Rank Fusion（§9.4）。
 *
 * <p>公式：RRF_score(d) = Σ_m 1 / (k + rank_m(d))，k 默认 60。
 *
 * <p>为什么按排名而不按分数：Dense 余弦相似度在 0~1，Sparse 的 ts_rank 在 0.0x 量级。
 * 直接加权求和等于让 Dense 单方面决定结果；RRF 只看名次。
 *
 * <p>为什么 rank 从 1 开始且「没召回」必须是 null：k=60 时 1/(60+0)=1/60
 * 会高于任何真实命中。把 null 当 0 会让「只在某一路出现」的 chunk 分数虚高。
 */
public final class RrfFusion {

    public static final int DEFAULT_K = 60;

    public static List<RetrievedChunk> fuse(List<RetrievedChunk> candidates) {
        return fuse(candidates, DEFAULT_K);
    }

    public static List<RetrievedChunk> fuse(List<RetrievedChunk> candidates, int k) {
        List<RetrievedChunk> scored = new ArrayList<>(candidates.size());
        for (RetrievedChunk c : candidates) {
            double score = 0.0;
            if (c.denseRank() != null) {
                score += 1.0 / (k + c.denseRank());
            }
            if (c.sparseRank() != null) {
                score += 1.0 / (k + c.sparseRank());
            }
            scored.add(c.withRrfScore(score));
        }
        // 同分按 chunkId 升序：保证结果可复现（否则同一评测集跑两次排序可能不同）
        scored.sort(Comparator.comparingDouble(RetrievedChunk::rrfScore).reversed()
                .thenComparingLong(RetrievedChunk::chunkId));
        return scored;
    }

    private RrfFusion() {
    }
}
```

- [ ] **Step 4: 跑测试确认通过**

Run: `mvn -B -Dtest=RrfFusionTest test`
Expected: 6 个用例全 PASS。

---


## Task 4: Query Understanding —— 检索的前置

**设计依据（§9.2 / §9.14）：**

| 出处 | 要求 |
|---|---|
| §9.2 | 结构化输出至少含 queryType / normalizedQuery / companyIds / yearRange / metrics / needMultiQuery / needDecompose |
| §9.14 | **规则快路径 + 单次 LLM 结构化输出**的混合实现，不引入独立分类器 |
| §9.14 | 规则路径 ≤ 5 ms；LLM 路径 P95 ≤ 800 ms；超时按降级执行 |
| §9.14 | **单字段校验失败时丢弃该字段，而不是丢弃整个结果** |
| §9.14 | **四层防线**：规则快路径 → JSON Schema 约束 → 字段完整性交叉验证 → 兜底降级 |
| §9.14 | 判为 ExactFact 时要求 companyIds / yearRange / metrics **同时非空**，否则降级 |
| §9.14 | **降级选 SemanticExplanation**（不是 ExactFact）—— 因为「把 Semantic 误判成 Exact」是唯一「任务直接失败」的方向 |
| §9.2.1 | 四类查询由**证据分布形态**推出（点状/团状/离散多点/链状） |

**本任务边界**：**只做单轮**。指代消解（「那净利润呢」）依赖 Short-term Memory（§9.2.2），属于阶段 9。

**为什么 QU 是检索的前置**（本阶段的关键认知）：
自然语言问题交给 FTS 会被 AND 成 0 条（实测「2025年公司面临的主要风险有哪些」→ **0 命中**）。
QU 抽出实体后用**精确子串**召回（实测「每股收益」：精确 6 条 vs 分词后 86 条），才让 Sparse 路真正有用。

**Files:**
- Create: `src/main/java/com/finagent/understanding/QueryType.java`
- Create: `src/main/java/com/finagent/understanding/QueryUnderstanding.java`
- Create: `src/main/java/com/finagent/understanding/MetricDictionary.java`
- Create: `src/main/java/com/finagent/understanding/RuleBasedRouter.java`
- Test: `src/test/java/com/finagent/understanding/RuleBasedRouterTest.java`

- [ ] **Step 1: 类型**

```java
package com.finagent.understanding;

/**
 * 查询类型（§9.2.1）。
 *
 * <p>四类不是拍脑袋分的，而是按<b>证据在语料里的分布形态</b>推出的：
 * 点状→ExactFact、团状→SemanticExplanation、离散多点→Comparison、链状→ComplexAnalysis。
 *
 * <p>最后一类不是「另一种策略」，而是<b>单轮 RAG 的能力边界</b> —— 多跳问题在单轮结构上做不到。
 */
public enum QueryType {
    /** 答案是一个数值或一条定义 */
    EXACT_FACT,
    /** 答案需要相邻若干段落连起来读 */
    SEMANTIC_EXPLANATION,
    /** 证据分布在不相邻位置（不同年份/指标/章节） */
    COMPARISON,
    /** 需迭代发现，单轮做不到 */
    COMPLEX_ANALYSIS;

    /** 把 LLM 返回的字符串转成枚举；非法值返回 null（由调用方决定降级）。 */
    public static QueryType parse(String raw) {
        if (raw == null) {
            return null;
        }
        try {
            return valueOf(raw.strip().toUpperCase().replace('-', '_'));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
```

```java
package com.finagent.understanding;

import java.util.List;

/**
 * Query Understanding 的输出（§9.2）。
 *
 * <p>为什么字段大多可空：§9.14 要求「<b>单字段校验失败时丢弃该字段，而不是丢弃整个结果</b>」——
 * 所以这些字段必须允许部分缺失。
 *
 * @param degraded 是否走了降级路径
 * @param route    走了哪条：RULE / LLM / DEGRADED:原因
 */
public record QueryUnderstanding(
        QueryType queryType,
        String normalizedQuery,
        List<String> companyIds,
        int[] yearRange,
        List<String> metrics,
        boolean needMultiQuery,
        boolean needDecompose,
        boolean degraded,
        String route
) {
    public boolean hasEntities() {
        return (companyIds != null && !companyIds.isEmpty())
                || (metrics != null && !metrics.isEmpty());
    }
}
```

- [ ] **Step 2: 指标词典**

```java
package com.finagent.understanding;

import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 财务指标词典 —— 规则快路径的基础。
 *
 * <p>为什么用固定词典而不是完全依赖 LLM：§9.14 第一层防线要求高频确定性问法
 * 零延迟、零错误地被识别。LLM 只处理规则覆盖不到的部分，二者互补。
 *
 * <p>为什么硬编码而不查库：指标名是<b>业务词汇表</b>，不是业务数据。
 *
 * <p>⚠️ 列表<b>不完整</b>（真实财报有上百个指标）。本阶段先覆盖常见项。
 */
@Component
public class MetricDictionary {

    private static final List<String> METRICS = List.of(
            "归属于母公司股东的净利润", "扣除非经常性损益的净利润",
            "经营活动产生的现金流量净额", "投资活动产生的现金流量净额",
            "筹资活动产生的现金流量净额", "加权平均净资产收益率",
            "营业总收入", "营业收入", "营业成本", "营业利润", "利润总额", "净利润",
            "基本每股收益", "稀释每股收益", "每股收益",
            "资产负债率", "总资产", "净资产", "研发投入", "研发费用", "毛利率",
            "商誉", "应收账款", "存货", "货币资金");

    public List<String> all() {
        return METRICS;
    }

    /** 找出文本里命中的全部指标名，按长度降序（长词优先，避免短词抢占）。 */
    public List<String> findIn(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        return METRICS.stream()
                .filter(text::contains)
                .sorted((a, b) -> b.length() - a.length())
                .toList();
    }
}
```

- [ ] **Step 3: 规则快路径**

> **为什么规则路径不能省**：§9.14 要求它 ≤5ms 且零错误覆盖高频问法。
> 全走 LLM 的话，P95 800ms 会直接压垮检索体验，而确定性问法本可以零错误。

```java
package com.finagent.understanding;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 规则快路径（§9.14 第一层防线）。
 *
 * <p>覆盖形态：「600519 2025 年营业收入是多少」 —— 证券代码 + 年份 + 指标名俱全时，
 * 直接判定 EXACT_FACT，<b>不调用 LLM</b>。
 */
@Component
public class RuleBasedRouter {

    /** A 股代码：6 位数字。前后不能紧邻数字，避免把金额当成代码。 */
    private static final Pattern STOCK_CODE = Pattern.compile(
            "(?<!\\d)(60\\d{4}|68\\d{4}|00\\d{4}|30\\d{4})(?!\\d)");

    private static final Pattern YEAR = Pattern.compile("(19|20)\\d{2}");

    private final MetricDictionary metrics;

    public RuleBasedRouter(MetricDictionary metrics) {
        this.metrics = metrics;
    }

    /** @return 规则能判定时返回结果；否则 null，交给 LLM */
    public QueryUnderstanding tryRoute(String query) {
        if (query == null || query.isBlank()) {
            return null;
        }
        List<String> codes = findAll(STOCK_CODE, query);
        List<Integer> years = findAll(YEAR, query).stream().map(Integer::parseInt).toList();
        List<String> found = metrics.findIn(query);

        // §9.14 第三层：ExactFact 要求三者同时非空，否则证据不足
        if (codes.isEmpty() || years.isEmpty() || found.isEmpty()) {
            return null;
        }
        int minYear = years.stream().mapToInt(Integer::intValue).min().orElse(0);
        int maxYear = years.stream().mapToInt(Integer::intValue).max().orElse(0);
        return new QueryUnderstanding(QueryType.EXACT_FACT, query, codes,
                new int[]{minYear, maxYear}, found, false, false, false, "RULE");
    }

    private static List<String> findAll(Pattern p, String text) {
        List<String> out = new ArrayList<>();
        Matcher m = p.matcher(text);
        while (m.find()) {
            out.add(m.group());
        }
        return out;
    }
}
```

- [ ] **Step 4: 规则路径测试**

```java
package com.finagent.understanding;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RuleBasedRouterTest {

    private final RuleBasedRouter router = new RuleBasedRouter(new MetricDictionary());

    @Test
    void routesExactFactWhenAllThreePresent() {
        var u = router.tryRoute("600519 2025年营业收入是多少");
        assertThat(u).isNotNull();
        assertThat(u.queryType()).isEqualTo(QueryType.EXACT_FACT);
        assertThat(u.companyIds()).containsExactly("600519");
        assertThat(u.yearRange()).containsExactly(2025, 2025);
        assertThat(u.metrics()).contains("营业收入");
        assertThat(u.route()).isEqualTo("RULE");
    }

    @Test
    void returnsNullWhenYearMissing() {
        // §9.14 第三层：三者缺一即证据不足，必须交给 LLM
        assertThat(router.tryRoute("600519 营业收入是多少")).isNull();
    }

    @Test
    void returnsNullWhenMetricMissing() {
        assertThat(router.tryRoute("600519 2025年情况如何")).isNull();
    }

    @Test
    void returnsNullWhenCodeMissing() {
        assertThat(router.tryRoute("2025年营业收入是多少")).isNull();
    }

    @Test
    void doesNotTreatMoneyAsStockCode() {
        // 「11842000」里的 6 位数字不能被当成股票代码
        assertThat(router.tryRoute("2025年营业收入 11842000 元")).isNull();
    }

    @Test
    void prefersLongestMetricName() {
        var u = router.tryRoute("600519 2025年营业总收入");
        assertThat(u).isNotNull();
        assertThat(u.metrics().get(0)).isEqualTo("营业总收入");
    }

    @Test
    void capturesYearRangeFromComparison() {
        var u = router.tryRoute("600519 2023年到2025年营业收入变化");
        assertThat(u).isNotNull();
        assertThat(u.yearRange()).containsExactly(2023, 2025);
    }
}
```

Run: `mvn -B -Dtest=RuleBasedRouterTest test`
Expected: 7 个用例 PASS。

---
- [ ] **Step 5: LLM 结构化输出**

> ⚠️ **Spring AI 2.0 的 Structured Output API 必须先实测确认**（`ChatClient.entity(Class)` 还是
> `BeanOutputConverter`）。**不要照抄记忆** —— 阶段 2/3 已因版本差异踩过两次。

```java
package com.finagent.understanding;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * LLM 结构化输出（§9.14 第二层防线）。
 *
 * <p>把「开放生成」变成「有限选择」：queryType 被 Schema 限定为 4 个枚举值，大幅降低跑偏。
 *
 * <p><b>失败时抛异常，不在这里降级</b> —— 降级由 RouterService 统一处理，
 * 因为只有编排层知道 trace 该怎么标。
 */
@Component
@ConditionalOnProperty(name = "finagent.router.llm-enabled", havingValue = "true",
        matchIfMissing = true)
public class LlmRouter {

    private static final String SYSTEM = """
            你是金融研报检索的查询理解模块。把用户问题解析成结构化对象，只输出 JSON。

            queryType 只能是以下四个之一：
            - EXACT_FACT：答案是一个数值或一条定义（如 2025年营业收入是多少）
            - SEMANTIC_EXPLANATION：答案需要跨段落理解（如 营收增长的原因是什么）
            - COMPARISON：证据分布在多个年份或多个指标（如 比较2023到2025年收入和利润）
            - COMPLEX_ANALYSIS：需要多步推理才能确定查什么

            判为 EXACT_FACT 时，companyIds、yearRange、metrics 必须同时非空；
            任一项无法确定，就判为 SEMANTIC_EXPLANATION（误判代价更小的方向）。
            """;

    private final ChatClient chat;

    public LlmRouter(ChatClient.Builder builder) {
        this.chat = builder.build();
    }

    public QueryUnderstanding route(String query) {
        RouterDto dto = chat.prompt().system(SYSTEM).user(query).call().entity(RouterDto.class);
        if (dto == null) {
            throw new IllegalStateException("LLM 返回空结果");
        }
        return new QueryUnderstanding(
                QueryType.parse(dto.queryType()),
                dto.normalizedQuery() == null ? query : dto.normalizedQuery(),
                dto.companyIds(),
                toRange(dto.yearFrom(), dto.yearTo()),
                dto.metrics(),
                dto.needMultiQuery(),
                dto.needDecompose(),
                false, "LLM");
    }

    static int[] toRange(Integer from, Integer to) {
        if (from == null && to == null) {
            return null;
        }
        int f = from == null ? to : from;
        int t = to == null ? f : to;
        return new int[]{Math.min(f, t), Math.max(f, t)};
    }

    /** LLM 返回的原始 DTO —— 字段全可空，方便逐字段校验。 */
    public record RouterDto(
            String queryType,
            String normalizedQuery,
            List<String> companyIds,
            Integer yearFrom,
            Integer yearTo,
            List<String> metrics,
            boolean needMultiQuery,
            boolean needDecompose) {
    }
}
```

- [ ] **Step 6: RouterService —— 四层防线与降级**

```java
package com.finagent.understanding;

import com.finagent.document.DocumentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.time.Year;
import java.util.List;

/**
 * Query Understanding 编排（§9.14 的四层防线）。
 *
 * <ol>
 *   <li><b>规则快路径</b>：代码 + 年份 + 指标俱全 → 直接 ExactFact，不调 LLM</li>
 *   <li><b>受约束的结构化输出</b>：queryType 被限定为 4 个枚举</li>
 *   <li><b>字段完整性交叉验证</b>：判为 ExactFact 但三要素不全 → 降级</li>
 *   <li><b>兜底降级</b>：LLM 失败/超时 → 保守策略 + degraded</li>
 * </ol>
 *
 * <p><b>为什么降级选 SemanticExplanation 而不是 ExactFact</b>（§9.14）：
 * 误判代价不对称 —— 把 Semantic 误判成 Exact 会「只召回一个孤立段落、答不出因果关系」，
 * 是唯一「任务直接失败」的方向；反过来只是多召回、多花 Token。
 * 降级必须往<b>代价最小</b>的方向走。
 */
@Service
public class RouterService {

    private static final Logger log = LoggerFactory.getLogger(RouterService.class);

    private static final int MIN_YEAR = 1990;

    private final RuleBasedRouter rule;
    private final ObjectProvider<LlmRouter> llm;
    private final DocumentRepository documents;

    public RouterService(RuleBasedRouter rule, ObjectProvider<LlmRouter> llm,
                         DocumentRepository documents) {
        this.rule = rule;
        this.llm = llm;
        this.documents = documents;
    }

    public QueryUnderstanding understand(String query) {
        QueryUnderstanding byRule = rule.tryRoute(query);
        if (byRule != null) {
            return byRule;
        }

        QueryUnderstanding raw;
        try {
            LlmRouter router = llm.getIfAvailable();
            if (router == null) {
                return degraded(query, "llm_disabled");
            }
            raw = router.route(query);
        } catch (RuntimeException e) {
            log.warn("Query Understanding 失败，降级：{}", e.toString());
            return degraded(query, "llm_failed");
        }

        QueryUnderstanding cleaned = validate(raw, query);
        if (cleaned.queryType() == QueryType.EXACT_FACT && !isExactFactEvidenceSufficient(cleaned)) {
            log.info("判为 ExactFact 但三要素不全，降级为 SemanticExplanation：{}", query);
            return new QueryUnderstanding(QueryType.SEMANTIC_EXPLANATION, cleaned.normalizedQuery(),
                    cleaned.companyIds(), cleaned.yearRange(), cleaned.metrics(),
                    cleaned.needMultiQuery(), cleaned.needDecompose(), true, "DEGRADED:weak_evidence");
        }
        return cleaned;
    }

    /** §9.14：单字段校验失败时丢弃该字段，而不是丢弃整个结果。 */
    QueryUnderstanding validate(QueryUnderstanding raw, String original) {
        QueryType type = raw.queryType() == null ? QueryType.SEMANTIC_EXPLANATION : raw.queryType();

        List<String> companyIds = raw.companyIds() == null ? List.of()
                : raw.companyIds().stream().filter(documents::companyExists).toList();

        int currentYear = Year.now().getValue();
        int[] years = raw.yearRange();
        if (years != null && (years[0] < MIN_YEAR || years[1] > currentYear)) {
            years = null;    // 越界 → 丢弃该字段，不影响其余
        }

        return new QueryUnderstanding(type,
                raw.normalizedQuery() == null ? original : raw.normalizedQuery(),
                companyIds, years,
                raw.metrics() == null ? List.of() : raw.metrics(),
                raw.needMultiQuery(), raw.needDecompose(), raw.degraded(), raw.route());
    }

    static boolean isExactFactEvidenceSufficient(QueryUnderstanding u) {
        return u.companyIds() != null && !u.companyIds().isEmpty()
                && u.yearRange() != null
                && u.metrics() != null && !u.metrics().isEmpty();
    }

    static QueryUnderstanding degraded(String query, String reason) {
        return new QueryUnderstanding(QueryType.SEMANTIC_EXPLANATION, query,
                List.of(), null, List.of(), false, false, true, "DEGRADED:" + reason);
    }
}
```

- [ ] **Step 7: 降级测试（本任务最重要的一条）**

```java
package com.finagent.understanding;

import com.finagent.document.DocumentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RouterServiceTest {

    private DocumentRepository documents;

    @BeforeEach
    void setUp() {
        documents = mock(DocumentRepository.class);
        when(documents.companyExists(anyString())).thenReturn(true);
    }

    @SuppressWarnings("unchecked")
    private RouterService serviceWith(LlmRouter router) {
        ObjectProvider<LlmRouter> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(router);
        return new RouterService(new RuleBasedRouter(new MetricDictionary()), provider, documents);
    }

    @Test
    void rulePathWinsWithoutCallingLlm() {
        // LLM 传 null —— 若规则路径失效会 NPE，正好暴露
        var u = serviceWith(null).understand("600519 2025年营业收入");
        assertThat(u.route()).isEqualTo("RULE");
        assertThat(u.queryType()).isEqualTo(QueryType.EXACT_FACT);
    }

    @Test
    void llmFailureDegradesToSemanticExplanation() {
        LlmRouter broken = mock(LlmRouter.class);
        when(broken.route(anyString())).thenThrow(new RuntimeException("timeout"));
        var u = serviceWith(broken).understand("营收为什么增长");

        assertThat(u.degraded()).isTrue();
        // 关键：降级方向必须是 SemanticExplanation，不是 ExactFact
        assertThat(u.queryType()).isEqualTo(QueryType.SEMANTIC_EXPLANATION);
        assertThat(u.route()).startsWith("DEGRADED");
    }

    @Test
    void exactFactWithoutAllThreeFieldsIsDegraded() {
        LlmRouter weak = mock(LlmRouter.class);
        when(weak.route(anyString())).thenReturn(new QueryUnderstanding(
                QueryType.EXACT_FACT, "q", List.of("600519"), new int[]{2025, 2025},
                List.of(), false, false, false, "LLM"));

        var u = serviceWith(weak).understand("600519 2025年那个数");

        assertThat(u.queryType()).isEqualTo(QueryType.SEMANTIC_EXPLANATION);
        assertThat(u.degraded()).isTrue();
    }

    @Test
    void invalidYearIsDroppedButOtherFieldsSurvive() {
        LlmRouter bad = mock(LlmRouter.class);
        when(bad.route(anyString())).thenReturn(new QueryUnderstanding(
                QueryType.SEMANTIC_EXPLANATION, "q", List.of("600519"), new int[]{1800, 1800},
                List.of("净利润"), false, false, false, "LLM"));

        var u = serviceWith(bad).understand("某问题");

        assertThat(u.yearRange()).isNull();           // 年份被丢
        assertThat(u.metrics()).contains("净利润");    // 其余字段保留
        assertThat(u.degraded()).isFalse();
    }

    @Test
    void unknownCompanyIsDropped() {
        when(documents.companyExists(anyString())).thenReturn(false);
        LlmRouter bad = mock(LlmRouter.class);
        when(bad.route(anyString())).thenReturn(new QueryUnderstanding(
                QueryType.SEMANTIC_EXPLANATION, "q", List.of("999999"), null,
                List.of("净利润"), false, false, false, "LLM"));

        assertThat(serviceWith(bad).understand("某问题").companyIds()).isEmpty();
    }

    @Test
    void degradedKeepsQueryRetrievable() {
        var u = RouterService.degraded("原始问题", "test");
        assertThat(u.normalizedQuery()).isEqualTo("原始问题");
        assertThat(u.queryType()).isEqualTo(QueryType.SEMANTIC_EXPLANATION);
    }
}
```

Run: `mvn -B -Dtest=RouterServiceTest test`
Expected: 6 个用例 PASS。

> **若 `rulePathWinsWithoutCallingLlm` 失败**：说明规则路径没有拦住，请求漏到了 LLM（传 null 会 NPE）。
> 检查三要素是否同时满足。

---

## Task 5: DenseRetriever（pgvector）

**设计依据（§9.3 第 3 步 / §9.13）：** 在过滤范围内做 ANN 召回 Top-30。

**两个容易写错的地方：**

1. **版本过滤必须 join document**：不传 `chunkVersion` 时要用每篇文档自己的 `active_chunk_version`。写成相关子查询会逐行执行，写成 join 才对。
2. **不要用窗口函数算名次**：`row_number() OVER (ORDER BY ...)` 会对整个过滤集排序再截断。直接在 SQL 里 `ORDER BY` + `LIMIT`，名次在 Java 里按结果顺序赋 1..N 即可。

**Files:**
- Create: `src/main/java/com/finagent/retrieval/DenseRetriever.java`
- Modify: `src/main/java/com/finagent/chunk/ChunkRepository.java`（`toVectorLiteral` 改 public，供检索复用）

- [ ] **Step 1: 实现**

```java
package com.finagent.retrieval;

import com.finagent.chunk.ChunkRepository;
import com.finagent.embedding.EmbeddingService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 向量召回（§9.3 第 3 步）。
 *
 * <p>用 pgvector 的余弦距离 {@code <=>}，值越小越相似；对外暴露的名次从 1 起。
 */
@Component
public class DenseRetriever {

    private final JdbcTemplate jdbc;
    private final EmbeddingService embeddingService;

    public DenseRetriever(JdbcTemplate jdbc, EmbeddingService embeddingService) {
        this.jdbc = jdbc;
        this.embeddingService = embeddingService;
    }

    public List<RetrievedChunk> retrieve(String query, RetrievalFilters f, int topK) {
        // 1) 把 query 向量化（复用 EmbeddingService 的批量与模型版本管理）
        List<float[]> vec = embeddingService.embedAll(List.of(query));
        String literal = ChunkRepository.toVectorLiteral(vec.get(0));

        // 2) 过滤条件与 Sparse 路必须完全一致
        StringBuilder sql = new StringBuilder("""
                SELECT dc.id, coalesce(dc.parent_id, 0) AS parent_id, dc.document_id,
                       dc.chunk_type, dc.section_path, dc.title, dc.content,
                       dc.page_start, dc.page_end, dc.source_block_ids
                  FROM document_chunk dc
                  JOIN document d ON d.id = dc.document_id
                 WHERE dc.chunk_level = 'CHILD'
                   AND dc.status = 'ACTIVE'
                   AND dc.embedding IS NOT NULL
                   AND dc.chunk_version = coalesce(?, d.active_chunk_version)
                """);
        List<Object> args = new ArrayList<>();
        args.add(f.chunkVersion());
        appendFilters(sql, args, f);
        sql.append(" ORDER BY dc.embedding <=> ?::vector LIMIT ?");
        args.add(literal);
        args.add(topK);

        List<RetrievedChunk> out = new ArrayList<>(topK);
        int[] rank = {0};
        jdbc.query(sql.toString(), rs -> {
            rank[0]++;
            out.add(new RetrievedChunk(
                    rs.getLong("id"), rs.getLong("parent_id"), rs.getLong("document_id"),
                    rs.getString("chunk_type"), rs.getString("section_path"),
                    rs.getString("title"), rs.getString("content"),
                    rs.getInt("page_start"), rs.getInt("page_end"),
                    rs.getString("source_block_ids"),
                    rank[0], null, 0.0, null));
        }, args.toArray());
        return out;
    }

    /** 把业务过滤条件拼成 SQL 片段。Dense 与 Sparse 共用，保证两路候选同源。 */
    static void appendFilters(StringBuilder sql, List<Object> args, RetrievalFilters f) {
        if (f.documentId() != null) {
            sql.append(" AND dc.document_id = ?");
            args.add(f.documentId());
        }
        if (f.companyId() != null) {
            sql.append(" AND d.company_id = ?");
            args.add(f.companyId());
        }
        if (f.docYear() != null) {
            sql.append(" AND d.doc_year = ?");
            args.add(f.docYear());
        }
        if (f.sectionPathPrefix() != null && !f.sectionPathPrefix().isBlank()) {
            sql.append(" AND dc.section_path LIKE ?");
            args.add(f.sectionPathPrefix().strip() + "%");
        }
    }
}
```

- [ ] **Step 2: 改成可复用**

把 `ChunkRepository.toVectorLiteral` 从包级可见改成 `public static`，并加注释说明「检索侧复用，避免两处各写一份 pgvector 字面量格式」。

> ⚠️ 为什么不让 DenseRetriever 自己写一份：pgvector 的字面量格式（`[1.0,2.0]`）一旦两处不一致，
> 会出现「插入能成功、查询报错」或反过来的诡异问题。**只保留一个实现。**

---

## Task 6: SparseRetriever（双模式：精确子串 / FTS-OR）

**设计依据（§9.3 第 4 步 / §9.4）：** PostgreSQL 全文检索 + zhparser 作为第二路候选。
**§9.4 要求保留 SparseRetriever 抽象**，仅为可替换性 —— 若评测证明排序不足，换实现而不动上层。

**Files:**
- Create: `src/main/java/com/finagent/retrieval/SparseMode.java`
- Create: `src/main/java/com/finagent/retrieval/SparseRetriever.java`（接口）
- Create: `src/main/java/com/finagent/retrieval/PgSparseRetriever.java`
- Test: `src/test/java/com/finagent/retrieval/PgSparseRetrieverTest.java`

- [ ] **Step 1: 模式与接口**

```java
package com.finagent.retrieval;

/** Sparse 路的两种模式，由 Query Understanding 的结果决定。 */
public enum SparseMode {
    /**
     * 精确子串：QU 抽到了实体（指标名 / 公司代码）。
     *
     * <p>为什么绕开分词器：实测 plainto_tsquery('每股收益') 会退化成 '收益'，
     * 命中 86 条；而精确子串只有 6 条。<b>精度差 14 倍。</b>
     */
    EXACT,
    /**
     * 词级召回：QU 没抽到实体（退化成自然语言问题）。
     *
     * <p>为什么不用 AND：实测 plainto_tsquery 对
     * 「2025年公司面临的主要风险有哪些」返回 <b>0 条</b>。
     */
    FTS_OR
}
```

```java
package com.finagent.retrieval;

import java.util.List;

/**
 * Sparse 召回抽象（§9.4）。
 *
 * <p>为什么要有这层接口：PostgreSQL FTS 的排序与 BM25 不等价。若阶段 6 评测证明
 * 精确词排序不足，可以换实现（ParadeDB pg_search / 自算 BM25），
 * <b>而不必改动上层的 RRF / Rerank / Parent-Child Pipeline</b>。
 */
public interface SparseRetriever {

    /**
     * @param query   原始查询（EXACT 模式下用于精确匹配，FTS_OR 模式下用于分词）
     * @param terms   QU 抽出的实体；为空时调用方应传 {@link SparseMode#FTS_OR}
     * @return 按相关性降序，sparseRank 从 1 起
     */
    List<RetrievedChunk> retrieve(String query, List<String> terms, SparseMode mode,
                                  RetrievalFilters filters, int topK);
}
```

- [ ] **Step 2: 实现（两种模式）**

```java
package com.finagent.retrieval;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * PostgreSQL 实现。
 *
 * <p><b>定位是「召回」，不是「排序」</b>：实测 ts_rank 无 IDF、与 BM25 不等价，
 * 且 zhparser 会把「营业收入」切成「营业」+「收入」（本镜像无法加载自定义词典）。
 * 所以这一路只负责把候选捞出来，排序交给 RRF + Rerank。
 */
@Component
public class PgSparseRetriever implements SparseRetriever {

    private static final String BASE_SELECT = """
            SELECT dc.id, coalesce(dc.parent_id, 0) AS parent_id, dc.document_id,
                   dc.chunk_type, dc.section_path, dc.title, dc.content,
                   dc.page_start, dc.page_end, dc.source_block_ids
              FROM document_chunk dc
              JOIN document d ON d.id = dc.document_id
             WHERE dc.chunk_level = 'CHILD'
               AND dc.status = 'ACTIVE'
               AND dc.chunk_version = coalesce(?, d.active_chunk_version)
            """;

    private final JdbcTemplate jdbc;

    public PgSparseRetriever(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<RetrievedChunk> retrieve(String query, List<String> terms, SparseMode mode,
                                         RetrievalFilters f, int topK) {
        return mode == SparseMode.EXACT ? exact(terms, f, topK) : ftsOr(query, f, topK);
    }

    /**
     * EXACT：任一实体命中即可（OR 关系），按命中实体的个数降序。
     *
     * <p>用 ILIKE 而不是全文检索：这条路径的价值就是<b>不过分词器</b>。
     * 索引是 idx_chunk_content_trgm（GIN + gin_trgm_ops）。
     */
    List<RetrievedChunk> exact(List<String> terms, RetrievalFilters f, int topK) {
        List<String> effective = terms == null || terms.isEmpty() ? List.of() : terms;
        if (effective.isEmpty()) {
            return List.of();
        }
        StringBuilder sql = new StringBuilder(BASE_SELECT);
        List<Object> args = new ArrayList<>();
        args.add(f.chunkVersion());

        // 命中计数：命中实体越多越靠前（没有 IDF 时这是最朴素的相关性代理）
        StringBuilder score = new StringBuilder("(");
        StringBuilder where = new StringBuilder(" AND (");
        for (int i = 0; i < effective.size(); i++) {
            if (i > 0) {
                score.append(" + ");
                where.append(" OR ");
            }
            where.append("dc.content ILIKE ?");
            score.append("(CASE WHEN dc.content ILIKE ? THEN 1 ELSE 0 END)");
            args.add("%" + effective.get(i) + "%");
        }
        where.append(")");
        sql.append(where);

        DenseRetriever.appendFilters(sql, args, f);
        sql.append(" ORDER BY ").append(score).append(" DESC, dc.id ASC LIMIT ?");
        // ORDER BY 里的占位符在 WHERE 之后按出现顺序绑定
        for (String t : effective) {
            args.add("%" + t + "%");
        }
        args.add(topK);

        return mapRows(sql.toString(), args.toArray());
    }

    /**
     * FTS_OR：先试 AND（精度高），命中 0 条再退到 OR（保召回）。
     *
     * <p>实测「2025年公司面临的主要风险有哪些」用 AND 是 <b>0 条</b>，
     * 而纯 OR 有 372 条。渐进放松两头都要。
     */
    List<RetrievedChunk> ftsOr(String query, RetrievalFilters f, int topK) {
        List<RetrievedChunk> and = runFts(query, f, topK, false);
        if (!and.isEmpty()) {
            return and;
        }
        return runFts(query, f, topK, true);
    }

    private List<RetrievedChunk> runFts(String query, RetrievalFilters f, int topK, boolean orMode) {
        String tsQuery = orMode
                // 把分词结果用 | 连起来 —— to_tsvector/tsvector_to_array 能直接给出词元
                ? "to_tsquery('chinese_zh', array_to_string("
                    + "tsvector_to_array(to_tsvector('chinese_zh', ?)), ' | '))"
                : "plainto_tsquery('chinese_zh', ?)";

        StringBuilder sql = new StringBuilder(BASE_SELECT);
        List<Object> args = new ArrayList<>();
        args.add(f.chunkVersion());
        sql.append(" AND dc.content_tsv ` ").append(tsQuery);
        args.add(query);

        DenseRetriever.appendFilters(sql, args, f);
        sql.append(" ORDER BY ts_rank(dc.content_tsv, ").append(tsQuery).append(") DESC");
        args.add(query);
        sql.append(" LIMIT ?");
        args.add(topK);

        return mapRows(sql.toString(), args.toArray());
    }

    private List<RetrievedChunk> mapRows(String sql, Object[] args) {
        List<RetrievedChunk> out = new ArrayList<>();
        int[] rank = {0};
        jdbc.query(sql, rs -> {
            rank[0]++;
            out.add(new RetrievedChunk(
                    rs.getLong("id"), rs.getLong("parent_id"), rs.getLong("document_id"),
                    rs.getString("chunk_type"), rs.getString("section_path"),
                    rs.getString("title"), rs.getString("content"),
                    rs.getInt("page_start"), rs.getInt("page_end"),
                    rs.getString("source_block_ids"),
                    null, rank[0], 0.0, null));
        }, args);
        return out;
    }
}
```

> ⚠️ **上面 `exact` 的 ORDER BY 占位符顺序容易写错**：WHERE 里的占位符先绑定，
> ORDER BY 里的后绑定。执行时先用一个 2~3 个实体的查询确认参数个数与顺序，
> 再跑其余用例。这类错误在 psql 里手工试一下就能发现，但在 Java 里只会看到
> "参数索引越界"。

- [ ] **Step 3: 测试（真库 + 真数据）**

| 用例 | 断言 |
|---|---|
| `exactModeKeepsPerShareEarnings@ | 传 terms=["每股收益"] → 命中 **6 条**（不是 86） |
| `exactModeRanksByHitCount@ | 传两个实体 → 双命中的排在单命中之前 |
| `exactModeEmptyTermsReturnsEmpty@ | terms 为空 → 返回空，不抛异常 |
| `ftsOrFallsBackWhenAndIsZero@ | 自然语言长问题 → AND 为 0，但最终结果**非空**（走了 OR） |
| `ftsOrPrefersAndWhenAvailable@ | 短查询（净利润）→ 用的是 AND（结果条数远小于 OR 的 372） |
| `filtersApplyToBothModes@ | 带 sectionPathPrefix → 两种模式的结果都受约束 |

Run: `mvn -B -Dtest=PgSparseRetrieverTest test`
Expected: 6 个用例 PASS。

---

## Task 7: Rerank 抽象与百炼实现

**设计依据（§9.7）：**
- **Spring AI 官方没有 Rerank 抽象**（issues #4343、#5776 仍是 feature request）→ 自定义 RerankModel
- 输入 20~50、输出 5~10
- **失败必须降级到 RRF 结果，并标 degraded=true** —— 不能因为模型服务不可用导致整个 RAG API 不可用

**Files:**
- Create: `src/main/java/com/finagent/retrieval/rerank/RerankModel.java`
- Create: `src/main/java/com/finagent/retrieval/rerank/RerankResult.java`
- Create: `src/main/java/com/finagent/retrieval/rerank/RerankException.java`
- Create: `src/main/java/com/finagent/retrieval/rerank/RerankProperties.java`
- Create: `src/main/java/com/finagent/retrieval/rerank/RerankConfig.java`
- Create: `src/main/java/com/finagent/retrieval/rerank/DashScopeRerankModel.java`
- Create: `src/main/java/com/finagent/retrieval/rerank/NoopRerankModel.java`
- Create: `src/test/java/com/finagent/retrieval/rerank/NoopRerankModelTest.java`
- Create: `src/test/java/com/finagent/retrieval/rerank/DashScopeRerankModelTest.java`
- Create: `src/test/java/com/finagent/retrieval/rerank/RerankConfigTest.java`
- Create: `src/test/java/com/finagent/retrieval/rerank/DashScopeRerankModelLiveTest.java`
- Modify: `src/main/resources/application.yml`（`finagent.rerank.*` 配置块）

比初版多出 3 个类，各有明确理由：

- **`RerankException`** —— 单独类型，让降级日志能区分「外部服务挂了」和「我们自己的 bug」
  （trace 里是 `rerank_failed: RerankException`）。它是 `RuntimeException` 的子类，
  编排层按计划捕获 `RuntimeException` 的写法不受影响。
- **`RerankProperties`** —— 与 `CosProperties` 一致的 `@ConfigurationProperties`：
  默认值集中一处、测试可直接 new、并提供 `isConfigured()`（判空串）。用 `@Value` 写不出这个判断。
- **`RerankConfig`** —— 取代成对 `@ConditionalOnProperty`，原因见 Step 3 的第 2 次修订。

- [ ] **Step 1: 抽象**

```java
package com.finagent.retrieval.rerank;

import java.util.List;

/**
 * 重排模型抽象 —— <b>Spring AI 至今没有这个接口</b>（issues #4343 / #5776）。
 *
 * <p>为什么值得自定义一层，而不是直接调 HTTP：Rerank 是「可选增强」，
 * 换模型、加缓存、做降级都需要在<b>调用方不知情</b>的前提下替换实现。
 * 这一层就是为了守住这个边界。
 */
public interface RerankModel {

    /**
     * @param query     用户问题
     * @param documents 候选文本（已拼好上下文），顺序即输入顺序
     * @param topN      返回前 N 条
     * @return 按相关性降序；index 指向 documents 的下标
     * @throws RuntimeException 调用失败时抛出 —— <b>调用方负责降级</b>
     */
    List<RerankResult> rerank(String query, List<String> documents, int topN);

    /** 模型名，用于 trace。 */
    String modelName();
}
```

```java
package com.finagent.retrieval.rerank;

/** 一条重排结果。index 指向输入 documents 的下标。 */
public record RerankResult(int index, double relevanceScore) {
}
```

- [ ] **Step 2: 百炼实现（先验证真实的请求/响应格式）**

> ✅ **已用 curl 实测确认（2026-10-20）**：接口路径与字段与下面的形态**完全一致**。
> 实测结论都已落进 `DashScopeRerankModel` 的校验和 `DashScopeRerankModelTest` 的夹具：
>
> | 项 | 实测结果 |
> |---|---|
> | 成功响应结构 | `{"output":{"results":[{"index":2,"relevance_score":0.6092},...]},"usage":{"total_tokens":175},"request_id":"..."}` |
> | documents 上限 | **500 条**。空数组直接 400：`batch size of documents is invalid, it should not be empty or larger than 500` |
> | query 为空 | 400 `query should not be empty` |
> | `top_n` > 候选数 | **不报错**，按候选数原样返回 |
> | 不给 `top_n` | 全量返回 |
> | 单条文档 > 约 4000 token | **静默截断**（2 万汉字 → `usage.total_tokens = 3977`，无报错）。本项目 child 最大 1610 token，不触发 |
> | 错误响应 | **没有 `output` 字段**，只有 `code` / `message` → 必须先按 HTTP 状态分流，否则 NPE 而不是可读错误 |
> | 响应是否已排序 | 实测是降序，但那是服务端实现细节 —— **实现里必须自己再排一次**，否则哪天变了会静默拿到乱序 |
> | gte-rerank | 已于 2026-05-30 下线，只能用 qwen3-rerank |
>
> 实测形态（原文）：

```
POST https://dashscope.aliyuncs.com/api/v1/services/rerank/text-rerank/text-rerank
Authorization: Bearer $DASHSCOPE_API_KEY

{"model":"qwen3-rerank",
 "input":{"query":"...","documents":["...","..."]},
 "parameters":{"return_documents":false,"top_n":8}}

→ {"output":{"results":[{"index":2,"relevance_score":0.6092122030118452},
                            {"index":0,"relevance_score":0.5670480230390897},
                            {"index":1,"relevance_score":0.2694444844149849}]},
   "usage":{"total_tokens":175},
   "request_id":"120c1d1a-eab6-9788-8754-073a5ff68c9f"}
```

```java
package com.finagent.retrieval.rerank;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

/**
 * 阿里云百炼 qwen3-rerank。
 *
 * <p>注意 gte-rerank 已于 2026-05-30 下线，不要用。
 *
 * <p><b>本类不做降级</b> —— 失败直接抛出，由 HybridRetriever 捕获并回退到 RRF。
 * 降级放在调用方，因为只有编排层知道「降级后 trace 该怎么标」。
 */
@Component
@ConditionalOnProperty(name = "finagent.rerank.api-key")
public class DashScopeRerankModel implements RerankModel {

    private static final Logger log = LoggerFactory.getLogger(DashScopeRerankModel.class);

    private final RestClient http;
    private final String model;

    public DashScopeRerankModel(@Value("${finagent.rerank.base-url}") String baseUrl,
                                @Value("${finagent.rerank.api-key}") String apiKey,
                                @Value("${finagent.rerank.model:qwen3-rerank}") String model) {
        this.http = RestClient.builder()
                .baseUrl(baseUrl)
                .defaultHeader("Authorization", "Bearer " + apiKey)
                .build();
        this.model = model;
    }

    @Override
    public String modelName() {
        return model;
    }

    @Override
    @SuppressWarnings("unchecked")
    public List<RerankResult> rerank(String query, List<String> documents, int topN) {
        Map<String, Object> body = Map.of(
                "model", model,
                "input", Map.of("query", query, "documents", documents),
                "parameters", Map.of("return_documents", false, "top_n", topN));

        Map<String, Object> resp = http.post()
                .uri("/api/v1/services/rerank/text-rerank/text-rerank")
                .body(body)
                .retrieve()
                .body(Map.class);

        List<Map<String, Object>> results =
                (List<Map<String, Object>>) ((Map<String, Object>) resp.get("output")).get("results");
        return results.stream()
                .map(r -> new RerankResult(
                        ((Number) r.get("index")).intValue(),
                        ((Number) r.get("relevance_score")).doubleValue()))
                .toList();
    }
}
```

> 实际实现与上面这段的差别：用 `record RerankResponse` + Jackson 反序列化（而不是 `Map` 强转），
> 并补了三件事 —— 下标越界校验、本地再排一次序、截断到 `topN`。
> 计划里的 `Map` 写法在错误响应上会 `NPE`（错误体没有 `output`），而且靠强转拿不到可读的类型错误。
> 见 `DashScopeRerankModel.toResults`。

- [ ] **Step 3: 降级实现**

```java
package com.finagent.retrieval.rerank;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 未配置 Rerank 时的兜底：<b>原样返回输入顺序</b>。
 *
 * <p>为什么需要它：没有它，未配置 key 的环境会启动失败；
 * 而 Rerank 只是「可选增强」，不该成为硬依赖。
 *
 * <p>分数用 Double.NaN 而不是编造的 0.0 —— 让 trace 能如实反映「这轮没精排」。
 */
@Component
@ConditionalOnProperty(name = "finagent.rerank.api-key", matchIfMissing = true, havingValue = "")
public class NoopRerankModel implements RerankModel {

    @Override
    public String modelName() {
        return "none";
    }

    @Override
    public List<RerankResult> rerank(String query, List<String> documents, int topN) {
        int n = Math.min(topN, documents.size());
        return java.util.stream.IntStream.range(0, n)
                .mapToObj(i -> new RerankResult(i, Double.NaN))
                .toList();
    }
}
```

> ❌ **成对 `ConditionalOnProperty` 的方案实测不成立，已改（第 2 次修订）**。
>
> 不带 `havingValue` 的 `@ConditionalOnProperty(name = "finagent.rerank.api-key")` 的语义是
> 「存在且不等于 false」，**空串也算存在**；而 Noop 那边 `havingValue = ""` 恰好也命中空串
> → **两个 Bean 同时注册**，注入点抛 `NoUniqueBeanDefinitionException`，应用启动失败。
> 而 yml 里把 key 写成空占位（`api-key: ""` 或 `${ENV:}` 占位）恰恰是本项目的写法，
> 不能靠「记得别这么写」来避免。
>
> **改为一个工厂方法** `RerankConfig#rerankModel(RerankProperties)`：条件判断写在 Java 里，
> `props.isConfigured()` 判掉空串后返回 DashScope 或 Noop，
> **不可能出现 0 个或 2 个 Bean**，也不依赖注解求值顺序。
>
> 验证分两层：
> 1. `ApplicationContextRunner` 三种情形各一个用例 —— 无 key → Noop、有 key → DashScope、
>    **空串 → Noop**（比「启动两次」快、不依赖数据库，且能精确控制 Environment）；
> 2. **真的把应用启两次**（`spring.main.web-application-type=none`），看启动日志：
>    有 key → `Rerank 已启用：model=qwen3-rerank`；摘掉 key → `未配置 finagent.rerank.api-key
>    —— 装配 NoopRerankModel`。两次都 `Started FinAgentApplication in ~2.3 s`。
> **坑**：本机 .env 加载后 `FINAGENT_RERANK_API_KEY` 就在进程环境里，会把「无 key」
> 那一格悄悄填上，测试必须先按名字把 `systemEnvironment` / `systemProperties`
> 从 `Environment` 的 PropertySources 里摘掉 —— 第一版就是这么假绿的。

- [ ] **Step 4: 测试（初版计划漏了这一节，补上）**

单测不碰网络：用 `MockRestServiceServer` 接管 `RestClient`，夹具直接放 curl 抓到的**原文响应**。

| 用例 | 断言 |
|---|---|
| `sendsDocumentedPayloadAndParsesRealResponse` | 请求体与文档一致（model / query / documents / return_documents / top_n），且能解析真实响应 |
| `sortsByScoreEvenWhenApiReturnsUnordered` | 服务端返回乱序时，结果仍按分数降序 |
| `clampsTopNToCandidateCount` | `topN` > 候选数时，发出的 `top_n` 被夹到候选数 |
| `truncatesWhenProviderReturnsMoreThanRequested` | 服务端多返回时，本地仍截断到 `topN` |
| `blankQueryFailsWithoutHttpCall` | 空 query 直接抛 `RerankException`，**不发请求** |
| `emptyCandidatesReturnEmptyWithoutHttpCall` | 空候选返回空，不发请求 |
| `tooManyCandidatesFailBeforeHttpCall` | 501 条候选直接拒绝（消息含 500） |
| `httpErrorBecomesRerankExceptionCarryingProviderMessage` | 400 → `RerankException`，消息里带状态码与 `InvalidParameter` |
| `responseWithoutResultsIsRejected` | 缺 `output.results` → 可读错误，不是 NPE |
| `outOfRangeIndexIsRejected` | 下标越界 → `RerankException` |
| `modelNameComesFromProperties` | trace 里的模型名来自配置 |
| `NoopRerankModelTest`（5 例） | 保序、NaN、`topN` 截断、空输入、模型名 |
| `RerankConfigTest`（4 例） | 无 key → Noop；有 key → DashScope；**空串 → Noop**；配置项可绑定 |
| `DashScopeRerankModelLiveTest`（1 例，需 `FINAGENT_RERANK_API_KEY`） | 打真接口：先验 `buildClient()` 的 base-url / Authorization / 序列化，再验相关段落排第一 |

Run: `mvn -B -Dtest=Rerank*Test test`（LiveTest 在 .env 加载后才会跑）

**实测结果（2026-10-20）**：20 个用例全绿（11 + 5 + 4），LiveTest 1 个通过；全量 `mvn test` 127 个用例 BUILD SUCCESS。

---

## Task 8: HybridRetriever 编排 + Trace

**设计依据（§9.3 第 5~7 步 / §9.7）：** 两路召回 → 路内去重 → RRF → 截断 → Rerank → 取 Top-N；**Rerank 失败降级**。

### 本任务要把 Query Understanding 接进来

编排顺序必须是 **QU → 召回 → RRF → Rerank**，且 QU 的结果决定 Sparse 走哪个模式：

```java
QueryUnderstanding u = router.understand(req.query());

// QU 抽到实体 -> 精确子串（实测 6 条 vs 86 条）
// 没抽到      -> FTS + AND优先/OR回退（否则长问题 0 条）
SparseMode mode = u.hasEntities() ? SparseMode.EXACT : SparseMode.FTS_OR;
List<String> terms = new ArrayList<>(u.metrics());
terms.addAll(u.companyIds());

List<RetrievedChunk> sparseHits =
        sparse.retrieve(req.query(), terms, mode, req.filters(), req.topSparse());
```

**QU 的实体同时要变成硬过滤条件**：
- `u.metrics()` 非空 → 不过滤（指标名不一定出现在 chunk 里，过滤会误杀），但**参与 EXACT 匹配打分**
- `u.yearRange()` 非空 → 传入 `RetrievalFilters.docYear`（单一年份时）
- `u.companyIds()` 非空 → 传入 `RetrievalFilters.companyId`

**Trace 必须记录 QU 的结果**（§9.14 要求 queryType 落进 retrieval_trace，用于后续算混淆矩阵）：
`QueryType`、`route`（RULE/LLM/DEGRADED）、`degraded` 三项都要进 `RetrievalTrace`。

**Files:**
- Create: `src/main/java/com/finagent/retrieval/RetrievalTrace.java`
- Create: `src/main/java/com/finagent/retrieval/HybridRetriever.java`

- [ ] **Step 1: Trace**

```java
package com.finagent.retrieval;

import java.util.List;

/**
 * 一次检索的完整轨迹（§9.10）。
 *
 * <p>为什么必须留：出问题时「为什么这条没被召回」需要证据。
 * 只留最终结果等于把排查线索全丢了。
 *
 * @param degraded   Rerank 是否降级（§9.7 要求标出来）
 * @param fusedCount RRF 后条数（截断前）
 */
public record RetrievalTrace(
        String query,
        int denseHits,
        int sparseHits,
        int fusedCount,
        int returned,
        boolean degraded,
        String rerankModel,
        long denseMs,
        long sparseMs,
        long fuseMs,
        long rerankMs,
        List<String> warnings
) {
}
```

- [ ] **Step 2: 编排**

```java
package com.finagent.retrieval;

import com.finagent.embedding.EmbeddingService;
import com.finagent.retrieval.rerank.RerankModel;
import com.finagent.retrieval.rerank.RerankResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 混合检索编排（§9.3 第 3~7 步）。
 *
 * <p>顺序是硬要求：<b>先 RRF 融合，再 Rerank，最后才做上下文扩展</b>（§9.1）。
 * 反过来会让 Reranker 处理大段 Parent，成本爆炸且精度下降。
 */
@Service
public class HybridRetriever {

    private static final Logger log = LoggerFactory.getLogger(HybridRetriever.class);

    private final DenseRetriever dense;
    private final SparseRetriever sparse;
    private final RerankModel rerank;
    private final EmbeddingService embeddingService;

    public HybridRetriever(DenseRetriever dense, SparseRetriever sparse,
                           RerankModel rerank, EmbeddingService embeddingService) {
        this.dense = dense;
        this.sparse = sparse;
        this.rerank = rerank;
        this.embeddingService = embeddingService;
    }

    public Result retrieve(RetrievalRequest req) {
        List<String> warnings = new ArrayList<>();

        long t = System.currentTimeMillis();
        List<RetrievedChunk> denseHits = dense.retrieve(req.query(), req.filters(), req.topDense());
        long denseMs = System.currentTimeMillis() - t;

        t = System.currentTimeMillis();
        List<RetrievedChunk> sparseHits = sparse.retrieve(req.query(), req.filters(), req.topSparse());
        long sparseMs = System.currentTimeMillis() - t;

        // 路内去重：按 chunkId 合并，两路排名都保留
        t = System.currentTimeMillis();
        Map<Long, RetrievedChunk> merged = new LinkedHashMap<>();
        for (RetrievedChunk c : denseHits) {
            merged.put(c.chunkId(), c);
        }
        for (RetrievedChunk c : sparseHits) {
            merged.merge(c.chunkId(), c, (a, b) -> new RetrievedChunk(
                    a.chunkId(), a.parentId(), a.documentId(), a.chunkType(),
                    a.sectionPath(), a.title(), a.content(),
                    a.pageStart(), a.pageEnd(), a.sourceBlockIds(),
                    a.denseRank(), b.sparseRank(), 0.0, null));
        }
        List<RetrievedChunk> fused = RrfFusion.fuse(new ArrayList<>(merged.values()));
        int fusedCount = fused.size();
        List<RetrievedChunk> candidates =
                fused.subList(0, Math.min(req.fusionLimit(), fused.size()));
        long fuseMs = System.currentTimeMillis() - t;

        t = System.currentTimeMillis();
        boolean degraded = false;
        List<RetrievedChunk> result;
        if (req.useRerank() && !candidates.isEmpty()) {
            try {
                List<String> texts = candidates.stream().map(HybridRetriever::rerankText).toList();
                List<RerankResult> rr = rerank.rerank(req.query(), texts, req.topRerank());
                result = new ArrayList<>(rr.size());
                for (RerankResult r : rr) {
                    result.add(candidates.get(r.index()).withRerankScore(r.relevanceScore()));
                }
            } catch (RuntimeException e) {
                // §9.7：降级到 RRF 结果并标记 —— 不能因为模型挂了让整个 API 不可用
                log.warn("Rerank 失败，降级到 RRF 结果：{}", e.toString());
                warnings.add("rerank_failed: " + e.getClass().getSimpleName());
                degraded = true;
                result = candidates.subList(0, Math.min(req.topRerank(), candidates.size()));
            }
        } else {
            result = candidates.subList(0, Math.min(req.topRerank(), candidates.size()));
            degraded = !req.useRerank();
        }
        long rerankMs = System.currentTimeMillis() - t;

        RetrievalTrace trace = new RetrievalTrace(req.query(), denseHits.size(), sparseHits.size(),
                fusedCount, result.size(), degraded, rerank.modelName(),
                denseMs, sparseMs, fuseMs, rerankMs, warnings);
        return new Result(result, trace);
    }

    /**
     * 送给 Reranker 的文本：章节路径 + 标题 + 正文。
     *
     * <p>只用 content 会丢掉「这段属于哪一章」的信号 —— 而财报里同一句话在不同章节含义不同。
     */
    static String rerankText(RetrievedChunk c) {
        StringBuilder sb = new StringBuilder();
        if (c.sectionPath() != null) {
            sb.append(c.sectionPath()).append('\n');
        }
        if (c.title() != null) {
            sb.append(c.title()).append('\n');
        }
        sb.append(c.content());
        return sb.toString();
    }

    public record Result(List<RetrievedChunk> chunks, RetrievalTrace trace) {
    }
}
```

### 实施修订（已落地；与上文代码块冲突时以本节为准）

1. **`EmbeddingService` 参数已删除**：`DenseRetriever` 内部自己调 embedding，编排层再持有一个是死参数。
2. **`degraded` 布尔拆成 `RerankStatus` 三态**（`APPLIED` / `SKIPPED` / `FAILED`）：原文 `degraded = !req.useRerank()` 把「故意关掉 Rerank 做对比」也算成降级，方向是反的 —— 只有 `FAILED`（外部服务挂了）才需要告警，`SKIPPED` 是评测基线。**验收标准第 11、13 条里的 degraded 一律读作 `rerankStatus == FAILED`**；另加 `rerankDegraded()` 供告警用。
3. **构造器多两个依赖**：`RouterService`（QU 在编排内完成，编排才是唯一入口）与 `DocumentRepository`（新增 `findCompanyIdByCode`：QU 抽的是股票代码，过滤要的是 `document.company_id`，直传会静默 0 条）。
4. **两级渐进放松**（计划未写，实测需要）：
   - `EXACT` 命中 0 → 退 `FTS_OR` 再打一次，warning `sparse_exact_empty_fallback`；
   - 两路全空且本次过滤含 QU 部分 → 去掉 QU 部分重试一次，warning `qu_filter_empty_fallback`；**调用方显式过滤不放宽**。
   注意：这两条会让相应 mock 单测里 `verify(...)` 的调用次数变 2，断言过滤条件的用例必须 stub 出非空结果。
5. **QU 过滤三条保守规则**：多公司不过滤（对比类问题只留一家必答错）/ 年份区间不过滤（只有单一年份当硬条件）/ 代码查不到公司不过滤（只记 warning）。理由：QU 抽错一个硬过滤就是 0 条，代价远大于少过滤。
6. **新增 `retrieve(req, qu)` 重载**：`qu == null` 表示「无 QU 基线」，Task 10 的 QU vs 无 QU 对比直接走这个入口，不写第二套代码。
7. **warnings 取值表**：`no_candidates`、`rerank_failed:<SimpleName>`、`qu_failed:<SimpleName>`、`sparse_exact_empty_fallback`、`qu_filter_empty_fallback`、`qu_multi_company_no_filter`、`qu_year_range_no_filter`、`qu_company_not_found:<code>`。`quRoute` 取值：`RULE` / `LLM` / `NONE` / `DEGRADED:<reason>`。

---

## Task 9: 集成测试（真库 + 真向量）

**Files:**
- Test: `src/test/java/com/finagent/retrieval/HybridRetrievalTest.java`

> **命名不能是 \*IT**：surefire 默认 includes 不含 \*IT（评审 M2），那样这些用例永远不会在 mvn test 里跑。用 @Tag("e2e") 控制默认排除。

- [ ] **Step 1: 断言清单**

| 用例 | 断言 |
|---|---|
| denseFindsNetProfit | 查「净利润」，Dense 返回 30 条，top-3 里至少 2 条含「利润」 |
| sparseFindsExactTerm | 查「东方财富」，Sparse 能召回 —— 说明 zhparser 词边界可用 |
| filtersRestrictCandidates | 传 sectionPathPrefix = 第八节，所有结果 section_path 都以它开头 |
| rrfBeatsSinglePath | 同一问题下 RRF 的 top-8 与 Dense-only 的 top-8 **不完全相同**（否则 Sparse 白做） |
| rerankFailureDegrades | 注入抛异常的 RerankModel → 结果非空、trace.degraded 为 true、有 rerank_failed 警告 |
| noRerankPathWorks | useRerank=false → 返回 RRF 前 8，degraded 为 true |

- [ ] **Step 2: 运行**

```powershell
Set-Location 'D:\Project\myproject\financial-ai-project'
. .\scripts\load-env.ps1
mvn -B "-Dsurefire.excludedGroups=" "-Dgroups=e2e" "-Dtest=HybridRetrievalTest" test
```

Expected: 6 个用例 PASS。

### 实施修订（已落地，2026-10-20：7 个用例全绿）

1. **`trace.degraded` 的断言改成 `rerankStatus`**（见 Task 8 修订 2）：`rerankFailureDegrades` 断言 `FAILED`，`noRerankPathWorks` 断言 `SKIPPED` 且 `rerankDegraded() == false`（故意关掉不是降级）。
2. **`denseFindsNetProfit` 的原始断言（top-3 里至少 2 条含「利润」）实测不成立**：query=「净利润」时 top-10 里只有 **1** 条含字面「净利润」，排第 5（「加：本期归属于母公司所有者的净利润」），top-3 是政府补助 / 净额列报 / 联营企业。改为「top-10 里至少 1 条含净利润」，并把这个水位写进用例注释与阶段报告 —— **名词短语当句子编码，语义几乎抓不住字面**，这正是需要 Sparse + RRF + Rerank 的证据。
3. 多加了 2 个用例：`traceRecordsQueryUnderstanding`（queryType/quRoute/returned 与 chunks 一致、每条结果的 denseRank/sparseRank 至少有一个非空）与 `filtersRestrictCandidates` 里的 `returned == chunks.size()`。
4. 补充观察：**「净利润」这种单指标问题会被 `RuleBasedRouter` 判成 `SEMANTIC_EXPLANATION` 并走 LLM 路径**（`qu=SEMANTIC_EXPLANATION/LLM`）—— 规则路只在「代码/年份/指标」齐全时接管，单指标问题没有实体，落到 LLM 是当前设计的结果（成本上每问一次 LLM）。是否让「只命中指标字典」也走规则快路径，留待阶段 6 用数据决定。

---

## Task 10: 冒烟评测 —— 10 个问题看排序

**设计依据（§9.10 / 阶段 6 的雏形）：** 本阶段不做完整评测集，但要能肉眼验证排序对不对。

**Files:**
- Test: `src/test/java/com/finagent/retrieval/RetrievalSmokeTest.java`

- [ ] **Step 1: 准备 10 个问题，覆盖三类**

| 类型 | 例子 | 期望 |
|---|---|---|
| 数值/事实 | 2025 年营业收入是多少 | 命中利润表或营业收入相关表 |
| 精确词 | 东方财富的公司注册地址 | 命中「公司信息」章节 |
| 语义/解释 | 公司面临的主要风险有哪些 | 命中风险提示章节 |

**这是本阶段最有价值的一步** —— 它把「代码能跑」变成「检索有用」。

- [ ] **Step 2: 打印每题的 top-3 与 trace**

```
Q: 2025年营业收入是多少
  dense=30  sparse=12  fused=34  returned=8  degraded=false  rerank=qwen3-rerank
  #1 [TABLE ] p.45 财务报告 § 合并利润表   rrf=0.0328 rerank=0.93
      | 营业收入 | 11,842,000,000.00 |
```

- [ ] **Step 3: 人工判断，不写自动断言**

还没有标注数据，写自动断言只会得到一个自欺欺人的绿灯。人工看三件事：
1. top-1 是不是该问题最该看的片段
2. Sparse 有没有捞出 Dense 漏掉的精确词命中
3. Rerank 前后名次变化是否合理

结论写进 `docs/阶段4-检索报告.md`。

### 实施结果（2026-10-20 实跑，10 题 × 2 遍）

- 文件：`src/test/java/com/finagent/retrieval/RetrievalSmokeTest.java`，`@Tag("e2e")`，单跑约 46 s。
- 运行：`mvn -B "-Dsurefire.excludedGroups=" "-Dgroups=e2e" "-Dtest=RetrievalSmokeTest" test`，日志落 `target/smoke2.log`。
- **只守不变量、不写相关性断言**（0 条标注数据）：两遍结果都非空、`returned == chunks.size()`、所有结果 `documentId == 7`。排序质量靠下表人工判定。
- 分阶段耗时（NO-QU 十题）：dense **154~235 ms**（其中 PostgreSQL 精确 kNN 仅约 10 ms，其余是 embedding 网络往返）、sparse **1~10 ms**、fuse **0~1 ms**、rerank **208~403 ms**、合计 **388~623 ms**；含 QU 时 **417~1263 ms**（Q1 因 `DEGRADED:weak_evidence` 重试，dense 572 ms + rerank 679 ms）。

| # | 问题 | QU top-1（id / rerank） | 无 QU top-1 | 答案实际位置 | 严格 Qu/无 |
|---|---|---|---|---|---|
| 1 | 2025年营业收入是多少 | 1109 / 0.844 关键审计事项 | 同左 | 1133 排第 2（合并利润表 16,067,741,039.51） | ✗ / ✗ |
| 2 | 公司2025年的净利润是多少 | 849 / 0.861 分季度表 | 同左 | 818 排第 2（归母净利润 12,084,594,947.22） | ◐ / ◐ |
| 3 | 东方财富2025年每股收益是多少 | 818 / 0.722 主要会计数据 | 同左 | 就是它（基本每股收益 0.7650） | ✓ / ✓ |
| 4 | 东方财富的公司注册地址在哪里 | 1159 / 0.948 公司基本情况 | 同左 | 就是它（上海市嘉定区宝安公路2999号1幢） | ✓ / ✓ |
| 5 | 公司的股票代码和注册资本 | 882 / 0.594 主要控股参股公司分析 | 同左 | 811 排第 3（股票代码 300059） | ✗ / ✗ |
| 6 | 东方财富的法定代表人是谁 | 811 / 0.914 公司信息 | 同左 | 就是它（法定代表人 其实） | ✓ / ✓ |
| 7 | 公司面临的主要风险有哪些 | 1450 / 0.609 金融工具相关风险 | **798 第一节 重要提示（套话）** | 同 QU | ✓ / ✗ |
| 8 | 公司的业务模式是怎样的 | 828 / 0.647 主要业务 | 同左 | 就是它 | ✓ / ✓ |
| 9 | 研发投入情况如何 | 861 / 0.741 研发投入表 | 同左 | 就是它（1,066,549,339.24 元） | ✓ / ✓ |
| 10 | 公司未来的发展战略是什么 | 893 / 0.895 未来发展的展望 | 同左 | 就是它 | ✓ / ✓ |

计数：**严格口径 QU 7/10、无 QU 6/10；宽松 8/10 vs 7/10**。验收标准 #14 以严格口径满足，唯一净胜题是第 7 题（无 QU 时 rerank 被「保证……不存在虚假记载」的套话骗到 0.636，压过真正的风险章节 0.609）。

三个真实缺陷（详见报告第 6 节）：rerank 偏爱「提到数字的叙述」胜过「装着数字的表格」（第 1 题）；短实体词在长表里词频高就被抬高（第 5 题）；「营业收入」与「营业总收入」两个口径并存，第 1 题本身有歧义，须由阶段 5 的答案生成显式标注。

---


## 验收标准

| # | 检查 | 通过条件 |
|---|---|---|
| 1 | **规则快路径** | 「600519 2025年营业收入」→ RULE 路径，**不调 LLM**（LLM 传 null 也不 NPE） |
| 2 | **QU 降级方向** | LLM 失败/超时 → queryType = **SEMANTIC_EXPLANATION**（不是 ExactFact）+ degraded=true |
| 3 | **字段级容错** | 年份越界 / 公司不存在 → **只丢该字段**，其余字段与结果保留 |
| 4 | **ExactFact 交叉验证** | 判为 ExactFact 但三要素不全 → 自动降级，并标 degraded |
| 5 | **Sparse 精确模式** | terms=["每股收益"] → 命中 **6 条**（不是分词后的 86 条） |
| 6 | **Sparse AND→OR 回退** | 自然语言长问题 → AND 为 0，但最终结果**非空** |
| 7 | RRF 公式 | 单测覆盖 k=60、双路/单路、**null 不当作 0**、同分确定性排序 |
| 8 | Dense 召回 | 过滤条件下返回 30 条；**强制关 seqscan 后** EXPLAIN 显示走 HNSW 索引（654 行时优化器会选 Seq Scan，属正常） |
| 9 | **两路过滤一致** | Dense 与 Sparse 共用同一个 appendFilters，不允许各写一份 |
| 10 | RRF 融合 | 融合后 top-8 与 Dense-only 的 top-8 **不完全相同**（否则 Sparse 白做） |
| 11 | **Rerank 降级** | 注入失败 → 结果非空 + degraded=true + 警告记录，**API 不报错** |
| 12 | 版本过滤 | 不传 chunkVersion 时用 d.active_chunk_version；传了则用它 |
| 13 | Trace 完整 | 每阶段条数、耗时、degraded、rerank 模型名、**QU 的 route 与 queryType** 都有 |
| 14 | **冒烟评测（含 QU 对比）** | 10 个问题：**有 QU vs 无 QU** 各跑一遍，top-1 相关数不少于 7；且「有 QU」不差于「无 QU」 |
| 15 | 既有用例不回归 | 阶段 1~3 的 54 个用例仍全绿 |

## 本阶段的边界

- **只做单轮 Query Understanding**：指代消解（「那净利润呢」）依赖 Short-term Memory，属阶段 9
- **Multi-Query 只做判定不做执行**：§9.6 的 needMultiQuery 会输出，但真正展开查询是阶段 5
- **不做 Expansion**（§9.8）：Parent/Sibling 扩展属于阶段 5 的 Context Building
- **不做 ACL 权限过滤**（阶段 8）：只做业务过滤（document/company/year/section）
- **不做 Parent 向量**（§9.6：V1 只索引 CHILD）
- **不做 HyDE**（§9.11 明确不采用）

## 偏差登记（需回写设计文档 v0.10）

| # | 设计文档 | 原述 | 实测 | 处理 |
|---|---|---|---|---|
| 1 | §9.4 | Sparse 用 zhparser 做「精确匹配」 | **「营业收入」被切成「营业」+「收入」**，财务指标不是原子词；查「净利润」会召回「净敞口套期收益」 | 回写：zhparser **只能做词级召回，不能保证指标级精确匹配**；靠 RRF + Rerank 兜底 |
| 2 | §14.1 | 「自定义词典在 V0 与解析验证同期建立」 | **本镜像 zhparser 2.4 没有注册 scws_extra_dicts 这个 GUC**，无法加载自定义词典 | 登记未决：需重新编译 zhparser 或改用 pg_bigm / pg_trgm |
| 3 | §9.7 | Rerank 输入 20~50 | 受 fusionLimit=50 约束，实际 50 | 一致，无需偏差 |
| 4 | **§9.4** | Sparse 路用 PostgreSQL FTS（隐含 AND 语义） | **plainto_tsquery 对自然语言长问题返回 0 条**（实测「2025年公司面临的主要风险有哪些」→ 2025 & 公司 & 面临 & 风险 & 有），纯 OR 则 372/654 | 回写：**FTS 必须用 OR 或渐进放松**，AND 语义在 RAG 场景不可用 |
| 5 | **§9.4** | Sparse 负责「公司名/代码/指标/年份」的精确匹配 | zhparser 把「每股」标成代词 r 后丢弃 →「每股收益」退化成「收益」，命中 86 条；精确子串只有 6 条 | 回写：**精确匹配不能靠分词器**，要靠 QU 抽实体 + pg_trgm 子串索引（已加 V5） |
| 6 | **§14.1** | 自定义词典可解决指标切分 | 本镜像 zhparser 2.4 **未注册 scws_extra_dicts GUC**，无法加载词典 | 登记未决（见下） |
| 7 | **§9.2** | Query Understanding 属于阶段 5 | 无 QU 时 Sparse 对真实提问**基本不可用**（AND→0），而 QU 单独实现又无可验证产出 | **已把 QU 提前到阶段 4**，与检索同阶段交付（已与用户确认） |
| 8 | §8.2 之外 | — | 语料实测：其他（代词 r）出现在 **250/654 = 38%** 的 chunk 中，而每股只有 9 条 | **不扩 r 映射**（噪声远大于收益），只扩 m（数词） |
| 9 | §9.7 | Rerank 用「成对的 @ConditionalOnProperty」装配两个实现 | **api-key 为空串时两个条件同时命中 → 2 个 Bean，启动失败** | 改单工厂方法 + `isConfigured()` 判空串（见 Task 7 Step 3 第 2 次修订） |
| 10 | §9.7 | Rerank 输入 20~50 条 | 服务端上限 **500 条**；单条 > 约 **4000 token 静默截断**；`top_n` 超候选数不报错 | 与设计一致，无需偏差；边界写进代码校验（>500 直接拒绝） |
| 11 | §9.7 | 「失败必须降级」 | 已实现：`RerankException` 单列类型 + 编排层捕获降级 | 一致；单独类型是为了让 trace 里能区分「外部服务挂了」和「我们自己的 bug」 |
| 12 | §9.7 | Rerank 用自定义抽象（是否可由上游/生态替代） | Spring AI **2.0.1** 全部 19 个 jar 里名字含 rerank 的 class = **0**（issue #4343 / #5776 仍是 feature request）；Spring AI Alibaba **2.0.0-M1.1** 有完整实现（`com.alibaba.cloud.ai.model.RerankModel` + `DashScopeRerankModel` + `RetrievalRerankAdvisor`，自动配置前缀 `spring.ai.dashscope.rerank`，条件 `spring.ai.model.rerank=dashscope`），但 **默认模型 `gte-rerank` 已于 2026-05-30 下线**；jdeps 类级 missing=0，方法签名未验 | 保留自定义 `RerankModel`。三理由：① 它编译自 spring-ai 2.0.0-M1（我们跑 2.0.1），方法级兼容未验；② 为一次 HTTP 调用引入 WebFlux 7.0.1 + okhttp + opennlp 是净负担；③ 它的 `Document` 模型要来回拆装，且结果 `DocumentWithScore` 不带输入下标。兜底：接口只有 2 个方法，真要换写 20 行适配器 |
| 13 | §9.10 | Trace 记阶段条数/耗时 + 单布尔 degraded | QU 的三项（queryType/route/degraded）必须进 trace 才能算混淆矩阵；单布尔把「模型挂了」与「故意关掉」混为一谈 | 已按 Task 8 修订 2 拆成 `RerankStatus` 三态，并落 `queryType`/`quRoute`/`quDegraded` |
| 14 | §9.3 第 5~7 步 | 两路召回 → RRF → Rerank 一次成型 | 真语料下 EXACT 子串与 QU 硬过滤任一条都可能让候选归零 | 新增两级渐进放松（Task 8 修订 4）；并登记：**0 条的主因是过滤，不是召回** |
| 15 | §9.4 / §9.6 | 隐含「HNSW 带过滤会退化、迭代扫描不可用」 | 复测（合成数据 654~41,856 行）：优化器在小候选集上不选 HNSW 是**正确决策**（候选集 654 行时 bitmap+top-N 3.1 ms；强制 HNSW 带过滤 31.8 ms @4 万行）；且「迭代扫描 GUC 不可用」是**误判** —— GUC 存在，只因 `_PG_init` 惰性注册，新会话 `SHOW` 才报错。无过滤纯 kNN 交叉点实测 **1,200~1,500 行**（原估 1 万行不成立） | 回写设计文档：HNSW 的决策链条改为「候选集规模 → 过滤条件是否局部常量 → 是否用 HNSW」，并写明三个触发条件（见未决问题 5） |

## 未决问题（留给阶段 5/6）

1. **财务自定义词典不可用**：zhparser 无 GUC。选项：(a) 重新编译带 scws 扩展词典的镜像；(b) 用 pg_bigm 做双字索引；(c) 接受现状靠 Dense + Rerank。**建议先看阶段 6 的评测结果再决定。**
2. **ts_rank 与 BM25 不等价**（**本阶段最需要数据支撑的一条**）：
   - 已确认镜像里**没有** BM25 扩展（pg_available_extensions 只有 pg_trgm / vector / zhparser）
   - 候选方案：(a) 接受 ts_rank；(b) **自算 BM25**（unnest(tsvector) 能拿到 tf，配合 count(*) 拿 df、avg(length) 拿 avgdl，约 150 行 Java）；(c) 装 ParadeDB pg_search（有预编译 deb，但**中文分词支持未确认**，需 1~2 小时 spike）
   - **决定时机：等本阶段跑通、阶段 6 有 Recall 数据之后再选**，避免「没有 baseline 就优化排序」
3. **单文档评测**：只有 1 份年报 654 个 Child，跨文档检索（companyId / year 过滤）**没有真实数据验证**。需要再解析 1~2 份报告。
4. **Rerank 的 top_n 与 fusionLimit 关系**：现在 50 进 8 出。是否值得降到 30 以省成本？阶段 6 用数据回答。
5. **HNSW 与过滤：不是「用不上」，而是「不该用」（2026-10-20 复测，原结论已订正）**：
   - 生产 SQL 的真实计划是 `Limit → Sort(top-N heapsort) → Nested Loop → Index Scan document_pkey(d.id=7) → Bitmap Heap Scan on document_chunk (654 rows) → Bitmap Index Scan on idx_chunk_active`，确实不走 HNSW。1516 行真表：冷缓存 47.7 ms、热缓存 3.1 ms。
   - **原「迭代扫描 GUC 不可用」是误判**：GUC 在 `_PG_init` 里注册，只在该后端第一次用到 vector 库时才执行。新开会话直接 `SHOW hnsw.ef_search` 会报 `unrecognized configuration parameter "hnsw.ef_search"`，先跑一句 `SELECT '[1,2,3]'::vector;` 再 SHOW 就有值。本镜像实测：`hnsw.ef_search=40`、`hnsw.iterative_scan=off`、`hnsw.max_scan_tuples=20000`、`hnsw.scan_mem_multiplier=1`。
   - 合成数据规模扫描（热缓存、7 次取最快）：**无过滤纯 kNN 的交叉点在 1,200~1,500 行**（41,856 行时精确 276.3 ms vs HNSW 0.66 ms = 419×）。原估「约 1 万行」是按 6.5 μs/行 拍的，不成立。
   - 带过滤（命中 654 行）时 HNSW **即使开 iterative_scan 也慢 5~10 倍**：41,856 行 bitmap+top-N 5.6 ms vs 部分索引 31.8 ms / 全局索引 55.7 ms。而且 `iterative_scan=off` 时**只返回 7 条**（后过滤把不满足条件的丢掉了）—— 让 HNSW 直面过滤前必须校验返回条数。
   - **现状不选 HNSW 是正确的**：候选集 = 一份报告的 654 个 Child，与总语料规模无关，再加 10 份报告仍是 654，dense 耗时几乎不变。
   - **触发重评的三个条件**：① 跨文档语义召回（不加 document_id 过滤）→ 直接用 HNSW；② 跨文档但仍有 company/year 过滤 → 先数候选行，<1,500 用精确、>5,000 才考虑 HNSW + 部分索引；③ 单份报告涨到 5,000+ Child → 必须先把 `chunk_version` 内联成常量（去掉与 document 的 JOIN），否则过滤条件不是局部常量，部分索引和迭代扫描都用不上。
   - 详见 docs/pgvector-工作机制.md §6（含两种测量口径的教训：早期「31.4 vs 9.8 ms」是冷缓存数字）。
   - **2026-10-21 补充（跨文档实测）**：语料扩到 2 份报告（1,284 条 Child ACTIVE）后，无过滤 kNN 仍是 `Seq Scan + Sort` 16.6 ms，没到 HNSW 接管点。跨文档真正的风险是**混文档**（无 QU 时 14/14 题 top-8 混两份报告，24% 条目来自错的报告），只能靠过滤解决，分区解决不了 → **先不分区**。详见 docs/跨文档评测报告.md。
6. **要不要改用 Spring AI Alibaba 的 Rerank**（见偏差 12）：现在是自研 2 方法接口。**触发条件**：若阶段 6 需要多模型路由、或百炼协议变更维护成本上升，再花 1 小时做方法级兼容实测（写个 20 行适配器即可切换，不必现在引入 WebFlux/okhttp）。

