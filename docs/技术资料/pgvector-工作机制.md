# pgvector 工作机制

> 本篇讲**原理与实测数据**，用于理解与面试。项目里的具体用法见 [关键契约.md](<关键契约.md>) 与 `DenseRetriever`。
>
> 所有数字均来自本项目实测（PostgreSQL 16.15 + pgvector 0.8.7，1516 行 chunk，1024 维）。

---

## 1. pgvector 是什么

**它是 PostgreSQL 的一个扩展，不是独立数据库。**

```sql
select extname, extversion from pg_extension;
--  vector | 0.8.7
```

装完之后：

- 多了一个**数据类型** `vector(n)`
- 多了三个**距离操作符** `<->` / `<=>` / `<#>`
- 多了两种**索引类型** `ivfflat` / `hnsw`

**没有第二个服务、第二个连接、第二次网络往返。** 向量和业务字段在**同一行、同一事务、同一张表**里。

```sql
-- 同一条 SQL 里既读文本又算向量距离
select id, content, embedding <=> embedding as 自距离, vector_dims(embedding)
  from document_chunk where id = 805;
```

### 与独立向量库的取舍

| | pgvector | 独立向量库（Milvus / Qdrant / ES） |
|---|---|---|
| 部署 | 无，装扩展 | 额外一个服务 + 一份运维 |
| 元数据与向量同源 | 同一行 | 两套存储，要同步 |
| 事务一致性 | 原生 | 最终一致 |
| 与业务表 JOIN | 直接 JOIN | 只能拿 ID 回来再查 |
| 带过滤的 ANN 性能 | **弱**（见 §6） | 有专门的 pre-filter |
| 超大规模 | 不如专用引擎 | 更强 |

**本项目选它的决定性理由**：阶段 3 的「重索引原子切换」要求
「写新版本 chunk（含向量）→ 校验 → 切指针 → 旧版退役」在**一个事务里**完成。
如果向量在外部库里，这个事务就不成立了。

---

## 2. 存储：就是一个定长 float32 数组

实测一行：

| 列 | 类型 | 字节数 |
|---|---|---|
| `content` | `text` | **115** |
| `embedding` | `vector(1024)` | **4100** |

- 1024 × 4 字节 = 4096，加 4 字节 varlena 头 = **4100**
- **没有压缩、没有量化、没有乘积量化（PQ）**

**向量比正文大 35 倍** —— 这是向量库成本结构的根源。
Milvus / Qdrant 会做 PQ/SQ 量化来换空间，pgvector 不做（省 CPU，吃磁盘）。

索引比数据还大：

```
主表 document_chunk        4.0 MB
idx_chunk_embedding (HNSW) 5.2 MB   <- 比数据还大
idx_chunk_content_trgm     7.4 MB
idx_chunk_tsv (GIN)        3.1 MB
```

---

## 3. 三个距离操作符

| 操作符 | 含义 | 索引 opclass |
|---|---|---|
| `<->` | L2 欧氏距离 | `vector_l2_ops` |
| `<=>` | **余弦距离** = 1 − 余弦相似度 | `vector_cosine_ops` |
| `<#>` | **负**内积（取负是为了统一「越小越近」） | `vector_ip_ops` |

实测同一行和它自己比：

```sql
select embedding <-> embedding,   -- 0.0000   L2
       embedding <=> embedding,   -- 0.0000  余弦
       embedding <#> embedding;   -- -1.0000  负内积
```

### 一个重要推论：`<#> = -1` 暴露了向量是归一化的

自内积 = 1 意味着模长 = 1。实测 `vector_norm(embedding) = 0.99999993`。

**向量已归一化时，L2 / 余弦 / 内积三种度量的排序完全等价**，可以随便选。
若向量未归一化，三者结果不同 —— 这是面试常问的陷阱。

---

## 4. 索引：HNSW 是「多层跳表式图」

### 为什么需要 ANN

精确 kNN 要对每一行算距离：`n × d` 次浮点运算。
1516 行 × 1024 维 ≈ **155 万次**。数据量一大就是灾难。

**ANN（Approximate Nearest Neighbor）** 用近似换速度，代价是**召回率不是 100%**。

### 两种索引

