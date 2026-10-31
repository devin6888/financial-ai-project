# 连续对话 Memory 设计学习文档

> 适用场景：Spring Boot / Spring AI / LangGraph4j / 金融 Agent / 企业级连续对话系统

---

## 1. 为什么连续对话需要 Memory

大模型 API 本身通常是无状态的。一次请求结束后，下一次请求并不会天然“记住”上一轮内容。

例如：

```text
User：帮我分析宁德时代
Assistant：……

User：它未来三年的主要风险是什么？
```

第二次请求中，如果系统没有把前文重新提供给模型，模型并不知道“它”指的是宁德时代。

因此，所谓连续对话，本质上是：

```text
应用层保存上下文
    ↓
根据当前请求选择需要的历史信息
    ↓
重新组装 Prompt
    ↓
发送给 LLM
```

Memory 的核心目标不是“把所有历史都发给模型”，而是：

> 在有限的 Context Window 中，只提供当前任务真正需要的历史信息。

---

# 2. 五层 Memory 模型

一个相对成熟的连续对话系统，可以把 Memory 分成 5 层：

| 层级 | 作用 | 典型存储 | 生命周期 |
|---|---|---|---|
| Recent Messages | 最近几轮原始对话 | Redis + PostgreSQL | 短期 |
| Conversation Summary | 当前会话历史压缩摘要 | PostgreSQL / Redis | 当前 Conversation |
| Agent State | Agent 当前任务执行状态 | Checkpoint Store / PostgreSQL / Redis | 当前 Thread / Task |
| Long-term Memory | 用户长期偏好、事实、决策 | PostgreSQL + pgvector | 长期 |
| Raw Chat History | 完整原始聊天记录 | PostgreSQL / 对象存储 | 长期 / 合规策略 |

可以用一句话记住：

```text
Recent Messages
= 刚刚聊了什么

Conversation Summary
= 这次聊天总体聊了什么

Agent State
= Agent 现在做到哪一步

Long-term Memory
= 过去有哪些值得长期记住的信息

Raw Chat History
= 当时原原本本说了什么
```

---

# 3. Recent Messages

## 3.1 作用

Recent Messages 用于解决最近几轮上下文理解问题。

例如：

```text
User：帮我分析宁德时代
Assistant：……
User：重点看看海外业务
Assistant：……
User：那欧洲市场呢？
```

模型需要看到前几轮，才能知道“那欧洲市场呢”仍然是在讨论宁德时代。

## 3.2 存储位置

推荐：

```text
Redis：保存最近消息，快速读取
PostgreSQL：保存完整原始消息
```

Redis 示例：

```text
conversation:10001:recent
```

数据库表：

```sql
chat_message
--------------------
id
conversation_id
user_id
role
content
token_count
created_at
```

## 3.3 什么时候写

每次用户和 Assistant 产生消息后立即写入。

```text
User Message
    ↓
保存
    ↓
LLM
    ↓
Assistant Response
    ↓
保存
```

## 3.4 什么时候读

每次调用 LLM 前读取。

```text
System Prompt
+
Recent Messages
+
Current User Message
```

## 3.5 保存多少

不建议只按“最近 20 条”这种固定条数控制。

更合理的是按照 Token Budget：

```text
Recent Message Budget = 8k tokens
```

从最新消息往前装，达到预算后停止。

---

# 4. Conversation Summary

## 4.1 作用

Conversation 越聊越长，不可能把全部消息永远发给 LLM。

例如：

```text
M1 ~ M150
    ↓
Conversation Summary

M151 ~ M180
    ↓
保留原始 Recent Messages
```

最终 Context：

```text
Conversation Summary
+
Recent Messages
+
Current Message
```

## 4.2 存储位置

推荐 PostgreSQL 为持久化来源，Redis 可做缓存。

```sql
conversation_summary
-------------------------
conversation_id
summary
covered_until_message_id
version
updated_at
```

其中：

```text
covered_until_message_id
```

非常重要，它表示 Summary 已经覆盖到哪一条消息。

例如：

```text
summary 已覆盖 message_id <= 150
```

下一次只需要总结：

