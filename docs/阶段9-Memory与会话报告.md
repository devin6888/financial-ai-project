# 阶段 9 · 会话与记忆（多轮 / 摘要 / 长期偏好）报告

> 执行日期：2026-10-28 ｜ 语料：东方财富 2025 年年度报告（document 7，654 个子块）+ 2024 年年度报告（document 38） ｜ 计划：docs/superpowers/plans/ST9-2026-10-27-09-memory-session.md（§1~§17） ｜ 本文所有数字都来自本机实跑，口径写在数字旁边。

## 怎么读这份报告（先看这 8 个词）

| 词 | 一句话解释（完整版见 docs/术语表.md §10） |
| --- | --- |
| 会话（conversation） | 一串连续问答的容器。它自己不是记忆，只是一个 id + 归属人 + 默认作用域。 |
| 短期记忆 | 最近 6 轮原文，直接拼进提示词。作用是让「那净利润呢」能接上上一句。 |
| 摘要 | 更早的对话压成 200 字一段。作用是让 30 轮之后还记得前面确认过的结论，又不撑爆上下文。 |
| 结构化上下文 | 4 个标量（公司 / 年份区间 / 文档集 / 当前指标），从上一轮问答里抽取，用来补全省略的主语。 |
| 长期记忆（LTM） | 跨会话的偏好与规则，例如「回答先给结论」「每个结论都要标注出处」。存 PostgreSQL 表，不存 Redis。 |
| Gate（闸门） | 判断「这一问到底要不要去翻长期记忆」的开关。绝大多数财务事实问答不该翻，翻了就是白花一次 embedding 调用。 |
| 指代消解 | 把「它」「那毛利率呢」里的省略主语补回来，交给检索器。本阶段只做单轮上下文补全，不做跨句指代。 |
| SUPERSEDED | 长期记忆改了之后旧行的状态。同一个 key 永远只有一条 ACTIVE，历史行保留可追溯。 |

## 1. 一句话结论

阶段 9 让系统从「一次问答」变成「一段对话」：**同一会话内可以省略主语（那净利润呢），跨会话可以记住偏好（以后都先给结论），但记忆永远不会被当成证据引用**。三条保证分别由短期窗口、摘要、结构化上下文、长期记忆四层落地，全部以 PostgreSQL 为唯一真相源（不引入 Redis），四层各有独立 token 配额，任何一层超预算都是整条丢弃、绝不截半句。

本阶段新增 34 个主代码文件 2329 行、18 个测试文件 2044 行、1 个迁移（V10，231 行，6 张表 + 11 条 RLS 策略）；非 e2e 用例从 343 涨到 **496**，6 个真模型多轮 e2e 全绿（152.7 秒）。过程中抓到 8 个真问题，其中 2 个是产品级缺陷：① 模型编造公司代码导致整轮被拒答 → 最终改成「模型只抽公司名、代码由程序查库」（C13）；② 模型说「证据不足」时 answer 与 note 都是空串（C6 第 5 条）。

## 2. 为什么还要做这一步

### 2.1 改造前的三个断点

| 断点 | 现象 | 后果 |
| --- | --- | --- |
| 无会话 | 每个请求都是独立的一问一答，服务端不留任何痕迹 | 「那净利润呢」会被当成一个没有主语的独立问题去检索，召回一堆无关段落 |
| 无记忆 | 用户说「以后都先给结论」，下一轮完全忘掉 | 用户必须每轮重复同一句要求，等于没有产品体验 |
| 无摘要 | 想让模型记住 30 轮之前的内容，只能把 30 轮原文全拼进提示词 | 8000 token 的窗口装不下，且 90% 是寒暄，真正的证据被挤掉 |

### 2.2 改造后的链路

