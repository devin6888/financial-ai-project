# 文本索引：tsvector 与 pg_trgm

> 阶段 4 配套学习材料。文中所有数字都是在本项目库（PostgreSQL 16.15 + zhparser 2.4 + pg_trgm + pgvector 0.8.7，1516 行 / 654 个有效 Child）上**实测**的，不是查来的经验值。
> 相关代码：`src/main/java/com/finagent/retrieval/PgSparseRetriever.java`、`src/main/resources/db/migration/V5__sparse_index_upgrade.sql`

---

## 0. 一句话定位

`trgm` 和 `tsv` 是**两种文本索引**（倒排表），不是业务数据列。它们都属于 Sparse 检索路径，分别解决两个不同的问题。

RAG 的稀疏（Sparse）路要做两件**不同**的事：

| 要做的事 | 用什么 | 依赖分词器吗 |
|---|---|---|
| 按**词**匹配："哪些 chunk 讨论过净利润" | 全文检索（tsvector + GIN） | **依赖**，词是什么由分词器决定 |
| 按**字面子串**匹配："哪一行原文写着 `每股收益`" | pg_trgm + GIN | **绕过**，完全不看分词器 |

这就是本项目两条子路径的由来：`SparseMode.FTS_OR` 与 `SparseMode.EXACT`。
前者召回高、精度低（会把"净敞口套期收益"当成"净利润"）；后者精度高、召回窄（少一个字就查不到）。两者都不完美，所以最终靠 RRF 融合 + Rerank 兜底。

---

## 1. tsvector：分词之后的"词项 + 位置"

### 1.1 它长什么样

```sql
SELECT content_tsv FROM document_chunk WHERE id = 818;
```

```
'0.5168':88,95 '0.6087':86 '0.7650':85 '067':13 '11.94%':103 '12':30 ...
```

- 冒号前是 **lexeme**（归一化后的词项）
- 冒号后是**位置列表**（这是原文里第几个词）
- 还可以带权重 A/B/C/D（`setweight`），本项目没用

位置列表是 `ts_rank` 能做"两个词挨得近就加分"的基础 —— 这也是它比"布尔匹配"强的地方。

### 1.2 它是怎么产生的

`to_tsvector(config, text)` 背后是两阶段：

| 阶段 | 做什么 | 本项目用的是什么 |
|---|---|---|
| **parser**（解析器） | 把文本切成 token，给每个 token 打词性标签 | zhparser（内核是 SCWS） |
| **dictionary**（字典） | 决定 token 变不变成 lexeme（停用词丢弃、词干化） | `simple`（不词干化，中文也不需要） |

配置名 `chinese_zh` 是 zhparser 提供的**文本搜索配置**，它把"词性 → 字典"的映射写在 `pg_ts_config_map` 里。

### 1.3 实测：zhparser 到底干了什么

```sql
SELECT alias, description, token, dictionaries, lexemes
  FROM ts_debug('chinese_zh', '每股收益同比增长12.5%，营业收入16,067,741,039.51元');
```

```
 alias |    description     | token   | dictionaries | lexemes
-------+--------------------+---------+--------------+----------
 r     | pronoun,代词       | 每股    | {}           |            ← 被丢弃
 n     | noun,名词          | 收益    | {simple}     | {收益}
 j     | abbreviation,简称  | 同比    | {simple}     | {同比}
 v     | verb,动词          | 增长    | {simple}     | {增长}
 e     | exclamation,感叹词 | 12.5%   | {simple}     | {12.5%}
 u     | auxiliary,助词     | ，      | {}           |            ← 被丢弃
 v     | verb,动词          | 营业    | {simple}     | {营业}
 n     | noun,名词          | 收入    | {simple}     | {收入}
 e     | exclamation,感叹词 | 16      | {simple}     | {16}
 u     | auxiliary,助词     | ,       | {}           |            ← 被丢弃
 e     | exclamation,感叹词 | 067     | {simple}     | {067}
 ...
 q     | quantity,量词      | 元      | {}           |            ← 被丢弃
```

