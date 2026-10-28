# 阶段 5：上下文构建与答案生成（ST5）

> 版本：v1.0（2026-10-21）
> 上游：docs/文档作用域设计.md v0.3（D1~D13）、docs/跨文档评测报告.md、设计文档 §9.8 / §9.9 / §9.10 / §9.12 / §9.13 / §9.15
> 前置：阶段 4 已完成（HybridRetriever + RRF + QU + Rerank，148 用例全绿）
> 命名：按用户约定的 ST5-YYYY-MM-DD-NN-name.md 格式，序号 05

---

> 📖 术语：本文出现的缩写（Parent/Child、embedding、RRF、QU、HNSW、e2e……）在 `docs/术语表.md` 有中文全称、为什么需要它、在本项目里长什么样。写法约定见 `docs/文档写作规范.md`。

## 0. 一句话目标

把「检索出 8 条 chunk」变成「一份可引用的、证据不越界的答案」：**上下文装配（§9.15）→ 答案生成（§9.12）→ 引用校验（§9.12 后半句）→ 一条能回放的 trace（§9.10）**。

三个必须解决的问题，全部来自阶段 4 的实测：

| 问题 | 实测证据 | 本阶段怎么解决 |
|---|---|---|
| **混文档** | 无 QU 时 14/14 题的结果同时含 2024 与 2025 两份报告；第 3 题问 2024 净利润，4 条来自 2025 年报，而两份报告里那个数字完全相同（9,610,120,743.64），生成阶段无从发现 | Task 2 作用域 + Task 3 不越界回退 |
| **选错证据层** | val@1 只有 8/12：top-1 常是叙述段（1658 主营业务分析 / 849 分季度表），真正该当证据的数字块排在 2~3 位 | Task 5 Parent 内二次选择（§9.15） |
| **引用不可验证** | 阶段 4 的 trace 只有 chunk id，没有页码/bbox；LLM 一旦自造 id 无从发现 | Task 6 引用必须反解析到上下文里的 chunk |

---

## 1. 范围

### 1.1 本阶段做

1. **V6 迁移**：document.period_start / period_end、doc_type 收敛 + CHECK、文档身份唯一约束、company.industry。
2. **作用域解析**：scope → resolvedDocumentIds（三种模式共用一条解析链）。
3. **检索契约改造**：RetrievalFilters 支持文档集合；QU 增加 docType；**QU 过滤落空时不再跨出作用域**。
4. **多文档配额融合**：比较类问题保证每份文档都有证据进入精排。
5. **上下文装配**：Parent 内二次选择、readingOrder 补齐、Token Budget、表格元信息随块携带。
6. **引用生成与校验**：§9.12 结构 + 反解析 + 无效引用剔除。
7. **答案生成**：结构化 JSON（answer / facts / citations / confidence）、拒答、单文档取证（D9）。
8. **编排与 API**：POST /api/chat、GET /api/scope/documents、trace 落日志。
9. **集成测试**：14 题跨文档 + 引用正确性 + 拒答 + 扩展建议。
10. **报告**：docs/阶段5-答案生成报告.md。

### 1.2 本阶段不做（明确排除）

| 不做 | 原因 |
|---|---|
| FinancialFact 入库与查询 | 用户已定 **Q1=A**：数字仍从 PDF 取，只保留 facts[] 结构壳（source=PDF） |
| 流式输出（SSE） | 用户已定 **Q2=A**：引用校验必须在返回前完成 |
| 文档级路由（大规模） | 阈值（>20 份 / >50,000 chunk）已定，但当前可检索文档只有 2 份，9 倍以上余量。C 模式退化单池并在 trace 标 doc_routing=unavailable |
| 会话持久化（conversation / message 表） | 阶段 9。本阶段 scope 由调用方每轮传入，sessionScope 由前端持有 |
| 指代消解 / 多轮改写 | 依赖短期记忆，阶段 9 |
| Web 前端 | 本阶段只出 API 与 JSON 契约，前端在阶段 7 |

### 1.3 三个技术选项（用户 2026-10-21 拍板，全选 A）

| # | 选项 | 决定 | 落地位置 |
|---|---|---|---|
| Q1 | FinancialFact | 不接，facts[] 由 LLM 从引用文本抽取，标 source=PDF | Task 7 |
| Q2 | 答案接口 | 非流式，一次返回完整 JSON | Task 8 |
| Q3 | 引用粒度 | pageStart / pageEnd + bbox | Task 6 |

---

## 2. 契约（写代码前先补 docs/关键契约.md）

### C5 作用域契约

- 作用域是**上界**，不是「提示」。任何检索都不得返回 resolvedDocumentIds 之外的 chunk，包括各种回退路径。
- 解析失败（0 份文档）→ **报错 SCOPE_EMPTY**，不得静默退化成全库。这是阶段 4 qu_filter_empty_fallback 踩过的坑：静默放宽让「查 2023 年」变成「返回 2024 与 2025 各 4 条」。
- 扩展（范围外文档）是**用户显式动作**：TURN_ONLY / SESSION / NONE。后端只负责算出扩展后的集合，不自动改 sessionScope。
- 解析结果必须带 resolverVersion（当前 scope-v1）与每份文档的 chunkVersion，供回放。

### C6 上下文装配契约

- 装配单位是 **Parent 内的 Child 集合**，不是一堆散 chunk。
- 入选规则（§9.15）：命中 Child 在 Parent 内按 rerankScore 降序，取不低于 0.6 倍该 Parent 最高分的 Child；再在 token 预算内补齐前后各 1 个同 Parent Child 以保持 readingOrder；Parent 全文仅在 queryType=COMPLEX_ANALYSIS 且 Parent ≤ 1,500 token 时使用。
- 每个块的 content 是**原文**；embedding_text 永远不得作为证据返回（C1）。
- 表格块必须携带 caption / unit / headers_json / pageStart / pageEnd，缺一项就写 null，**不得从正文里猜**。
- 预算超限时按低分块先出局，出局块记 context_truncated warning；**不得截断单个块的字符串**。

### C7 引用契约

- 引用只能指向**本轮上下文里出现过的 chunk**（比 §9.12 的「能反解析到真实 chunk」更严：真实 chunk 也不一定能引用，必须是被送去生成的那几块）。
- 页码、bbox、caption、unit **一律从库里重新读**，不采信模型输出。
- 反解析失败 → 剔除并计数，confidence 降级；剔除率超过 50% → 整条答案降为 low。

---

## 3. 任务分解

### 依赖图

```
Task 1 (V6)
   └─> Task 2 (ScopeResolver) ──> Task 3 (检索契约) ──> Task 4 (配额融合)
                                                             │
                          Task 5 (Context Builder) <──────────┘
                                   │
                          Task 6 (Citation) ──> Task 7 (Answer) ──> Task 8 (API)
                                                                        │
                                                          Task 9 (集成测试) ──> Task 10 (报告)
```

Task 1~4 可以并行做单元测试；Task 5 依赖 Task 3 的 ResolvedScope 传参；Task 7 依赖 Task 5/6。

## File Structure