~~~text
POST /api/chat { question, conversationId }
  |
  +-- 1. 会话归属校验（不是我的会话 -> 404，不确认存在性）
  +-- 2. 作用域解析（请求 scope 优先，否则用会话默认 scope）        [阶段 8 的 RLS / ACL 不变]
  +-- 3. 装配记忆（MemoryAssembler）
  |      结构化上下文 conversation_context   5% 预算
  |      此前对话摘要 conversation_summary  10% 预算
  |      最近 6 轮原文 conversation_message  15% 预算
  +-- 4. Query Understanding（带上下文块）-> 指代消解
  +-- 5. Gate 判断是否翻长期记忆 -> 召回 + 注入     5% 预算（INSTRUCTION 独立 600 token）
  +-- 6. 混合检索 + 重排（用消解后的 normalizedQuery）        [阶段 4~5 不变]
  +-- 7. 上下文装配 + 答案生成 + 引用校验                    [阶段 5 不变]
  +-- 8. 落库一轮问答（USER + ASSISTANT 两条）
  +-- 9. 显式记忆命令解析（「记住：…」）-> 写长期记忆
  +-- 10. 摘要器（待汇总条数 > 12 才压最早的一半）
~~~

关键点：**记忆不进 ContextBuilder**。证据块 [E1..En] 的编号是数组下标（见 docs/关键契约.md C12 与 src/main/java/com/finagent/citation/CitationValidator.java:41），把记忆塞进 blocks 会让编号整体错位，而且记忆块没有 documentId / pageStart / bbox，引用会「看起来有出处却点不开」。所以记忆只在提示词的「背景信息·不可引用」段出现，两条线路在 AnswerPrompts 汇合。

### 2.3 为什么不是「存个 chat history 就行」

| 只存 history 会缺什么 | 后果 | 本阶段的对应设计 |
| --- | --- | --- |
| 没有预算概念 | 30 轮之后提示词超长，模型开始丢证据 | 五层各自配额（合计 75%），基数 8000 token |
| 没有摘要 | 越早的信息越先被挤掉，且挤掉是静默的 | 待汇总 > 12 条时把最早的一半压成 200 字，指针只前移 |
| 没有结构化上下文 | 模型要靠读原文猜「它」指哪家公司 | 4 个标量显式落库，指代消解直接读 |
| 没有 Gate | 每轮都查长期记忆 = 每轮多一次 embedding 调用 | 14 个关键词 + QU 字段两道判断，不相关零调用 |
| 记忆混进证据 | 记错的偏好被包装成「有出处的结论」 | 记忆永不进 blocks，永不产生引用编号 |

## 3. 交付物（9 个任务）

| # | 任务 | 主要文件 | 状态 |
| --- | --- | --- | --- |
| 1 | V10 迁移：6 张表 + 子表 tenant_id + 11 条 RLS 策略 | src/main/resources/db/migration/V10__conversation_and_memory.sql | 完成（V10 success） |
| 2 | 会话存储与 REST | src/main/java/com/finagent/memory/Conversation*.java、src/main/java/com/finagent/chat/ConversationController.java（6 个端点） | 完成 |
| 3 | 短期记忆 + Prompt 注入 | src/main/java/com/finagent/memory/ShortTermMemory.java、MemoryPromptRenderer.java | 完成 |
| 4 | 结构化上下文 | src/main/java/com/finagent/memory/ConversationContextService.java、ContextPatch.java | 完成 |
| 5 | 指代消解 | src/main/java/com/finagent/understanding/TurnContext.java、RouterService.understand(query, ctx) | 完成 |
| 6 | 会话摘要 | src/main/java/com/finagent/memory/ConversationSummarizer.java、LlmSummaryGenerator.java | 完成 |
| 7 | 长期记忆写入（命令 + API） | src/main/java/com/finagent/memory/LongTermMemoryService.java、MemoryCommandParser.java、MemoryController.java | 完成 |
| 8 | Gate + 召回 + 分预算注入 | src/main/java/com/finagent/memory/MemoryGate.java、LongTermMemoryRecaller.java、MemoryAssembler.withLongTerm | 完成 |
| 9 | 契约 C12 + 术语 + README + 报告 + e2e | docs/关键契约.md、docs/术语表.md、README.md、src/test/java/com/finagent/memory/ConversationE2ETest.java | 完成（本文即交付物之一） |