| | IVFFlat | HNSW |
|---|---|---|
| 原理 | k-means 聚成 `lists` 个簇，查询只搜最近的 `probes` 个簇 | 多层邻近图，从稀疏顶层逐层下探 |
| 建索引 | 快 | 慢 |
| 召回 | 较低 | **高** |
| 内存 | 小 | 大 |
| 建索引前要有数据 | **必须**（要聚类） | 可以边写边建 |

本项目用 **HNSW**：`USING hnsw (embedding vector_cosine_ops)`。

### HNSW 的三个关键参数

| 参数 | 作用 | 默认 |
|---|---|---|
| `m` | 每层每个节点最多连多少条边 | 16 |
| `ef_construction` | **建索引时**的候选队列宽度（越大越准也越慢） | 64 |
| `ef_search` | **查询时**的候选队列宽度（运行时才起作用） | 40 |

**`ef_search` 是唯一能在运行时调的**：调大 → 召回更高、更慢。

### 硬限制

`vector` 类型本身支持到 **16000 维**，但 **HNSW / IVFFlat 索引上限是 2000 维**。
本项目锁 **1024 维**：既满足百炼 `text-embedding-v4` 的输出，又在索引能力内。

---

## 5. 查询是怎么执行的

### 触发 HNSW 的写法

```sql
ORDER BY embedding <=> '[...]'::vector LIMIT k
```

**两个条件缺一不可**：

1. `ORDER BY 向量列 <距离操作符> 常量`
2. **必须有 `LIMIT`** —— HNSW 是增量找最近邻的算法，有 `LIMIT` 才能提前停止；
   没有 `LIMIT` 就得走完整个图，还不如全表扫

### 我们的实际 SQL

```sql
SELECT dc.id, dc.content, dc.section_path, dc.page_start, ...
  FROM document_chunk dc
  JOIN document d ON d.id = dc.document_id
 WHERE dc.chunk_level = 'CHILD'
   AND dc.status    = 'ACTIVE'
   AND dc.embedding IS NOT NULL
   AND dc.chunk_version = coalesce(?, d.active_chunk_version)
 ORDER BY dc.embedding <=> ?::vector
 LIMIT 30;
```

**就是普通 SQL**，没有专用 API。整条链路只有一步在 SQL 之外：

```
(1) Java 调百炼 API  ->  float[1024]        <- 唯一不在 SQL 里的一步
(2) 格式化成 '[0.1,0.2,...]' 字符串
(3) 塞进 ?::vector 占位符
(4) PG 执行：过滤 + 算距离 + 排序 + LIMIT
```

**pgvector 没有二进制协议绑定**，传的是**文本字面量**。
格式一旦两处不一致，会出现「插入成功但查询报错」这种极难排查的问题 ——
所以项目里只保留一个 `toVectorLiteral` 实现。

---

### 为什么向量放在 ORDER BY，而不是 WHERE

这是向量检索最容易搞错的一点：`<=>` 是**数值函数**，不是**布尔谓词**。

它答的不是「这一行匹配吗」，而是「离得有多远」。实测对每一行都返回一个数：

```
 id  | 余弦距离
-----+----------
 796 |   0.1961
 798 |   0.1445
 800 |   0.0795
 801 |   0.0867
```

所以它天然属于 `ORDER BY`（排序依据），不属于 `WHERE`（筛选条件）。

#### 硬写成 WHERE 会怎样

```sql
WHERE embedding <=> ?::vector < 0.30
```

能跑，但**结果数完全失控**（实测同一批 654 个 CHILD）：

| 阈值 | 命中条数 |
|---|---|
| < 0.20 | **17** |
| < 0.30 | **294** |
| < 0.40 | **551** |
| < 0.50 | **646** |
| < 0.60 | **654**（全中） |

**阈值是不可知的**：定低了漏召回，定高了等于没过滤。
0.6 会全中，是因为同一份年报里任意两段的余弦距离本来就只有 0.1~0.5 ——
它们共享领域词汇与句式。换一份文档、换一个模型，阈值全得重调。

而 **Top-K 是良定义的问题**：「最近的 30 个是谁」，答案永远是 30 条，
与数据分布无关。下游（RRF 要 30 条、Rerank 要 50 条）需要的正是**固定数量**。

#### 还有技术原因：只有 ORDER BY + LIMIT 能用 HNSW

| 写法 | 能否用 ANN 索引 |
|---|---|
| `ORDER BY dist LIMIT k` | **能** —— HNSW 就是设计来回答「最近 k 个」的 |
| `WHERE dist < X`（不限数量） | **不能** —— 只能全表扫，HNSW 不会「计数」 |