**三条结论，全是坑：**

1. **「每股」被标成代词 `r`，字典为空 → 直接丢弃**。于是「每股收益」在索引里只剩「收益」。
   这是中文财务指标最致命的问题：**指标名不是原子词**。查「每股收益」会召回所有含「收益」的 chunk（实测 86 条），其中大部分是"投资收益""其他收益""净敞口套期收益"。

2. **千分位数字被拆碎**：`16,067,741,039.51` 变成 `16` / `067` / `741` / `039.51` 四个 lexeme（逗号是助词被丢）。
   所以 **FTS 搜不到完整金额**，"16067741039.51" 在 tsvector 里根本不存在 —— 这类查询只能靠 ILIKE + trgm 或结构化字段。

3. 数字被标成 `e`（感叹词）纯属 SCWS 词性标注的粗糙；它侥幸活下来，只是因为 `chinese_zh` 把 `e` 也映射到了 `simple` 字典。
   `chinese_zh` 只映射 8 类词性：`a / e / i / j / l / m / n / v`；`r`（代词）、`u`（助词）、`q`（量词）全部丢弃。

> 顺带一个实现细节（V5 迁移踩过的坑）：`pg_ts_config_map.maptokentype` 存的是**词性别名的 ASCII 码**，不是词性编号。
> 例如 `m` 存的是 109，而不是某个枚举值。写 SQL 要这么判断：`WHERE maptokentype = ascii('m')`。

### 1.4 存储形态：两个东西都叫 tsv，别混

| 对象 | 是什么 | 占多大 | 谁维护 |
|---|---|---|---|
| 列 `content_tsv tsvector GENERATED ALWAYS AS (to_tsvector('chinese_zh', content)) STORED` | 分词结果**本身** | 2.1 KB/行 | 写入时自动算 |
| 索引 `idx_chunk_tsv GIN(content_tsv)` | 词项到行的**倒排表** | 3.1 MB / 1516 行 | 自动 |

**为什么用生成列而不是每次查询现算**：`to_tsvector` 是纯 CPU 操作，但如果每次查询都要对 654 个 chunk 重新分词，耗时是几十毫秒级；存成列只在写入时算一次。

### 1.5 查询形态与三个真实的坑

```sql
-- 1) 精确语法，需要自己拼，用户输入必须转义
to_tsquery('chinese_zh', '收益 & 营业')

-- 2) 自动把整句话切成 AND
plainto_tsquery('chinese_zh', '2025年公司面临的主要风险有哪些')

-- 3) 支持引号 / OR / 减号，最安全（推荐给用户输入）
websearch_to_tsquery('chinese_zh', '风险 OR 不确定性 -商誉')
```

实测（本项目，654 个 CHILD）：

| 写法 | 结果 |
|---|---|
| `plainto_tsquery('2025年公司面临的主要风险有哪些')` → 变成 `2025 & 公司 & 面临 & 风险 & 有` | **0 条** |
| 同样的词改成全 OR | **372 / 654** |
| `ILIKE '%每股收益%'` | **6 条** |
| FTS 搜"每股收益"（实际只搜"收益"） | **86 条** |

**坑 1：AND 语义在 RAG 场景基本不可用。** 自然语言长句拆成 AND，等于要求一个 chunk 同时含全部词，几乎必然 0 条。
本项目的处理：**AND 先试，0 条再退 OR**（`SparseRetriever` 的 `ftsWithFallback`）—— 渐进放松，精度优先、召回兜底。

**坑 2：ts_rank 不是 BM25。** 它用词频 + 位置 + 权重，但**没有 idf**（不惩罚"人人都有"的词）、**没有文档长度归一化**（长文档天然吃亏）。
想真 BM25，要么自算（`unnest(tsvector)` 拿 tf + `count(*)` 拿 df + `avg(length)` 拿 avgdl），要么换引擎。本项目把它列为阶段 6 未决问题。