```
src/main/resources/db/migration/
└── V6__scope_and_answer.sql        # 报告期 / doc_type 收敛 / 文档身份唯一 / company.industry

src/main/java/com/finagent/scope/          ← 新增
├── ScopeKind.java                    # DOCUMENTS / FILTER / CORPUS
├── ChatScope.java                    # 请求层面的范围（用户传什么）
├── ScopeQuery.java                   # FILTER 条件：公司 / 年份 / 类型 / 行业
├── DocMeta.java                      # 一份可检索文档的元数据快照（含报告期、chunk 版本）
├── ResolvedScope.java                # 解析结果：documentIds + DocMeta + resolverVersion
├── ScopeResolver.java                # scope → documentIds，含 0 份判定
├── ScopeAuthorizer.java              # 权限接口（阶段 9 换实现）
├── AllowAllAuthorizer.java           # 本阶段默认实现
├── ScopeProperties.java              # 软上限 5 份 / 路由阈值 20 份、5 万 chunk
└── ScopeEmptyException.java          # 0 份 → 409 SCOPE_EMPTY

src/main/java/com/finagent/retrieval/      ← 部分改动
├── RetrievalFilters.java             # 改：documentId → documentIds，加 docType
├── DocumentQuotaBalancer.java        # 新增：多文档配额（RRF 之后、Rerank 之前）
└── HybridRetriever.java              # 改：吃 ResolvedScope；回退不越界

src/main/java/com/finagent/context/        ← 新增
├── ContextBlock.java                 # 一块证据：原文 + 表格元信息 + 页码/bbox + 名次
├── ContextPackage.java               # 证据包：渲染文本 + blocks + token 数 + warnings
├── ContextBuilder.java               # §9.15：Parent 内二次选择 + 邻居补齐 + 预算
└── ContextProperties.java            # 6000 token / 0.6 阈值 / 邻居半径 1 / 整段 1500

src/main/java/com/finagent/citation/       ← 新增
├── Citation.java                     # §9.12 结构（含 docTitle、sectionPath）
├── BBox.java                         # {page, x, y, w, h}
├── TableRef.java                     # 本阶段恒 null（库里没有 tableId）
└── CitationValidator.java            # [E3] → chunk；越界/重复剔除

src/main/java/com/finagent/answer/         ← 新增
├── AnswerDto.java                    # LLM 输出契约：sufficient/answer/facts/usedEvidence
├── AnswerPrompts.java                # 系统提示词（唯一改 prompt 的地方）
├── AnswerService.java                # 调用 + lenient 解析 + 降级 + 拒答
├── FactGuard.java                    # D9 单文档取证
├── AnswerResult.java                 # answer + facts + citations + confidence + warnings
└── AnswerProperties.java             # facts 上限 8 等

src/main/java/com/finagent/chat/           ← 新增（唯一知道全链路的地方）
├── ChatRequest.java                  # question + scope + extensionPolicy
├── ChatResponse.java                 # answer/facts/citations/scope/trace/extensionSuggested
├── ChatService.java                  # 编排：scope → QU → 检索 → 上下文 → 答案
├── ChatTrace.java                    # §9.10 trace（组合 RetrievalTrace，不改它）
├── ScopeTrace.java
├── ChatController.java               # POST /api/chat、GET /api/scope/documents
└── ChatExceptionHandler.java         # SCOPE_EMPTY → 409、BAD_REQUEST → 400

src/main/java/com/finagent/document/       ← 改动
├── DocumentRepository.java           # 改：+ backfillPeriod（已完成）、+ findRetrievable（Task 2）
└── DocumentParseService.java         # 改：解析完成后回填报告期（已完成）

src/main/java/com/finagent/chunk/          ← 改动
└── ChunkRepository.java              # 改：+ findByIds / findChildrenByParents（ChunkView）

src/test/java/com/finagent/
├── document/DocumentPeriodTest.java          # 6 条（已完成）
├── scope/ScopeResolverTest.java              # 10 条
├── retrieval/ScopeFilteringTest.java         # 6 条（含 ANY(?) 绑定验证）
├── retrieval/DocumentQuotaBalancerTest.java  # 6 条
├── context/ContextBuilderTest.java           # 12 条
├── citation/CitationValidatorTest.java       # 10 条
├── answer/AnswerServiceTest.java             # 8 条（含 lenient 解析与拒答）
├── chat/ChatControllerTest.java              # 5 条（MockMvc）
└── chat/ChatE2ETest.java                     # 14 题 × 引用/混文档/拒答/数值
```

**为什么 scope 单独一个包**：它是「配置 → 集合」的纯函数，与检索的存储、索引、向量全无关系。分开后 10 条解析规则可以只对着数据库测，不用起检索链路，也不会因为改检索而误伤。

**为什么配额放在 retrieval 而不是 context**：配额发生在 RRF 之后、Rerank 之前 —— 它裁的是「候选」，不是「上下文」。放进 context 包会让人以为它可以放在检索之后调用，那就失去意义了（Rerank 已经按全局分数截断过）。

**为什么 answer 与 chat 分开**：answer 只依赖「问题 + 证据包 + 范围」，不依赖检索器；chat 是唯一知道全链路的类。这样 AnswerService 可以用手造的证据包单测，不必起 Spring 容器、不必调百炼。

**为什么不再单开一个 web 包**：controller 只有两个端点，且与编排同属「一次对话」的边界；拆两个包会为了一个 40 行的类多一层 import 关系。等阶段 8 有鉴权/多租户横切逻辑时再拆。

**改动面统计**：新增 40 个类（含 8 个测试类），修改 5 个已有类（RetrievalFilters / HybridRetriever / ChunkRepository / DocumentRepository / DocumentParseService），其中后两个已在 Task 1 完成，检索侧 3 个在 Task 3 改。

---

## Task 1：V6 迁移

### 目标

把作用域需要的元数据补齐，并把 doc_type 的脏数据收敛。

### 现状（2026-10-21 实测）

```
id | company_id | doc_type      | doc_year | version | parse_status
 2 |          2 | ANNUAL_REPORT |     2025 |       1 | FAILED
 4 |          4 | ANNUAL        |          |       1 | FAILED
 7 |          7 | ANNUAL        |     2025 |       1 | DONE
38 |          7 | ANNUAL        |     2024 |       1 | DONE
```

- 无 (company_id, doc_year, doc_type, version) 唯一约束 → 同一份报告可以重复登记两份，作用域解析会重复计数。
- 无 period_start / period_end → 无法判定本期与上期，也就无法处理对比列陷阱。
- company 只有 id, code, name, market，无 industry → B 模式按行业筛选落空。

### 迁移文件

新增 src/main/resources/db/migration/V6__scope_and_answer.sql：