HNSW 的算法本质是「增量地找越来越远的邻居，凑够 k 个就停」，它**没有计数能力**。

#### 两者可以同时用

```sql
WHERE dc.chunk_level = 'CHILD'          -- 业务过滤（布尔，真正的 WHERE）
  AND dc.status      = 'ACTIVE'
  AND dc.chunk_version = ...
  AND dc.embedding IS NOT NULL
ORDER BY dc.embedding <=> ?::vector      -- 相似度排序（距离，ORDER BY）
LIMIT 30
```

| 位置 | 干什么 | 操作符类型 |
|---|---|---|
| `WHERE` | 缩小候选集（哪些 chunk 有资格） | **布尔** |
| `ORDER BY` | 在候选集里按相关度排序 | **数值** |
| `LIMIT` | 取前 K 个 | — |

#### 心智模型

| | 传统 SQL | 向量检索 |
|---|---|---|
| 问题 | 「哪些行**满足条件**」 | 「哪些行**离得最近**」 |
| 操作符 | `=` `>` `LIKE` → **布尔** | `<=>` → **数值（距离）** |
| 位置 | `WHERE` | **`ORDER BY`** |
| 结果 | **全部**匹配行 | **Top-K** |
| 索引 | B-tree / GIN 加速**过滤** | HNSW 加速**最近邻** |

一句话：传统 SQL 是「**筛选**」，向量检索是「**排序**」。

这也解释了为什么向量检索的接口通常叫 `search(query, topK)` 而不是
`filter(query, condition)` —— **`topK` 是必填参数，因为「最近邻」本身不构成有限集合。**

#### 那「有没有相关内容」怎么判断

不能用固定阈值（0.6 就全中了）。可行的方向：

- **相对判据**：top1 与 top30 的距离差距
- **Rerank 分数**：精排后的绝对分低于阈值则判为「无相关内容」

（本项目把这条留给阶段 6 的评测，见 ST4 的未决问题。）

---

## 6. 最关键的坑：带 WHERE 时，HNSW 该不该用

### 实测执行计划

```
Limit
  ->  Sort  (Sort Key: (dc.embedding <=> $0), top-N heapsort)
        ->  Nested Loop
              ->  Index Scan using document_pkey on document d   (d.id = 7)
              ->  Bitmap Heap Scan on document_chunk dc          (654 rows)
                    ->  Bitmap Index Scan on idx_chunk_active
```

**优化器选的是「先用 `idx_chunk_active` 过滤出 654 行，再精确排序」—— HNSW 索引完全没用上。**
（2026-10-20 重测；早期记录里这一步是 Seq Scan + 丢弃 862 行，后来加了 `idx_chunk_active` 就变成 Bitmap 了。）

### 为什么

HNSW 是**图遍历**：从入口点出发，走向「更近的邻居」。
而 `WHERE chunk_level='CHILD'` 是**行过滤**，无法推进图遍历里去。

pgvector 只有三种应对：

| 策略 | 行为 | 问题 |
|---|---|---|
| **后过滤** | 先取 k 个最近邻，再过滤 | 可能返回**不足 k 条** |
| **前过滤** | 先过滤再排序（= 我们现在的计划） | **退化成全表扫** |
| **迭代扫描** | pgvector 0.8+ 的 `hnsw.iterative_scan`（取满 k 条才停） | 本镜像**有这个 GUC**（默认 `off`），但开了也打不过「过滤 + 精确排序」，见下文实测 |

### 先说测量口径（这里踩过坑）

早期记录里有一组「精确 9.8 ms vs HNSW 31.4 ms」，据此写下「HNSW 更慢、交叉点约 1 万行」。2026-10-20 重测后**这两条都不成立**：

- 那 31.4 ms 是「索引刚建完、页面还在磁盘上」的**冷缓存**数字；同一份数据热缓存下 HNSW 只要 0.45 ms。
- 「约 1 万行」是按 6.5 μs/行 拍的估算；**实测交叉点在 1,200~1,500 行**。

**教训：比「谁更快」之前先固定三件事** —— 缓存状态（热/冷）、是否含 planning 时间、候选集是过滤前还是过滤后的规模。

### 缓存只影响「多快」，不影响「选哪个计划」（重启容器实测）

重启 PostgreSQL 后立刻跑生产 SQL，再紧接着跑第二遍 —— **执行计划和成本估算一模一样**：

