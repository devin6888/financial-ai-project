# 阶段 3：切块与入库 实施计划

> **For agentic workers:** 本环境未安装 superpowers 执行子技能，采用「每个任务由子代理独立执行 + 主代理在任务间验收」替代。步骤用复选框（- [ ]）语法跟踪。

> 📖 术语：本文出现的缩写（Parent/Child、embedding、RRF、QU、HNSW、e2e……）在 `docs/术语表.md` 有中文全称、为什么需要它、在本项目里长什么样。写法约定见 `docs/文档写作规范.md`。

**Goal:** 把阶段 2 产出的 NormalizedDocument 切成 Parent/Child，对 Child 调百炼 text-embedding-v4（1024 维）写入 pgvector，并用 chunk 版本化实现可回滚的重切/重嵌入。

**Architecture:** 切块与入库全在 Java 侧完成（Python 只负责解析，阶段 2 已定）。嵌入走 Spring AI 的 EmbeddingModel 抽象，但**批量必须自己控**（百炼单请求上限 10 行）。入库采用「新版本写入 → 校验 → 原子切换 document.active_chunk_version → 旧版本标 SUPERSEDED」，检索永远只读 active 版本的 ACTIVE chunk。

**Tech Stack:** Java 21 · Spring Boot 4.0.8 · Spring AI 2.0.1 · PostgreSQL 16 + pgvector · 阿里云百炼 text-embedding-v4

---

## 本计划的依据：设计文档 §8

阶段 3 是设计文档里规格写得最细的一章，逐条对应如下（**这些是硬约束，不是参考**）：

| 出处 | 要求 |
|---|---|
| §8.2 | Child 目标 **300~700 tokens**；Parent 约 1500~3000；结构优先级 Section > Table/List > Paragraph > Sentence > Token |
| §8.2 | 表格必须保持「表名/单位/表头 + 完整数据行」；大表按**行组**切 Child 并**重复表头**；跨页表逻辑合并，page 只作 metadata |
| §8.2 | Parent 和原始 block 必须持久化 —— 未来调 Child 大小可**局部重切，不必再解析 PDF** |
| §8.5 | **V1 只给 Child 建向量**，Parent.embedding 允许为空 |
| §8.5 | TEXT Child 可留小量语义 overlap；TABLE **不用**机械 overlap，而是重复表头/单位；LIST 尽量完整；不跨章节 |
| §8.5 | embedding_text 可动态拼 `document_title + section_path + child_content`，但库里分字段存原始 content |
| §8.6 | 保存 `embedding_model` / `embedding_version` / **`embedding_text_hash`** —— 模型或拼接模板变了能精确圈出重嵌入范围 |
| §8.6 | `document.reindex` 支持四模式：RETRIEVAL_ONLY / RECHUNK / REEMBED / FULL_REPARSE |
| §8.6 | **原子切换**：新版本写入 → 校验（数量/向量完整性/抽样引用可解析）→ 切 `active_chunk_version` → 旧版本 SUPERSEDED。**切换失败保留旧版本可用** |
| §8.7 | 表格 Child 必须带 caption / unit / headers / 口径 / page range；脚注经 `related_footnote_ids` 关联 |
| §8.8 | document_chunk 字段清单（本计划补齐缺的 8 个） |

## 现状与差距（已核对 V1/V2 迁移后的真实 schema）

`document_chunk` 已有：`id, document_id, parent_id, chunk_level, chunk_type, section_path, title, content, page_start, page_end, source_block_ids, token_count, chunk_index, status, parser_version, chunk_strategy_version, embedding, embedding_model, embedding_version, metadata, created_at, content_tsv@

§8.8 要求但**缺失**的：

| 字段 | 用途 | 必须性 |
|---|---|---|
| `chunk_version` | 重索引原子切换的版本键 | **必填** |
| `source_offset` / `bbox` | 回溯原始块、前端高亮 | 必填 |
| `caption` / `unit` / `currency` | 表格题注、计量单位、币种 | TABLE 必填 |
| `headers_json` | 表头（含跨页重复表头） | TABLE 必填 |
| `related_footnote_ids` | 关联脚注，避免「数值对但口径错」 | 有脚注时必填 |
| `restatement_of` | 重述/追溯调整指向 | 可空 |
| `embedding_text_hash` | 精确识别需重嵌入的范围（§8.6） | 必填 |
| `document.active_chunk_version` | 检索只读该版本的 ACTIVE chunk | **必填** |

> 阶段 1 建表时留了 `status` 和 `parser_version`/`chunk_strategy_version`，但没有版本切换所需的 `chunk_version` 与 `active_chunk_version` —— 那正是本阶段要补的核心。

## File Structure

```
src/main/java/com/finagent/
├── chunk/
│   ├── ChunkStrategy.java            # 切块参数与版本号（唯一事实源）
│   ├── TokenEstimator.java           # token 估算（中文近似，可替换）
│   ├── ParentChunker.java            # NormalizedDocument -> Parent 列表
│   ├── ChildChunker.java             # Parent -> Child 列表（TEXT/TABLE/LIST 分策略）
│   ├── TableRenderer.java            # Table -> markdown（不用 chunker 的序列化）
│   ├── ChunkWriter.java              # 入库 + 校验 + 原子切换
│   ├── ChunkRepository.java          # document_chunk 的 SQL
│   ├── ReindexService.java           # 四模式重索引
│   └── model/
│       ├── Chunk.java                # 切块中间产物（未入库）
│       ├── ChunkLevel.java
│       └── ChunkType.java
├── embedding/
│   ├── EmbeddingService.java         # 包装 Spring AI EmbeddingModel，控批量
│   └── EmbeddingBatch.java           # 批量结果 + 模型/版本信息
└── document/
    └── NormalizedDocumentReader.java # 从 COS 读回阶段 2 的产物
```

**为什么把切块与嵌入分成两个包**：切块是**纯函数**（输入文档结构，输出 chunk 列表，可离线单测）；嵌入是**外部依赖**（网络、配额、批量限制）。分开后切块逻辑不需要 mock 网络就能测。

---

## Task 1: V3 迁移 —— 补齐 §8.8 要求的字段

**Files:**
- Create: `src/main/resources/db/migration/V3__chunk_versioning.sql`

- [ ] **Step 1: 写迁移**

```sql
-- 阶段 3：chunk 版本化与 §8.8 补齐字段
-- 为什么现在做：§8.6 的重索引原子切换依赖 chunk_version + active_chunk_version，
-- 而阶段 1 建表时只留了 status / chunk_strategy_version，没有版本键。

ALTER TABLE document
    ADD COLUMN IF NOT EXISTS active_chunk_version    INT,
    ADD COLUMN IF NOT EXISTS chunk_strategy_version  VARCHAR(32);

COMMENT ON COLUMN document.active_chunk_version IS
    '检索只读该版本对应的 ACTIVE chunk；重索引时原子切换';

ALTER TABLE document_chunk
    ADD COLUMN IF NOT EXISTS chunk_version        INT,
    ADD COLUMN IF NOT EXISTS source_offset        TEXT,
    ADD COLUMN IF NOT EXISTS bbox                 JSONB,
    ADD COLUMN IF NOT EXISTS caption              TEXT,
    ADD COLUMN IF NOT EXISTS unit                 VARCHAR(32),
    ADD COLUMN IF NOT EXISTS currency             VARCHAR(8),
    ADD COLUMN IF NOT EXISTS headers_json         JSONB,
    ADD COLUMN IF NOT EXISTS related_footnote_ids TEXT,
    ADD COLUMN IF NOT EXISTS restatement_of       BIGINT REFERENCES document_chunk(id),
    ADD COLUMN IF NOT EXISTS embedding_text_hash  VARCHAR(64);

-- 检索主路径：文档 + 版本 + 层级 + 状态
CREATE INDEX IF NOT EXISTS idx_chunk_active
    ON document_chunk (document_id, chunk_version, chunk_level, status);

-- 重嵌入扫描：找出某模型/版本下缺失或不匹配的向量
CREATE INDEX IF NOT EXISTS idx_chunk_embedding_meta
    ON document_chunk (embedding_model, embedding_version)
    WHERE embedding IS NOT NULL;