```sql
SELECT *
FROM chat_message
WHERE conversation_id = ?
  AND id > 150
ORDER BY id;
```

## 4.3 什么时候触发

不建议每轮都更新 Summary。

常见触发条件：

```text
Recent Messages 超过 N 轮

或

Context Token 超过阈值

或

距离上次 Summary 又增加 N 条消息
```

例如：

```text
最近消息 > 20轮
或者
Token > 10k
```

## 4.4 如何生成

采用滚动摘要：

```text
Old Summary
+
New Messages
    ↓
LLM Summarizer
    ↓
New Summary
```

Prompt 示例：

```text
你负责维护当前会话摘要。

已有摘要：
{oldSummary}

新增对话：
{newMessages}

要求：
1. 保留重要实体、用户目标、约束、偏好和已达成结论。
2. 删除寒暄、重复和无关内容。
3. 不得创造对话中不存在的信息。
4. 如果信息发生变化，以最新信息为准。
5. 控制摘要长度。
```

## 4.5 重要原则

Conversation Summary 是：

> Context 压缩机制。

它不是 Raw History 的替代品。

总结完成后，原始消息仍然应该保留在数据库中。

---

# 5. Agent State

## 5.1 作用

Agent State 和普通聊天记忆不是一回事。

例如金融 Agent 正在执行：

```text
读取财报      ✅
行业分析      ✅
估值分析      ⏳
报告生成      ❌
```

这些信息属于：

```text
Agent State
```

而不是 Conversation Summary。

## 5.2 示例

```json
{
  "company": "宁德时代",
  "financialDataLoaded": true,
  "valuationMethod": "DCF",
  "currentNode": "valuation",
  "status": "RUNNING"
}
```

## 5.3 存储位置

如果使用 LangGraph4j：

```text
Checkpoint Store
```

底层推荐：

```text
PostgreSQL
```

热点状态可以额外放 Redis。

表结构示例：

```sql
agent_checkpoint
-------------------------
thread_id
checkpoint_id
state_json
current_node
status
version
created_at
```

## 5.4 什么时候触发

每个关键 Node 执行完成后保存 Checkpoint。

```text
financial_analysis
    ↓
保存 Checkpoint
    ↓
industry_analysis
    ↓
保存 Checkpoint
    ↓
valuation
```

## 5.5 怎么使用

Agent 运行前：

```text
threadId
    ↓
loadState()
```

Node 执行：

```text
Old State
    ↓
Node
    ↓
New State
```

执行后：

```text
saveCheckpoint()
```

## 5.6 和 Long-term Memory 的区别

```text
“这次分析用 DCF”
→ Agent State

“以后分析股票都优先用 DCF”
→ Long-term Memory
```

因此：

> Agent State 记录当前任务状态；Long-term Memory 记录跨任务长期有价值的信息。

---

# 6. Long-term Memory

## 6.1 作用

Long-term Memory 解决的是跨 Conversation 的长期记忆问题。

例如用户之前说：

```text
以后分析股票时，优先关注 ROE 和自由现金流。
```

几个月后新建一个 Conversation：

```text
帮我分析腾讯。
```

系统仍然可以根据长期记忆，知道用户偏好的分析维度。

---

# 7. Long-term Memory 从哪里来

Long-term Memory 通常有两种来源。

## 7.1 显式记忆

用户主动要求记住：

```text
记住，以后分析股票都优先看 ROE 和自由现金流。
```

这种记忆：

```text
来源明确
置信度高
优先级高
```

适合直接进入 Memory Write Pipeline。

## 7.2 隐式记忆

用户没有说“记住”，但表达了稳定偏好：

```text
我做股票分析的时候一直比较关注自由现金流，不太看短期股价。
```

这种信息可以由 LLM 做 Memory Extraction。

---

# 8. Spring AI 是否自动判断 Long-term Memory

Spring AI 提供的主要是：

```text
ChatMemory
VectorStore
Advisor
Embedding
Model API
```

但以下逻辑通常需要业务层自己实现：

```text
是否值得长期保存

保存成什么类型

是否和已有 Memory 冲突

是否更新旧 Memory

是否需要合并

什么时候失效
```

常见做法：