| | 冷（刚重启） | 热（紧接着） |
|---|---|---|
| 计划 | `Limit → Sort(top-N) → Nested Loop → Bitmap Heap Scan` | 完全相同 |
| cost 估算 | `503.96..504.01` | `503.96..504.01` |
| Execution Time | **14.5 ms** | **3.4 ms** |
| Buffers | `read=606, hit=1724` | `hit=2330, read=0` |

优化器**看不到** shared_buffers 里有什么，它只按「行数 × 页数 × random_page_cost」估成本。
所以：**「走不走 HNSW」是成本模型决定的，与缓存无关；缓存只决定同一个计划跑多快。**

这也说明前面那句「冷缓存下 HNSW 31.4 ms」是在解释**为什么当时测出 HNSW 更慢**，
而不是在解释**为什么优化器没选 HNSW** —— 这两件事必须分开说。

### 规模扫描：无过滤的纯 kNN（合成数据，缓存热，7 次取最快）

| 行数 | 精确 Seq Scan + Sort | HNSW（优化器自己选） | 倍数 |
|---|---|---|---|
| 654 | 2.7 ms | 不选 | 精确更快 |
| 800 | 3.4 ms | 不选 | 精确更快 |
| 1,200 | 4.8 ms | 不选 | 精确更快 |
| 1,500 | — | **0.62 ms** | **HNSW 开始接管** |
| 3,270 | 16.4 ms | 1.27 ms | 13× |
| 6,540 | 24.9 ms | 0.43 ms | 58× |
| 13,080 | 85.4 ms | 0.36 ms | 237× |
| 26,160 | 102.7 ms | 0.39 ms | 263× |
| 41,856 | **276.3 ms** | **0.66 ms** | 419× |

精确扫是 O(n)，HNSW 约 O(log n) —— 所以交叉点之后差距不是「快一点」，而是按行数线性放大。

### 规模扫描：带过滤（按 document_id 过滤，命中 1/64 ≈ 654 行）

| 行数 | 过滤 + 精确排序（优化器选的 bitmap + top-N） | 强制 HNSW + iterative_scan |
|---|---|---|
| 654 | 2.2 ms | 43 ms（首次、冷页面） |
| 3,270 | 3.8 ms | 37.8 ms |
| 13,080 | 3.7 ms | 31.3 ms |
| 41,856 | 5.6 ms | 31.8 ms（部分索引）/ 55.7 ms（全局索引） |

**带过滤时即使开了 `hnsw.iterative_scan`，HNSW 仍要多花 5~10 倍时间**：过滤后只剩 654 行，bitmap + top-N 只要几毫秒；而图遍历每一次探针都是一次随机 I/O，还要反复重扫才凑满 30 条。

### 顺带发现的真坑：不开 iterative_scan，HNSW 会「少给」

4 万行、过滤到 654 行时，强制走 HNSW 且 `hnsw.iterative_scan=off`，**只返回 7 条**就停了 —— 这是「后过滤」的经典失败：先取 30 个近邻，再把不满足条件的丢掉，剩多少算多少。开了 `relaxed_order` 才凑满 30 条（`Rows Removed by Filter: 2289`）。

**只要让 HNSW 直接面对过滤条件，就必须同时校验返回条数**，否则召回会静默变少 —— 这种 bug 不报错，只让答案变差。

### 为什么本项目「不选 HNSW」是对的

生产 SQL 一定带 `document_id` + `chunk_version` + `chunk_level` + `status` 四个条件，把候选压到**一份报告的 654 个 Child**：

- 候选集大小与**总语料规模无关**，只与单份报告的大小有关；
- 再加 10 份报告，这个数仍然是 654，dense 耗时几乎不变；
- 真正会变的是「跨文档语义召回」（不加 document 过滤），那时 HNSW 才是必需品。

### 附带的好处：精确扫的召回率是 100%

| | 精确扫 | HNSW |
|---|---|---|
| 召回 | **100%**（真最近邻） | 约 95~99%（`ef_search=40`） |
| 可复现性 | 完全确定 | 依赖参数 |

**这正是「向量与元数据同表」的两面性**：
好处是能在同一条 SQL 里过滤；代价是过滤器与 ANN 索引不好配合 ——
但在当前数据量下，这个"代价"反而变成了优势（精确 + 更快）。

### 什么时候需要回来处理