**坑 3：GUC 与索引无关。** 有人以为 `default_text_search_config` 会影响已有索引 —— 不会。索引里存的是**建索引那一刻**的分词结果，改配置必须 `REINDEX` 重建，否则新旧数据用不同的分词规则，查询会漏。

---

## 2. pg_trgm：把字符串切成三字符片段

### 2.1 原理

把字符串切成"连续 3 个字符"，两端补空格凑边界：

```
'revenue'  →  "  r"  " re"  "enu"  "eve"  "nue"  "rev"  "ue "  "ven"    (7 字符 → 8 个)
'每股收益' →  5 个                                                        (4 字符 → 5 个)
```

规律：长度 n → **n+1** 个片段（两端各补一个空格）。
**中文按单字切**，一个汉字就是一个"字符"，所以一个 805 字的 chunk 会产生约 800 个片段，去重后实测 **322 个**。

```sql
SELECT array_length(show_trgm(content), 1) FROM document_chunk WHERE id = 818;
-- 322
```

**这就是 trgm 索引为什么比原文还大的原因**：一行 805 字的文本，要在倒排表里写入 322 条 posting（每条指向"哪些行包含这个片段"）。

### 2.2 为什么中文显示成 `0x9e8d9e`

```sql
SELECT show_trgm('revenue')::text;   -- {"  r"," re","enu","eve","nue","rev","ue ","ven"}
SELECT show_trgm('每股收益')::text;  -- {0x9e8d9e,0xc5ae36,0xe86026,0x32dd5f,0x46b7dd}
```

多字节字符被编码成 3 字节的内部键显示，**不影响使用**，只是别被吓到。

### 2.3 必须指定 opclass（实测报错）

```sql
CREATE TEMP TABLE t(x text);
CREATE INDEX ON t USING gin (x);
```

```
ERROR:  data type text has no default operator class for access method "gin"
HINT:   You must specify an operator class for the index or define a
        default operator class for the data type.
```

**`text` 类型没有默认的 GIN opclass** —— 光写 `USING gin` 根本建不出来。必须是：

```sql
CREATE INDEX idx_chunk_content_trgm ON document_chunk USING gin (content gin_trgm_ops);
```

概念要分清：

| 概念 | 是什么 | 类比 |
|---|---|---|
| **GIN** | 索引**结构**（倒排表：键 → 行号列表） | 一本书的索引页 |
| **opclass**（`gin_trgm_ops`） | 键**怎么生成**（切成三字符片段） | 索引页按"三字一组"编排 |
| **pg_trgm** | 提供该 opclass 的扩展 | 编排规则本身 |

### 2.4 它能加速什么、不能加速什么

| 查询写法 | trgm 能否加速 | 说明 |
|---|---|---|
| `LIKE '%x%'` / `ILIKE '%x%'` | ✅ | 两端通配，btree 完全无用，trgm 是唯一解 |
| `LIKE 'x%'` | ✅ 但 btree 通常更好 | 前缀匹配 btree 更省 |
| `=` / `<` / `>` | ❌ | 用 btree |
| `~ 'regex'` | ✅ 部分 | 正则里能提取字面量时 |
| `similarity(a,b) > 0.5` | ⚠️ 需要 GiST | GIN 对相似度排序不友好 |

### 2.5 similarity 在中文上不可靠（实测）

```sql
SELECT similarity('每股收益','每股收益'),                    -- 1.000
       similarity('每股收益','每股收益增长率'),              -- 0.444
       similarity('每股收益','营业收入'),                    -- 0.000
       similarity('归母净利润','归属于母公司股东的净利润');   -- 0.188
```

**最后一行是关键**：中文的"简称 ↔ 全称"改写，相似度只有 0.188，**低于 pg_trgm 默认阈值 0.3**。
也就是说，指望 `similarity` 自动匹配"归母净利润"和"归属于母公司股东的净利润"会失败。

**结论：中文场景下精确子串（`ILIKE '%...%'`）比模糊相似度靠谱得多**，这也是 EXACT 路径存在的理由 —— 它的价值不是"更聪明"，而是"更笨但不会错"。