## 4. 契约 C12 摘要（完整版见 docs/关键契约.md）

### 4.1 分层与真相源

| 层 | 存哪 | 行基数 | 能否被引用 |
| --- | --- | --- | --- |
| 短期原文 | conversation_message | 每轮 2 行，追加 | 否 |
| 摘要 | conversation_summary | **每会话 1 行**（PK=conversation_id，覆盖写 version+1） | 否 |
| 结构化上下文 | conversation_context | **每会话 1 行**（ON CONFLICT DO UPDATE） | 否 |
| 长期记忆 | long_term_memory | 每 key 一条 ACTIVE，旧行 SUPERSEDED | 否 |
| 证据 | document_chunk | 不变 | **只有它能** |

为什么摘要和结构化上下文只留一行：它们是「当前状态」不是「事件」，摘要可以由 message 重算，所以不必存历史；不可重算的变更动作（谁把偏好从 A 改成 B）进 memory_event 追加表。

### 4.2 三条硬规则

1. **记忆永不产生引用**。CitationValidator 只认 ContextBlock，记忆在提示词的另一段。
2. **预算只能整条丢，不能截断**。截半句会让模型自己补数字（幻觉）。INSTRUCTION 例外：独立 600 token，超了合并压缩不丢弃，压不下记 instruction_overflow 警告 —— 规则被静默裁掉等于违反用户指令。
3. **越权一律 404 或空结果**。他人会话不确认存在性；ACL 滤空的候选集必须抛 ScopeEmptyException，不能退化成全库。

### 4.3 Prompt 装配预算（基数 context-window-tokens=8000）

| 段 | 配额 | 说明 |
| --- | --- | --- |
| System | 常量 | 规则在这里，不受配额、不参与裁剪 |
| 结构化上下文 | 5%（400） | 4 个标量，实测只占 20~40 token |
| 摘要 | 10%（800） | 一段 200 字，约 300 token |
| 短期窗口 | 15%（1200） | 最近 6 轮 |
| 长期记忆 | 5%（400），≤3 条 | INSTRUCTION 另算 600 |
| 证据 | 40%（3200） | E1..En |
| 当前问题 + 输出 | 余 25% | 兜底顺序：证据（低分先丢）→ 短期（最旧）→ 摘要 → 结构化 → LTM |

### 4.4 第四个契约：C13 公司名 → 公司代码

阶段 9 收尾时又补了一份契约（C13），因为它改的是**「谁有权把中文公司名翻成证券代码」**这个职责边界：

| 名字 | 类型 | 含义 | 谁产生 |
| --- | --- | --- | --- |
| `QueryUnderstanding.companyMentions` | `List<String>` | 原文里出现的公司名字面 | 模型抽取 + **子串校验** |
| `QueryUnderstanding.companyCodes` | `List<String>` | 6 位证券代码 | 原文写了代码时抄；否则由程序查库得到 |
| `ResolvedScope.companyIds()` | `Set<Long>` | 数据库 `company.id` 主键 | 作用域解析（沿用旧名，它确实是 id） |

四条硬规则：mentions 必须是原文子串；codes 必须在原文里字面出现（这一刀根治模型编代码）；
代码为空时用 mentions 在当前作用域内匹配；仍为空且作用域只有一家公司时兜底并记 `company_from_scope`。
顺序是**用户原话 > 会话上下文 > 单公司作用域 > 无约束**。

## 5. 实测

### 5.1 六张表与 RLS

| 项 | 实测 |
| --- | --- |
| Flyway | V1..V10 全部 success，最新 = 10 conversation and memory |
| pg_policies 总数 | **11**（阶段 8 的 5 条业务表 + 阶段 9 的 6 张新表，一张一条） |
| conversation | 15 行（含 Task 2~6 集成测试残留 14 行 + 演示会话 1 行，e2e 自己造的行已清理） |
| conversation_message | 26 行 |
| conversation_summary / conversation_context | 0 / 0（e2e 清理后归零；运行期间的写入在日志里可见） |
| long_term_memory | 4 行（含 1 条 SUPERSEDED 历史行，验证版本化） |
| memory_event | 2 行（SUPERSEDE 审计） |

