# 金融研报 RAG：Parent-Child Chunk 设计学习文档

> 适用场景：金融研报、上市公司年报、公告、财务 PDF 等复杂文档的 RAG 入库与检索  
> 项目基线：金融研报智能分析 Agent v0.7  
> 核心目标：**结构优先、Child 负责检索、Parent 负责上下文扩展、表格与脚注可追溯、支持局部重切与重嵌入。**

---

## 1. 为什么金融 PDF 不能简单按固定 Token 切分

最简单的 RAG Chunk 方式通常是：

```text
PDF
↓
提取纯文本
↓
每 500 Token 切一段
↓
Embedding
↓
Vector Search
```

这种方式适合普通文本知识库，但不适合金融研报。

金融文档常见特点：

- 存在严格的章节层级；
- 表格非常多；
- 同一个财务指标通常包含“指标名 + 年份 + 数值 + 单位 + 口径”；
- 表格可能跨页；
- 会计口径和脚注可能决定数字的真正含义；
- 页码只是引用信息，不一定是语义边界；
- 同一小节里多个段落共同表达一个完整结论。

例如：

```text
营业收入
2025 年：358.2 亿元
2024 年：319.0 亿元
同比增长：12.3%
```

如果固定 Token 切分把“营业收入”和“358.2 亿元”切开，检索虽然可能命中数字，但模型无法确认这个数字属于什么指标。

因此金融文档的基本原则应该是：

> **先识别文档结构和语义边界，再用 Token 数量做最后一级兜底。**

切分优先级：

```text
Section / Subsection
        >
Table / List
        >
Paragraph
        >
Sentence
        >
Token Boundary
```

---

# 2. 为什么 V1 就采用 Parent-Child

项目不采用：

```text
V1：平铺 Chunk
V2：再升级 Parent-Child
```

而是从 V1 就建立 Parent-Child 结构。

原因主要有三个。

## 2.1 避免后期重新切分和重新 Embedding

如果第一版只是：

```text
Chunk 1
Chunk 2
Chunk 3
```

以后改成：

```text
Parent
├── Child 1
├── Child 2
└── Child 3
```

很可能需要重新：

```text
解析
→ 切块
→ Embedding
→ 重建向量索引
```

而如果第一版就保存：

```text
Parent
Source Block
Child
```

以后大多数 Retrieval 策略变化都只影响查询逻辑。

例如：

```text
Child-only
→ Sibling Expansion
→ Parent Expansion
```

这些变化通常不需要重新 Embedding。

---

## 2.2 同时兼顾“检索精度”和“上下文完整性”

小 Chunk：

```text
优点：检索精准
缺点：上下文不足
```

大 Chunk：

```text
优点：信息完整
缺点：Embedding 语义容易被稀释
```

Parent-Child 正好把两个问题拆开：

```text
Child
=
Retrieval Unit

Parent
=
Context Unit
```

即：

> **用小块找，用大块读。**

---

## 2.3 更适合金融文档结构

例如：

```text
第三节 管理层讨论与分析
└── 3.2 主营业务分析
    ├── 营业收入总体变化
    ├── 产品收入结构
    ├── 地区收入结构
    └── 收入变化原因
```

可以表示成：

```text
Parent：3.2 主营业务分析

├── Child 1：营业收入总体变化
├── Child 2：产品收入结构
├── Child 3：地区收入结构
└── Child 4：收入变化原因
```

这样 Query 命中 Child 后，可以按问题类型决定是否扩展 Parent。

---

# 3. Parent 是什么

Parent 不是简单的“大 Chunk”。

Parent 的定义应该是：

> **一个业务语义完整、内部内容高度相关的上层知识单元。**

常见 Parent：

- 一个完整二级/三级小节；
- 一组连续表格及其说明；
- 一组风险条款；
- 一段完整会计政策；
- 一个财务报表及其必要上下文。

建议范围：

```text
约 1,500 ~ 3,000 tokens
```

但这个数字只是参考。

真正优先级：

```text
语义完整性
>
固定 Token 大小
```

一个完整小节即使略大，也可以继续作为 Parent。

---

# 4. Child 是什么

Child 是：

> **可以独立参与召回和 Rerank 的最小语义单元。**

V1 建议目标：

```text
300 ~ 700 tokens
```