```sql
-- 阶段 5：作用域元数据
-- 1) 公司行业（B 模式筛选项）
ALTER TABLE company ADD COLUMN industry varchar(64);

-- 2) 报告类型收敛：历史遗留 ANNUAL_REPORT 统一成 ANNUAL
UPDATE document SET doc_type = 'ANNUAL' WHERE doc_type = 'ANNUAL_REPORT';
ALTER TABLE document ADD CONSTRAINT document_doc_type_check
    CHECK (doc_type IN ('ANNUAL', 'SEMI_ANNUAL', 'Q1', 'Q3'));

-- 3) 报告期：用于判定本期证据与对比列陷阱
ALTER TABLE document ADD COLUMN period_start date;
ALTER TABLE document ADD COLUMN period_end   date;

-- 存量回填：只处理年报（其它类型需要解析产物里的报告期，留空并在解析流程里补）
UPDATE document
   SET period_start = make_date(doc_year, 1, 1),
       period_end   = make_date(doc_year, 12, 31)
 WHERE doc_type = 'ANNUAL' AND doc_year IS NOT NULL
   AND period_start IS NULL;

-- 4) 文档身份唯一：同一公司、同一期间、同一类型、同一版本只允许一行
CREATE UNIQUE INDEX uq_document_identity
    ON document (company_id, doc_year, doc_type, version)
 WHERE company_id IS NOT NULL AND doc_year IS NOT NULL;

COMMENT ON COLUMN company.industry IS '所属行业（B 模式筛选项，可空）';
COMMENT ON COLUMN document.period_start IS '报告期起始日（年报=1/1），用于判定本期/上期证据';
COMMENT ON COLUMN document.period_end IS '报告期截止日（年报=12/31）';
COMMENT ON COLUMN document.doc_type IS '报告类型：ANNUAL/SEMI_ANNUAL/Q1/Q3';
```

### 为什么唯一索引带 version

同一份年报被更正重发时（version=2），旧版本仍要保留给历史回答做引用回放。所以唯一键必须含 version，而不是「一份报告只能有一行」。

### 代码侧

- DocumentRepository 新增 findCompanyById(long)、listCompanies()、setIndustry(code, industry)（供运维脚本用）。
- upsertDocument **不加** period 参数。入库时按 doc_type + doc_year 推期间，放在 DocumentParseService.persistResult 里做，避免动已通过测试的签名。

### 验收

1. flyway_schema_history 有 V6 且 success = true。
2. SELECT doc_type, count(*) FROM document GROUP BY 1 → 只有 ANNUAL（4 行）。
3. SELECT id, period_start, period_end FROM document WHERE doc_year IS NOT NULL → doc 7 为 2025-01-01 至 2025-12-31，doc 38 为 2024-01-01 至 2024-12-31。
4. 重复插入 (7, 2024, 'ANNUAL', 1) 报 uq_document_identity 冲突。
5. psql 的 \d+ document 能看到三条新注释。

---

## Task 2：ScopeResolver

### 目标

把用户给的 scope 解析成一组**确定可检索的 document id**，并在解析阶段就暴露「0 份」这种失败。

### 数据模型

包 com.finagent.scope：

```java
/** 用户意图层面的范围（请求里传什么）。 */
public record ChatScope(ScopeKind kind, List<Long> documentIds, ScopeQuery query) {
    public static ChatScope corpus() { return new ChatScope(ScopeKind.CORPUS, List.of(), null); }
    public static ChatScope documents(List<Long> ids) { ... }
    public static ChatScope filter(ScopeQuery q) { ... }
}

public enum ScopeKind { DOCUMENTS, FILTER, CORPUS }

/** FILTER 模式的筛选条件，全部可空 = 不限。 */
public record ScopeQuery(List<String> companyCodes, List<Integer> years,
                         List<String> docTypes, List<String> industries) { }

/** 解析结果：检索层唯一认的范围对象。 */
public record ResolvedScope(
        ScopeKind kind,
        List<Long> documentIds,        // 空 = 全库（仅 CORPUS）
        List<DocMeta> documents,
        String resolverVersion,        // "scope-v1"
        String effectiveReason,        // "SESSION" | "TURN_ONLY_EXTENSION" | "SESSION_EXTENSION"
        List<String> warnings) {

    public boolean isEmpty() { return documentIds.isEmpty(); }
    public int size() { return documentIds.size(); }
    public Set<Long> companyIds() { ... }        // 供 QU 校验
    public DocMeta document(long id) { ... }
    public ResolvedScope extend(List<Long> extra, String reason) { ... }
}

/** 一份可检索文档的元数据快照。 */
public record DocMeta(long id, Long companyId, String companyCode, String companyName,
                      Integer docYear, String docType, LocalDate periodStart, LocalDate periodEnd,
                      int chunkVersion, String title) { }
```

### 核心方法

```java
@Component
public class ScopeResolver {

    static final String RESOLVER_VERSION = "scope-v1";

    private final DocumentRepository documents;
    private final ScopeProperties props;
    private final ScopeAuthorizer authorizer;   // 本阶段 AllowAllAuthorizer

    /** 解析；0 份文档抛 ScopeEmptyException（由 @RestControllerAdvice 转 409）。 */
    public ResolvedScope resolve(ChatScope scope) {
        return switch (scope == null ? ScopeKind.CORPUS : scope.kind()) {
            case CORPUS -> resolveCorpus();
            case DOCUMENTS -> resolveDocuments(scope.documentIds());
            case FILTER -> resolveFilter(scope.query());
        };
    }
}
```

### 解析规则（逐条对应 D 决策）

| 规则 | 依据 |
|---|---|
| DOCUMENTS 超过 5 份 → 只写 warning scope_over_soft_limit，不拦住 | D3 / D12 |
| 请求里不存在的 id → 剔除并 warning scope_document_not_found；全部不存在 → SCOPE_EMPTY | 无 |
| 存在但未 DONE 或无 active_chunk_version → 剔除并 warning scope_document_not_ready | 库里 doc 2（茅台）就是 FAILED，0 chunk |
| FILTER 四个条件全部为空 = 全库（等价 CORPUS），但 kind 保持 FILTER | D10 |
| FILTER 解析出 0 份 → SCOPE_EMPTY，**不回退全库** | C5 |
| years 用 doc_year 精确匹配（不做区间） | 区间由 QU 处理，作用域只认哪几份 |
| docTypes 传 null 或空数组 = 全部类型 | D10 |
| 权限 | 本阶段只有 ScopeAuthorizer 接口 + AllowAllAuthorizer 默认实现，阶段 9 换实现 |

### 需要 DocumentRepository 新增的查询

```java
/** 可检索文档（DONE 且有 active_chunk_version）。四个条件全部可空。 */
public List<DocMeta> findRetrievable(List<Long> ids, List<String> companyCodes,
                                     List<Integer> years, List<String> docTypes,
                                     List<String> industries)
```

SQL 用「空则不限」的写法，避免拼字符串。**注意 null 语义**：PostgreSQL 里 = ANY(NULL) 返回 NULL 而不是 false，所以不要写 (? IS NULL OR d.id = ANY(?)) 的双占位符版本，直接用单占位符：

```sql
SELECT d.id, d.company_id, c.code, c.name, d.doc_year, d.doc_type,
       d.period_start, d.period_end, d.active_chunk_version, d.title
  FROM document d
  LEFT JOIN company c ON c.id = d.company_id
 WHERE d.parse_status = 'DONE'
   AND d.active_chunk_version IS NOT NULL
   AND (?::bigint[] IS NULL OR array_position(?::bigint[], d.id) IS NOT NULL)
   AND (?::text[]   IS NULL OR array_position(?::text[], c.code) IS NOT NULL)
   ...
 ORDER BY d.doc_year DESC NULLS LAST, d.id
```