子表也各带 tenant_id + 各自的 RLS 策略：**RLS 不会替我们 join 父表**，只给 conversation 加策略的话，直接查 conversation_message 就能跨租户读到别人的对话内容（阶段 8 的教训）。

### 5.2 多轮指代消解（真模型，日志原文）

| 轮次 | 用户问题 | 检索实际用的 query | 结果 |
| --- | --- | --- | --- |
| 1 | 东方财富2025年营业总收入是多少 | 同左 | sufficient，引用 7 |
| 2 | 那净利润呢？ | 东方财富2025年净利润是多少 | warnings 含 query_inferred_from_context，答案含 12,084,594,947 |
| 3 | 比去年增长多少？ | 东方财富2025年营业总收入比2024年… | 答案「比去年增长38.46%」 |
| 4 | 它现在的现金流怎么样？ | 东方财富2025年经营活动产生的现金流量净额是多少 | 6 条引用全部指向 document 7 |
| 5 | 那毛利率呢？ | — | sufficient=true（该语料确实有毛利率口径） |

注意第 2 行：**消解发生在 QU 之后、检索之前**，检索用的是 normalizedQuery，答案 Prompt 里仍然放用户原话（避免用户看到被改写的问题）。

### 5.3 摘要

| 项 | 实测 |
| --- | --- |
| 触发阈值 | 待汇总消息数 > 12（finagent.memory.summary-trigger-messages） |
| 每次压多少 | 待汇总条数的**一半**（偶数化、最少 2 条）—— 不是全量，全量会让摘要每轮重写、old→new diff 失去意义 |
| 指针 | summary_until_message_id 只前进（SQL 用 GREATEST），下游窗口从指针之后开始，摘要与原文不重复计入 |
| 失败处理 | 只加 summary_failed 警告，指针不动，下一轮重试 |
| 测试 | ConversationSummarizerDbTest 3 例：15 轮 30 条 → version=1 指针=第 14 条真实 id；再攒 10 轮 → version=2 指针=第 32 条；3 轮不够阈值不写 |

### 5.4 长期记忆 Gate 与零开销

| 场景 | embedding 调用 | 记忆注入 |
| --- | --- | --- |
| 「东方财富2025年营业总收入是多少」 | **0 次**（Gate 不通过，连库都不查） | 无 |
| 问题里出现「格式 / 偏好 / 记住 / 上次 / 照旧 …」 | 0 次（规则命中 key 前缀） | 精确命中项必进 |
| 规则命中数 < 3 条 | 1 次（补向量召回） | 相似度 ≥ 0.6（距离 ≤ 0.4）才入选 |
| 无任何记忆的用户 | **0 次**（候选为空直接返回） | 无 |

`<=>` 返回的是**距离**（越小越像），阈值写成相似度时必须是 `1 - longTermMinScore`，写反了就变成「越不相关越入选」。

### 5.5 端到端 6 个用例（真模型，152.7 秒）

| 用例 | 断言 | 实测输出 |
| --- | --- | --- |
| 多轮指标追问 | sufficient + query_inferred_from_context + 数值 | CONV-E2E\|case=metric\|docIds=[7, 7]\|len=87 |
| 同比追问 | 引用非空 + 给出增长率与两个年度 | case=yoy\|answer=营业总收入比去年增长38.46%。 |
| 换指标保持公司 | 引用全部指向 document 7 | case=cashflow\|docIds=[7,7,7,7]\|sufficient=true |
| 可能证据不足的问题 | 有引用，或（note 非空 + citations 为空 + 警告含 `evidence_insufficient`） | case=grossmargin\|sufficient=true |
| 偏好跨会话生效 | trace.memory.gateHit=true + longTermItems ≥ 1 | case=preference\|gateHit=true\|items=1 |
| 记忆不冒充证据 | 引用全部有 documentId/pageStart/childChunkId，或明确拒答 | case=notcited\|sufficient=true\|citations=4\|repeatedMemory=false\|ungrounded=false |