Child 应做到：

- 不跨重要章节；
- 尽量保持一个主题；
- 指标名和值不要分离；
- 表格行保持完整；
- 能够独立做 Embedding；
- 能回溯 Parent 和原始 PDF。

---

# 5. Parent 与 Child 的关系

```text
Document
│
├── Parent P1
│   ├── Child C1
│   ├── Child C2
│   └── Child C3
│
└── Parent P2
    ├── Child C4
    └── Child C5
```

核心字段：

```text
parent_id
```

例如：

```text
C1.parent_id = P1.id
C2.parent_id = P1.id
C3.parent_id = P1.id
```

这样检索命中：

```text
C2
```

以后可以快速获取：

```text
P1
C1
C3
```

用于上下文扩展。

---

# 6. Chunk 类型

金融文档中的不同内容不能采用同样的切分规则。

建议至少区分：

```text
TEXT
TABLE
LIST
FIGURE
CAPTION
FOOTNOTE
```

其中 V1 最重要的是：

```text
TEXT
TABLE
LIST
```

---

# 7. TEXT Chunk 规则

普通正文：

```text
公司 2025 年实现营业收入 358.2 亿元，
同比增长 12.3%。

主要增长来源于……
```

优先按照：

```text
Paragraph
↓
Sentence
↓
Token
```

拆分。

### Text Overlap

TEXT Child 可以保留少量 overlap，例如：

```text
50 ~ 100 tokens
```

但 overlap 只是辅助。

不要让 overlap 破坏章节边界。

---

# 8. TABLE Chunk 是金融项目的重点

财务表格不能使用普通 Token Splitter。

例如：

| 项目 | 2025 | 2024 |
| --- | ---: | ---: |
| 营业收入 | 358.2 | 319.0 |
| 净利润 | 82.1 | 75.6 |

正确的语义单元应该包含：

```text
表名
+
单位
+
表头
+
完整数据行
+
会计口径
```

例如：

```text
表：主要会计数据
单位：亿元
口径：合并报表

项目 | 2025 | 2024
营业收入 | 358.2 | 319.0
净利润 | 82.1 | 75.6
```

---

## 8.1 大表怎么切

如果表格有 100 行，不能把整个表作为一个 Child。

应该：

```text
TABLE Parent
│
├── Child 1
│   ├── caption
│   ├── unit
│   ├── headers
│   └── row 1~20
│
├── Child 2
│   ├── caption
│   ├── unit
│   ├── headers
│   └── row 21~40
│
└── ...
```

注意：

> **每个 Child 都重复表头和单位。**

不要使用普通文本的机械 overlap。

---

# 9. 跨页表格

财报经常出现：

```text
Page 128
表格上半部分

Page 129
表格下半部分
```

如果直接按照页码切：

```text
Page128 → Chunk A
Page129 → Chunk B
```

会破坏逻辑表。

因此：

```text
page
```

是：

> Citation Metadata

而不是：

> 强制 Chunk Boundary

正确流程：

```text
PDF
↓
Docling / Parser
↓
识别跨页 table continuation
↓
合并成 logical_table
↓
再生成 Parent / Child
```

但仍然必须保留：

```text
row / cell → page / bbox
```

这样最终引用还能定位到原始 PDF。

---

# 10. 脚注为什么必须关联

例如：

```text
营业收入：358.2 亿元
```

下面写：

```text
注：2024 年比较数据因会计政策调整进行了重述。
```

如果 RAG 只召回：

```text
358.2 亿元
```

却没有带脚注，就可能产生：

```text
数字本身正确
但比较口径错误
```

所以设计中应保留：

```text
related_footnote_ids
```

用于：

```text
Parent
Table
Child
```

关联对应 Footnote。

---

# 11. NormalizedDocument

项目不会让 RAG 直接依赖 Docling 私有数据结构。

中间保留统一模型：

```text
NormalizedDocument
│
└── Section
    ├── Heading
    ├── Paragraph
    ├── Table
    ├── List
    ├── Figure
    ├── Caption
    └── Footnote
```

核心目的：

```text
Docling
        \
         → NormalizedDocument → ParentChildChunkBuilder
        /
MinerU
```

这样以后替换 Parser 时：

```text
RAG
Database
Chunk
Embedding
```