实现时用 JdbcTemplate 的 (sql, mapper, Object... args) 重载，空条件传 null。

### 测试（ScopeResolverTest，约 10 条）

1. CORPUS → 解析出 2 份（doc 7、38），resolverVersion = scope-v1。
2. DOCUMENTS([7,38]) → 2 份，顺序按请求。
3. DOCUMENTS([7,999]) → 1 份 + scope_document_not_found。
4. DOCUMENTS([2])（茅台，FAILED）→ ScopeEmptyException，message 含 not_ready。
5. DOCUMENTS(6 个 id) → 不抛异常 + scope_over_soft_limit。
6. FILTER(years=[2023]) → ScopeEmptyException（**不是**回退全库）。
7. FILTER(companyCodes=[300059], years=[2024]) → 只有 38。
8. FILTER(全空) → 2 份，kind 仍是 FILTER。
9. extend([38], "TURN_ONLY_EXTENSION") → 幂等（已含的不重复）。
10. companyIds() 去重后返回 {7}。

---

## Task 3：检索契约改造（不越界回退）

### 目标

让检索知道「有边界」，而且**回退时也不能越界**。

### 改动 1：RetrievalFilters 用集合替代单值

现状：

```java
public record RetrievalFilters(Long documentId, Long companyId, Integer docYear,
                               String sectionPathPrefix, Integer chunkVersion)
```

改成：

```java
public record RetrievalFilters(List<Long> documentIds, Long companyId, Integer docYear,
                               String docType, String sectionPathPrefix, Integer chunkVersion) {

    public RetrievalFilters {
        documentIds = documentIds == null ? List.of() : List.copyOf(documentIds);
    }

    public static RetrievalFilters none() { ... }
    public static RetrievalFilters ofDocument(long id) {
        return new RetrievalFilters(List.of(id), null, null, null, null, null);
    }
    public static RetrievalFilters ofDocuments(List<Long> ids) { ... }
}
```

**不加 periodFrom/To**（偏离设计文档 §7 表格，见偏差登记 #1）。理由：检索过滤只需要 doc_year 与 doc_type；报告期的真正用途是 Task 6 判断这条证据是不是本期，放进 WHERE 只会多一个恒为 null 的条件。

调用点（实测只有 3 处）：DenseRetriever.java:84、HybridRetriever.java:241、RetrievalRequestTest.java:55。

### 改动 2：DenseRetriever.appendFilters（Dense / Sparse 共用）

```java
if (!f.documentIds().isEmpty()) {
    if (f.documentIds().size() == 1) {
        sql.append(" AND dc.document_id = ?");
        args.add(f.documentIds().get(0));
    } else {
        sql.append(" AND dc.document_id = ANY(?)");
        args.add(f.documentIds());          // JdbcTemplate 把 List 绑成数组
    }
}
if (f.docType() != null) { sql.append(" AND d.doc_type = ?"); args.add(f.docType()); }
```

> ANY(?) 的 List 绑定依赖 PG 驱动把 List 转数组。若实测报 cannot cast，退化为拼 IN (?,?,…) 占位符 —— **在 Task 3 的第一条测试里就验证**，不要留到 Task 9。

### 改动 3：QueryUnderstanding 增加 docType

records 的兼容写法（保留 9 参构造器，避免改 30 处调用点）：

```java
public record QueryUnderstanding(QueryType queryType, String normalizedQuery,
        List<String> companyIds, int[] yearRange, List<String> metrics,
        boolean needMultiQuery, boolean needDecompose,
        String docType,                 // 新增：ANNUAL/SEMI_ANNUAL/Q1/Q3，可空
        boolean degraded, String route) {

    /** 兼容旧调用点。 */
    public QueryUnderstanding(QueryType t, String q, List<String> c, int[] y, List<String> m,
                              boolean mq, boolean md, boolean degraded, String route) {
        this(t, q, c, y, m, mq, md, null, degraded, route);
    }
}
```

RuleBasedRouter 抽关键词：半年报 / 中报 / 半年度报告 → SEMI_ANNUAL；一季报 / 第一季度 → Q1；三季报 / 第三季度 → Q3；年报 / 年度报告 → ANNUAL。
LlmRouter 的 SYSTEM 提示加一行字段说明，RouterDto 加 String docType。

### 改动 4：HybridRetriever 接收 ResolvedScope

新增重载（旧的两个保持不变，内部传 null = 全库基线，既有测试零改动）：

```java
public Result retrieve(RetrievalRequest req, QueryUnderstanding qu, ResolvedScope scope)
```

doRetrieve 里：

1. base = req.filters()；若 scope 非空且 documentIds 非空 → 用 scope.documentIds() 覆盖 base.documentIds()。
2. mergeFilters(base, qu, warnings) 只做**收窄**：QU 的 companyId 不在 scope.companyIds() → 丢弃 + qu_company_out_of_scope；QU 的 docYear 不在 scope.documents() 的年份集合里 → 丢弃 + qu_year_out_of_scope（专治「问 2023 但库里只有 24/25」）。
3. qu_filter_empty_fallback 改为：**清掉 QU 带来的 company / year / docType，保留 documentIds** 再查一次。仍为 0 → 返回空 + no_evidence_in_scope，**不再返回全库结果**。

### 测试（ScopeFilteringTest，约 6 条）

1. ofDocuments([7,38]) 的 SQL 能执行且两边的 chunk 都出现（验证 ANY(?) 绑定）。
2. scope=[7] + QU 抽出 2024 → qu_year_out_of_scope，结果全部来自 7。
3. scope=[38] + QU 抽出 2025 → 结果全部来自 38，或 0 条 + no_evidence_in_scope（**不得**返回 7 的 chunk）。
4. scope=[38] + QU 空结果回退 → 结果仍全部来自 38。
5. 无 scope（旧重载）→ 行为与阶段 4 完全一致（回归）。
6. docType=Q3 → 0 条（库里没有三季报），不抛异常。

---

## Task 4：多文档配额融合

### 问题

RRF 之后再截 50 条进精排。若两份文档的语义相关度差得多，截断会把弱的那份整份挤掉 —— 于是「对比 2024 与 2025 的营收」只拿到一份报告的证据，答案只能编。

### 算法

新增 com.finagent.retrieval.DocumentQuotaBalancer：

```java
/**
 * 按文档配额保留候选：每份文档先保底 ceil(limit / N) 条，剩余名额按融合分补。
 *
 * <p>为什么在 RRF 之后、Rerank 之前做：Rerank 是唯一能跨文档比较分数的环节，
 * 配额的意义就是「保证每份文档都有样本进入 Rerank」，放后面就没意义了。
 */
public static List<RetrievedChunk> apply(List<RetrievedChunk> fused, int limit, int documentCount) {
    if (documentCount < 2 || fused.size() <= limit) {
        return fused;
    }
    int quota = (int) Math.ceil((double) limit / documentCount);
    // 1) 按 fused 顺序分组（保持 RRF 名次）
    // 2) 每份取前 quota 条
    // 3) 剩余名额按原顺序填，chunkId 去重
}
```

### 触发条件