第 6 个用例的设计值得单独说：它先往长期记忆里写一条**故意错的**「东方财富2025年营业总收入是 1 亿元」，然后问正常问题。如果记忆被当成证据，答案里就会出现 1 亿元并带上引用编号。实测答案是正常金额、4 条引用全部指向真实块、`repeatedMemory=false` —— 记忆只作为「用户偏好」出现在背景段，没有污染证据。

### 5.6 测试统计

| 口径 | 数字 |
| --- | --- |
| 非 e2e | **496 通过 / 0 失败 / 1 跳过**（DoclingParserAdapterTest 需解析服务）—— 阶段 8 结束 343，阶段 9 +153 |
| 阶段 9 新增非 e2e 类 | ConversationRepositoryTest 4 / ConversationServiceTest 6 / ConversationControllerTest 9 / ShortTermMemoryTest 7 / ConversationContextServiceTest / ContextSlotTest / TurnContextTest 6 / RouterServiceContextTest 7 / ConversationSummarizerTest 6 / ConversationSummaryRepositoryTest 3 / ConversationSummarizerDbTest 3 / ConversationSummarizerDbTest / MemoryCommandParserTest 8 / LongTermMemoryServiceDbTest 8 / MemoryControllerTest 5 / MemoryGateTest 9 / LongTermMemoryRecallerDbTest 8 / MemoryAssemblerTest 9 / ChatServiceTest 20 |
| e2e | 阶段 9 新增 ConversationE2ETest 6 个用例，152.7 秒；加上阶段 7~8 的 14 个 e2e 类，全仓 e2e 共 15 个类 |
| 阶段 9 收尾后新增的非 e2e 用例（§17 与 C13） | CompanyResolverTest 10 / RouterServiceTest +4 / ChatServiceTest +2（共 23） |
| 代码规模 | src/main/java/com/finagent/memory 34 个文件 2329 行；src/test/java/com/finagent/memory 18 个文件 2044 行 |

## 6. 抓到的八个问题

### 6.1 结构化上下文被 Jackson 多写一个字段

- **现象**：scope_json 落库后多了 `"empty": true`，读回时字段数和预期不同。
- **原因**：ScopeQuery 有 `isEmpty()` 这个布尔 getter，Jackson 把它当成属性序列化。
- **修法**：ScopeSnapshots.scopeJson 手写四个字段（kind / documentIds / query），不靠自动序列化。
- **教训**：往 jsonb 里存「值对象」时，`isXxx()` 方法会被当成字段；要么加 @JsonIgnore，要么手写。

### 6.2 PATCH 请求体里缺 clear 就 400

- **现象**：`{}` 请求体返回 400，而不是走「不清理任何槽位」的逻辑。
- **原因**：ContextPatch 里 `boolean clear` 是基本类型，Jackson 认为 creator 属性必填。
- **修法**：改成 `Boolean clear` + 紧凑构造器 + `cleared()`（`Boolean.TRUE.equals(clear)`）。
- **教训**：外部请求体的基本类型字段一律用包装类型，「缺省」和「false」是两件事。

### 6.3 指代消解给公司名加了两次

- **现象**：问「东方财富的净利润是多少」，改写结果是「东方财富（300059） 东方财富的净利润是多少」。
- **原因**：判重用的是 companyLabel()（「东方财富（300059）」），而原文里只有「东方财富」，`contains` 判 false。
- **修法**：只要公司名**或**代码任一出现在问题里就算已出现。
- **教训**：判重要按「最短可识别单位」比，不要拿拼好的展示串去比。

### 6.4 预算为 0 反而变成「不限量」