| 触发条件 | 处理 |
|---|---|
| 跨文档语义召回（不加 document_id 过滤） | 直接用 HNSW；行数 > 1,500 时它快一个数量级以上 |
| 跨文档但仍有 company/year 过滤 | 先数候选行数：< 1,500 用精确；> 5,000 再考虑 HNSW + iterative_scan + 部分索引 |
| 单份报告涨到 5,000+ Child | 同上，且必须先把 `chunk_version` 内联成常量（去掉 JOIN） |

**为什么要去 JOIN**：现在 `chunk_version = coalesce(?, d.active_chunk_version)` 依赖与 `document` 的连接，过滤条件要等 join 完成才能确定；先查出 `active_chunk_version` 当参数传进来，条件就全变成局部常量，部分索引 + 迭代扫描才有可能生效。

### 规模推演：1000 万 chunk 时会发生什么

假设语料涨到 1000 万 chunk（约 1.5 万份年报），查询仍然带 `document_id`：

| 规模 | 表行数 | 过滤后候选 | 计划 | 耗时 |
|---|---|---|---|---|
| 现在 | 1,516 | 654 | Bitmap + Sort | 3.4 ms（热）/ 14.5 ms（冷） |
| 合成实测 | 41,856 | 654 | Bitmap + Sort | 5.6 ms |
| 推演 | 10,000,000 | 654 | Bitmap + Sort（不变） | 30~60 ms（冷页） |

表大了 239 倍，候选行数一点没变 —— 成本模型算的是**要碰多少页**，而这只由候选集决定。
存储账（按实测每行 8.4 KB 堆 + 4 KB HNSW + 4.9 KB trgm + 2.1 KB tsv）：数据 ~84 GB、HNSW ~40 GB、trgm + tsv ~70 GB，总计约 195 GB。

**HNSW 救不了高选择性的过滤**：1000 万里过滤到 654 行 = 1/15,000，后过滤想凑满 30 条得扫约 45 万个索引项。
这时正确做法不是调大 `ef_search`，而是**按过滤列分区**（pgvector 官方建议）：每个公司/年份一个分区、各自建 HNSW，
查询先做分区裁剪，再在分区内用 HNSW 或精确扫 —— 过滤变成物理裁剪，索引才吃得下。

### 本项目为什么先不分区（2026-10-21 跨文档实测）

把语料从 1 份报告扩到 2 份（1,284 条 Child ACTIVE）后实测：

| 场景 | 候选条数 | 计划 | 耗时 |
|---|---|---|---|
| 单文档问答（带 `document_id`） | 630~654 | Bitmap + Sort | 3~4 ms |
| 跨文档检索（不带过滤） | 1,284 | Seq Scan + Sort(top-N) | 16.6 ms |

两条都远没到「候选集大」的程度，分区的收益在这里等于零。

更重要的是：跨文档暴露出来的问题**不是慢，是混**。无 QU 时 14/14 个问题的 top-8 都同时含两份报告，
平均 24% 的条目来自错的那一份（最坏 4/8）—— 这会让生成阶段把 2024 与 2025 的数字混进同一段上下文。
这个用**过滤**解决（QU 抽年份 → `d.doc_year = ?`），分区解决不了。

详见 `docs/跨文档评测报告.md`。触发分区仍是三条：跨文档候选 > 50 万 / 单表 > 500 万行 / 需要冷热分离。

### 附：为什么新开会话 `SHOW hnsw.ef_search` 会报错

GUC 在 `_PG_init` 里注册，而 `_PG_init` 只在该后端**第一次用到 vector 库**时执行。新开 psql 直接 `SHOW hnsw.ef_search;` → `unrecognized configuration parameter`；先跑一句 `SELECT '[1,2,3]'::vector;` 再 SHOW 就有值了。

**之前把「有 GUC」误判成「没有 GUC」，就是栽在这一步。** 本镜像（0.8.7）实测：`hnsw.ef_search=40`、`hnsw.iterative_scan=off`、`hnsw.max_scan_tuples=20000`、`hnsw.scan_mem_multiplier=1`、`ivfflat.probes=1`。


**一句话总结**：现在精确扫赢，是因为候选集只有 654 行；
一旦候选集涨到几千行就该让 HNSW 上场 —— 而它能不能上场，取决于过滤条件是不是局部常量
（依赖 JOIN 的条件会让 HNSW 彻底不可用）。

---

## 7. 本项目配置速查