只在 scope.size() >= 2 **且**（queryType == COMPARISON 或 yearRange 跨年）时启用。理由：单文档问题套配额只会把无关块塞进 Rerank，浪费精排预算。

### 测试（DocumentQuotaBalancerTest，6 条）

1. 2 份文档、limit=50、候选 60 → 每份至少 25 条。
2. 3 份文档、limit=50 → 每份至少 17 条。
3. 某份文档只有 3 条 → 不重复填，名额让给其它文档。
4. 候选少于 limit → 原样返回（不重排）。
5. 单文档 → 原样返回。
6. 输出无重复 chunkId 且长度不超过 limit。

---

## Task 5：Context Builder（§9.8 / §9.15）

### 目标

把 List<RetrievedChunk> 变成「带结构的证据包」，解决 val@1 只有 8/12 的问题。

### 数据模型

包 com.finagent.context：

```java
/** 一块证据。content 是原文，永不含 embedding_text。 */
public record ContextBlock(
        int evidenceNo,             // 1 起，答案里用 [E1] 引用
        long chunkId, long parentId, long documentId,
        String docTitle, String sectionPath, String title,
        String content,
        Integer pageStart, Integer pageEnd,
        String chunkType,           // TEXT / TABLE
        String caption, String unit, String headersJson,
        String bboxJson,            // 原样带出，Task 6 转结构
        double score,               // Rerank 分；未精排为 0
        int chunkIndex,             // 同 Parent 内的阅读顺序
        String role) { }            // HIT | SIBLING | PARENT_WHOLE

public record ContextPackage(String text, List<ContextBlock> blocks, int tokenCount,
                             List<String> warnings, int parentsExpanded, int siblingsAdded) { }
```

### 装配流程

```java
@Component
public class ContextBuilder {

    public ContextPackage build(String query, QueryType queryType,
                                List<RetrievedChunk> hits, ResolvedScope scope) {
        // 1) 批量读库：hits 的全字段 + 各自 Parent 的全部 Child（两次 IN 查询）
        // 2) 按 parentId 分组；组内按 rerankScore 降序
        // 3) 组内二次选择：score >= 0.6 * 组内最高分
        // 4) 补齐：命中 child 前后各 1 个 chunkIndex 邻居（同 Parent），role=SIBLING
        // 5) queryType == COMPLEX_ANALYSIS 且 Parent token <= 1500 → 整段 Parent（role=PARENT_WHOLE）
        // 6) 排序：先按组最高分降序，组内按 chunkIndex 升序（保证 readingOrder）
        // 7) 预算裁剪：累加 token，超出则跳过（不截断字符串），记 context_truncated
        // 8) 渲染 text，连续编号 [E1]..[En]
    }
}
```

### 渲染格式（决定引用的可读性）

```
[E1] 东方财富2025年年度报告 · 第 47 页 · 第二节 > 六、主要会计数据 · TABLE
表：主要会计数据（单位：元）
表头：项目 | 2025年 | 2024年
内容：
| 营业收入 | 16,067,741,039.51 | 12,084,594,947.22 |
```

- caption 为空时省略「表：」行；unit 为空时省略单位；headers_json 为空时省略「表头」行。
- 块之间空一行；**不做任何改写、不做摘要**。

### 配置

```yaml
finagent:
  context:
    max-tokens: 6000            # 证据预算（qwen-plus 32k，留足答案空间）
    min-score-factor: 0.6       # §9.15 的 0.6
    neighbor-radius: 1          # 前后各 1 个 Child
    parent-whole-max-tokens: 1500
```

### 需要的仓储方法

ChunkRepository 新增：

```java
public List<ChunkView> findByIds(List<Long> ids);
public List<ChunkView> findChildrenByParents(List<Long> parentIds);

public record ChunkView(long id, long documentId, Long parentId, String chunkType,
                        String sectionPath, String title, String content,
                        Integer pageStart, Integer pageEnd, int tokenCount, int chunkIndex,
                        String caption, String unit, String headersJson, String bboxJson) { }
```

> page_start / page_end / bbox 在库里可空 → ChunkView 用 Integer，渲染时 null 显示「页码未知」。不要在这里做非空断言，会 NPE。

### 测试（ContextBuilderTest，约 12 条）

1. 两个命中来自同一 Parent → 只展开一次 Parent，不重复加载 Sibling。
2. 组内 3 条分数 0.9 / 0.85 / 0.3 → 只保留前两条（0.3 < 0.54）。
3. 邻居补齐：命中 chunkIndex=5 → 带上 4 与 6，role=SIBLING。
4. 命中已在首或尾 → 只补一侧，不越界。
5. 预算 100 token、块各 60 → 只装 1 块 + context_truncated。
6. 表格块 → 渲染含 caption / unit / 表头三行。
7. caption 为 null → 该行不出现，不留空行。
8. COMPLEX_ANALYSIS + Parent 1200 token → role=PARENT_WHOLE。
9. EXACT_FACT + Parent 1200 token → 仍走二次选择（不整段）。
10. 渲染顺序：组内 chunkIndex 升序（readingOrder）。
11. 空 hits → 返回空包，不抛异常。
12. evidenceNo 连续从 1 开始，与 blocks 下标一致。

---

## Task 6：Citation 生成与校验（§9.12）

### 数据模型

包 com.finagent.citation：

```java
public record Citation(long documentId, Long parentChunkId, long childChunkId,
                       Integer pageStart, Integer pageEnd,
                       List<BBox> bbox, TableRef tableRef,
                       String evidenceType,   // TABLE | TEXT | NARRATIVE
                       String unit, String scope,   // 合并 / 母公司
                       String docTitle, String sectionPath) { }

public record BBox(int page, double x, double y, double w, double h) { }

public record TableRef(String tableId, Integer rowStart, Integer rowEnd) { }
```

### bbox 的现实

库里 document_chunk.bbox 存的是**单个归一化框**（实测样例）：

```json
{"top": 0.4445, "left": 0.1035, "right": 0.9156, "bottom": 0.5271}
```

不是 §9.12 写的数组。转换规则（偏差登记 #4）：

```java
BBox b = new BBox(pageStart, left, top, right - left, bottom - top);
```

即输出仍为数组，但每个 chunk 只有一个元素；page 取 page_start。多框要等 Docling 输出 block 级坐标，留到阶段 7。

tableRef 恒为 null（库里没有 tableId），偏差 #5。行范围可由 headers_json 与 content 行数推算，但**不猜**。

### 校验器

```java
@Component
public class CitationValidator {

    /**
     * @param emitted 模型输出的证据编号（[E3] → 3）
     * @return 校验后的引用 + 被剔除的编号
     */
    public Result validate(List<Integer> emitted, List<ContextBlock> blocks) {
        // 1) 编号必须落在 [1, blocks.size()]，否则剔除（模型最常见的错误：引用不存在的 E9）
        // 2) 去重保序
        // 3) 从 ContextBlock 取库里读来的页码/bbox/caption，不采信模型
        // 4) scope（合并/母公司）：正文含「归属于母公司」判断，判断不了写 null
    }

    public record Result(List<Citation> citations, List<Integer> dropped, List<String> warnings) { }
}
```

### 测试（CitationValidatorTest，10 条）