- **现象**：MemoryAssemblerTest 里把 longTermRatio 设成 0，结果所有长期记忆都被注入。
- **原因**：`if (budget > 0 && used + t > budget) continue;` —— budget=0 时整个判断短路跳过。
- **修法**：`budget <= 0` 表示这一层关闭（与 structured / summary 的语义统一）。
- **教训**：这是测试逼出来的，不是读代码看出来的；「0 是关掉还是不限」必须在实现里写死一种语义并统一。

### 6.5 关记忆开关 = 启动失败

- **现象**：application-test.yml 里 `finagent.memory.enabled=false`，整个 Spring 上下文起不来：No qualifying bean of type SummaryGenerator。
- **原因**：LlmSummaryGenerator 上加了 `@ConditionalOnProperty(enabled=true)`，而 ChatService 的构造器要求它必须存在。
- **修法**：去掉条件注解，Bean 一直存在；开关只在运行时判断（`props.enabled()`）。
- **教训**：**运行时开关不要做成装配期条件**；装配期条件只适合「依赖缺失时功能不可用」（如 COS 四件套）。

### 6.6 模型编造公司代码，整轮被拒答（产品级缺陷）

- **现象**：e2e 里「按我的要求标注出处：东方财富2025年营业总收入是多少」被拒答，日志 `作用域外公司，拒答：[600519]`（600519 = 贵州茅台，问题里根本没提），耗时 3.34 秒、无检索。
- **原因**：两层。① LLM 做 Query Understanding 时把公司代码（当时字段名叫 `companyIds`）编成了 600519；② RouterService.validate 从阶段 6 起「只校验 6 位数字格式、保留未知代码」（那是为了修「问五粮液答东方财富」的主体错配），于是编造值一路通过，ChatService 判「用户问的公司不在作用域内」→ 拒答。
- **第一版修法**（ST9 §15，已废弃）：加「公司代码文本落地校验」—— QU 给出恰好 1 个作用域外代码时，若问题原文点名了唯一的本作用域公司，就把代码改锚到它。
- **第二版修法**（ST9 §16，最终）：发现病根不是「模型偶尔给错」，而是**分工本身错了** —— 提示词让模型输出证券代码，等于要求它凭训练记忆做「公司名 → 代码」的映射，没有权威源可查，出错只是时间问题。改为：模型只抽 `companyMentions`（原文里的公司名字面，必须逐字出现在问题里），代码由 `CompanyResolver` 在**当前作用域的 company 行**里查。第一版补丁（`groundCompanyFromQuestion`）随之删除。
- **教训**：**抽取（extraction）交给模型，实体链接（entity linking）交给数据库**。凡是「需要查权威表才能确定的值」，都不该让模型直接产出；模型能产出的只能是它眼前这段文字里的东西。

### 6.7 问题里没有公司名时，稀疏检索丢掉了公司这个词

- **现象**：§16 落地后跑 `RetrievalEvalTest`，27 条全部变成 `qu=SEMANTIC_EXPLANATION/DEGRADED:weak_evidence`（此前是 EXACT_FACT）。召回与 MRR 没变（1.000 / 0.782），但 Sparse 精确模式少了 `300059` 这个词。
- **原因**：评测问题形如「2024年营业总收入是多少？」——公司由作用域隐含，问题里没有公司名；按 §16 的规则模型不许凭空补代码 → EXACT_FACT 三要素不全 → 降级。
- **修法**：`ChatService` 在 `CompanyResolver` 之后补一步 —— 代码仍为空且**作用域里只有一家公司**时用它的代码兜底，记 `company_from_scope`；两家以上不兜底（替用户选主体就是主体错配）。
- **教训**：「不许模型猜」不等于「不许系统推断」。**确定性推断**（作用域本身是用户设定的约束）与**概率性猜测**（模型凭记忆给代码）是两件事，前者可以写进代码，后者只能被原文证伪。

### 6.8 模型说「证据不足」时，answer 与 note 都是空串