### 2.6 索引不是万能的：选择性决定一切

同一个 trgm 索引，两个词的命运完全不同：

| 查询 | 命中行数 | 优化器选择 | 耗时 |
|---|---|---|---|
| `content ILIKE '%每股收益%'` | 6 / 654 | **Bitmap Index Scan on idx_chunk_content_trgm** | **0.41 ms** |
| `content ILIKE '%公司%'` | 317 / 654 | **放弃 trgm**，走 `idx_chunk_active` 拿 654 行再逐行过滤 | **66 ms** |

` %公司% ` 的执行计划片段：

```
->  Bitmap Heap Scan on document_chunk dc  (rows=317)
      Filter: (content ~~* '%公司%')
      Rows Removed by Filter: 337
      ->  Bitmap Index Scan on idx_chunk_active  (rows=655)   ← 不是 trgm
Execution Time: 66 ms
```

**命中一半的行时，倒排表 + 回表的开销已经超过直接全扫**，planner 会主动放弃索引。这不是配置问题，是代价模型的正确判断。

> 实践含义：EXACT 路径只适合"具体到能唯一标识"的词（指标名、科目名、编号），
> 对"公司/报告/年度"这类泛词**是有害的**。QU 只抽指标词，正是为了避免这个。

### 2.7 没有 trgm 会慢多少（实测）

```sql
SET enable_bitmapscan = off; SET enable_indexscan = off;  -- 强制顺序扫
EXPLAIN ANALYZE EXECUTE exactq(NULL, 7, '%每股收益%');
```

```
Seq Scan on document_chunk dc  (actual time=2.672..83.580 rows=6)
  Filter: ((content ~~* '%每股收益%') AND ...)
  Rows Removed by Filter: 1510
  Buffers: shared hit=675
Execution Time: 85.68 ms
```

**0.41 ms（有 trgm） vs 85.68 ms（顺序扫） ≈ 209 倍**。
注意这只在 1516 行的小表上 —— 行数涨 100 倍，顺序扫也跟着涨 100 倍，而倒排表基本不变。

---

## 3. 两者对比（背下来）

| 维度 | tsvector 全文检索 | pg_trgm 三元组 |
|---|---|---|
| 索引结构 | GIN（倒排） | GIN（倒排） |
| 键是什么 | 分词后的**词项** | 三个**字符**组成的片段 |
| 依赖分词器 | ✅ 强依赖 | ❌ 不看分词器 |
| 能否精确匹配指标名 | ❌ 「每股收益」退化成「收益」 | ✅ 字面子串 |
| 能否容忍同义词/改写 | ✅ 词级匹配，勉强 | ❌ 差一个字就查不到 |
| 能否匹配长数字 | ❌ 千分位被拆碎 | ✅ 完整子串 |
| 大小（本项目 1516 行） | 3.1 MB | 7.4 MB |
| 典型耗时 | 1~10 ms | 0.4~66 ms（看选择性） |
| 服务的路径 | `SparseMode.FTS_OR` | `SparseMode.EXACT` |
| 失败模式 | 召回一堆同词根的噪声 | 漏掉所有近似写法 |

**它们不是二选一，而是互补**：FTS 保召回，EXACT 保精度，RRF 融合两路排名，Rerank 再精排。

---

## 4. 本项目的实测账本

### 4.1 索引大小（1516 行 / 654 个有效 Child）

| 索引 | 大小 | 每行 | 1000 万行推算 |
|---|---|---|---|
| `idx_chunk_content_trgm` | 7.4 MB | 5.0 KB | ~49 GB |
| `idx_chunk_embedding`（HNSW） | 5.2 MB | 4.0 KB/向量 | ~40 GB |
| `idx_chunk_tsv` | 3.1 MB | 2.1 KB | ~21 GB |
| 表（堆 + TOAST） | 12.4 MB | 8.4 KB | ~84 GB |