都不需要跟着重构。

---

# 12. Docling / Arconia 在 Chunk 中负责什么

目前项目默认：

```text
Spring Boot
+
Arconia
+
Docling Serve
```

Docling 负责：

```text
PDF Layout
OCR
Reading Order
Heading
Paragraph
Table
List
Caption
```

Arconia 负责：

```text
Spring Boot
↕
Docling Serve
```

的基础设施集成。

但业务上的：

```text
哪个 Section 是 Parent
哪些块属于同一个 Parent
Parent / Child ID
金融表格的业务语义
脚注关系
Chunk Version
```

仍由：

```text
financial-document
```

负责。

也就是：

> Docling 负责“看懂 PDF”，业务层负责“组织金融知识”。

---

# 13. Chunk Builder 的完整处理流程

```text
PDF
↓
Docling Serve
↓
Parser Adapter
↓
NormalizedDocument
↓
Document Tree
↓
遍历 Section
↓
生成 Parent
↓
按 Block 类型生成 Child Candidate
↓
长度检查
↓
如果过长：
    Paragraph boundary
        ↓
    Sentence boundary
        ↓
    Token boundary
↓
补齐 Metadata
↓
生成 contextualized embedding_text
↓
Embedding
↓
pgvector
```

---

# 14. Embedding 只做 Child

V1 规则：

```text
CHILD
→ Embedding

PARENT
→ embedding 可为空
```

原因：

```text
Child
负责检索

Parent
负责扩展上下文
```

这样可以减少：

```text
Embedding 成本
向量数量
Parent/Child 重复命中
```

以后如果评测证明：

```text
Parent-level Retrieval
```

确实有价值，再单独补 Parent Embedding。

不会影响已有 Child 索引。

---

# 15. Contextualized Embedding Text

数据库中的：

```text
content
```

应该保留原始标准化内容。

不要直接把用于 Embedding 的拼接文本覆盖它。

例如原始 Child：

```text
2025 年营业收入为 358.2 亿元。
```

Embedding 时可以构造：

```text
贵州茅台
2025年年度报告
管理层讨论与分析
营业收入分析

2025 年营业收入为 358.2 亿元。
```

即：

```text
embedding_text
=
document_title
+
section_path
+
title
+
child_content
```

这样能提升 Embedding 语义。

但数据库仍分开保存：

```text
content
section_path
title
metadata
```

---

# 16. 为什么要保存 embedding_text_hash

如果以后拼接策略从：

```text
section + content
```

改成：

```text
document_title
+ section
+ subsection
+ content
```

Embedding 输入变了。

即使：

```text
content
```

没有变，也应该重新 Embedding。

因此保存：

```text
embedding_text_hash
```

就可以判断：

```text
真正用于 Embedding 的文本是否变化
```

---

# 17. Retrieval Unit 与 Context Unit

这是 Parent-Child RAG 最重要的概念之一。

```text
Retrieval Unit
=
Child
```

意味着：

```text
Dense Retrieval
Sparse Retrieval
RRF
Rerank
```

都是以 Child 为候选。

然后：

```text
Context Unit
```

根据 Query 类型动态决定。

可能是：

```text
Child Only
```

也可能：

```text
Child + Siblings
```

或者：

```text
Parent
```

---

# 18. 三种 Context Expansion

## 18.1 Child Only

适合：

```text
精确事实查询
```

例如：

```text
2025 年营业收入是多少？
```

命中：

```text
Child C1
```

如果 C1 已包含：

```text
指标
年份
值
单位
```

就不需要扩展整个 Parent。

---

## 18.2 Sibling Expansion

适合：

```text
解释类问题
```

例如：

```text
公司收入为什么增长？
```

可能：

```text
C1 = 收入总体变化
C4 = 增长原因
```

最后上下文：

```text
C1 + C4
```

---

## 18.3 Parent Expansion

适合：

```text
综合分析
完整小节理解
```

例如：

```text
全面分析主营业务收入结构。
```

可以返回：

```text
Parent P1
```

或者 Parent 内经过预算控制后的多个 Child。

---

# 19. Chunk 与 Hybrid Retrieval 的关系

项目的 RAG 标准流水线：