```text
规则判断
+
LLM 判断
```

例如：

```java
if (message.contains("记住")
        || message.contains("以后都")
        || message.contains("从现在开始")) {
    triggerMemoryExtraction();
}
```

然后交给 LLM 输出结构化结果。

---

# 9. Memory Extraction

推荐让 LLM 输出结构化 Memory Candidate：

```json
{
  "shouldRemember": true,
  "type": "PREFERENCE",
  "subject": "stock_analysis",
  "key": "focus_metrics",
  "value": ["free_cash_flow", "roe"],
  "confidence": 0.95,
  "importance": 0.8
}
```

业务代码再决定是否真正落库：

```java
MemoryDecision decision = memoryExtractor.analyze(messages);

if (decision.shouldRemember()
        && decision.confidence() >= 0.8) {
    memoryService.upsert(decision);
}
```

---

# 10. Long-term Memory 数据模型

不要只保存：

```text
content
embedding
```

建议结构化：

```sql
user_memory
----------------------------------
id
user_id
memory_type
subject
memory_key
memory_value
content
status
confidence
importance
valid_from
valid_to
source_conversation_id
source_message_id
embedding
created_at
updated_at
```

示例：

```text
memory_type = PREFERENCE
subject = STOCK_ANALYSIS
memory_key = valuation_method
memory_value = DCF
status = ACTIVE
```

结构化字段负责：

```text
冲突
版本
状态
用户隔离
分类过滤
```

Vector 负责：

```text
语义检索
```

---

# 11. Long-term Memory 为什么不能全量发送给 LLM

假设用户已经积累 1000 条 Memory。

当前问题：

```text
帮我分析宁德时代未来三年的投资风险。
```

不应该：

```text
1000条 Memory
+
Recent Messages
+
Summary
+
Current Message
```

全部发送。

原因：

```text
Token 成本高
上下文变长
噪声增加
无关信息干扰推理
```

正确方式是：

```text
Current User Message
    ↓
Embedding
    ↓
Vector Search
    ↓
Top-K Candidates
    ↓
Metadata Filter / Rerank
    ↓
Relevant Memories
    ↓
Prompt
```

---

# 12. Long-term Memory Retrieval

例如 Long-term Memory 中有：

```text
用户关注 ROE 和自由现金流
用户偏好 DCF 估值
用户使用 Spring Boot
用户之前研究过宁德时代海外业务
用户喜欢研究 Kafka
```

当前问题：

```text
帮我分析宁德时代未来三年的投资风险。
```

Vector Search 可能找出：

```text
宁德时代海外业务相关 Memory
similarity = 0.91

用户关注 ROE 和自由现金流
similarity = 0.87

用户偏向长期投资
similarity = 0.84
```

最后只取 Top 3~5 放进 Prompt。

---

# 13. Metadata Filter

Long-term Memory 检索不应该只看向量相似度。

可以先过滤：

```text
user_id = 当前用户
status = ACTIVE
memory_type ∈ 允许的类型
```

例如：

```sql
WHERE user_id = ?
AND status = 'ACTIVE'
AND memory_type IN (
  'PREFERENCE',
  'PROJECT',
  'DECISION'
)
```

然后再做 Vector Search。

正确顺序：

```text
Metadata Filter
    ↓
Vector Search
    ↓
Rerank
```

---

# 14. Rerank

可以综合考虑：

```text
Semantic Similarity
Importance
Recency
Confidence
Memory Type Weight
```

例如：

```text
finalScore =
0.6 * similarity
+ 0.2 * importance
+ 0.1 * recency
+ 0.1 * confidence
```

先取 Top 20，再选最终 Top 3~5。

---

# 15. 是否每条消息都要查询 Long-term Memory

不一定。

例如：

```text
“谢谢”
→ 不需要

“继续”
→ 多数情况不需要

“帮我分析这个公司的财务”
→ 可能需要

“还是按照我以前的投资偏好来分析”
→ 一定需要
```

可以增加：

```text
Memory Retrieval Decision
```

由规则 + LLM 判断当前请求是否需要检索长期记忆。

---

# 16. Long-term Memory 写入流程

推荐：