**trgm 是单项最大的索引，比向量索引还大 40%** —— 因为它索引了全部 1516 行（含 104 个 PARENT、758 个 SUPERSEDED 旧版本），而检索只查 CHILD + ACTIVE。

### 4.2 真实使用情况（容器重启后 30 分钟内）

```sql
SELECT indexrelname, idx_scan, idx_tup_read
  FROM pg_stat_user_indexes WHERE relname='document_chunk' ORDER BY idx_scan DESC;
```

| 索引 | idx_scan | idx_tup_read |
|---|---|---|
| `idx_chunk_active` | 117 | 70,099 |
| `idx_chunk_tsv` | 56 | 11,098 |
| `idx_chunk_content_trgm` | 55 | 1,841 |
| `idx_chunk_embedding` | 2 | 120 |

两个文本索引都在被真实使用，不是"建了没人用"。

### 4.3 生产查询的真实计划（EXACT 路径）

```
Limit (actual time=0.282..0.283 rows=6)
  ->  Sort (top-N)
        ->  Nested Loop
              Join Filter: (COALESCE(d.active_chunk_version) = dc.chunk_version)
              ->  Index Scan using document_pkey on document d  (id = 7)
              ->  Bitmap Heap Scan on document_chunk dc  (rows=6)
                    Recheck Cond: (content ~~* '%每股收益%')
                    Rows Removed by Filter: 8
                    ->  Bitmap Index Scan on idx_chunk_content_trgm  (rows=14)
Execution Time: 0.409 ms
```

注意 `Rows Removed by Filter: 8` —— trgm 索引是**全局的**（不带 document_id / chunk_version 条件），
先捞全库 14 条，再靠过滤条件砍到 6 条。这就是"改成部分索引能省 57% 空间和一片回表"的由来。

---

## 5. 运维与调优

### 5.1 GIN 的 fastupdate 与 pending list

GIN 默认 `fastupdate=on`：新写入先攒在 pending list（小链表），查询时要额外扫它，攒到阈值才批量合并进主索引。
大批量入库后如果查询突然变慢，先看这个：

```sql
SELECT pg_size_pretty(pg_relation_size('idx_chunk_tsv')) AS main,
       (SELECT pg_relation_size(relid) FROM pg_stat_user_indexes
         WHERE indexrelname='idx_chunk_tsv') AS x;
VACUUM ANALYZE document_chunk;   -- 合并 pending list + 更新统计
```

### 5.2 大规模导入的正确顺序

1. 导入前 `DROP INDEX`（trgm / tsv / hnsw）
2. 灌数据
3. `CREATE INDEX CONCURRENTLY` 重建
4. `ANALYZE`

理由是逐行维护三套 GIN + 一个 HNSW 图，写入放大约 5~10 倍。

### 5.3 部分索引能砍掉一半

```sql
CREATE INDEX CONCURRENTLY idx_chunk_content_trgm_active
    ON document_chunk USING gin (content gin_trgm_ops)
 WHERE chunk_level = 'CHILD' AND status = 'ACTIVE';
```

代价：所有查询必须带这两个条件（本项目恒定带），否则用不上。

### 5.4 膨胀与 REINDEX

```sql
SELECT pg_size_pretty(pg_relation_size('idx_chunk_content_trgm')),
       pg_size_pretty(pg_relation_size('idx_chunk_tsv'));
-- 对比行数增长，若远超线性增长，说明有死元组未回收
REINDEX INDEX CONCURRENTLY idx_chunk_content_trgm;
```

### 5.5 统计信息决定计划

优化器靠 `pg_statistic` 估"ILIKE 能命中多少行"。**大批量导入后不 ANALYZE，它会一直用旧估计**，
于是可能出现"该走索引却全表扫"或反之。每次批量写入后必须 `ANALYZE document_chunk`。

---

## 6. 八个常见误区