```text
Query
↓
Metadata Filter
↓
Dense Child Retrieval
+
Sparse Child Retrieval
↓
RRF
↓
Cross-Encoder Rerank
↓
Parent / Sibling Expansion
↓
Context Packing
↓
LLM
```

重要顺序：

> **先 Rerank Child，再 Parent Expansion。**

不要：

```text
Child Search
↓
立即扩整个 Parent
↓
Rerank
```

否则 Parent 太大，会：

```text
增加 Reranker 成本
引入无关信息
降低精度
```

---

# 20. document_chunk 数据模型

建议核心字段：

```text
document_chunk
--------------------------------

id
document_id

parent_id

chunk_level
    PARENT / CHILD

chunk_type
    TEXT / TABLE / LIST / ...

section_path
title
content

page_start
page_end

source_block_ids
source_offset
bbox

token_count
chunk_index

parser_version
chunk_strategy_version

embedding
embedding_model
embedding_version
embedding_text_hash

metadata JSONB
```

---

# 21. Metadata 为什么很重要

Chunk 不只是：

```text
content + embedding
```

金融系统还需要：

```text
companyId
year
documentType
section
reportType
currency
statementScope
page
permissions
```

这样查询：

```text
贵州茅台 2025 年合同负债
```

可以先：

```text
company_id = 600519
year = 2025
document_type = ANNUAL_REPORT
```

然后再：

```text
Dense
+
Sparse
```

降低噪声并保证数据隔离。

---

# 22. Source Block 为什么必须保留

Parent 和 Child 不是最终不可变数据。

未来可能调整：

```text
Child size
Overlap
Table split
Chunk Strategy
```

如果只保存最终 Chunk：

```text
Child 1
Child 2
```

以后必须重新解析原始 PDF。

如果保存：

```text
NormalizedDocument
+
source_block
+
Parent
+
Child
```

则可以：

```text
Parent/source block
↓
重新生成 Child
↓
重新 Embedding 受影响部分
```

不用重新跑昂贵 PDF Layout/OCR。

---

# 23. Version 设计

至少维护：

```text
parser_version
chunk_strategy_version
embedding_model
embedding_version
embedding_text_hash
```

分别代表：

### parser_version

```text
Docling / MinerU
解析规则版本
```

### chunk_strategy_version

```text
Parent-Child 规则版本
```

### embedding_version

```text
Embedding 模型/参数版本
```

这样系统能知道：

```text
到底需要重新检索？
重新切块？
重新 Embedding？
还是重新解析？
```

---

# 24. 四种重建模式

项目定义：

```text
RETRIEVAL_ONLY
RECHUNK
REEMBED
FULL_REPARSE
```

---

## RETRIEVAL_ONLY

例如修改：

```text
TopK
RRF
Rerank
Sibling Expansion
Parent Expansion
```

只改检索逻辑。

```text
不重新 Chunk
不重新 Embedding
```

---

## RECHUNK

例如：

```text
Child 300~700
改成
400~800

Table split 策略变化
```

需要：

```text
基于 Parent / Source Block
重新生成 Child
```

受影响 Child 再 Embedding。

---

## REEMBED

例如：

```text
更换 Embedding 模型
```

不需要：

```text
重新解析 PDF
重新识别表格
```

只需要：

```text
现有 Child
↓
重新 embedding
```

---

## FULL_REPARSE

例如：

```text
Docling Parser 版本重大升级
OCR/Layout 结果变化
表格识别策略变化
```

才需要：

```text
PDF
↓
重新解析
```

这是成本最高的一种。

---

# 25. Chunk 与 FinancialFact

对于：

```text
营业收入
净利润
资产
负债
现金流
```

不能把 PDF Chunk 当成唯一数据源。

建议：

```text
PDF
↓
Chunk
```

负责：

```text
证据
管理层解释
风险描述
上下文
```

与此同时：

```text
FinancialFact
```

保存结构化指标：

```json
{
  "companyId": "600519",
  "metric": "revenue",
  "year": 2025,
  "value": 358.2,
  "unit": "亿元",
  "scope": "consolidated"
}
```

未来可以优先来自：

```text
XBRL
权威金融数据源
```

而不是全部依赖 PDF RAG。

---

# 26. 一个完整例子

原始年报：

```text
第三节 管理层讨论与分析

3.2 主营业务分析

2025 年公司实现营业收入 358.2 亿元，同比增长 12.3%。

主要增长来源于核心产品销量提升……

表：主要产品收入
单位：亿元
...
```