```text
New Conversation Messages
        ↓
Memory Extractor
        ↓
是否值得长期保存？
        ↓
Memory Candidate
        ↓
Normalize
        ↓
Existing Memory Lookup
        ↓
NEW / SAME / UPDATE / CONFLICT / APPEND
        ↓
Conflict Resolver
        ↓
Persist
        ↓
Embedding
        ↓
pgvector
```

---

# 17. Memory 冲突处理

假设已有 Memory：

```text
valuation_method = PE
```

后来用户明确说：

```text
以后不用 PE 了，改用 DCF。
```

不能简单新增一条，因为以后检索可能同时拿到：

```text
PE
DCF
```

推荐做版本化。

旧 Memory：

```text
value = PE
status = SUPERSEDED
valid_to = 2026-09-01
```

新 Memory：

```text
value = DCF
status = ACTIVE
valid_from = 2026-09-01
```

查询当前有效记忆时：

```sql
WHERE status = 'ACTIVE'
```

---

# 18. 不要认为“后说的永远覆盖先说的”

例如：

```text
原 Memory：
项目使用 Java 21

后来用户问：
Java 17 能不能实现？
```

这里并不是修改项目版本。

因此 Memory Extractor 要区分：

```text
ASSERTION
PREFERENCE
QUESTION
HYPOTHESIS
CORRECTION
DECISION
TEMPORARY_STATE
```

只有类似：

```text
CORRECTION
DECISION
PREFERENCE_CHANGE
```

才应该触发更新。

---

# 19. Memory Consolidation

很多 Memory 不是冲突，而是逐渐补充。

例如：

```text
用户关注 ROE
用户关注自由现金流
用户不太关注短期股价波动
```

可以合并成：

```json
{
  "type": "PREFERENCE",
  "subject": "investment_analysis",
  "value": {
    "focus": ["ROE", "free_cash_flow"],
    "low_priority": ["short_term_price_fluctuation"]
  }
}
```

这叫：

```text
Memory Consolidation
```

它和 Conversation Summary 不一样：

```text
Conversation Summary
= 压缩当前会话

Memory Consolidation
= 整理长期记忆
```

---

# 20. Memory 遗忘机制

Long-term Memory 不能只增不减。

常见机制：

## 20.1 显式失效

用户说：

```text
以后别再按 DCF 了。
```

则：

```text
DCF Memory → SUPERSEDED / DELETED
```

## 20.2 时间衰减

例如：

```text
用户最近正在研究英伟达
```

半年后价值可能下降。

可以降低 recency score。

## 20.3 TTL

例如临时 Memory：

```text
用户本周正在研究宁德时代
```

可以：

```text
expires_at = +30 days
```

## 20.4 低价值长期未访问

例如：

```text
importance < 0.2
AND
last_accessed > 1 year
```

可以标记：

```text
ARCHIVED
```

---

# 21. Soft Forget 与 Hard Delete

建议区分状态：

```text
ACTIVE
SUPERSEDED
EXPIRED
ARCHIVED
DELETED
```

正常检索只取：

```text
ACTIVE
```

但审计可以保留旧版本。

特别是在金融业务中：

> Agent 不再使用某条 Memory，和系统物理删除数据，是两个不同的问题。

---

# 22. Long-term Memory 异步化

不建议把 Long-term Memory 提取全部放在主请求链路中。

否则：

```text
User Request
    ↓
LLM Response
    ↓
Memory Extraction
    ↓
Embedding
    ↓
DB Write
    ↓
返回用户
```

会增加延迟。

推荐：

```text
User Request
    ↓
LLM Response
    ↓
立即返回用户
    ↓
Conversation Event
    ↓
Kafka
    ↓
Memory Worker
    ↓
Extract
Resolve
Embed
Persist
```

可以区分：

```text
Short-term State
→ 同步

Long-term Memory
→ 异步
```

---

# 23. Raw Chat History

## 23.1 作用

Raw Chat History 是系统事实记录。

它和 Recent Messages 不同。

Recent Messages：

```text
主要给 LLM 使用
```

Raw History：

```text
主要用于历史记录、审计、重建、Debug
```

## 23.2 存储位置

推荐：