1. **"有 GIN 索引就能加速 ILIKE"** —— 要看 opclass。没有 `gin_trgm_ops`，`text` 连索引都建不了。
2. **"建了索引就会用"** —— planner 按代价选。`%公司%` 命中一半时它会主动放弃 trgm（66 ms）。
3. **"全文检索能精确匹配指标名"** —— 中文分词会切碎：`每股收益` → `收益`，`营业总收入` → `营业` + `总收入`。
4. **"ts_rank 就是 BM25"** —— 没有 idf、没有长度归一化，只是 tf + 位置。
5. **"tsvector 越大越好"** —— 位置信息可以 `strip()` 掉，只留词项，索引能小一半，代价是失去短语/邻近匹配。本项目留了位置（因为要 `ts_rank`）。
6. **"pg_trgm 只能做英文"** —— 中文可以，但按**字符**切，字符越密（中文一行 40 字）片段越多，索引越大。
7. **"ILIKE 大小写无关所以用不了索引"** —— trgm 对 ILIKE 同样有效（`~~*` 操作符有 opclass 支持）。
8. **"索引越多查询越快"** —— 每个索引都是写入放大 + 优化器多一条路要评估。本项目 `document_chunk` 上已经有 9 个索引。

---

## 7. 面试问答

**Q1：为什么要给同一张表建两个文本索引？**
因为它们回答两个不同问题。tsvector 回答"哪些文档**讨论**了净利润"（按词），trgm 回答"哪一行**原文写着** `每股收益` 这四个字"（按字面）。
前者依赖分词器、召回宽；后者绕过分词器、精度高。中文里这两者差别很大（分词会把"每股收益"削成"收益"）。

**Q2：GIN 是什么？为什么文本索引用它而不是 btree？**
GIN = 广义倒排索引：键 → 行号列表。文本的"键"是词项或 n-gram，一篇文档对应几百个键，
一个 btree 条目对应一个完整的值，根本表达不了"一个值包含很多键"。btree 每行一个条目，GIN 每行多个条目。

**Q3：GIN 和 GiST 有什么区别？**
GIN 查询快、更新慢、体积大；GiST 相反，支持 KNN 排序（所以 `similarity` 排序用 GiST）。
检索系统一般用 GIN，如果写入极频繁才考虑 GiST。

**Q4：为什么 `plainto_tsquery` 在 RAG 里几乎不可用？**
它把整句变成 AND。自然语言长句拆成 10 个词做 AND，要求一个 chunk 同时含这 10 个词，实测 0/654。
所以要么 OR，要么"AND 先试、0 条退 OR"。

**Q5：中文全文检索最难的地方是什么？**
不是性能，是**分词边界与业务语义不一致**。财务场景里"每股收益""归母净利润"是原子概念，
但分词器按通用语料切，会把它们拆成"每股 + 收益""归母 + 净 + 利润"，甚至把"每股"当代词丢掉。
解决方向：自定义词典（本项目镜像不支持 scws 扩展词典）、领域词表 + 精确子串、或换引擎。

**Q6：为什么要 ILIKE，不用 similarity？**
实测中文简称/全称的相似度只有 0.188，低于默认阈值 0.3。similarity 对"改写"友好，对"中文缩写"并不友好。
ILIKE 是"笨但不会错"的选择：命中就一定是字面包含。

**Q7：trgm 索引为什么这么大？**
因为片段数 ≈ 字符数。一个 805 字的 chunk → 322 个去重片段 → 322 条 posting。
1516 行 → 7.4 MB。相比之下 tsv 只有 3.1 MB，因为词的个数远少于字的个数。

**Q8：什么时候该换掉 tsvector 上 BM25？**
当评测显示"长文档系统性吃亏""高频词淹没有效词"时。BM25 需要 idf 和长度归一化，tsvector 都没有。
自算 BM25 约 150 行 Java（`unnest(tsvector)` 拿 tf、`count(*)` 拿 df、`avg(length)` 拿 avgdl），
或换 ParadeDB / Elasticsearch。