1. [1,3] → 2 条引用，页码/bbox 来自 block。
2. [1,1,2] → 去重成 2 条。
3. [0,9]（越界）→ 0 条 + 2 条 dropped + warning。
4. 空列表 → 空引用，不抛异常。
5. bbox 从 {top,left,right,bottom} 正确转 {x,y,w,h}。
6. bbox 为 null → bbox 为空数组，其余字段照常。
7. 表格块 → evidenceType=TABLE，unit 透传。
8. 正文含「归属于母公司」→ scope=母公司。
9. 剔除率超过 50% → warning citations_mostly_dropped。
10. 引用顺序与模型输出顺序一致（不按分数重排）。

---

## Task 7：答案生成

### 目标

给定证据包，产出 §9.12 结构的答案，且**数字只来自证据**。

### 关键设计：用编号引用，不让模型输出 id

模型不知道 chunk_id（也不该让它猜）。上下文里每块带 [E1] 到 [En]，模型只输出编号，后端映射回真实 chunk。这同时满足 C7「引用必须指向上下文里出现过的块」—— 编号天生受限于上下文。

### 提示词（AnswerPrompts.java）

```
你是财务分析助手，只依据【证据】回答。

规则：
1. 每个数字必须逐字来自证据，保留原单位与精度；禁止换算、四舍五入、跨报告拼接。
2. 证据不足时 sufficient=false，在 answer 里说明缺少什么，禁止推测。
3. 引用只能使用证据编号（E1、E2…），不得编造编号。
4. 同一个指标在不同证据里出现不同数值时，取与问题期间一致的那份，并在 note 里说明存在差异。
5. facts 只放问题问到的指标，最多 8 条，没问到的不放。
6. 只输出 JSON，不要 markdown 代码块。
```

输出 DTO：

```java
public record AnswerDto(boolean sufficient, String answer, List<FactDto> facts,
                        List<String> usedEvidence, String confidence, String note) {
    public record FactDto(String metric, String period, String value, String unit,
                          String scope, String evidence) { }
}
```

### 调用与容错

```java
AnswerDto dto;
try {
    dto = chat.prompt().system(SYSTEM).user(userPrompt).call().entity(AnswerDto.class);
} catch (Exception e) {
    dto = parseLenient(rawText);     // 去掉 markdown 围栏后手动解析；仍失败 → AnswerGenerationException
}
```

失败时的降级（不静默）：返回 sufficient=false、confidence=error、warnings 含 answer_generation_failed，**不返回无引用的自由文本**。理由：没有引用的答案在金融场景里等于谣言。

### 单文档取证（D9）

FactGuard：

- 同一 metric + period 的 fact 来自超过 1 份文档 → 保留「期间与问题年份一致」的那条；
- 无法判定 → 全部保留但 confidence=low，并加 warning fact_multi_doc_conflict；
- 数值在两份文档里不一致 → 直接 low，并在 answer 追加「两份报告数值不一致」提示。

### 拒答

```java
if (pkg.blocks().isEmpty()) {
    return AnswerResult.refuse("NO_EVIDENCE_IN_SCOPE", "在所选范围内没有找到相关证据…");
}
```

拒答也是正常 JSON：sufficient=false、citations 为空、confidence=insufficient。

### 测试（AnswerServiceTest + FactGuardTest，约 8 条）

1. 正常 JSON → 解析出 answer / facts / citations，confidence=high。
2. sufficient=false → 不进 facts 校验，直接返回。
3. 模型返回带 markdown 围栏 → lenient 解析成功。
4. 模型返回非 JSON → 降级为 error，warnings 含 answer_generation_failed。
5. 引用越界 → 由 Task 6 剔除，confidence 降为 medium。
6. 两文档 fact 冲突 → warning + low。
7. 空上下文 → 拒答，**不调用 LLM**（mock 断言 0 次交互）。
8. facts 超过 8 条 → 截断前 8 条 + warning。

---

## Task 8：编排、trace 与 API

### 为什么新增 ChatTrace 而不是改 RetrievalTrace

RetrievalTrace 是 15 个参数的 record，被 21 个 HybridRetriever 测试断言。往里加字段会同时改动 21 处构造与断言，收益为零。改为**组合**：

```java
public record ChatTrace(String traceId, RetrievalTrace retrieval, ScopeTrace scope,
                        int contextBlocks, int contextTokens,
                        int citationsEmitted, int citationsDropped,
                        long contextMs, long answerMs, List<String> warnings) { }

public record ScopeTrace(ScopeKind kind, List<Long> documentIds, String resolverVersion,
                         String effectiveReason, boolean docRoutingAvailable) { }
```

### 编排

```java
@Service
public class ChatService {
    public ChatResponse ask(ChatRequest req) {
        ResolvedScope scope = scopeResolver.resolve(req.scope());                 // Task 2
        QueryUnderstanding qu = router.understand(req.question());                // 阶段 4
        var result = retriever.retrieve(RetrievalRequest.of(req.question()), qu, scope);  // Task 3/4
        ContextPackage pkg = contextBuilder.build(req.question(), qu.queryType(),
                                                  result.chunks(), scope);        // Task 5
        AnswerResult ans = answerService.answer(req.question(), qu, pkg, scope);  // Task 7（含 Task 6）
        return new ChatResponse(ans, scope, pkg, trace);
    }
}
```

### API

POST /api/chat：

```json
{
  "question": "2024年营业收入是多少",
  "scope": { "kind": "DOCUMENTS", "documentIds": [38] },
  "extensionPolicy": "ASK"
}
```

响应（200）核心字段：

```json
{
  "answer": "2024年营业总收入 12,084,594,947.22 元。",
  "facts": [{ "metric": "营业总收入", "period": "2024", "value": "12084594947.22",
              "unit": "元", "scope": "合并", "source": "PDF" }],
  "citations": [{ "documentId": 38, "parentChunkId": 512, "childChunkId": 1610,
                  "pageStart": 47, "pageEnd": 47,
                  "bbox": [{ "page": 47, "x": 0.103, "y": 0.41, "w": 0.81, "h": 0.09 }],
                  "tableRef": null, "evidenceType": "TABLE", "unit": "元", "scope": "合并",
                  "docTitle": "东方财富2024年年度报告",
                  "sectionPath": "第二节 > 六、主要会计数据" }],
  "confidence": "high",
  "scope": { "session": { "kind": "DOCUMENTS", "documentIds": [38] },
             "effective": { "kind": "DOCUMENTS", "documentIds": [38], "reason": "SESSION" },
             "resolvedDocumentIds": [38], "resolverVersion": "scope-v1" },
  "warnings": [],
  "trace": { "traceId": "…", "contextBlocks": 3, "contextTokens": 1840,
             "citationsEmitted": 1, "citationsDropped": 0,
             "contextMs": 41, "answerMs": 4210,
             "denseMs": 190, "sparseMs": 4, "fuseMs": 1, "rerankMs": 260, "totalMs": 4706 },
  "extensionSuggested": null
}
```

GET /api/scope/documents → 供前端选择文档：

```json
[{ "id": 38, "title": "东方财富2024年年度报告", "companyCode": "300059",
   "companyName": "东方财富", "docYear": 2024, "docType": "ANNUAL",
   "chunkCount": 630, "pageCount": 177 }]
```