| 项 | 值 |
|---|---|
| 扩展版本 | pgvector 0.8.7 |
| 维度 | 1024（百炼 text-embedding-v4 输出） |
| 列类型 | `vector(1024)` |
| 索引 | `USING hnsw (embedding vector_cosine_ops)` |
| 距离 | 余弦（`<=>`） |
| 向量是否归一化 | 是（模长 0.99999993） |
| 查询配额 | Dense 30 -> RRF 融合 50 -> Rerank 8（§9.13） |
| 向量存储成本 | 每行 4100 字节 |
| 实例内总行数 | 1516（v1 SUPERSEDED + v2 ACTIVE） |

---

## 8. 面试要点（自测清单）

1. **pgvector 是数据库还是扩展？** —— 扩展。向量与业务数据同表同事务，这是选它的核心理由。
2. **为什么锁 1024 维？** —— 模型输出 1024；HNSW 索引上限 2000 维；两者交集。
3. **余弦和 L2 怎么选？** —— 向量已归一化时三者排序等价；选余弦是因为语义直观且是文本嵌入的默认。
4. **HNSW 为什么快？** —— 多层图导航，约 O(log n)；代价是召回率非 100%，靠 `ef_search` 调。
   **数据量小时它可能更慢** —— 图遍历是随机 I/O，精确扫是顺序扫描。但「小」的界线是**实测**出来的：
   本项目交叉点在 **1,200~1,500 行**；早期「31.4 vs 9.8 ms」是冷缓存数字，热缓存下 HNSW 反而只要 0.45 ms。
   **说「谁更快」之前必须交代缓存状态与候选集规模。**
5. **`ORDER BY ... LIMIT` 为什么必须有 LIMIT？** —— HNSW 是增量算法，没 LIMIT 就走完整个图。
6. **带 WHERE 时会发生什么？** —— 优化器会先按元数据过滤（Bitmap Index Scan）再精确排序，**不选 HNSW**；
   因为过滤后只剩 654 行，精确排序几毫秒就完了。实测 4 万行表、过滤到 654 行时：bitmap+排序 5.6 ms，
   强制 HNSW + `iterative_scan` 要 31.8 ms。**不是「用不上」，而是「不该用」。**
7. **向量占多少空间？** —— 1024 × 4 + 4 = 4100 字节/行，比正文大 35 倍。
8. **向量查询要写什么特殊 API 吗？** —— 不用，就是普通 SQL，把向量当字符串字面量传进去。
9. **向量是自动更新的吗？** —— **不是**。`content_tsv` 是生成列会自动算，`embedding` 是普通列，
   而且数据库没法调用外部模型服务。见 §9。
10. **为什么不用专门的向量库？** —— 原子切换需要事务；见 §1 的取舍表。
11. **向量和文本混在一张表有什么代价？** —— 过滤器与 ANN 索引冲突，见 §6。
12. **向量为什么放在 ORDER BY 而不是 WHERE？** —— `<=>` 返回的是**数值**不是布尔；
    阈值不可知（实测 0.2→17 条、0.6→654 条全中）；只有 top-k 能被 HNSW 加速。见 §5。
13. **想判断「有没有相关内容」怎么办？** —— 不能用固定阈值，要用相对判据（top1 与 top30 的距离差）
    或 Rerank 的绝对分。

---

## 9. 附录：`embedding` 不会自动更新（实测）

```sql
BEGIN;
select left(embedding::text,34), left(embedding_text_hash,12), md5(content_tsv::text)
  from document_chunk where id = 805;

update document_chunk set title = '释义（已修改）' where id = 805;

select left(embedding::text,34), left(embedding_text_hash,12), md5(content_tsv::text)
  from document_chunk where id = 805;
ROLLBACK;
```

结果：

| 字段 | 改 title 后 |
|---|---|
| `content_tsv` 指纹 | **变了**（生成列，自动重算） |
| `embedding` | **没变** |
| `embedding_text_hash` | **没变** |

**为什么不可能自动更新**：

- 生成列只能用 **SQL 表达式**；而向量来自**外部 HTTP API**
- 数据库里没有 `embed()` 函数；就算写 PL/Python 触发器，也是把网络调用塞进事务，
  会被外部服务卡住（正是评审 C2 批评过的问题）

**本项目的应对**：chunk 写入后**不可变**，任何内容变化走 RECHUNK -> 新 `chunk_version` -> 全新行 + 全新向量。