**Q9：`content_tsv` 为什么是 GENERATED STORED，而 `embedding` 不是？**
`to_tsvector` 是纯本地函数，PostgreSQL 自己能算，所以能自动生成；
`embedding` 要调用外部 embedding 服务，数据库不可能去发 HTTP 请求，只能由应用层写入 ——
所以两张表的数据"新鲜度"责任不同，这也是"embedding 不会自动更新"这条结论的根源。

**Q10：索引为什么没被用上？排查顺序？**
① `EXPLAIN (ANALYZE, BUFFERS)` 看有没有 Index/Bitmap 节点；
② 看 `Rows Removed by Filter`，若远大于返回行数说明过滤没下推；
③ `ANALYZE` 更新统计；
④ 选择性是否太差（命中 30% 以上，planner 会放弃索引）；
⑤ 表达式/类型是否匹配（`text` vs `varchar`、`lower()` 包了列会让普通索引用不上、但 trgm 仍可用）；
⑥ 索引是否 INVALID（`pg_index.indisvalid`）。

**Q11：`LIKE '%x%'` 为什么用不了 btree？**
btree 的键是**完整值**按字典序排列，只能判断前缀范围。左端通配意味着"x 可能在任意位置"，
无法定位到连续区间。trigram 把"包含 x"转成"包含 x 的所有 3 字片段"，变成可定位的倒排查询。

**Q12：如果数据涨到 1000 万，这两套索引怎么办？**
① 先改部分索引（只索引 CHILD + ACTIVE，本项目能省约 57%）；
② trgm 若只服务"指标词精确匹配"，可退化成"指标词 → chunk_id"的小映射表，比全量 trigram 小两个数量级；
③ 按 `document_id` / 年份分区，让过滤变物理裁剪；
④ 保留 tsv，但注意 GIN pending list 与 VACUUM 节奏。

---

## 8. 延伸：什么时候该换方案

| 症状 | 可能的方向 |
|---|---|
| 长文档系统性排后 | BM25（自算 / ParadeDB pg_search / ES） |
| 中文分词切碎指标名 | 自定义词典、领域词表、pg_bigm、或精确子串 |
| 索引占磁盘过多 | 部分索引、`strip()` 去掉位置、分区 |
| 写入太慢 | 导入期 DROP INDEX + 重建、调 `gin_pending_list_limit` |
| 需要"语义 + 字面"一起排 | 就是本项目现在的方案：双路召回 + RRF + Rerank |

---

## 附：本文所有实测命令

```sql
-- 分词到底做了什么
SELECT alias, description, token, dictionaries, lexemes
  FROM ts_debug('chinese_zh', '每股收益同比增长12.5%，营业收入16,067,741,039.51元');

-- 一个 chunk 有多少个 trigram
SELECT array_length(show_trgm(content), 1) FROM document_chunk WHERE id = 818;

-- 两个索引的大小与使用次数
SELECT indexrelname, pg_size_pretty(pg_relation_size(indexrelid)) AS size, idx_scan, idx_tup_read
  FROM pg_stat_user_indexes WHERE relname = 'document_chunk' ORDER BY pg_relation_size(indexrelid) DESC;

-- EXACT 路径的真实计划（把 enable_* 关掉可以对比"没有索引"的世界）
PREPARE exactq(int, bigint, text) AS
SELECT dc.id FROM document_chunk dc JOIN document d ON d.id = dc.document_id
 WHERE dc.chunk_level='CHILD' AND dc.status='ACTIVE'
   AND dc.chunk_version = coalesce($1, d.active_chunk_version)
   AND dc.document_id = $2 AND (dc.content ILIKE $3)
 ORDER BY (CASE WHEN dc.content ILIKE $3 THEN 1 ELSE 0 END) DESC, dc.id ASC LIMIT 30;

EXPLAIN (ANALYZE, BUFFERS) EXECUTE exactq(NULL, 7, '%每股收益%');
SET enable_bitmapscan = off; SET enable_indexscan = off;
EXPLAIN (ANALYZE, BUFFERS) EXECUTE exactq(NULL, 7, '%每股收益%');
```