### 错误码

| 场景 | HTTP | body |
|---|---|---|
| 作用域解析 0 份 | 409 | error=SCOPE_EMPTY，带 message 与 warnings |
| 参数缺失 / 问题为空 | 400 | error=BAD_REQUEST |
| 答案生成失败 | 200 | 正常结构 + confidence=error（**不是 500**：这是业务结果） |

### trace 落盘

logs/chat-trace.jsonl 一行一条（traceId + 全字段），便于离线复盘。不做数据库表 —— 阶段 9 再上 conversation / message。

### 测试（ChatControllerTest，MockMvc 5 条）

1. 正常请求 → 200 + citations 非空 + traceId 非空。
2. scope 0 份 → 409 + SCOPE_EMPTY。
3. question 为空 → 400。
4. 同一请求两次 → traceId 不同。
5. GET /api/scope/documents → 至少 2 条，含 docYear。

---

## Task 9：集成测试

新增 src/test/java/com/finagent/chat/ChatE2ETest.java（@SpringBootTest + MockMvc、@Tag("e2e")，真调百炼）：

### 断言清单

1. **引用可反解析**：每条 citation 的 childChunkId 都能在库里查到，且属于 resolvedDocumentIds（14 题全跑）。
2. **不混文档**：单年期问题（1~12 题）citations 的 documentId 集合大小 = 1。
3. **拒答**：问「2023 年营业收入」→ sufficient=false 或 409 SCOPE_EMPTY，**不得**返回 2024/2025 的数字。
4. **数字正确**：6 指标 × 2 年，value / unit 与 CrossDocumentEvalTest 的期望一致（抽成 EvalFixtures 复用）。
5. **对比题**：第 14 题（2024 vs 2025）→ citations 覆盖 2 份文档。
6. **页码**：citation 的 pageStart 落在 1 到 pages_parsed 之间。

### 性能

记录 answerMs 分布，报告给 P50 / P90。不设硬阈值（LLM 抖动大），但超过 15 s 视为异常并打印。

---

## Task 10：报告

docs/阶段5-答案生成报告.md，结构沿用阶段 4 报告：

0. 一句话结论
1. 结果总览（新增单测数、e2e 通过数、全量 mvn 结果、端到端耗时）
2. 交付清单（Task 1~9 → 文件）
3. 链路实测（检索 → 上下文 → 生成 → 校验的分段耗时，一条完整 trace）
4. 上下文装配效果（块数 / token 数 / 表格块占比 / 因预算出局块数）
5. 与阶段 4 对比（val@1 8/12 → ?，混文档 14/14 → ?）
6. 三个真实缺陷（跑出来的，不是设计的）
7. 与预期偏差
8. 未决问题

同时回写：docs/关键契约.md（C5 / C6 / C7）、docs/superpowers/plans/2026-10-05-demo-roadmap.md（阶段 5 状态）。

### 实施结果（2026-10-21 实跑，14 题 × 真模型）

- 运行：`mvn -B test "-Dtest=ChatE2ETest" "-Dsurefire.excludedGroups=" "-Dgroups=e2e"`，**111.6 s**。
- 四条结构性不变量全部成立：引用 100% 可反解析（chunk+doc 查回真实行）、引用 0 次越出作用域、
  fact 数值 100% 逐字出现在被引用 chunk 正文里、confidence 全为 high。
- 单题耗时 3.6~6.6 s（生成占 80%+，上下文装配 18 ms 量级，检索 0.8~1.0 s）。
- 14 题里 **8 题出现 context_truncated**：Parent 展开 + 邻居补齐后常在 5.9k token，6000 预算边缘反复整块出局。
- Q13（问 2023、作用域只有 2024 年报）答出 11,081,439,282.37 —— 来自 2024 年报的**上年同期对比列**，
  trace 记 `qu_year_out_of_scope:2023`：这条不是 bug，但答案应标明数值来源期间（阶段 6 做）。
- Q14（2024 vs 2025 对比）只产出 1 条 fact：提示词规则「facts 只放问到的指标」被模型理解成单指标，阶段 6 加「按年份逐条校验」。

---

## 4. 总体验收标准

| # | 标准 | 判定方式 |
|---|---|---|
| 1 | 全量 mvn -B test 绿 | 0 failure / 0 error |
| 2 | 新增单测不少于 55 条 | 各 Task 测试数求和 |
| 3 | 引用 100% 可反解析 | Task 9 断言 1 |
| 4 | 单年期问题混文档 0 次 | Task 9 断言 2（14 题） |
| 5 | 2023 题不编造 | Task 9 断言 3 |
| 6 | 6 指标 × 2 年数值全对 | Task 9 断言 4 |
| 7 | 上下文不超预算 | ContextBuilderTest 5 |
| 8 | trace 能回放一次完整调用 | 抽查 logs/chat-trace.jsonl 一行 |

---

## 5. 风险与回退

| 风险 | 概率 | 应对 |
|---|---|---|
| ANY(?) 的 List 绑定在 PG 驱动上失败 | 中 | Task 3 第一条测试先验；失败退化为 IN (?,?,…) 动态占位符 |
| qwen-plus 输出 JSON 解析失败率高于预期 | 中 | 先 lenient 解析，再失败则降级为 error；报告里统计失败率 |
| 上下文 6000 token 把好块挤掉 | 低 | 按分数降序装填；报告里给因预算出局块数 |
| QueryUnderstanding 加字段破坏既有测试 | 中 | 保留 9 参构造器委托；先跑全量测试再提交 |
| V6 唯一索引在脏数据上失败 | 低 | 迁移前先 GROUP BY … HAVING count(*) > 1 核对（当前 4 行无冲突） |
| 引用 bbox 单框导致前端高亮不准 | 中 | 模型里说明为块级框；阶段 7 再做 block 级坐标 |

---

## 6. 偏差登记（执行时逐条填）