```text
PostgreSQL
```

数据量巨大时可配合对象存储。

## 23.3 什么时候写

每条原始消息实时落库。

包括：

```text
User Message
Assistant Message
```

金融 Agent 还可能包括：

```text
Tool Call
Tool Result
```

## 23.4 怎么使用

主要用于：

```text
查看历史聊天
Conversation Summary 重建
Memory Extraction 重跑
Debug
审计
数据分析
用户反馈定位
```

---

# 24. 一次完整请求的 Context 构建

可以设计成：

```text
                    Current User Message
                            │
                            ▼
                     Context Builder
                            │
          ┌─────────────────┼──────────────────┐
          │                 │                  │
          ▼                 ▼                  ▼
 Recent Messages     Conversation       Relevant Long-term
                        Summary              Memory
          │                 │                  │
          └─────────────────┼──────────────────┘
                            │
                            ▼
                          Prompt
                            │
                            ▼
                           LLM
```

最终 Prompt：

```text
System Prompt

Conversation Summary

Relevant Memories

Recent Messages

Current User Message
```

---

# 25. Agent State 不一定直接全部进入 Prompt

Agent State 主要服务于：

```text
LangGraph4j
程序控制
流程恢复
Checkpoint
条件判断
```

不应该把整个 state_json 每次都发送给 LLM。

应该只把当前 Node 真正需要的字段传给模型。

例如：

```text
State 中有：
financialData
industryData
valuationResult
rawDocuments
checkpointMetadata
internalStatus

当前 Node 只需要：
financialData
valuationMethod
```

那么只传这两个。

---

# 26. 五层 Memory 的触发与读取总表

| Memory | 存哪里 | 写入触发 | 读取触发 | 使用方式 |
|---|---|---|---|---|
| Recent Messages | Redis + PostgreSQL | 每条 User/Assistant 消息 | 每次 LLM 调用 | 直接拼接最近上下文 |
| Conversation Summary | PostgreSQL / Redis | Token/消息数超阈值 | 每次 LLM 调用 | 替代早期历史 |
| Agent State | Checkpoint Store / PostgreSQL | 每个关键 Node 完成 | Agent 启动/恢复/继续 | 控制 Agent 流程 |
| Long-term Memory | PostgreSQL + pgvector | 显式触发或异步提取 | 当前问题需要历史信息时 | Vector Search + Top-K |
| Raw Chat History | PostgreSQL / 对象存储 | 每条消息实时写 | 审计/历史/重建/Debug | 不直接全量进入 Prompt |

---

# 27. 推荐的 Spring Java 架构

```text
Spring Boot
    │
    ├── ConversationService
    │       │
    │       ├── PostgreSQL
    │       │   └── Raw Chat History
    │       │
    │       └── SummaryService
    │              └── Conversation Summary
    │
    ├── MemoryService
    │       │
    │       ├── MemoryExtractor
    │       ├── ConflictResolver
    │       ├── EmbeddingModel
    │       └── PostgreSQL + pgvector
    │
    ├── ContextBuilder
    │       │
    │       ├── Summary
    │       ├── Recent Messages
    │       └── Relevant Memories
    │
    ├── Spring AI
    │       │
    │       ├── ChatClient
    │       ├── Tool Calling
    │       ├── RAG
    │       └── MCP
    │
    └── LangGraph4j
            │
            ├── Agent State
            ├── Node
            ├── Edge
            └── Checkpoint
```

---

# 28. 推荐的基础设施职责划分

```text
PostgreSQL
= Source of Truth
```

用于：

```text
Raw Chat History
Conversation Summary
Agent Checkpoint
Long-term Memory Metadata
```

---

```text
Redis
= Hot Cache
```

用于：

```text
Recent Messages
Summary Cache
Active Agent State Cache
```

---

```text
pgvector
= Semantic Retrieval
```

用于：

```text
Long-term Memory Embedding Search
```

---

```text
Kafka
= Async Memory Pipeline
```

用于：

```text
Memory Extraction
Summary Compaction
Memory Consolidation
```

---

# 29. 推荐数据库表

## conversation

```sql
conversation
------------------
id
user_id
title
created_at
updated_at
```