- **现象**：e2e「那毛利率呢」返回 `confidence=insufficient`、`citations=[]`，但 `answer` 与 `note` 都是空串 —— 客户端既没法展示，也没法报警。
- **原因**：`AnswerService` 的 `!dto.sufficient()` 分支直接透传 `dto.answer()` / `dto.note()`，且一条 warning 都不加；模型这次两样都没给。
- **修法**：该分支补 `evidence_insufficient` 警告；空文案给固定说明（「证据不足：检索到的内容里没有能支撑这个问题的信息。」/「模型判定现有证据不足以回答，且未给出原因。」）。契约写进 C6 第 5 条。
- **教训**：**「答不了」也是一种答案**，必须与「答得了」同样有可展示文本、可机器判断的原因。只给一个 `confidence=insufficient` 的响应，在监控眼里和超时没区别。

## 7. 已知限制

| # | 限制 | 影响 | 何时处理 |
| --- | --- | --- | --- |
| 1 | 不做跨句指代消解（「它」只能靠上一轮的结构化槽位） | 极度省略的问法仍可能召回偏 | 阶段 10 或引入专门 coref 模型 |
| 2 | 不做 LLM 自动提炼长期记忆 | 用户必须显式说「记住」或调 API，否则不记 | 想清楚准确率评估方式之后 |
| 3 | 摘要用 qwen-plus，失败只降级为「不摘要」 | 长对话早期信息可能丢失一轮 | 已有重试语义，暂够 |
| 4 | 未接 Redis | 每轮多几次索引查询（实测 0.08~0.125 ms） | 压测证明是瓶颈时再加 |
| 5 | 集成测试残留 14 个会话行 | conversation 表行数不干净 | 阶段 10 加清理夹具 |
| 6 | 组织级记忆（ORG）留字段未启用 | subject_type 只写 USER | 多租户团队场景再做 |
| 7 | 记忆不进 trace 的完整正文，只记计数（items/gateHit） | 排查「为什么没召回」要去看库 | 需要时扩 MemoryTrace |

## 8. 验收对照（ST9 §5 十条）

| # | 验收项 | 结果 |
| --- | --- | --- |
| 1 | 同一会话内第二次追问能补全主语，warnings 含 query_inferred_from_context | ✅ 见 5.2 |
| 2 | 不同会话之间不串上下文 | ✅ 会话隔离由 conversation_id 决定，偏好用例是新建会话 |
| 3 | 偏好写入后新会话生效（Gate 命中 + 注入） | ✅ preference\|gateHit=true\|items=1 |
| 4 | 记忆永不产生引用 | ✅ notcited 用例 + CitationValidator 只认 blocks |
| 4b | 公司名能解析成代码、编造的代码被丢弃 | ✅ C13 + CompanyResolverTest 10 例 + ChatServiceTest 23 例 |
| 5 | 不相关问题 embedding 调用次数为 0 | ✅ MemoryGateTest + 5.4 表 |
| 6 | 摘要只在超阈值时触发，指针只前进 | ✅ ConversationSummaryRepositoryTest + DbTest |
| 7 | 他人会话一律 404 | ✅ ConversationService.requireOwned |
| 8 | 无 conversationId 时行为与阶段 8 完全一致 | ✅ 343 个阶段 8 用例一个没挂 |
| 9 | 六张表全部有 RLS 策略 | ✅ pg_policies=11 |
| 10 | 全量非 e2e 绿 | ✅ 496 通过 / 0 失败 / 1 跳过 |

## 9. 下一步

1. 阶段 10（Agent 工作流）：Multi-Agent + LangGraph4j + HITL。届时会话与记忆层就是「多轮任务的记忆底座」，不需要改表。
2. 想评估的可以先用 docs/阶段6-人工核验表.md 的 32 题做「多轮化」改造（每题追加一个追问），把指代消解的召回率量化。
3. conversation 表的历史残留行建议在阶段 10 加一个测试清理夹具，避免每次 e2e 都留垃圾。