| # | 计划写的 | 实际做的 | 原因 |
|---|---|---|---|
| 1 | RetrievalFilters 加 periodFrom / To | 只加 docType，期间由 doc_year + DocMeta.period 承担 | WHERE 里加恒 null 条件没有收益；期间的用途是 Task 6 判本期 |
| 2 | RetrievalFilters.documentId（单值） | 改 documentIds（集合），ofDocument 保留为糖 | 作用域天然多文档 |
| 3 | 扩展 RetrievalTrace | 新增 ChatTrace 组合 | 避免改 21 个测试的 15 参构造 |
| 4 | bbox 为 {page,x,y,w,h} 数组 | 单元素数组，由 {top,left,right,bottom} 换算 | 库里存单框归一化坐标 |
| 5 | tableRef 为 {tableId,rowStart,rowEnd} | 恒 null | 没有 tableId；不猜行范围 |
| 6 | 数字题走 FinancialFact | 走 PDF + facts（source=PDF） | 用户 Q1=A |
| 7 | Task 1 代码侧加 listCompanies / setIndustry | 只加 backfillPeriod | 前两个暂无调用方，等 Task 8 真需要再加（YAGNI） |
| 8 | 报告期回填只写在迁移里 | 迁移只回填存量 ANNUAL；新增 DocumentRepository.backfillPeriod，在解析完成时调用 | 半年报/季报的报告期不能从 doc_year 单值推出，必须按类型算 |
| 9 | 未预料：V6 的 CHECK 打断两处测试夹具 | 改 VectorStoreSmokeTest 与 ChunkWriterTest 里的 ANNUAL_REPORT → ANNUAL | 夹具用的正是要收敛掉的脏值，约束按预期生效 |
| 10 | 未预料：uq_document_identity 打断冒烟测试 | 冒烟测试的临时文档年份改为 1999 | 原用 2025 与真实文档（600519/2025/ANNUAL/v1）身份重复 |
| 11 | FILTER 条件全空 → 全库 | 同计划（不过滤 + 警告 scope_filter_empty_all_corpus）；四个空条件传空列表而非 null | 空列表能区分「条件都给了但没填」与「根本没给条件」 |
| 12 | Task 3：documentIds 用 ANY(?) 绑 List | 改 IN (?,?,…) 占位符拼接 | 实测 PG 驱动报「无法推测实例 java.util.ImmutableCollections$ListN 的 SQL 类型」；JdbcTemplate 变参 API 拿不到 setObject(i,list,Types.ARRAY) 入口。参数值仍是预编译 ?，无注入风险 |
| 13 | Task 3：新增重载 retrieve(req, ResolvedScope) | 改名 retrieveInScope(req, scope) | 与新重载并存时 retrieve(req, null) 在两个重载间歧义、编译失败；而「传 null 表示无 QU 基线」是阶段 4 的显式用法，不能废除 |
| 14 | Task 3：docType 只在 RuleBasedRouter / LlmRouter 抽 | 新增 DocTypes（四值常量 + 中文措辞识别 + isValid），RouterService 在 LLM 漏填时用措辞补 | 「半年报」里含「年报」，匹配顺序必须是半年报 > 一季报 > 三季报 > 年报；把顺序规则集中到一处才不会被两个 router 各写一遍写反 |
| 15 | Task 3：作用域覆盖 base 的文档条件 | 取交集；完全不相交时用哨兵 id -1（EMPTY_DOCUMENT_SET） | SQL 里空列表 = 不过滤 = 全库，必须有个永不匹配的值才能表达「空集」 |
| 16 | Task 3：CORPUS 作用域也套 documentIds | CORPUS 不拼 IN | 把全库 id 拼进 IN 会让优化器放弃索引（阶段 4 实测本规模下走 Seq Scan + Sort 才 3.4 ms） |
| 17 | Task 3：回退只清 QU 条件 | 同计划；但额外加 no_evidence_in_scope 警告分支，仅当「作用域非空且非 CORPUS」 | 让「范围内真没证据」与「全库也没证据」在 trace 里可区分 |
| 18 | Task 4：3 份文档、limit=50 时每份至少 ceil(50/3)=17 | 实际保证 floor(limit/N)=16，且至少两份拿到 17 | ceil × N 可能超过 limit（17×3=51>50），必然有一份被切掉一条；循环里必须带 out.size() < limit，否则实测输出 51 条 |
| 19 | Task 4：配额 = ceil(limit / documentCount 参数) | 改用「fused 里实际有候选的文档数」算配额，documentCount 只当开关 | 作用域 2 份但只有 1 份召回时，按 2 算会白留一半名额给不存在的候选 |
| 20 | Task 5：ContextBlock 不含 token 数 | 加 int tokenCount | 预算必须用库里的 token_count（与切块时同一把尺），用渲染文本重算会与 chunk 预算口径不一致 |
| 21 | Task 5：ChunkView 只到正文与页码 | 加 chunkLevel、docTitle | 判断 PARENT 级命中要 chunkLevel；渲染页眉要标题，否则要多查一次 document |
| 22 | Task 5：命中块必能展开 Parent | children 为空时用命中块本身，记 context_parent_missing，不补邻居 | 历史数据与 PARENT 级命中没有 parent_id 或没有孩子；原先的空分支会让整个组静默消失 |
| 23 | Task 5：邻居按 chunkIndex ± radius | 按「该 Parent 的 children 排序后的位置 ± radius」 | chunkIndex 是全局编号，同一 Parent 内可能跳号（表格分割、被替换的块）；按编号差找邻居会漏块。副作用：低分命中块若与高分块相邻，会被正确保留（不是 bug，已在测试里固定） |
| 24 | Task 6：CitationValidator 只做校验 | 额外提供 toCitation(ContextBlock)，校验与映射共用一条路径 | 阶段 5 只有一键路径，多写一份映射迟早分叉 |
| 25 | Task 6：bbox 为空时给空数组 | 给 null，前端据此不画框 | 空数组会被前端当成「有框但坐标为空」，反而要判断两次 |
| 26 | Task 7：ChatClient 直接 .call().entity(AnswerDto.class) | 抽 AnswerGenerator 接口 + .call().content() 再由 AnswerParser 解析 | entity() 拿不到原始文本，解析失败既不能去围栏挽救也没法把原文写日志；抽接口让拒答/降级规则不烧 token 就能测 |
| 27 | Task 7：FactGuard 按 metric+period 分组 | 按 metric 分组，期间内再比数值 | 按 metric+period 分组时 2023 与 2024 的两条永不碰面，「取与问题年份一致的那份」规则永远不触发（测试里当场暴露） |
| 28 | Task 7：AnswerResult 无剔除计数 | 加 `int citationsDropped` | trace 与评测都要看「模型编了几个不存在的编号」，只留 boolean 不够 |
| 29 | Task 8：HTTP 层用 @WebMvcTest | 改用 `MockMvcBuilders.standaloneSetup` | Spring Boot 4 把测试切片拆到独立模块（`spring-boot-test-autoconfigure` 4.0.8 里已无 WebMvcTest），本项目没引那个模块；standalone 只验路由/绑定/状态码，正是这层要验的 |
| 30 | Task 8：响应 scope.session 是会话配置 | 阶段 5 无会话表，session 就是本轮解析结果 | 还没有 conversation 表，凭空造一个 session 状态会和 effective 冲突；阶段 9 上表后 session 才有独立语义 |
| 31 | Task 8：scope.docRoutingAvailable 由 ScopeResolver 判断 | ChatTrace 里恒 false | 文档级路由本阶段没实现（D11 阈值未达），写死 false 比假装算过更诚实 |

---

## 7. 未决问题

1. **文档级路由**（超过 20 份或超过 50,000 chunk）本阶段不实现，ResolvedScope.docRoutingAvailable 已留位。触发条件：可检索文档超过 20 份。
2. **多框 bbox**：需要 Docling 的 block 级坐标，当前只到 chunk 级。
3. **答案缓存**：D7 明确不做结果缓存，但同问题 + 同 scope 重复调用会重复付费。触发条件：单日调用超过 200 次。
4. **facts 单位归一**：元 / 万元 / 亿元原样透传，跨报告对比需要换算层。触发条件：出现实测不一致案例。
5. **拒答阈值**：目前只看上下文是否为空，没看 rerank 分。若实测出现「有块但都不相关」，再加 minRerankScore。
6. **scope 持久化**：本阶段前端每轮传，阶段 9 落 conversation.scope_json。