## chat_message

```sql
chat_message
------------------
id
conversation_id
user_id
role
content
token_count
model
created_at
```

## conversation_summary

```sql
conversation_summary
----------------------------
conversation_id
summary
covered_until_message_id
version
updated_at
```

## agent_checkpoint

```sql
agent_checkpoint
----------------------------
thread_id
checkpoint_id
state_json
current_node
status
version
created_at
```

## user_memory

```sql
user_memory
----------------------------------
id
user_id
memory_type
subject
memory_key
memory_value
content
status
confidence
importance
valid_from
valid_to
source_conversation_id
source_message_id
embedding
created_at
updated_at
```

---

# 30. 推荐请求时序

## 同步主链路

```text
User Message
    ↓
保存 Raw History
    ↓
读取 Conversation Summary
    ↓
读取 Recent Messages
    ↓
判断是否需要 Long-term Memory
    ↓
必要时 Vector Retrieval
    ↓
Context Builder
    ↓
Spring AI / LLM
    ↓
LangGraph4j Agent 执行
    ↓
保存 Agent Checkpoint
    ↓
生成 Assistant Response
    ↓
保存 Raw History
    ↓
更新 Recent Messages
    ↓
返回用户
```

## 异步链路

```text
Conversation Event
    ↓
Kafka
    ↓
┌────────────────────┬──────────────────────┐
│                    │                      │
▼                    ▼                      ▼
Summary          Memory Extraction    Memory Consolidation
Compaction            │                      │
                      ▼                      ▼
                Conflict Resolve        Cleanup / Archive
                      │
                      ▼
                  pgvector
```

---

# 31. 关键设计原则

## 原则 1：不要把所有历史都塞给模型

应该：

```text
Summary
+
Recent Messages
+
Relevant Memory
```

而不是：

```text
All History
```

## 原则 2：Long-term Memory 不是聊天记录仓库

Long-term Memory 是：

> 可检索、可更新、可失效、可版本化的长期知识。

## 原则 3：Raw History 永远是 Source of Truth

Summary、Memory、State 都可以重建或修正。

Raw History 是最原始事实来源。

## 原则 4：Agent State 和 Memory 分开

```text
当前任务状态
≠
长期用户记忆
```

## 原则 5：Memory 要有生命周期

必须考虑：

```text
Write
Update
Conflict
Consolidate
Retrieve
Expire
Archive
Delete
```

## 原则 6：Vector Search 只是 Memory Retrieval 的一部分

更完整的是：

```text
Metadata Filter
→ Vector Search
→ Rerank
→ Top-K
```

---

# 32. 最终心智模型

可以把整个系统理解成：

```text
                         User
                           │
                           ▼
                       Chat API
                           │
                           ▼
                    Context Builder
                           │
        ┌──────────────────┼──────────────────┐
        │                  │                  │
        ▼                  ▼                  ▼
 Recent Messages    Conversation       Relevant Memory
      Redis             Summary          pgvector
                           │                  │
        └──────────────────┼──────────────────┘
                           │
                           ▼
                        Spring AI
                           │
                           ▼
                      LangGraph4j
                           │
                           ▼
                       Agent State
                           │
                           ▼
                      Checkpoint
                           │
                           ▼
                       PostgreSQL
```

而后台异步运行：

```text
Conversation Event
        ↓
      Kafka
        ↓
Memory Extractor
Summary Compactor
Memory Consolidator
        ↓
 PostgreSQL / pgvector
```

---

# 33. 一句话总结

连续对话 Memory 的核心不是“保存聊天记录”，而是：

> 把不同时间尺度、不同作用域的信息分层保存，在每次请求时只选择当前任务真正需要的上下文重新提供给模型。

真正成熟的设计应该做到：

```text
Recent Messages
负责短期连续性

Conversation Summary
负责当前会话压缩

Agent State
负责复杂任务执行状态

Long-term Memory
负责跨会话长期记忆

Raw Chat History
负责完整事实和可追溯性
```

如果这五层职责分清楚，后续无论接 Spring AI、LangGraph4j、RAG、MCP 还是金融业务 Agent，整个连续对话架构都会非常清晰。