结构化后：

```text
Parent P100
title = 主营业务分析

├── Child C101
│   type = TEXT
│   content = 营业收入总体变化
│
├── Child C102
│   type = TEXT
│   content = 收入增长原因
│
└── Child C103
    type = TABLE
    content = 产品收入表
```

查询：

```text
2025 年营业收入是多少？
```

检索：

```text
Dense + Sparse
↓
RRF
↓
C101
↓
Rerank
↓
Child Only
```

查询：

```text
为什么收入增长？
```

可能：

```text
Dense + Sparse
↓
C102
↓
Rerank
↓
Sibling Expansion
↓
C101 + C102
```

查询：

```text
全面分析主营业务收入变化
```

可能：

```text
C101 / C102 / C103
↓
Rerank
↓
Parent Expansion
↓
P100
```

---

# 27. V1 正式规则总结

V1 从一开始就采用：

```text
Parent-Child
```

具体规则：

1. **结构优先于 Token。**
2. **Parent 保持完整语义。**
3. **Child 是最小检索单元。**
4. **Child 目标约 300~700 tokens。**
5. **Parent 常见约 1,500~3,000 tokens。**
6. **不同重要章节默认不跨界。**
7. **TEXT 允许小量 overlap。**
8. **TABLE 不做机械 overlap，重复表头和单位。**
9. **跨页表格先逻辑合并。**
10. **脚注与会计口径保留关联。**
11. **V1 只对 Child 做 Embedding。**
12. **Child 保存 parent_id 与完整 source metadata。**
13. **Parent 和 source block 必须持久化。**
14. **Embedding 使用 contextualized text。**
15. **检索先针对 Child，Rerank 后再做 Parent/Sibling Expansion。**
16. **Chunk / Parser / Embedding 都要版本化。**
17. **支持局部 RECHUNK / REEMBED，而不是一律 FULL_REPARSE。**

---

# 28. 最容易踩的坑

### 坑 1：固定 500 Token 一刀切

问题：

```text
破坏章节
破坏表格
指标和值分离
```

---

### 坑 2：Parent 和 Child 都全部 Embedding

可能造成：

```text
重复召回
候选污染
向量量翻倍
```

V1 没必要。

---

### 坑 3：先扩大 Parent 再 Rerank

导致：

```text
Reranker 输入过大
成本增加
噪声增加
```

正确顺序：

```text
Child Retrieval
→ Child Rerank
→ Expansion
```

---

### 坑 4：page 作为 Chunk Boundary

页码是物理布局概念。

不是：

```text
语义边界
```

特别是跨页表格。

---

### 坑 5：只保存 Chunk，不保存原始结构

后期一改策略：

```text
重新解析整个 PDF
```

成本非常高。

---

### 坑 6：表格转成普通纯文本后直接切

会丢失：

```text
header
unit
scope
row relation
```

---

### 坑 7：只保存 content，不保存 metadata

最终无法做到：

```text
Metadata Filter
精确 Citation
Parent Expansion
局部 Rechunk
```

---

# 29. 最终架构

```text
PDF
 │
 ▼
Arconia + Docling Serve
 │
 ▼
Parser Adapter
 │
 ▼
NormalizedDocument
 │
 ├── Section
 ├── Paragraph
 ├── Table
 ├── List
 └── Footnote
 │
 ▼
ParentChildChunkBuilder
 │
 ├── Parent
 │
 └── Child
 │
 ▼
Contextualized Embedding Text
 │
 ▼
Embedding Model
 │
 ▼
pgvector
 │
 ▼
Dense + Sparse Child Retrieval
 │
 ▼
RRF
 │
 ▼
Cross-Encoder Rerank
 │
 ▼
Child / Sibling / Parent Expansion
 │
 ▼
Context Builder
 │
 ▼
LLM / Agent
```

---

# 30. 一句话记住整套方案

> **Parser 负责看懂 PDF，Parent 保留完整语义，Child 负责精确检索；先检索并重排 Child，再按问题需要扩展 Sibling/Parent；所有结构、页码、表格和脚注都必须可回溯，Chunk 与 Embedding 从一开始就做好版本化和局部重建能力。**