```

- [ ] **Step 2: 应用迁移并核对**

Run:
```powershell
Set-Location 'D:\Project\myproject\financial-ai-project'
mvn -B test
docker compose exec -T postgres psql -U finagent -d finagent -c "select version, description, success from flyway_schema_history order by installed_rank;"
docker compose exec -T postgres psql -U finagent -d finagent -c "\d document_chunk"
```

Expected: 迁移历史多出 V3；`\d document_chunk` 能看到 `chunk_version` / `caption` / `headers_json` 等新列。

---

## Task 2: 切块策略与 token 估算

**Files:**
- Create: `src/main/java/com/finagent/chunk/ChunkStrategy.java`
- Create: `src/main/java/com/finagent/chunk/TokenEstimator.java`
- Create: `src/test/java/com/finagent/chunk/TokenEstimatorTest.java`

- [ ] **Step 1: 写测试**

```java
package com.finagent.chunk;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TokenEstimatorTest {

    @Test
    void countsChineseCharactersRoughlyOnePerToken() {
        // 中文按「一字约一 token」估（Qwen 系 tokenizer 的常见比例）
        assertThat(TokenEstimator.estimate("东方财富信息股份有限公司")).isBetween(10, 14);
    }

    @Test
    void countsAsciiWordsNotCharacters() {
        // 英文按词算，不能按字符
        assertThat(TokenEstimator.estimate("revenue 2025 annual report"))
                .isBetween(4, 8);
    }

    @Test
    void handlesEmptyAndNull() {
        assertThat(TokenEstimator.estimate("")).isZero();
        assertThat(TokenEstimator.estimate(null)).isZero();
    }

    @Test
    void mixedTextAddsUp() {
        int a = TokenEstimator.estimate("营业收入");
        int b = TokenEstimator.estimate("revenue");
        // 评审 C12：初版断言 a+b+1 是错的 —— 空格不产生 token。
        // 实测 estimate("营业收入 revenue") = 5 = 4 + 1。
        assertThat(TokenEstimator.estimate("营业收入 revenue")).isEqualTo(a + b);
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `mvn -B -Dtest=TokenEstimatorTest test`
Expected: 编译失败（`TokenEstimator` 不存在）。

- [ ] **Step 3: 实现**

```java
package com.finagent.chunk;

/**
 * token 估算。
 *
 * <p>为什么需要：§8.2 用 token 数定义 Child（300~700）与 Parent（1500~3000）的粒度，
 * 但本项目不引入 tokenizer 依赖 —— 引入一个 tokenizer 只是为了切块边界，
 * 而边界本来就允许一定误差（设计文档还说"优先按 paragraph/sentence 边界拆"）。
 *
 * <p>估算规则（对中文财报足够）：
 * <ul>
 *   <li>每个 CJK 字符算 1 个 token（Qwen 系对中文约合一字一 token）</li>
 *   <li>连续 ASCII 字母/数字算 1 个 token（按"词"而非"字符"）</li>
 *   <li>其余非空白字符各算 1 个</li>
 * </ul>
 *
 * <p>若要精确，替换本类即可 —— 切块逻辑只依赖这个静态方法。
 */
public final class TokenEstimator {

    public static int estimate(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        int tokens = 0;
        boolean inAsciiWord = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isWhitespace(c)) {
                inAsciiWord = false;
                continue;
            }
            if (isCjk(c)) {
                tokens++;
                inAsciiWord = false;
            } else if (isAsciiWordChar(c)) {
                if (!inAsciiWord) {
                    tokens++;
                    inAsciiWord = true;
                }
            } else {
                tokens++;
                inAsciiWord = false;
            }
        }
        return tokens;
    }

    private static boolean isCjk(char c) {
        return (c >= 0x4E00 && c <= 0x9FFF)      // 基本汉字
                || (c >= 0x3400 && c <= 0x4DBF)  // 扩展 A
                || (c >= 0xF900 && c <= 0xFAFF); // 兼容汉字
    }

    private static boolean isAsciiWordChar(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                || (c >= '0' && c <= '9');
    }

    private TokenEstimator() {
    }
}
```

- [ ] **Step 4: 跑测试确认通过**

Run: `mvn -B -Dtest=TokenEstimatorTest test`
Expected: 4 个用例全 PASS。

- [ ] **Step 5: 写切块策略（参数唯一事实源）**

```java
package com.finagent.chunk;

/**
 * 切块参数与策略版本。
 *
 * <p><b>参数直接来自设计文档 §8.2</b>，改任何一个都必须同时升 {@link #VERSION} ——
 * 因为 chunk_strategy_version 是判断"要不要重切"的依据（§8.6）。
 */
public final class ChunkStrategy {

    /**
     * 切块策略版本。**任何影响 chunk 边界的改动都必须升这个号。**
     * 格式：v<主>.<次> —— 主版本变更意味着必须 RECHUNK，次版本意味着可 RETRIEVAL_ONLY。
     */
    public static final String VERSION = "v1.0";

    /** Child 目标 token 数（§8.2：300~700） */
    public static final int CHILD_MIN_TOKENS = 300;
    public static final int CHILD_MAX_TOKENS = 700;

    /**
     * Parent 锚在 section_path 的第几层（1 起）。**本阶段最重要的一个参数。**
     *
     * <p>评审用真实 181 页数据实测的 Child 入区间（300~700 token）天花板：
     * depth 1 → 9 个 Parent / 95.9%；**depth 2 → 98 个 / 64.7%（默认）**；
     * depth 3 → 296 个 / 32.0%；最深层（初版做法）→ 479 个 / 中位仅 48 token / 17.0%。
     */
    public static final int PARENT_DEPTH = 2;

    /** TEXT Child 的语义 overlap（§8.5：小量即可；TABLE 不用机械 overlap） */
    public static final int TEXT_OVERLAP_TOKENS = 60;

    private ChunkStrategy() {
    }
}
```

---

## Task 3: 切块领域模型

**Files:**
- Create: `src/main/java/com/finagent/chunk/model/ChunkLevel.java`
- Create: `src/main/java/com/finagent/chunk/model/ChunkType.java`
- Create: `src/main/java/com/finagent/chunk/model/Chunk.java`

- [ ] **Step 1: 三个模型**

```java
package com.finagent.chunk.model;

public enum ChunkLevel {
    /** 上下文单元：完整语义小节 / 表格组，V1 不建向量 */
    PARENT,
    /** 检索单元：Parent 内可独立召回的语义单元，V1 只给它建向量 */
    CHILD
}
```

```java
package com.finagent.chunk.model;

public enum ChunkType {
    TEXT,
    TABLE,
    LIST
}
```

```java
package com.finagent.chunk.model;

import java.util.List;

/**
 * 切块的**中间产物**（尚未入库，此时还没有 id 与 embedding）。
 *
 * <p>刻意与数据库实体分开：切块是纯函数，不该知道自增主键、向量、事务这些东西。
 * 入库由 {@code ChunkWriter} 负责把父子关系与版本号补上。
 *
 * @param level        PARENT / CHILD
 * @param type         TEXT / TABLE / LIST
 * @param sectionPath  自根到本文小节的路径（来自 NormalizedDocument 的 section_path）
 * @param title        本小节的标题（section_path 的最后一段）
 * @param content      **原始**标准化内容 —— 事实源，不含为 embedding 拼接的上下文
 * @param pageStart    起始页（1 起始）
 * @param pageEnd      结束页
 * @param sourceBlockIds 回溯 NormalizedDocument 的 block_id / table_id，逗号分隔
 * @param bboxJson     原始区域（JSON 字符串），供前端高亮；Parent 取并集
 * @param tokenCount   token 估算值
 * @param chunkIndex   同级内的顺序
 * @param caption      表格题注
 * @param unit         计量单位，如「元」
 * @param currency     币种，如「CNY」
 * @param headersJson  表头（JSON 数组），表格分片时每片重复
 * @param relatedFootnoteIds 关联脚注的 block_id
 */
public record Chunk(
        ChunkLevel level,
        ChunkType type,
        List<String> sectionPath,
        String title,
        String content,
        int pageStart,
        int pageEnd,
        String sourceBlockIds,
        String bboxJson,
        int tokenCount,
        int chunkIndex,
        String caption,
        String unit,
        String currency,
        String headersJson,
        String relatedFootnoteIds,
        /** 字符级偏移。§8.8 要求，但阶段 2 的产物没有这个粒度 —— **只建列不写入**（评审 Y3） */
        String sourceOffset
) {
    /** section_path 的存储形式：用 § 分隔，便于 LIKE 前缀匹配。 */
    public String sectionPathText() {
        return sectionPath == null ? null : String.join(" § ", sectionPath);
    }
}
```

---
## Task 4: Parent 切分 —— 按「截断到 PARENT_DEPTH 的 section_path」归组

**设计依据（§8.2 / §8.5）：** Parent = **完整语义小节**，约 1500~3000 token，负责上下文扩展；
V1 不建向量。§8.5 另有一句关键约束：**「不同一级/二级章节默认不跨界」** —— 反过来说，
**更深的小节之间是可以跨的**。

### ⚠️ 评审实测暴露的根本设计错误（必须读）

初版把 Parent 锚在 **section_path 的最深层级**，实测结果与 §8.2 严重不符：

| 指标 | §8.2 要求 | **实测（最深层级归组）** |
|---|---|---|
| Parent token | 1500~3000 | 中位 **48**，p90 361，479 个里**只有 1 个**达到 1500 |
| Child 落在 300~700 的比例 | 计划写 ≥80% | **16.9%**（532 个里 90 个） |
| 理论天花板（允许句级自由切） | — | **17.0%** —— 因为 415 个章节**总量本身不足 300 token** |

**根因**：阶段 2 的 `section_path` 是**最深路径**，例如
`["第八节 财务报告", "五、主要会计政策及会计估计", "12、长期股权投资"]` ——
按它归组，Parent 就成了"12、长期股权投资"这种**几十字的小节**，根本不是"完整语义小节"。

**按归组深度实测的天花板：**

| 归组深度 | Parent 数 | Child 入区间天花板 |
|---|---|---|
| depth 1（第X节） | 9 | 95.9% |
| **depth 2** | **98** | **64.7%** |
| depth 3 | 296 | 32.0% |
| depth 5（初版做法） | 479 | 17.0% |

**处置：把 Parent 深度做成参数 `ChunkStrategy.PARENT_DEPTH`，默认 2。**
这样"锚在哪一层"变成一行常量，而不是散落在算法里。深度 2 在语义（像"完整小节"）与
粒度（98 个 Parent，上下文扩展有东西可扩）之间最平衡。

**Files:**
- Create: `src/main/java/com/finagent/document/DocumentModel.java`
- Create: `src/main/java/com/finagent/chunk/model/ParentGroup.java`
- Create: `src/main/java/com/finagent/chunk/ParentChunker.java`
- Test: `src/test/java/com/finagent/chunk/ParentChunkerTest.java`

- [ ] **Step 1: DocumentModel —— 蛇形字段名，绕开 Jackson 注解**

```java
package com.finagent.document;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * 阶段 2 产物（NormalizedDocument）的**最小读取视图**。
 *
 * <p>为什么不复用 {@link ParsedDocument}：那个是「解析作业完成的通知」（只有计数与 rawJson），
 * 这个是「切块的输入」（要逐块的结构）。用途不同，不要合并。
 *
 * <p><b>字段名刻意用蛇形</b>（page_no / block_id / section_path），与产物 JSON 的键一致 ——
 * 这样 record 组件名直接匹配 JSON 键，**不需要任何 @JsonProperty**。
 * 评审用真实 2.5MB 产物实测验证过：page_count=181 / blocks=1220 / tables=345 全部映射正确。
 *
 * <p><b>⚠️ 注解的包名是 com.fasterxml.jackson.annotation，不是 tools.jackson</b>：
 * 阶段 2 确认过 Spring Boot 4 默认用 Jackson 3（core/databind 在 tools.jackson），
 * 但**注解没有搬家**，仍在 com.fasterxml.jackson.annotation。
 * 评审真机 javac 验证：写成 tools.jackson.databind.annotation 会「找不到符号」。
 * 这是对阶段 2 那条结论的**过度推广**，特此更正。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record DocumentModel(Meta meta, List<Block> blocks, List<Table> tables) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Meta(int page_count, String source_name, String parser_version) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Block(String block_id, String label, String text,
                        Provenance provenance, List<String> section_path) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Table(String table_id, int page_start, int page_end,
                        int num_rows, int num_cols, List<Cell> cells,
                        String caption, List<String> section_path, BBox bbox) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Cell(String text, int row, int col, int row_span, int col_span) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Provenance(int page_no, BBox bbox) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record BBox(double left, double top, double right, double bottom) {
    }
}
```

- [ ] **Step 2: 在 ChunkStrategy 里加 PARENT_DEPTH，并删掉两个从未引用的常量**

```java
    /**
     * Parent 锚在 section_path 的第几层（1 起）。
     *
     * <p><b>这是本阶段最重要的一个参数</b>，因为它决定了 Parent 的粒度是否像「完整语义小节」。
     * 评审用真实 181 页数据实测的 Child 入区间（300~700 token）天花板：
     * <ul>
     *   <li>depth 1 → 9 个 Parent，天花板 95.9%（但单个 Parent 远超 3000 token）</li>
     *   <li><b>depth 2 → 98 个 Parent，天花板 64.7%（默认，语义与粒度最平衡）</b></li>
     *   <li>depth 3 → 296 个 Parent，天花板 32.0%</li>
     *   <li>最深层（初版做法）→ 479 个 Parent，中位仅 48 token，天花板 17.0%</li>
     * </ul>
     */
    public static final int PARENT_DEPTH = 2;
```

> **同时删掉**初版里两个从未被引用的常量：`PARENT_SOFT_MAX_TOKENS`、
> `EMBEDDING_TEXT_TEMPLATE_VERSION`（评审 Y1：定义了不用是噪音）。`TABLE_MAX_ROWS_PER_CHILD` @
> 也删掉 —— 它的用途被 Task 6 的「按渲染 token 预算切分」取代。

- [ ] **Step 3: ParentGroup 与测试**

```java
package com.finagent.chunk.model;

import com.finagent.document.DocumentModel;

import java.util.List;

/** 一个 Parent 及其成员 —— Child 切分需要逐块边界，所以不能只传拼好的字符串。 */
public record ParentGroup(Chunk parent,
                          List<DocumentModel.Block> blocks,
                          List<DocumentModel.Table> tables) {
}
```

```java
package com.finagent.chunk;

import com.finagent.chunk.model.ParentGroup;
import com.finagent.document.DocumentModel;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ParentChunkerTest {

    private static DocumentModel docWith(List<DocumentModel.Block> blocks,
                                         List<DocumentModel.Table> tables) {
        return new DocumentModel(null, blocks, tables);
    }

    private static DocumentModel.Block block(String id, String text, int page, String... path) {
        return new DocumentModel.Block(id, "text", text,
                new DocumentModel.Provenance(page, null), List.of(path));
    }

    private static DocumentModel.Table table(String id, int page, String... path) {
        return new DocumentModel.Table(id, page, page, 1, 1, List.of(), null,
                List.of(path), null);
    }

    @Test
    void truncatesSectionPathToParentDepth() {
        // 深度 5 的三级路径应归到前两级（PARENT_DEPTH = 2）
        var doc = docWith(List.of(
                block("b0", "a", 1, "第八节 财务报告", "五、会计政策", "12、长期股权投资"),
                block("b1", "b", 1, "第八节 财务报告", "五、会计政策", "13、其他")), List.of());

        List<ParentGroup> groups = ParentChunker.split(doc);

        assertThat(groups).hasSize(1);
        assertThat(groups.get(0).parent().sectionPath())
                .containsExactly("第八节 财务报告", "五、会计政策");
        assertThat(groups.get(0).blocks()).hasSize(2);
    }

    @Test
    void ordersGroupsByFirstPageNotByFirstSeen() {
        // 评审 C4：初版把「只有表格」的章节一律追加到文档末尾，chunk_index 顺序错乱。
        // 现在统一按最小页码排序。
        var doc = docWith(
                List.of(block("b0", "正文在最后", 180, "第九节 末章")),
                List.of(table("t0", 3, "第一节 开头"),
                        table("t1", 6, "第二节 中间")));

        List<ParentGroup> groups = ParentChunker.split(doc);

        assertThat(groups).extracting(g -> g.parent().title())
                .containsExactly("第一节 开头", "第二节 中间", "第九节 末章");
    }

    @Test
    void blocksWithoutSectionPathGoIntoOneLeadingParent() {
        var doc = docWith(List.of(
                block("b0", "封面文字", 1),
                block("b1", "正文", 2, "第一节")), List.of());

        List<ParentGroup> groups = ParentChunker.split(doc);

        assertThat(groups).hasSize(2);
        assertThat(groups.get(0).parent().title()).isEqualTo("(无章节)");
        assertThat(groups.get(0).parent().content()).contains("封面文字");
    }

    @Test
    void parentPageRangeCoversAllMembers() {
        var doc = docWith(List.of(
                block("b0", "a", 3, "S"), block("b1", "b", 7, "S")), List.of());
        var parent = ParentChunker.split(doc).get(0).parent();
        assertThat(parent.pageStart()).isEqualTo(3);
        assertThat(parent.pageEnd()).isEqualTo(7);
    }
}
```

- [ ] **Step 4: 实现 ParentChunker**

```java
package com.finagent.chunk;

import com.finagent.chunk.model.Chunk;
import com.finagent.chunk.model.ChunkLevel;
import com.finagent.chunk.model.ChunkType;
import com.finagent.chunk.model.ParentGroup;
import com.finagent.document.DocumentModel;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 把 DocumentModel 归组成 Parent。
 *
 * <p><b>为什么按 section_path 归组</b>：阶段 2 已为每个 block/table 算好章节路径
 *（含坐标重排与编号规则重建层级），直接拿来当边界，复用那部分工作。
 *
 * <p><b>为什么截断到 {@link ChunkStrategy#PARENT_DEPTH} 层</b>：见 ChunkStrategy 的注释 ——
 * 按最深层级归组会让 Parent 退化成几十字的小节，实测中位仅 48 token，与 §8.2 的
 * 「完整语义小节（1500~3000）」严重不符。
 */
public final class ParentChunker {

    private static final String NO_SECTION_TITLE = "(无章节)";

    public static List<ParentGroup> split(DocumentModel doc) {
        Map<List<String>, List<DocumentModel.Block>> blocksByKey = new LinkedHashMap<>();
        Map<List<String>, List<DocumentModel.Table>> tablesByKey = new LinkedHashMap<>();
        Map<List<String>, List<String>> fullPathByKey = new LinkedHashMap<>();
        Map<List<String>, Integer> firstPageByKey = new LinkedHashMap<>();

        for (DocumentModel.Block b : doc.blocks()) {
            if (b.text() == null || b.text().isBlank()) {
                continue;
            }
            List<String> key = parentKey(b.section_path());
            blocksByKey.computeIfAbsent(key, k -> new ArrayList<>()).add(b);
            fullPathByKey.putIfAbsent(key, normalize(b.section_path()));
            int page = b.provenance() == null ? 0 : b.provenance().page_no();
            firstPageByKey.merge(key, page, Math::min);
        }
        for (DocumentModel.Table t : doc.tables()) {
            List<String> key = parentKey(t.section_path());
            tablesByKey.computeIfAbsent(key, k -> new ArrayList<>()).add(t);
            fullPathByKey.putIfAbsent(key, normalize(t.section_path()));
            firstPageByKey.merge(key, t.page_start(), Math::min);
        }

        // 评审 C4 修正：统一按最小页码排序，而不是「正文先、仅表格的章节追加到末尾」。
        // 后者会让 chunk_index 顺序与原文完全脱节，阶段 4 的邻居扩展会取错人。
        List<List<String>> keys = new ArrayList<>(firstPageByKey.keySet());
        keys.sort(Comparator.comparingInt(firstPageByKey::get));

        List<ParentGroup> groups = new ArrayList<>();
        int index = 0;
        for (List<String> key : keys) {
            List<DocumentModel.Block> blocks = blocksByKey.getOrDefault(key, List.of());
            List<DocumentModel.Table> tables = tablesByKey.getOrDefault(key, List.of());
            if (blocks.isEmpty() && tables.isEmpty()) {
                continue;
            }

            StringBuilder sb = new StringBuilder();
            List<String> memberIds = new ArrayList<>();
            int minPage = Integer.MAX_VALUE;
            int maxPage = Integer.MIN_VALUE;

            for (DocumentModel.Block b : blocks) {
                sb.append(b.text().strip()).append('\n');
                memberIds.add(b.block_id());
                int page = b.provenance() == null ? 0 : b.provenance().page_no();
                minPage = Math.min(minPage, page);
                maxPage = Math.max(maxPage, page);
            }
            for (DocumentModel.Table t : tables) {
                memberIds.add(t.table_id());
                minPage = Math.min(minPage, t.page_start());
                maxPage = Math.max(maxPage, t.page_end());
            }

            String content = sb.toString().strip();
            if (content.isEmpty()) {
                content = "(本章节仅含表格：" + tables.size() + " 张)";
            }
            if (minPage == Integer.MAX_VALUE) {
                minPage = 0;
                maxPage = 0;
            }

            List<String> path = fullPathByKey.getOrDefault(key, key);
            Chunk parent = new Chunk(
                    ChunkLevel.PARENT,
                    // 评审 C11：同时有正文与表格时不能标成 TABLE —— 内容主体是正文。
                    // chunk_type 描述的是**内容形态**，表格另由 TABLE Child 承载。
                    blocks.isEmpty() ? ChunkType.TABLE : ChunkType.TEXT,
                    path, path.isEmpty() ? NO_SECTION_TITLE : path.get(path.size() - 1),
                    content, minPage, maxPage,
                    String.join(",", memberIds), null,
                    TokenEstimator.estimate(content), index++,
                    null, null, null, null, null, null);
            groups.add(new ParentGroup(parent, blocks, tables));
        }
        return groups;
    }

    /** section_path 截断到 PARENT_DEPTH 层，作为归组键。 */
    static List<String> parentKey(List<String> sectionPath) {
        List<String> path = normalize(sectionPath);
        if (path.size() <= ChunkStrategy.PARENT_DEPTH) {
            return path;
        }
        return List.copyOf(path.subList(0, ChunkStrategy.PARENT_DEPTH));
    }

    private static List<String> normalize(List<String> path) {
        return path == null ? List.of() : path;
    }

    private ParentChunker() {
    }
}
```

- [ ] **Step 5: 跑测试**

Run: `mvn -B -Dtest=ParentChunkerTest test`
Expected: 4 个用例全 PASS。

---

## Task 5: Child 切分 —— TEXT / LIST

**设计依据（§8.5）：** Child 默认 300~700 token；TEXT 可保留小量 overlap；
TABLE 不用机械 overlap；LIST 尽量完整；**不跨一级/二级章节**（Parent 已保证）。

### ⚠️ 评审实测出的死循环（必须按下面写法修）

初版在 overlap 分支写的是 `start = lastIdx`，而当**一片只含 1 个 block 且该块 ≤ 120 token**时，
`lastIdx == start`，`start` 被设回原值 → **死循环**。真实数据命中
（section `["第八节 财务报告", ...]` 的 block token 是 `[29, 709]`）——
评审真机跑了 3 秒未返回，确认永挂。

**修法：overlap 条件必须加 @lastIdx > start@。**

**Files:**
- Create: `src/main/java/com/finagent/chunk/ChildChunker.java`
- Test: `src/test/java/com/finagent/chunk/ChildChunkerTextTest.java`

- [ ] **Step 1: 写测试（**用真 Parent，不能传 null**）**

```java
package com.finagent.chunk;

import com.finagent.chunk.model.Chunk;
import com.finagent.chunk.model.ChunkLevel;
import com.finagent.chunk.model.ChunkType;
import com.finagent.chunk.model.ParentGroup;
import com.finagent.document.DocumentModel;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ChildChunkerTextTest {

    private static String para(int tokens) {
        return "财".repeat(tokens);
    }

    private static DocumentModel.Block block(String id, String text, int page) {
        return new DocumentModel.Block(id, "text", text,
                new DocumentModel.Provenance(page, null),
                List.of("第一节", "一、小节"));
    }

    /**
     * 评审 B6：初版这个助手传 parent=null，而 toChild 会访问 group.parent().sectionPath()
     * → 5 个用例全 NPE（计划却写着全 PASS）。**Parent 必须是真对象。**
     */
    private static ParentGroup groupOf(DocumentModel.Block... blocks) {
        Chunk parent = new Chunk(ChunkLevel.PARENT, ChunkType.TEXT,
                List.of("第一节", "一、小节"), "一、小节", "(parent)",
                1, 1, "", null, 1, 0, null, null, null, null, null, null);
        return new ParentGroup(parent, List.of(blocks), List.of());
    }

    @Test
    void splitsWhenAccumulatedTokensWouldExceedMax() {
        ParentGroup g = groupOf(
                block("b0", para(400), 1),
                block("b1", para(400), 1),
                block("b2", para(400), 2));

        List<Chunk> children = ChildChunker.splitText(g);

        assertThat(children).hasSize(3);
        assertThat(children.get(0).sourceBlockIds()).isEqualTo("b0");
        assertThat(children.get(1).sourceBlockIds()).isEqualTo("b1");
    }

    @Test
    void packsSmallBlocksTogether() {
        ParentGroup g = groupOf(
                block("b0", para(100), 1),
                block("b1", para(100), 1),
                block("b2", para(100), 1));

        List<Chunk> children = ChildChunker.splitText(g);

        assertThat(children).hasSize(1);
        assertThat(children.get(0).sourceBlockIds()).isEqualTo("b0,b1,b2");
    }

    /** 评审 B1：这个形状是真实数据里存在的（单块 ≤120 token），初版会死循环。 */
    @Test
    void doesNotLoopForeverOnTinySingleBlockSlice() {
        ParentGroup g = groupOf(block("b0", para(29), 1),
                block("b1", para(709), 2));
        List<Chunk> children = ChildChunker.splitText(g);
        assertThat(children).isNotEmpty();
        // 全部 block 都要被覆盖到，不能因为跳过 overlap 而漏块
        assertThat(String.join(",", children.stream()
                .map(Chunk::sourceBlockIds).toList())).contains("b0").contains("b1");
    }

    @Test
    void oversizedSingleBlockBecomesItsOwnChild() {
        ParentGroup g = groupOf(block("b0", para(2000), 1));
        List<Chunk> children = ChildChunker.splitText(g);
        assertThat(children).hasSize(1);
        assertThat(children.get(0).tokenCount()).isGreaterThan(ChunkStrategy.CHILD_MAX_TOKENS);
    }

    /**
     * 评审 C5：初版这个用例的形状 [400,50,400,400] 实际切成 [b0],[b1,b2],[b3]，
     * b1 是天然成片、**根本没测到 overlap**。真正触发 overlap 的形状是 [250,60,400]。
     */
    @Test
    void overlapsSmallTrailingBlockIntoNextChild() {
        ParentGroup g = groupOf(
                block("b0", para(250), 1),
                block("b1", para(60), 1),
                block("b2", para(400), 2));

        List<Chunk> children = ChildChunker.splitText(g);

        assertThat(children).hasSize(2);
        assertThat(children.get(0).sourceBlockIds()).isEqualTo("b0,b1");
        // 第二片以 b1 开头 —— overlap 生效
        assertThat(children.get(1).sourceBlockIds()).startsWith("b1");
    }

    @Test
    void childKeepsSectionPathAndType() {
        ParentGroup g = groupOf(block("b0", para(400), 5));
        Chunk c = ChildChunker.splitText(g).get(0);
        assertThat(c.type()).isEqualTo(ChunkType.TEXT);
        assertThat(c.pageStart()).isEqualTo(5);
        assertThat(c.pageEnd()).isEqualTo(5);
        assertThat(c.sectionPath()).containsExactly("第一节", "一、小节");
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `mvn -B -Dtest=ChildChunkerTextTest test`
Expected: 编译失败（ChildChunker 未实现）。

- [ ] **Step 3: 实现 TEXT 切分**

```java
package com.finagent.chunk;

import com.finagent.chunk.model.Chunk;
import com.finagent.chunk.model.ChunkLevel;
import com.finagent.chunk.model.ChunkType;
import com.finagent.chunk.model.ParentGroup;
import com.finagent.document.DocumentModel;

import java.util.ArrayList;
import java.util.List;

/** Parent -> Child 切分。TEXT/LIST 与 TABLE 走**不同策略**（§8.5 明确要求）。 */
public final class ChildChunker {

    /**
     * TEXT / LIST：按整块累积到 300~700 token。
     *
     * <p>为什么不在句子级切：中文财报的定义句常有几十字，句级切会把
     * 「本公司…（以下简称…）」这类句子劈开。设计文档的优先级也是 Paragraph &gt; Sentence &gt; Token。
     */
    public static List<Chunk> splitText(ParentGroup group) {
        List<DocumentModel.Block> blocks = group.blocks();
        List<Chunk> children = new ArrayList<>();
        if (blocks.isEmpty()) {
            return children;
        }

        int index = 0;
        int start = 0;
        while (start < blocks.size()) {
            int end = start;
            int tokens = 0;
            while (end < blocks.size()) {
                int t = TokenEstimator.estimate(blocks.get(end).text());
                if (end > start && tokens + t > ChunkStrategy.CHILD_MAX_TOKENS) {
                    break;
                }
                tokens += t;
                end++;
                if (tokens >= ChunkStrategy.CHILD_MIN_TOKENS) {
                    break;
                }
            }

            children.add(toChild(group, blocks.subList(start, end), index++,
                    ChunkType.TEXT));

            int lastIdx = end - 1;
            int lastTokens = TokenEstimator.estimate(blocks.get(lastIdx).text());
            // 【评审 B1 修正】必须同时满足 lastIdx > start，否则当一片只有 1 个块时
            // start 会被设回原值 → 死循环（真实数据 [29, 709] 命中）。
            if (end < blocks.size() && lastIdx > start
                    && lastTokens <= ChunkStrategy.TEXT_OVERLAP_TOKENS * 2) {
                start = lastIdx;      // 下一片从这块重新开始（块级 overlap，不截断句子）
            } else {
                start = end;
            }
        }
        return children;
    }

    /** 按 Parent 的内容形态分派。**正文与表格都要产出 Child**（评审：初版只走一个分支会丢正文）。 */
    public static List<Chunk> splitAll(ParentGroup group) {
        List<Chunk> children = new ArrayList<>();
        if (!group.blocks().isEmpty()) {
            children.addAll(splitText(group));
        }
        for (DocumentModel.Table t : group.tables()) {
            children.addAll(splitTable(group, t));
        }
        if (children.isEmpty() && !group.tables().isEmpty()) {
            children.addAll(splitTable(group, group.tables().get(0)));
        }
        return children;
    }

    static Chunk toChild(ParentGroup group, List<DocumentModel.Block> slice,
                         int index, ChunkType type) {
        StringBuilder sb = new StringBuilder();
        List<String> ids = new ArrayList<>();
        int minPage = Integer.MAX_VALUE;
        int maxPage = Integer.MIN_VALUE;
        for (DocumentModel.Block b : slice) {
            sb.append(b.text().strip()).append('\n');
            ids.add(b.block_id());
            int page = b.provenance() == null ? 0 : b.provenance().page_no();
            minPage = Math.min(minPage, page);
            maxPage = Math.max(maxPage, page);
        }
        String content = sb.toString().strip();
        // 评审 C10：真实数据有 137 个 list_item，不能一律标 TEXT
        ChunkType actual = slice.stream().anyMatch(b -> "list_item".equals(b.label()))
                ? ChunkType.LIST : type;

        return new Chunk(
                ChunkLevel.CHILD, actual,
                group.parent().sectionPath(), group.parent().title(), content,
                minPage == Integer.MAX_VALUE ? 0 : minPage,
                maxPage == Integer.MIN_VALUE ? 0 : maxPage,
                String.join(",", ids), null,
                TokenEstimator.estimate(content), index,
                null, null, null, null, null,
                // 评审 C6：bbox 必须有写入路径 —— 取成员块 bbox 的并集
                ChunkGeometry.unionBbox(slice));
    }

    private ChildChunker() {
    }
}
```

> **注意 `splitAll` 里有一段看起来多余的兜底**（`children.isEmpty() && !tables.isEmpty()`）：
> 它对应"章节只有表格、没有任何正文块"的情况 —— 那种 Parent 的 `blocks` 为空，
> 上面第一个分支不会产出任何 Child。**不要删掉它。**

- [ ] **Step 4: 补 ChunkGeometry（bbox 并集）**

```java
package com.finagent.chunk;

import com.finagent.document.DocumentModel;

import java.util.List;

/**
 * 几何工具：把多个元素的 bbox 并成"覆盖它们的矩形"。
 *
 * <p>为什么需要（评审 C6）：§8.8 要求 chunk 带 bbox 供前端高亮，而初版
 * ParentChunker 与 toChild 全传 null —— 验收 #4 因此不可能通过。
 * 真实产物里**每个 block 都有 bbox**，并集是现成可算的。
 */
public final class ChunkGeometry {

    /** 空列表返回 null（而不是一个假的零矩形）。 */
    public static String unionBbox(List<DocumentModel.Block> blocks) {
        double left = Double.MAX_VALUE;
        double top = Double.MAX_VALUE;
        double right = -Double.MAX_VALUE;
        double bottom = -Double.MAX_VALUE;
        boolean any = false;
        for (DocumentModel.Block b : blocks) {
            if (b.provenance() == null || b.provenance().bbox() == null) {
                continue;
            }
            DocumentModel.BBox bb = b.provenance().bbox();
            left = Math.min(left, bb.left());
            top = Math.min(top, bb.top());
            right = Math.max(right, bb.right());
            bottom = Math.max(bottom, bb.bottom());
            any = true;
        }
        if (!any) {
            return null;
        }
        return "{\"left\":%s,\"top\":%s,\"right\":%s,\"bottom\":%s}"
                .formatted(left, top, right, bottom);
    }

    private ChunkGeometry() {
    }
}
```

- [ ] **Step 5: Chunk 模型要加 sourceOffset（§8.8 要求，V3 已建列）**

在 `Chunk` record 末尾追加：

```java
        String sourceOffset
```

并要求所有构造点补上（TEXT Child 传 `null` —— NormalizedDocument 没有字符级偏移，
**在计划里如实标注"只建列不写入"**，不要假装有值）。

- [ ] **Step 6: 跑测试**

Run: `mvn -B -Dtest=ChildChunkerTextTest test`
Expected: 6 个用例全 PASS。

## Task 6: 表格渲染与 TABLE Child

**设计依据（§8.2 / §8.5 / §8.7）：** 表格保持「表名/单位/表头 + 完整数据行」；大表按**行组**切并**重复表头**；
TABLE 不用机械 overlap；表格 Child 必须带 `caption / unit / headers`。

### 评审实测暴露的两个问题（必须按下面写法修）

**C7：span 铺展冗余且危险。** 真实产物的 `cells` **已按位置物化**（评审实测"去掉铺展 vs 带铺展"
网格 **0 处差异**），但铺展造成 **1134 次越界写**（64 张表，最大超出 12 列）被静默丢弃，
**285 个格子被不同文本多次写入**（61 张表，本数据恰好顺序良性）。
**修法：只按 (row, col) 落格，且「仅填空白格」。**

**C8：`TABLE_MAX_ROWS_PER_CHILD = 40` 对真实数据永不触发**（max num_rows 恰好 40，0 张 > 40），
但 **25 张表渲染后超过 700 token**（最大约 3886）。按行数切是错的维度 ——
**应改按「渲染后的 token 预算」切**。

**Files:**
- Create: `src/main/java/com/finagent/chunk/TableRenderer.java`
- Create: `src/main/java/com/finagent/chunk/TableMeta.java`
- Modify: `src/main/java/com/finagent/chunk/ChildChunker.java`（加 splitTable）
- Test: `src/test/java/com/finagent/chunk/TableRendererTest.java`
- Test: `src/test/java/com/finagent/chunk/ChildChunkerTableTest.java`

- [ ] **Step 1: TableRenderer（含 C7 修正）**

```java
package com.finagent.chunk;

import com.finagent.document.DocumentModel;

import java.util.ArrayList;
import java.util.List;

/**
 * 表格 -> markdown。
 *
 * <p><b>为什么不用 Docling chunker 的序列化</b>（阶段 2 实测）：它会把释义表输出成
 * 「中国证监会, 指 = 指. 中国证监会, 释义内容 = 中国证券监督管理委员会.」，表结构全丢。
 */
public final class TableRenderer {

    private static final int DEFAULT_HEADER_ROWS = 1;

    public static String toMarkdown(DocumentModel.Table table) {
        return toMarkdown(table, DEFAULT_HEADER_ROWS);
    }

    public static String toMarkdown(DocumentModel.Table table, int headerRows) {
        List<List<String>> grid = toGrid(table);
        StringBuilder sb = new StringBuilder();
        if (table.caption() != null && !table.caption().isBlank()) {
            sb.append(table.caption().strip()).append('\n');
        }
        if (grid.isEmpty()) {
            return sb.toString().strip();
        }
        int cols = grid.stream().mapToInt(List::size).max().orElse(0);
        for (int r = 0; r < grid.size(); r++) {
            sb.append(rowToMarkdown(grid.get(r), cols)).append('\n');
            if (r == headerRows - 1) {
                sb.append(separatorRow(cols)).append('\n');
            }
        }
        return sb.toString().strip();
    }

    /**
     * 把稀疏单元格铺成二维文本网格。
     *
     * <p><b>【评审 C7 修正】</b>初版会把 row_span/col_span 铺满整个跨度 —— 但真实产物的 cells
     * 已按位置物化，铺展既冗余（实测 0 处差异），又造成 1134 次越界写被静默丢弃、285 个格子
     * 被重复覆盖（last-write-wins）。现在：越界忽略 + <b>只填空白格</b>。
     */
    public static List<List<String>> toGrid(DocumentModel.Table table) {
        int rows = Math.max(table.num_rows(), 1);
        int cols = Math.max(table.num_cols(), 1);
        List<List<String>> grid = new ArrayList<>(rows);
        for (int r = 0; r < rows; r++) {
            List<String> row = new ArrayList<>(cols);
            for (int c = 0; c < cols; c++) {
                row.add("");
            }
            grid.add(row);
        }
        if (table.cells() == null) {
            return grid;
        }
        for (DocumentModel.Cell cell : table.cells()) {
            String text = escape(cell.text());
            int rs = Math.max(cell.row_span(), 1);
            int cs = Math.max(cell.col_span(), 1);
            for (int dr = 0; dr < rs; dr++) {
                for (int dc = 0; dc < cs; dc++) {
                    int r = cell.row() + dr;
                    int c = cell.col() + dc;
                    if (r >= rows || c >= cols) {
                        continue;                    // 越界忽略，不写穿
                    }
                    if (grid.get(r).get(c).isEmpty()) {
                        grid.get(r).set(c, text);    // 只填空白格
                    }
                }
            }
        }
        return grid;
    }

    static String rowToMarkdown(List<String> row, int cols) {
        StringBuilder sb = new StringBuilder("|");
        for (int c = 0; c < cols; c++) {
            sb.append(' ').append(c < row.size() ? row.get(c) : "").append(" |");
        }
        return sb.toString();
    }

    static String separatorRow(int cols) {
        StringBuilder sb = new StringBuilder("|");
        for (int c = 0; c < cols; c++) {
            sb.append(" --- |");
        }
        return sb.toString();
    }

    private static String escape(String text) {
        if (text == null) {
            return "";
        }
        return text.replace("|", "\\|").replace("\n", " ").strip();
    }

    private TableRenderer() {
    }
}
```

- [ ] **Step 2: 写 TableRenderer 测试（保留初版 5 个 + 加 C7 回归）**

初版 5 个用例（markdown 表格、caption、合并单元格铺展、null 不输出、`|` 转义）全部保留，
**再加一个 C7 回归**：

```java
    @Test
    void laterCellDoesNotOverwriteEarlierOne() {
        // 评审 C7：初版 last-write-wins，285 个格子被覆盖（本数据恰好顺序良性，但语义是错的）
        var cells = List.of(
                new DocumentModel.Cell("第一次", 0, 0, 1, 1),
                new DocumentModel.Cell("第二次", 0, 0, 1, 1));
        var grid = TableRenderer.toGrid(
                new DocumentModel.Table("t0", 1, 1, 1, 1, cells, null, List.of(), null));
        assertThat(grid.get(0).get(0)).isEqualTo("第一次");
    }
```

- [ ] **Step 3: TableMeta —— 从题注抽单位与币种**

**依据 §8.7：** 表格 Child 必须携带 caption、unit、口径。真实产物 **70/345 张表有题注**（形如 `单位：元`）。

```java
package com.finagent.chunk;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 从表格题注里抽单位与币种。
 *
 * <p>为什么用启发式：Docling 只给题注文本，单位是<b>题注里的一句话</b>。
 * 抽不出来时**如实留 null**，不要猜（猜错的单位会让"数值对但比较口径错"）。
 */
public record TableMeta(String unit, String currency) {

    private static final Pattern UNIT = Pattern.compile("单位[：:]\\s*([^\\s，,。]{1,16})");

    public static TableMeta fromCaption(String caption) {
        if (caption == null || caption.isBlank()) {
            return new TableMeta(null, null);
        }
        Matcher m = UNIT.matcher(caption);
        if (!m.find()) {
            return new TableMeta(null, null);
        }
        String unit = m.group(1);
        // 只有题注明确提到币种时才填 currency
        String currency = unit.contains("人民币") || unit.equals("元") ? "CNY"
                : unit.contains("美元") ? "USD" : null;
        return new TableMeta(unit, currency);
    }
}
```

- [ ] **Step 4: splitTable（B2 + C8）—— 加进 ChildChunker**

```java
    /**
     * TABLE 切分：按<b>渲染后的 token 预算</b>切行组，每片重复表头与题注。
     *
     * <p><b>为什么不用行数阈值</b>（评审 C8）：初版定的「每 40 行一片」对真实数据永不触发
     * —— 345 张表里最大就是 40 行，而有 25 张渲染后超过 700 token（最大约 3886）。
     * 行数与 token 数不成正比，所以要按<b>渲染结果</b>切。
     *
     * <p>§8.2 硬要求：大表按行组切 Child，并<b>重复表头</b>。
     */
    public static List<Chunk> splitTable(ParentGroup group, DocumentModel.Table table) {
        List<Chunk> children = new ArrayList<>();
        List<List<String>> grid = TableRenderer.toGrid(table);
        if (grid.isEmpty()) {
            children.add(tableChild(group, table, "", 0));
            return children;
        }

        String caption = table.caption() == null ? "" : table.caption().strip();
        int cols = grid.stream().mapToInt(List::size).max().orElse(0);
        List<String> header = grid.get(0);
        int headerTokens = TokenEstimator.estimate(caption)
                + TokenEstimator.estimate(String.join(" ", header));

        int from = 1;                 // 第 0 行是表头，不作为数据行
        int index = 0;
        while (from < grid.size()) {
            int to = from;
            int tokens = headerTokens;
            while (to < grid.size()) {
                int rowTokens = TokenEstimator.estimate(String.join(" ", grid.get(to)));
                if (to > from && tokens + rowTokens > ChunkStrategy.CHILD_MAX_TOKENS) {
                    break;
                }
                tokens += rowTokens;
                to++;
            }
            StringBuilder sb = new StringBuilder();
            if (!caption.isEmpty()) {
                sb.append(caption).append('\n');
            }
            sb.append(TableRenderer.rowToMarkdown(header, cols)).append('\n');
            sb.append(TableRenderer.separatorRow(cols)).append('\n');   // 每片重复表头
            for (int r = from; r < to; r++) {
                sb.append(TableRenderer.rowToMarkdown(grid.get(r), cols)).append('\n');
            }
            children.add(tableChild(group, table, sb.toString().strip(), index++));
            from = to;
        }
        return children;
    }

    private static Chunk tableChild(ParentGroup group, DocumentModel.Table table,
                                    String content, int index) {
        TableMeta meta = TableMeta.fromCaption(table.caption());
        return new Chunk(
                ChunkLevel.CHILD, ChunkType.TABLE,
                group.parent().sectionPath(), group.parent().title(), content,
                table.page_start(), table.page_end(),
                table.table_id(), bboxJson(table.bbox()),
                TokenEstimator.estimate(content), index,
                table.caption(), meta.unit(), meta.currency(),
                headersJson(table), null, null);
    }

    private static String bboxJson(DocumentModel.BBox b) {
        return b == null ? null : "{\"left\":%s,\"top\":%s,\"right\":%s,\"bottom\":%s}"
                .formatted(b.left(), b.top(), b.right(), b.bottom());
    }

    /** 表头单元格的 JSON 数组，供阶段 4 的 Context Packing 与 Rerank 使用（§8.8）。 */
    private static String headersJson(DocumentModel.Table table) {
        if (table.cells() == null) {
            return null;
        }
        StringBuilder sb = new StringBuilder("[");
        boolean first = true;
        for (DocumentModel.Cell c : table.cells()) {
            if (c.row() != 0) {
                continue;
            }
            if (!first) {
                sb.append(',');
            }
            first = false;
            sb.append('"').append(c.text() == null ? ""
                    : c.text().replace("\\", "\\\\").replace("\"", "\\\""))
              .append('"');
        }
        return sb.append(']').toString();
    }
```

- [ ] **Step 5: ChildChunkerTableTest —— 必须含「合成大表」用例**

**真实数据里最大表恰好 40 行，靠真实数据测不到分片路径** —— 这正是评审 C8 说的
「需求完全没被验证」。所以**必须造一个人造大表**。

断言清单：
- 小表 → 1 个 Child，token ≤ 700
- **合成大表（100 行 × 长文本）→ 多个 Child，且每一片都含表头行与分隔行**
- caption 为 `单位：元` 时 `unit == "元"`、`currency == "CNY"`
- 每个 TABLE Child：`type == TABLE`、`headersJson` 非空、`sourceBlockIds == table_id`

Run: `mvn -B -Dtest=TableRendererTest,ChildChunkerTableTest test`
Expected: 全部 PASS。

---

## Task 7: Embedding 服务 —— 批量必须自己控

> **📌 契约提醒**：`embedding_text` 的组成规则（含分隔符、为什么不落库、改动流程）
> 已记录在 [关键契约.md](<../../关键契约.md>) 的 **C1**。改拼接逻辑前先读它。

**设计依据（§8.6）：** 只给 CHILD 建向量；保存 `embedding_model / embedding_version / embedding_text_hash`。

**硬限制（路线图核实）：** 百炼 `text-embedding-v4` **单请求最多 10 行**。Spring AI 的
`EmbeddingModel` 会把整个 List 一次性发出去，所以**批量必须自己切**。

### 评审实测出的 API 事实（B3，已用 javap 验证）

`EmbeddingModel` 的**抽象方法只有两个**：`call(EmbeddingRequest)` 与 `embed(Document)`；
而 `embed(List<String>)` **确实存在但是 default 方法**。因此：

- 生产代码用 `embed(List<String>)` **没问题**
- 但写 `FakeEmbeddingModel`时**必须补上那两个抽象方法**，否则编译失败 ——
  初版计划里的假模型与匿名内部类**都编译不过**（计划却写着"3 个用例 PASS"）

- [ ] **Step 1【必做】先确认签名**

```powershell
Set-Location 'D:\Project\myproject\financial-ai-project'
mvn -B -DskipTests dependency:build-classpath "-Dmdep.outputFile=target/cp.txt"
$cp = "target\classes;" + (Get-Content target\cp.txt -Raw).Trim()
javap -cp $cp org.springframework.ai.embedding.EmbeddingModel
```

Expected: 看到 `call(EmbeddingRequest)` 与 `embed(Document)` 是抽象方法；
`embed(List<String>)` 与 `embed(String)` 是 default。**把实际签名记在下方再动手。**

- [ ] **Step 2: EmbeddingService（含 C9 修正）**

**评审 C9**：初版把 `embedding_model` 存成 **Java 类名**、把 `embedding_version` 硬编码成
`text-embedding-v4` —— **语义反了**。后果是 `idx_chunk_embedding_meta` 会误判重嵌入范围。

正确语义：`embedding_model` = 服务商模型名（`text-embedding-v4`）；`embedding_version` = 我们自己
定义的"接入版本"（模型没换但改了调用参数时升它）。

```java
package com.finagent.embedding;

import com.finagent.chunk.model.Chunk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 向量化服务。
 *
 * <p><b>唯一职责是"控批量"</b> —— Spring AI 的 EmbeddingModel 会把整个列表一次性发出去，
 * 而百炼 text-embedding-v4 单请求上限 10 行。不切批就会直接 400。
 *
 * <p>输入用拼接后的 embedding_text（含标题与章节路径），但**不改写 chunk.content** ——
 * 拼接模板是"给模型看的 Prompt"，原始内容才是事实源（§8.5）。
 */
@Service
public class EmbeddingService {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingService.class);

    /** 百炼 text-embedding-v4 的单请求行数上限（路线图已核实） */
    public static final int MAX_ROWS_PER_REQUEST = 10;

    /** 服务商模型名 —— 写进 embedding_model */
    public static final String MODEL = "text-embedding-v4";

    /**
     * 本项目的接入版本 —— 写进 embedding_version。
     * 模型没换、但我们改了调用参数（如 dimensions、encoding_format）时升它。
     */
    public static final String VERSION = "v1";

    private final EmbeddingModel embeddingModel;

    public EmbeddingService(EmbeddingModel embeddingModel) {
        this.embeddingModel = embeddingModel;
    }

    public String modelName() {
        return MODEL;
    }

    public String modelVersion() {
        return VERSION;
    }

    /**
     * 批量向量化，返回与输入<b>等长等序</b>的向量列表。
     *
     * @throws IllegalStateException 返回数量与输入不符（宁可失败也不要错位入库）
     */
    public List<float[]> embedAll(List<String> texts) {
        if (texts.isEmpty()) {
            return List.of();
        }
        List<float[]> result = new ArrayList<>(texts.size());
        for (int i = 0; i < texts.size(); i += MAX_ROWS_PER_REQUEST) {
            int end = Math.min(i + MAX_ROWS_PER_REQUEST, texts.size());
            List<String> batch = texts.subList(i, end);
            List<float[]> vectors = embeddingModel.embed(batch);
            if (vectors.size() != batch.size()) {
                throw new IllegalStateException(
                        "向量数量与输入不符：输入 " + batch.size() + " 条，返回 " + vectors.size() + " 条");
            }
            result.addAll(vectors);
            log.debug("已向量化 {}/{}", end, texts.size());
        }
        return result;
    }

    /**
     * 构造送进模型的文本。
     *
     * <p>模板变了要重嵌入，所以入库时同时记 embedding_text_hash —— 它能精确圈出
     * "哪些 chunk 的 embedding_text 变了"。
     */
    public static String embeddingText(String documentTitle, Chunk chunk) {
        StringBuilder sb = new StringBuilder();
        if (documentTitle != null && !documentTitle.isBlank()) {
            sb.append(documentTitle.strip()).append('\n');
        }
        if (chunk.sectionPath() != null && !chunk.sectionPath().isEmpty()) {
            sb.append(String.join(" > ", chunk.sectionPath())).append('\n');
        }
        if (chunk.caption() != null && !chunk.caption().isBlank()) {
            sb.append(chunk.caption().strip()).append('\n');
        }
        sb.append(chunk.content());
        return sb.toString();
    }
}
```

- [ ] **Step 3: FakeEmbeddingModel（B3 修正）**

```java
    /**
     * 假模型：记录每次收到的批量大小，并回等长的假向量。
     *
     * <p>⚠️ 必须实现 call(EmbeddingRequest) 与 embed(Document) 两个<b>抽象</b>方法
     *（评审 javap 实测），否则编译不过。embed(List) 是 default，可以覆盖。
     */
    static class FakeEmbeddingModel implements EmbeddingModel {
        final List<Integer> batches = new ArrayList<>();
        int dimension = 1024;

        @Override
        public List<float[]> embed(List<String> texts) {
            batches.add(texts.size());
            List<float[]> out = new ArrayList<>();
            for (int i = 0; i < texts.size(); i++) {
                out.add(new float[dimension]);
            }
            return out;
        }

        // ↓↓↓ 这两个是抽象方法，必须实现（方法体留空即可）
        @Override
        public org.springframework.ai.embedding.EmbeddingResponse call(
                org.springframework.ai.embedding.EmbeddingRequest request) {
            return null;
        }

        @Override
        public float[] embed(org.springframework.ai.document.Document document) {
            return new float[dimension];
        }
    }
```

> **失败的注入方式（C2）**：不要用"把批量上限改成 0"——`i += 0` 会**死循环**。
> 用"返回条数不匹配的假模型"来触发失败路径（下面的用例就是这么写的）。

- [ ] **Step 4: 测试**

3 个用例（初版就有，但签名修正后才能真正编译）：
- 23 条 → 批量 `[10, 10, 3]`
- 空输入 → 完全不调模型
- 返回条数不匹配 → 抛 `IllegalStateException` 且消息含"向量数量与输入不符"

Run: `mvn -B -Dtest=EmbeddingServiceTest test`
Expected: 3 个用例全 PASS（不需要网络）。

---

## Task 8: 入库与原子切换

**设计依据（§8.6）：** 新版本写入 → 校验 → **原子切换 `active_chunk_version`** → 旧版本 SUPERSEDED。
**切换失败时保留旧版本继续可用。**

### 评审实测出的三处问题（都必须修）

**B5：`parentIds.set(g, parentId)` 对空 ArrayList → IndexOutOfBoundsException**，**单线程就崩**。
初版自己那个 ⚠️ 只提了并发，漏了这条。**改成局部 `List<Long>` @ + `add()`** ——
两段式控制流（先插完所有 Parent 再插 Child）**不需要改**。

**C1：并发重索引会把两套数据混进同一 chunk_version**，全是 ACTIVE 且不报错 ——
正是计划最怕的"新老混用"。需要**行锁 + 唯一索引**双保险。

**C2：嵌入的网络调用被包在 ```Transactional` 里**（532 个 child ≈ 54 次 HTTP，持有 1025 行插入的事务）。
**改成"事务外算向量 → 短事务写库+切换"。**

**Files:**
- Modify: `src/main/resources/db/migration/V3__chunk_versioning.sql`（加唯一索引）
- Create: `src/main/java/com/finagent/chunk/ChunkRepository.java`
- Create: `src/main/java/com/finagent/chunk/ChunkPersister.java`（`Transactional）
- Create: `src/main/java/com/finagent/chunk/ChunkWriter.java`（编排，不开事务）
- Test: `src/test/java/com/finagent/chunk/ChunkWriterTest.java`（**不要叫 *IT**，见 Task 10 M2）

- [ ] **Step 1: V3 加唯一索引（C1 的一半）**

```sql
-- 评审 C1：并发重索引可能把两套数据写进同一 chunk_version（都 ACTIVE、都不报错）。
-- 唯一索引让"同一版本内索引重复"直接失败，而不是静默混入。
CREATE UNIQUE INDEX IF NOT EXISTS uq_chunk_version_index
    ON document_chunk (document_id, chunk_version, chunk_level, chunk_index);
```

- [ ] **Step 2: ChunkPersister（短事务 + 行锁）**

```java
package com.finagent.chunk;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 只负责"写库 + 校验 + 切换"，**不开网络调用**（C2）。
 *
 * <p>与 {@link ChunkWriter} 拆开的原因：```Transactional` 靠代理生效，
 * **同类内部调用不会开事务** —— 把事务方法放在另一个 Bean 里，才能被正确代理。
 */
@Component
public class ChunkPersister {

    private final JdbcTemplate jdbc;
    private final ChunkRepository chunks;

    public ChunkPersister(JdbcTemplate jdbc, ChunkRepository chunks) {
        this.jdbc = jdbc;
        this.chunks = chunks;
    }

    /**
     * 行锁 + 读当前版本。**必须在事务内调用**。
     *
     * <p>评审 C1：没有锁时两个并发重索引会各自读到同一个 base 版本、算出同一个 newVersion，
     * 然后互不干扰地把自己的行写进去 —— 结果同一版本里混了两套数据，全是 ACTIVE，无任何报错。
     */
    @Transactional
    public int lockAndReadVersion(long documentId) {
        Integer v = jdbc.queryForObject(
                "SELECT coalesce(active_chunk_version, 0) FROM document WHERE id = ? FOR UPDATE",
                Integer.class, documentId);
        return v == null ? 0 : v;
    }

    /** 写入新版本 + 校验 + 原子切换，全部在一个事务内。 */
    @Transactional
    public int persistAndSwitch(long documentId, int newVersion,
                                WritePlan plan, String parserVersion) {
        long lastParentId = -1;
        for (PlannedGroup g : plan.groups()) {
            lastParentId = chunks.insertParent(documentId, newVersion, g.parent(), parserVersion);
            for (PlannedChild c : g.children()) {
                chunks.insertChild(documentId, lastParentId, newVersion, c.chunk(),
                        c.embedding(), c.embeddingTextHash());
            }
        }
        chunks.assertComplete(documentId, newVersion, plan.parentCount(), plan.childCount());
        chunks.switchActiveVersion(documentId, newVersion);
        chunks.supersedeOtherVersions(documentId, newVersion);
        return newVersion;
    }
}
```

- [ ] **Step 3: ChunkWriter（编排：事务外算向量）**

```java
package com.finagent.chunk;

import com.finagent.chunk.model.Chunk;
import com.finagent.chunk.model.ChunkLevel;
import com.finagent.chunk.model.ParentGroup;
import com.finagent.embedding.EmbeddingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;

/** 编排：切好的 chunk -> 向量 -> 入库（短事务）。 */
@Service
public class ChunkWriter {

    private static final Logger log = LoggerFactory.getLogger(ChunkWriter.class);

    private final ChunkPersister persister;
    private final EmbeddingService embeddingService;

    public ChunkWriter(ChunkPersister persister, EmbeddingService embeddingService) {
        this.persister = persister;
        this.embeddingService = embeddingService;
    }

    public int writeVersion(long documentId, String documentTitle,
                            List<ParentGroup> groups, List<List<Chunk>> childrenPerGroup,
                            String parserVersion) {
        // ---------- 1) 事务外：算向量（532 个 child ≈ 54 次 HTTP，不该持有事务）----------
        List<String> texts = new ArrayList<>();
        for (List<Chunk> children : childrenPerGroup) {
            for (Chunk c : children) {
                if (c.level() == ChunkLevel.CHILD) {
                    texts.add(EmbeddingService.embeddingText(documentTitle, c));
                }
            }
        }
        List<float[]> vectors = embeddingService.embedAll(texts);

        // ---------- 2) 事务内：拿版本号 -> 写 -> 校验 -> 切换 ----------
        int base = persister.lockAndReadVersion(documentId);
        int newVersion = base + 1;

        WritePlan plan = WritePlan.assemble(groups, childrenPerGroup, vectors);
        int written = persister.persistAndSwitch(documentId, newVersion, plan, parserVersion);
        log.info("文档 {} 已切到 chunk 版本 {}（Parent {} / Child {}）",
                documentId, written, plan.parentCount(), plan.childCount());
        return written;
    }

    static String sha256(String text) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("计算 SHA-256 失败", e);
        }
    }
}
```

- [ ] **Step 4: 补 WritePlan / PlannedGroup / PlannedChild**

三个简单的 record，把「Parent、它的 Child、每个 Child 的向量与 hash」绑在一起，
让 persister 不需要知道 embedding 的存在：

```java
public record WritePlan(List<PlannedGroup> groups) {
    public record PlannedGroup(Chunk parent, List<PlannedChild> children) {
    }
    public record PlannedChild(Chunk chunk, float[] embedding, String embeddingTextHash) {
    }

    public int parentCount() { return groups.size(); }
    public int childCount() { return groups.stream().mapToInt(g -> g.children().size()).sum(); }

    /** 把向量按 CHILD 的顺序贴回对应的 chunk。 */
    public static WritePlan assemble(List<ParentGroup> groups, List<List<Chunk>> childrenPerGroup,
                                     List<float[]> vectors) { ... }
}
```

> ⚠️ 初版那个"把 @parentIds@ 写成实例字段"的做法**必须彻底删掉** ——
> B5 说它单线程就崩，C1 说并发会串数据。Step 2/3 的两段式写法（每个 group 插完 Parent
> 立刻用返回的 id 插它的 Child）**天然没有暂存问题**。

- [ ] **Step 5: 测试（含失败保留旧版本）**

用例：
1. 首次写入 → `active_chunk_version == 1`，Parent/Child 数量正确，向量覆盖 100%
2. 再写一次 → `active_chunk_version == 2`，旧版本全部 `SUPERSEDED`
3. **失败注入**：让 `embedAll` 返回条数不匹配（用**抛异常的 stub**，不是"批量改 0"）→
   断言 `active_chunk_version` **不变**、旧版本仍 `ACTIVE`、新版本**没有任何残留行**

Run: `mvn -B -Dtest=ChunkWriterTest test`
Expected: 3 个用例全 PASS。

---

## Task 9: RECHUNK 入口 —— 以及**未实现模式的显式失败**

**设计依据（§8.6）：** `document.reindex` 支持 RETRIEVAL_ONLY / RECHUNK / REEMBED / FULL_REPARSE。

### 评审对初版 Task 9 的两条批评（都接受）

**M1：初版 `ReindexService` 没有任何调用方**，`FULL_REPARSE` 还把"重新解析"甩给一个
不存在的「调用方」—— 等于四个模式一个都没交付。

**Y2：建议把 RETRIEVAL_ONLY 与 REEMBED 后移到阶段 4**。理由成立：

| 模式 | 为什么本阶段做不了 |
|---|---|
| RETRIEVAL_ONLY | 它的作用对象是**检索参数**，而阶段 3 没有检索代码 |
| REEMBED | 它需要"按 embedding_model/version 圈出待重嵌入的 chunk"，而**只有真换模型时才知道该怎么用** |

**本阶段只交付 RECHUNK**（它正好是 Task 10 端到端在跑的流程），另外两个
**显式抛异常**并写明"阶段 4 实现"。**空方法假装支持是最坏的情况** ——
调用方会以为重嵌入生效了。

**Files:**
- Create: `src/main/java/com/finagent/chunk/ReindexMode.java`
- Create: `src/main/java/com/finagent/chunk/ReindexService.java`
- Create: `src/main/java/com/finagent/chunk/ReindexRunner.java`（**调用方**，M1）
- Test: `src/test/java/com/finagent/chunk/ReindexServiceTest.java`

- [ ] **Step 1: 模式枚举 + 服务**

```java
public enum ReindexMode {
    RETRIEVAL_ONLY,
    /** 本阶段唯一实现的模式：用已存的 NormalizedDocument 重新切块并重嵌入，**不重新解析 PDF** */
    RECHUNK,
    REEMBED,
    FULL_REPARSE
}
```

```java
@Service
public class ReindexService {

    private final DocumentRepository documents;
    private final NormalizedDocumentLoader loader;
    private final ChunkWriter writer;

    public ReindexService(DocumentRepository documents, NormalizedDocumentLoader loader,
                          ChunkWriter writer) { ... }

    /** @return 新的 chunk 版本号 */
    public int reindex(long documentId, ReindexMode mode) {
        if (mode != ReindexMode.RECHUNK) {
            // 显式失败，而不是静默什么都不做（评审 Y2 / M1）
            throw new UnsupportedOperationException(
                    mode + " 尚未实现：RETRIEVAL_ONLY 依赖阶段 4 的检索参数，"
                            + "REEMBED 依赖换模型场景，FULL_REPARSE 依赖调用解析服务。"
                            + "本阶段只交付 RECHUNK。");
        }
        var st = documents.findParseState(documentId);
        if (!"DONE".equals(st.parseStatus())) {
            throw new IllegalStateException("文档 " + documentId + " 尚未解析完成，无法重切");
        }
        DocumentModel model = loader.load(st.parsedObjectKey());
        var groups = ParentChunker.split(model);
        var children = groups.stream().map(ChildChunker::splitAll).toList();
        return writer.writeVersion(documentId, st.title(), groups, children,
                model.meta() == null ? st.parserVersion() : model.meta().parser_version());
    }
}
```

> `DocumentRepository` 的 `findParseState` 返回的 record 目前没有 `title` —— **本步骤要为它补上**
>（阶段 2 建的那个 record 只选了状态相关字段）。

- [ ] **Step 2: ReindexRunner（M1：给它一个真实调用方）**

```java
package com.finagent.chunk;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 重索引的命令行入口 —— 让 {@link ReindexService} 真的有人调用（评审 M1）。
 *
 * <p>用法：{@code mvn spring-boot:run -Dspring-boot.run.arguments="--finagent.reindex.document-id=7"}
 * 或设环境变量 {@code FINAGENT_REINDEX_DOCUMENT_ID=7}。
 *
 * <p>为什么用 ApplicationRunner 而不是 REST 接口：本阶段还没有 Web 层需求，
 * 加一个 controller 会引入权限、审计等本阶段不处理的关注点。CLI 足够，阶段 4 再开接口。
 */
@Component
@ConditionalOnProperty(name = "finagent.reindex.document-id")
public class ReindexRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(ReindexRunner.class);

    private final ReindexService reindexService;
    private final long documentId;

    public ReindexRunner(ReindexService reindexService,
                         org.springframework.core.env.Environment env) {
        this.reindexService = reindexService;
        this.documentId = Long.parseLong(env.getProperty("finagent.reindex.document-id", "0"));
    }

    @Override
    public void run(ApplicationArguments args) {
        int version = reindexService.reindex(documentId, ReindexMode.RECHUNK);
        log.info("重切完成：document_id={} 新 chunk 版本={}", documentId, version);
    }
}
```

- [ ] **Step 3: 测试**

- `RETRIEVAL_ONLY` / `REEMBED` / `FULL_REPARSE` 调用 → 抛 `UnsupportedOperationException`，
  消息里含"尚未实现"（**不能是空方法**）
- `RECHUNK` 走通（用假 loader + 假 writer）

Run: `mvn -B -Dtest=ReindexServiceTest test`
Expected: PASS。

---

## Task 10: 端到端 —— 拿真实的 181 页产物切块并入库

### ⚠️ 评审 M2：初版把用例命名成 `*IT`，**它们永远不会在 `mvn test` 里运行**

surefire 默认 `includes` 是 `*Test / Test* / *Tests / *TestCase` —— **不含 `*IT`**。
这意味着核心验收 #6/#7（原子切换、失败保留旧版）在默认构建里是**假绿灯**：
`mvn test` @照样 BUILD SUCCESS，因为那些用例根本没跑。

**修法：改名 `ChunkPipelineTest`（不用 `*IT`）**，继续用 `\u0040Tag("e2e")` 控制默认排除。
**不要**引入 failsafe（那要改构建阶段，本阶段没必要）。

**Files:**
- Create: `src/main/java/com/finagent/document/NormalizedDocumentLoader.java`
- Test: `src/test/java/com/finagent/ChunkPipelineTest.java`

- [ ] **Step 1: NormalizedDocumentLoader**

```java
package com.finagent.document;

import com.finagent.storage.ObjectStorage;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/** 从 COS 读回阶段 2 的产物 —— RECHUNK 不碰 PDF 的关键。 */
@Component
public class NormalizedDocumentLoader {

    private final ObjectStorage storage;
    private final ObjectMapper mapper;

    public NormalizedDocumentLoader(ObjectStorage storage, ObjectMapper mapper) {
        this.storage = storage;
        this.mapper = mapper;
    }

    public DocumentModel load(String parsedObjectKey) {
        byte[] raw = storage.get(parsedObjectKey);
        return mapper.readValue(raw, DocumentModel.class);
    }
}
```

> ⚠️ `ObjectMapper` 必须是 **Jackson 3** 的（`tools.jackson`）—— 阶段 2 已踩过：
> 用 `com.fasterxml.jackson` 会在启动时报 "No qualifying bean"，且**编译期看不出来**。
> 但 **注解**仍在 `com.fasterxml.jackson.annotation`（见 Task 4 Step 1）—— 两者不一样，别混。

- [ ] **Step 2: ChunkPipelineTest（可承诺的断言）**

```java
@SpringBootTest
@ActiveProfiles("test")
@Tag("e2e")
class ChunkPipelineTest {

    @Test
    void chunksAndIndexesTheAnnualReport() {
        // ... 读产物、切块、写库（同初版）

        int parents = chunks.countByVersion(DOC, version, "PARENT");
        int children = chunks.countByVersion(DOC, version, "CHILD");

        // 打印完整分布（评审要求：不要遮一个假的达标率）
        ChunkStats stats = chunks.statsByVersion(DOC, version);
        System.out.printf("""
                ===== 切块与入库结果 =====
                chunk 版本 : %d
                Parent     : %d
                Child      : %d
                token      : min=%d p50=%d p90=%d max=%d
                <=700      : %.1f%%   (硬上限，必须 100%%)
                >=150      : %.1f%%   (下限，目标 >=80%%)
                ==========================
                """, version, parents, children,
                stats.min(), stats.p50(), stats.p90(), stats.max(),
                stats.pctAtMost(700), stats.pctAtLeast(150));
    }
}
```

- [ ] **Step 3: 关键断言（按评审重构后的口径）**

```java
        // 1) 硬上限：任何 Child 都不能超 700（这才是保护 embedding 模型的那条）
        assertThat(stats.max()).isLessThanOrEqualTo(700);
        // 2) 下限：目标口径改成 ≥150 token（评审实测 ≥300 在数学上不可达）
        assertThat(stats.pctAtLeast(150)).isGreaterThanOrEqualTo(80.0);
        // 3) 向量覆盖
        assertThat(chunks.countChildrenWithoutEmbedding(DOC, version)).isZero();
        // 4) 原子切换
        assertThat(chunks.activeVersion(DOC)).isEqualTo(version);
        // 5) 内容覆盖（评审 M5）：光比数量兜不住"静默丢内容"
        assertThat(chunks.countDistinctSourceBlocks(DOC, version))
                .isEqualTo(blocksInModel + tablesInModel);
```

> **第 5 条是评审 M5 的修正**：初版建议「各 group 的 Child 数之和 == 库里 CHILD 数」——
> 但 `writeVersion` 写的就是传入的东西，数量**必然相等**，兜不住任何东西。
> 真正要断言的是 **source_block_ids 是否覆盖了产物的全部 block 与 table**。

- [ ] **Step 4: 运行**

```powershell
Set-Location 'D:\Project\myproject\financial-ai-project'
. .\scripts\load-env.ps1
mvn -B "-Dsurefire.excludedGroups=" "-Dgroups=e2e" "-Dtest=ChunkPipelineTest" test
```

Expected: 打印分布表 + BUILD SUCCESS。

- [ ] **Step 5: 报告写入 `docs/阶段3-切块与入库报告.md`**

必须包含：Parent/Child 数量、**完整 token 分布**（min/p50/p90/max）、
`≤700` 与 `≥150` 的比例、向量覆盖率、**切块耗时与嵌入耗时分开记**、抽样引用可解析率。

---

## 验收标准

| # | 检查 | 通过条件 |
|---|---|---|
| 1 | V3 迁移 | 新列齐全；`flyway_schema_history` 有 V3；**含 `uq_chunk_version_index` 唯一索引** |
| 2 | Parent 归组 | Parent 数 == **归组键数**（按 PARENT_DEPTH 截断后的去重 section_path 数）。**不要**写成"与阶段 2 章节数一致" —— 581 个 heading 与 493 个 Parent 本来就不该相等（评审 M4） |
| 3 | **token 分布** | **实测**：TEXT/LIST **100% ≤ 700**；TABLE 有 **5 个单行超限**（最大 1610，承诺事项表的一行 —— 行是不可拆的原子单位）；**≥150 仅 68.8%**。**结论：token 下限不是本语料的可达指标**（401 个表格 Child 中 67% ≥150，小表格无法合并不破坏语义）。改为以**上下文完整性 100%**（section_path / bbox / embedding_text_hash 齐全）作质量门 |
| 4 | 表格 Child | 每张表至少 1 个 TABLE Child；**合成大表**分片且**每片带表头**；有题注的写入 caption，能抽出的写入 unit/currency；`headers_json` 非空 |
| 5 | 向量覆盖 | `countChildrenWithoutEmbedding == 0` |
| 6 | **原子切换** | 重切后 `active_chunk_version` 递增；旧版本全部 @SUPERSEDED@。**用 SQL 断言**（阶段 3 没有检索代码，无法测"检索只读新版"，评审 C3） |
| 7 | **失败保留旧版** | 用抛异常的 stub 注入失败 → 事务回滚、`active_chunk_version` **不变**、旧版本仍 `ACTIVE`、新版本无残留行 |
| 8 | 批量正确性 | 假模型记录到的批量 ≤ 10 |
| 9 | RECHUNK 不碰 PDF | 重切全程无解析服务调用（可用 mock 或日志断言） |
| 10 | 全量测试 | `mvn test` BUILD SUCCESS；**且 e2e 用例名不以 `IT` 结尾**（否则默认构建永远不跑它们，是假绿灯） |
| 11 | **内容无丢失** | `source_block_ids` 覆盖产物的**全部** block 与 table（评审 M5：光比数量没意义） |
| 12 | 既有用例不回归 | 阶段 1/2 的 5 个用例仍全绿 |

## 本阶段的边界

- **不做检索**（阶段 4）；**不做 Parent Embedding**（§8.6 明确 V1 只索引 CHILD）
- **不做 FinancialFact**（§8.7 要求，但属独立子系统 —— **建议单独一份计划**）
- **REEMBED / RETRIEVAL_ONLY / FULL_REPARSE 显式抛不支持**（评审 Y2 建议后移阶段 4）
- **跨页表格的逻辑合并不做**：阶段 2 的 Table 按页切，合并要改解析侧
- **V3 里 `restatement_of / source_offset / related_footnote_ids` 只建列不写入**（评审 Y3）：
  本阶段没有产出它们的路径。**必须在迁移文件里注明**，避免以后误以为已有数据

## 偏差登记（需回写设计文档 v0.10）

| # | 设计文档 | 原述 | 实测/实际 | 处理 |
|---|---|---|---|---|
| 1 | §8.2 | **Parent 约 1500~3000 tokens** | 按最深层级归组时中位仅 **48**、p90 361，479 个里**只有 1 个**达 1500 | **已把 Parent 深度做成参数 `PARENT_DEPTH`（默认 2）**；回写文档：Parent 粒度由**归组深度**决定，且**默认取前 2 级** |
| 2 | §8.2 | Child 300~700 | 按最深归组时入区间仅 **16.9%**，理论上限 **17.0%** | 验收口径改为「≤700 硬上限 + ≥150 下限」；回写文档：**该 token 区间在真实年报上不可达**，需按文档粒度校准 |
| 3 | §8.5 | Parent = 完整语义小节 | 阶段 2 的 section_path 是**最深路径**，直接当边界会退化成几十字的小节 | 回写：**Parent 边界 = section_path 的前 PARENT_DEPTH 级** |
| 4 | §8.6 | 四模式重索引 | 本阶段只交付 RECHUNK，另外三个显式抛异常 | 回写：**REEMBED/RETRIEVAL_ONLY/FULL_REPARSE 待阶段 4** |
| 5 | §8.8 | `chunk_version` / `active_chunk_version` | 阶段 1 建表**漏了** | V3 补齐（阶段 1 计划需同步修正） |
| 6 | §8.2 | 跨页表格逻辑合并 | 未合并，按页成片 | 登记为未决 |
| 7 | §8.2 | 「Child 默认 300~700 tokens」 | 真实语料：**≥150 的仅 68.8%**，p50=263；401 个表格 Child 中 67% ≥150（小表格不能合并），TEXT 有大量「不适用 / 单位：元」类 3~7 token 的独立片段 | **验收口径已改**：token 下限不作门禁，改以**上下文完整性**为质量指标（实测 **100%**）。回写文档：§8.2 的 300~700 是**期望值而非可达约束** |
| 8 | §8.8 | `chunk_index` 未定义范围 | 切块时它是**每个 Parent 内**从 0 编号；入库要求版本内**全局唯一**（否则撞 `uq_chunk_version_index`） | 已在 `WritePlan.assemble` 统一重编号。回写文档：**chunk_index 是版本内全局序号**，sibling 扩展按 `parent_id` 过滤后再排序 |
| 9 | §8.2 | 「Parent 和原始 block 必须持久化」 | 原始 block **已由阶段 2 的 COS 产物满足**（`normalized.json` 里的 blocks/tables 就是它）；Parent 由本阶段写入 `document_chunk` | **不额外建 blocks 表**。回写文档：明确「原始 block 的持久化形态 = 解析产物对象」，避免读者以为要单独存一份 |

## 未决问题（留给阶段 4/6）

1. **跨页表格未合并**：会以 `page_start != page_end` 的分片形式存在。
2. **Child token 分布**：若大量 Child < 150（因为某些 Parent 总量就不足），是否要**跨 Parent 合并**？
   先看 Task 10 的实测分布。
3. **表格 caption 覆盖率仅 20%**（阶段 2 实测 70/345）：没有题注的表格在 embedding_text 里缺表名，
   可能影响表格类问题召回。阶段 6 专门看这一类。
4. **Parent 是否值得建向量**：§8.6 说"若评测证明有价值再补"。阶段 6 用数据回答。
5. **`PARENT_DEPTH` 该取多少**：默认 2（评审实测天花板 64.7%）。若阶段 6 发现上下文扩展不够用，
   降到 1（天花板 95.9%）即可 —— 改一个常量。
