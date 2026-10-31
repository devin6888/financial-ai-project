我建议你这次不要把 LangGraph4j 当成一个普通的 Java 框架来学习，而是把它当成 Agent 工作流编排引擎来学习。

因为你现在正在开发金融 RAG / 金融研报智能分析 Agent，LangGraph4j 在你的项目中并不只是负责调用大模型，而是要承担整个 Agent 的流程控制，包括：

- 判断用户当前要执行什么任务。
- 决定先执行哪个节点，后执行哪个节点。
- 控制多个分析任务并行执行。
- 根据中间分析结果决定下一步走向。
- 保存工作流状态，支持中断、恢复。
- 支持多轮对话，让 Agent 能够延续之前的任务。
- 在分析质量不合格时重新执行，或者转入人工审核。

这些能力正是 LangGraph4j 值得学习的地方。

我会结合你的技术栈来讲：

Spring Boot + Spring AI + LangGraph4j + PostgreSQL/pgvector + Redis + Kafka。

我们最终要达到的目标是：你能够独立设计一个完整的金融研报 Agent 工作流，而不仅仅是会使用几个 API。

# 一、先建立 LangGraph4j 的整体认知

## 1. LangGraph4j 到底解决什么问题？

先假设一个场景。

用户向你的金融 RAG 系统提问：

> 帮我分析一下贵州茅台最近三年的经营情况，并生成一份投资研究报告。

如果不用 LangGraph4j，可能会怎么实现？

你可能会在 Spring Boot 中写一套业务流程：

```
public Report analyze(String company) {

    // 1. 查询公司基本信息
    Company companyInfo = getCompanyInfo(company);

    // 2. 查询财务数据
    FinancialData financial = getFinancialData(company);

    // 3. 查询行业数据
    IndustryData industry = getIndustryData(company);

    // 4. 分析财务情况
    FinancialAnalysis financialAnalysis =
        analyzeFinancial(financial);

    // 5. 分析行业情况
    IndustryAnalysis industryAnalysis =
        analyzeIndustry(industry);

    // 6. 生成风险分析
    RiskAnalysis risk = analyzeRisk(
        financialAnalysis,
        industryAnalysis
    );

    // 7. 生成报告
    return generateReport(
        financialAnalysis,
        industryAnalysis,
        risk
    );
}
```

这种方式有什么问题？

问题一：流程是写死的。

例如，财务分析完成后，发现数据不完整，需要重新检索财务数据。

原来的代码需要增加大量判断。

问题二：流程不够灵活。

例如：

- 如果用户只想分析财务，不需要执行行业分析。
- 如果风险分析发现重大问题，需要重新分析。
- 如果需要人工审核，则应该暂停工作流。
- 如果报告质量不合格，需要返回指定节点重新执行。

随着需求增加，普通的业务代码会变得越来越复杂。

问题三：没有天然的工作流状态管理机制。

如果 Agent 执行到财务分析阶段，突然服务重启，如何知道执行到了哪一步？

如果用户过一会儿回来，想继续刚才的研报任务，又应该如何恢复？

这些问题就是 LangGraph4j 想解决的。

## 2. LangGraph4j 的核心思想

LangGraph4j 的核心思想可以概括成一句话：

将一个复杂的 Agent 任务拆分成多个节点，通过边连接节点，并通过 State 在节点之间传递数据。

它的核心模型：

<img src="C:\Users\67564\AppData\Roaming\Typora\typora-user-images\image-20261030141952026.png" alt="image-20261030141952026" style="zoom:67%;" />

这几个概念是学习 LangGraph4j 的基础。

后面所有内容都围绕它们展开。

# 二、必须掌握的 8 个核心知识点

我建议按照下面的顺序学习，不要一开始就研究多 Agent、复杂工作流。

## LangGraph4j 学习路线

1. Graph 基础与执行机制

   StateGraph、Node、Edge、compile、invoke

   第一阶段：基础必学

2. State 状态管理机制

   AgentState、State Schema、Channel、Reducer、状态更新

3. 条件路由与循环

   Conditional Edge、Router、循环执行、终止条件

4. Checkpoint 与持久化

   CheckpointSaver、Thread、状态恢复、历史快照

5. 中断与人工介入

   Interrupt、Breakpoints、Resume、Human-in-the-loop

6. 并行执行与异步

   Parallel Branch、CompletableFuture、并发状态合并

7. SubGraph 与模块化

   子图、父子状态、工作流复用

8. Spring AI 集成与生产实践

   LLM、Tool Calling、流式输出、异常处理、可观测性

下面逐个深入。

## 1. Graph 基础与执行机制

这是最基础，也是最重要的一部分。

### 1.1 什么是 Graph？

Graph 就是图。

它由两种主要元素组成：

- Node：节点，负责执行具体任务。
- Edge：边，负责决定节点之间的执行关系。

例如，我们先设计一个非常简单的金融分析工作流：

<img src="C:\Users\67564\AppData\Roaming\Typora\typora-user-images\image-20261030142036766.png" alt="image-20261030142036766" style="zoom:67%;" />

在 LangGraph4j 中，这些节点和关系都需要通过代码定义。

### 1.2 Node：节点

Node 是实际执行任务的地方。

例如：

```java
public class CompanyNode implements NodeAction<AgentState> {

    @Override
    public Map<String, Object> apply(AgentState state) {

        String companyName = state.value("companyName")
                .orElseThrow();

        // 查询公司信息
        Company company = companyService
                .getCompany(companyName);

        // 返回需要更新的状态
        return Map.of(
            "companyInfo", company
        );
    }
}
```

**这里有几个关键点：**

1. Node 可以读取当前 State。
2. Node 可以执行普通 Java 业务逻辑。
3. Node 可以调用 Spring AI。
4. Node 可以调用数据库、向量数据库、外部 API。
5. Node 执行完成后，返回状态更新内容。

**特别注意：**

Node 并不等于 Agent。

一个 Node 可以只是普通 Java 方法，也可以是一个包含 LLM 推理、工具调用的复杂 Agent。

例如：

| Node          | 具体职责         |
| ------------- | ---------------- |
| CompanyNode   | 查询公司基本信息 |
| RetrievalNode | 执行 RAG 检索    |
| FinancialNode | 财务指标分析     |
| IndustryNode  | 行业研究         |
| RiskNode      | 风险识别         |
| ReportNode    | 报告生成         |

你可以把 Node 理解为一个个可组合的业务执行单元。

### 1.3 Edge：边

Edge 决定 Node 执行完成后应该去哪里。

普通 Edge：

```
graph.addEdge("company", "financial");
graph.addEdge("financial", "report");
```

意思是：

```
company → financial → report
```

但真实 Agent 不可能总是按照固定顺序执行。

例如，财务数据分析后，需要判断数据是否完整：

```
财务分析
    |
    v
数据完整性判断
    |
    |--- 完整 ------> 生成报告
    |
    |--- 不完整 ----> 重新检索
```

这就涉及条件边 Conditional Edge，后面我们专门讲。

### 1.4 StateGraph：构建工作流

一个简单的工作流大致是这样的：

```
StateGraph<AgentState> graph =
        new StateGraph<>(AgentState::new);

graph.addNode("company", new CompanyNode());
graph.addNode("financial", new FinancialNode());
graph.addNode("report", new ReportNode());

graph.addEdge(START, "company");
graph.addEdge("company", "financial");
graph.addEdge("financial", "report");
graph.addEdge("report", END);

CompiledGraph<AgentState> compiledGraph =
        graph.compile();
```

这里有两个非常重要的概念：

第一，StateGraph 是工作流定义。

你在这里声明有哪些节点、节点之间有什么关系。

第二，CompiledGraph 是编译后的可执行工作流。

`compile()` 会将工作流定义编译成可运行对象，并检查图结构是否存在问题。

执行时：

```
Map<String, Object> inputs = Map.of(
    "companyName", "贵州茅台"
);

var result = compiledGraph.invoke(inputs);
```

注意，以上是帮助理解的简化示意代码。实际使用时，需要结合你选择的 LangGraph4j 版本、状态类型和具体执行 API。

官方文档对 StateGraph、Node、Edge 和 CompiledGraph 有完整介绍：[LangGraph4j 官方 GitHub README](https://github.com/langgraph4j/langgraph4j/blob/main/README.md?utm_source=chatgpt.com)。

## 2. State：状态管理机制

我认为这是 LangGraph4j 最值得花时间研究的知识点之一。

很多人刚接触 LangGraph4j 时，会觉得它就是把多个 Java 方法串联起来。

实际上，真正让它区别于普通流程编排的，是 State 状态管理机制。

### 2.1 为什么需要 State？

继续刚才的金融研报场景。

假设有三个节点：

- CompanyNode：获取公司信息。
- FinancialNode：分析财务数据。
- ReportNode：生成报告。

CompanyNode 查询到了：

```
{
  "companyName": "贵州茅台",
  "stockCode": "600519"
}
```

FinancialNode 又查询到了：

```
{
  "revenue": 174000000000,
  "netProfit": 90000000000
}
```

那么 ReportNode 如何拿到前面两个节点的数据？

一种做法是：

```
financialNode(companyInfo);
reportNode(companyInfo, financialData);
```

这种方式会产生一个问题：

随着节点越来越多，参数传递会越来越复杂。

LangGraph4j 使用共享 State 解决这个问题。

可以理解为：

共享 State

companyName

贵州茅台

stockCode

600519

financialData

财务数据对象

financialAnalysis

财务分析结果

report

研报内容

所有节点都可以读取自己需要的状态字段，并提交新的状态更新。

### 2.2 AgentState 是什么？

LangGraph4j 中的 `AgentState` 是状态的基础抽象。

可以将它理解为一个共享的 Map：

```
Map<String, Object>
```

每个 Node 都可以从里面读取数据，也可以返回需要更新的数据。

例如：

```
String companyName =
    state.value("companyName").orElse("");
```

更新：

```
return Map.of(
    "financialAnalysis", analysisResult
);
```

### 2.3 State Schema 是什么？

这是接下来必须理解的概念。

State 不只是一个随意存放数据的 Map。

LangGraph4j 允许你为 State 定义 Schema，描述有哪些字段，以及这些字段应该如何处理更新。

例如：

```
Map<String, Channel<?>> schema = Map.of(
    "companyName", Channel.<String>of(),
    "financialData", Channel.<FinancialData>of(),
    "analysisResults", Channel.<List<String>>appender()
);
```

这是概念性示例，具体泛型和构造 API 需要以实际版本为准。

这里涉及一个非常重要的知识点：Reducer。

### 2.4 Reducer：状态更新规则

假设两个 Node 都要更新 `analysisResults`。

Node A：

```
return Map.of(
    "analysisResults",
    List.of("收入增长分析")
);
```

Node B：

```
return Map.of(
    "analysisResults",
    List.of("净利润增长分析")
);
```

问题来了：

如果两个节点更新同一个 State 字段，最终应该保留谁的数据？

这就是 Reducer 需要解决的问题。

Reducer 决定新值如何与旧值合并。

常见思路：

| 更新策略       | 含义               | 适用场景           |
| -------------- | ------------------ | ------------------ |
| Overwrite      | 新值覆盖旧值       | 公司基本信息       |
| Append         | 新值追加到原有集合 | 分析结果、日志     |
| Custom Reducer | 自定义合并逻辑     | 多个分析分支的结果 |

例如：

```
原始 analysisResults：

[]

Node A 更新：

["收入增长分析"]

Node B 更新：

["净利润增长分析"]

最终：

[
  "收入增长分析",
  "净利润增长分析"
]
```

这就是 Append 的作用。

为什么这个知识点对你特别重要？

因为你的金融 Agent 后续很可能需要：

- 财务分析和行业分析并行执行。
- 多个检索节点分别返回检索结果。
- 多个分析节点分别生成结论。
- 最后统一合并结果。

如果不了解 Reducer，就很容易出现状态覆盖、数据丢失等问题。

官方 README 中专门介绍了 `Channel.Reducer`、`Channel.Default` 和 `Channel.Appender`。

GitHub+1



## 3. Conditional Edge：条件路由与循环

这是让 Agent 从固定流程变成动态工作流的关键。

### 3.1 普通 Edge 和 Conditional Edge 的区别

普通 Edge：

```
A → B → C
```

执行顺序固定。

Conditional Edge：

```
       ┌── B
A → 判断
       └── C
```

执行方向由当前 State 决定。

例如：

```
graph.addConditionalEdges(
    "financial",
    state -> {
        Boolean complete = state
            .value("dataComplete")
            .orElse(false);

        return complete
            ? "report"
            : "retrieval";
    },
    Map.of(
        "report", "report",
        "retrieval", "retrieval"
    )
);
```

上面是路由逻辑的示意。

这里的核心思想是：

1. FinancialNode 执行。
2. 更新 State 中的 `dataComplete`。
3. Edge 读取当前 State。
4. 根据条件决定下一个 Node。

注意：条件路由不一定需要 LLM。

有两种常见方式：

方式一：Java 代码判断

例如：

```
if (retrievalCount >= 3) {
    return "manual_review";
}
```

适合确定性规则。

方式二：LLM 判断

例如：

```
请判断当前财务分析结果是否足够完整。

如果完整，返回 COMPLETE。
如果缺少数据，返回 RETRIEVE。
如果存在重大疑问，返回 REVIEW。
```

适合需要语义理解的情况。

实际项目中建议优先使用 Java 规则处理明确条件，将 LLM 用在真正需要语义判断的地方。

### 3.2 循环执行

LangGraph4j 与传统 DAG 工作流一个很大的区别是：它支持循环。

例如：

RetrievalNode

检索财务数据

AnalysisNode

分析检索结果

QualityCheckNode

判断分析质量

合格

生成报告

不合格

返回检索

这就可以实现一个自我检查和修正的 Agent 工作流。

但需要特别注意：

一定要设计最大循环次数。

否则可能出现：

```
检索 → 分析 → 不合格
检索 → 分析 → 不合格
检索 → 分析 → 不合格
...
```

导致无限循环和 Token 浪费。

可以在 State 中设置：

```
retryCount
maxRetryCount
```

由 Java 规则控制重试上限。

## 4. Checkpoint：状态持久化

这是你开发生产级金融 Agent 必须深入掌握的内容。

### 4.1 Checkpoint 是什么？

先看一个场景。

用户提交：

> 帮我生成一份贵州茅台的投资研究报告。

Agent 执行：

```
1. 查询公司信息
2. 获取财务数据
3. 财务分析
4. 行业分析
5. 风险分析
6. 生成报告
```

假设执行到第 4 步，服务器突然重启。

如果没有持久化机制，之前执行的内容可能全部丢失。

Checkpoint 就是解决这个问题的。

可以把 Checkpoint 理解为：

在工作流执行过程中，定期保存某个时刻的 State 快照。

例如：

## 工作流执行历史

1. Checkpoint 1

   company_node 执行完成

   已保存

2. Checkpoint 2

   financial_node 执行完成

   已保存

3. Checkpoint 3

   industry_node 执行完成

   已保存

4. Checkpoint 4

   risk_node 正在执行

   执行中断

服务器恢复后

从最近的有效状态恢复工作流，而不是简单地从头执行。

### 4.2 Thread 是什么？

这个概念非常容易和 Chat Memory 混淆。

Thread 可以理解为一条独立的工作流执行历史。

例如：

| Thread ID  | 对应任务             |
| ---------- | -------------------- |
| thread_001 | 贵州茅台研报任务     |
| thread_002 | 宁德时代研报任务     |
| thread_003 | 用户的另一个分析任务 |

同一个 Thread 会有多个 Checkpoint。

```
thread_001
    |
    |--- checkpoint_001
    |
    |--- checkpoint_002
    |
    |--- checkpoint_003
    |
    |--- checkpoint_004
```

注意：

Thread 不等于用户 ID，也不必然等于 conversationId。

在你的系统里，我建议区分：

| 标识           | 作用                       |
| -------------- | -------------------------- |
| userId         | 用户身份                   |
| conversationId | 对话会话                   |
| threadId       | LangGraph4j 工作流执行线程 |
| taskId         | 一次具体研报任务           |

如果一次会话可能启动多个独立研报任务，就不要简单地把所有任务共用一个 threadId。

### 4.3 CheckpointSaver

LangGraph4j 提供 CheckpointSaver 抽象，可以将状态快照保存到持久化存储。

它有内存实现，也有数据库实现。

你使用 PostgreSQL，因此可以重点研究官方的：

```
langgraph4j-postgres-saver
```

它支持使用 PostgreSQL 持久化工作流状态，涉及 JSONB 状态存储、数据源配置以及状态恢复。

GitHub+1



官方资料：

[PostgreSQL Checkpoint Saver 源码与使用说明](https://github.com/langgraph4j/langgraph4j/blob/main/langgraph4j-postgres-saver/README.md?utm_source=chatgpt.com)

这里还有一个重要问题：

Checkpoint 和长期记忆不是一回事。

结合你的金融 RAG 项目：

- Checkpoint：保存当前 Agent 工作流执行进度。
- Chat Memory：保存对话历史。
- Long-term Memory：保存需要跨会话使用的长期信息。
- RAG Knowledge Base：保存企业研报、公告、财务文档等知识。

这四种数据的生命周期、查询方式和用途都不一样，不建议混在一起。

## 5. Interrupt：中断与人工介入

这个功能在金融研报 Agent 中很有价值。

假设用户要求：

> 分析贵州茅台，并自动生成一份投资研究报告。

Agent 完成所有分析后，准备发布报告。

但是你不希望 AI 自动发布，需要用户确认。

那么可以设计：

```
报告生成
    |
    v
报告质量检查
    |
    v
等待人工审核
    |
    |--- 用户通过 ---> 发布报告
    |
    |--- 用户拒绝 ---> 返回修改
```

这里就涉及 Human-in-the-loop。

### 5.1 Interrupt 的作用

Interrupt 可以让工作流在某个位置暂停，等待外部输入。

例如：

```
// 伪代码：表达中断等待人工审核的业务逻辑
public Map<String, Object> reviewNode(AgentState state) {

    // 暂停工作流，等待人工审批
    // 实际中断 API 请以使用版本的实现为准

    return Map.of();
}
```

需要理解的是：

中断不是简单地让 Java 线程 `sleep()`，也不是把 HTTP 请求一直挂在那里。

它的设计目标是：

1. 工作流执行到指定位置。
2. 保存当前状态。
3. 将需要人工处理的信息返回给业务层。
4. 工作流暂停。
5. 用户提交审批结果。
6. 使用之前的 Thread 恢复执行。

### 5.2 在你的项目中有哪些应用？

我认为至少有三个场景：

| 场景     | 中断原因                         |
| -------- | -------------------------------- |
| 研报生成 | 用户确认是否生成最终报告         |
| 数据冲突 | 两份财报数据不一致，需要人工确认 |
| 投资建议 | 对高风险结论进行人工复核         |

这里要注意一个边界：

人工审核流程最好由你的 Spring Boot 业务层管理用户权限、审核记录和审批接口，LangGraph4j 负责暂停和恢复工作流。

## 6. Parallel Branch：并行执行

这个知识点直接关系到金融 RAG 的性能。

假设你需要分析一家公司的经营情况：

- 财务分析
- 行业分析
- 新闻分析
- 竞争对手分析

这些任务在一定程度上可以独立执行。

如果采用串行方式：

```
财务分析：10 秒
行业分析：8 秒
新闻分析：6 秒
竞争对手分析：9 秒

总耗时：33 秒
```

如果并行执行：

```
                  ┌── 财务分析（10 秒）──┐
                  │                      │
开始 ─────────────┼── 行业分析（8 秒）───┼── 汇总
                  │                      │
                  ├── 新闻分析（6 秒）───┤
                  │                      │
                  └── 竞争对手分析（9秒）─┘
```

理想情况下：

\[ T_{\text{parallel}} \approx \max(10,8,6,9)=10s \]

当然，实际还需要考虑线程调度、数据库连接池、LLM 限流和结果汇总时间。

### 6.1 LangGraph4j 如何实现？

它支持并行分支执行。

你可以把多个节点安排在同一个执行阶段：

```
graph.addEdge(START, "financial");
graph.addEdge(START, "industry");
graph.addEdge(START, "news");

graph.addEdge("financial", "summary");
graph.addEdge("industry", "summary");
graph.addEdge("news", "summary");
```

上面是表达拓扑结构的示意，实际并行分支需要使用框架对应的并行执行机制，而不是仅仅依靠多条普通 Edge 就认为一定会并发执行。

### 6.2 并行执行最大的难点：State 合并

假设：

财务分析节点返回：

```
{
  "financialAnalysis": "营收持续增长"
}
```

行业分析节点返回：

```
{
  "industryAnalysis": "行业竞争加剧"
}
```

新闻分析节点返回：

```
{
  "newsAnalysis": "近期出现监管政策变化"
}
```

那么最后 SummaryNode 如何拿到三个结果？

这又回到了前面学习的 Reducer。

你需要重点研究：

- 多个节点同时更新 State。
- 不同字段如何合并。
- 相同字段发生并发更新怎么办。
- 某一个分支失败，其他分支怎么办。
- 汇总节点如何等待所有必要分支完成。

官方有专门的并行分支教程：

[LangGraph4j Parallel Branch 教程](https://github.com/langgraph4j/langgraph4j/blob/main/how-tos/parallel-branch.ipynb?utm_source=chatgpt.com)。

## 7. SubGraph：子图与模块化

当你的金融 Agent 越来越复杂时，所有节点放在一个 StateGraph 中会非常难以维护。

例如：

```
金融研报 Agent
    |
    |--- 财务分析
    |       |--- 财务数据检索
    |       |--- 指标计算
    |       |--- 财务趋势分析
    |       |--- 财务质量检查
    |
    |--- 行业分析
    |       |--- 行业资料检索
    |       |--- 行业竞争分析
    |       |--- 行业发展趋势
    |
    |--- 风险分析
            |--- 财务风险
            |--- 经营风险
            |--- 行业风险
```

如果全部写在一个 Graph 中，节点可能越来越多。

这时候就可以将一组节点封装成一个 SubGraph。

### 7.1 子图的核心思想

例如，先单独创建一个财务分析工作流：

FinancialSubGraph

财务数据检索

财务指标计算

财务趋势分析

财务质量检查

返回财务分析结果

然后在主工作流中，把整个财务分析工作流当成一个整体来使用。

这样有几个好处：

1. 业务模块更加独立。
2. 子图可以重复使用。
3. 每个子图可以单独测试。
4. 可以降低主工作流的复杂度。

LangGraph4j 支持将编译后的图作为节点使用，也支持不同的子图组合方式。

GitHub+1



官方教程：

[LangGraph4j SubGraph 相关教程](https://github.com/langgraph4j/langgraph4j/tree/main/how-tos?utm_source=chatgpt.com)。

## 8. Spring AI 集成：真正落地到你的项目

这一部分非常重要。

你之前选择的技术分工是：

| 技术                | 负责的事情                        |
| ------------------- | --------------------------------- |
| Spring Boot         | 业务接口、权限、数据库、任务管理  |
| Spring AI           | LLM、Embedding、RAG、Tool Calling |
| LangGraph4j         | Agent 工作流编排                  |
| PostgreSQL/pgvector | 业务数据、向量检索                |
| Redis               | 缓存等                            |
| Kafka               | 异步任务和事件处理                |

我建议继续保持这个边界。

### 8.1 LangGraph4j 不负责什么？

它不是：

- 向量数据库。
- Embedding 模型。
- 文档解析框架。
- 完整的 RAG 检索框架。
- 业务数据库。
- 通用消息队列。

它主要负责的是：

让上述能力按照可控的流程组织起来。

### 8.2 Node 中如何调用 Spring AI？

例如，你有一个 RetrievalNode：

```
@Component
public class RetrievalNode
        implements NodeAction<AgentState> {

    private final VectorStore vectorStore;

    public RetrievalNode(VectorStore vectorStore) {
        this.vectorStore = vectorStore;
    }

    @Override
    public Map<String, Object> apply(AgentState state) {

        String question =
            state.value("question").orElseThrow();

        List<Document> documents =
            vectorStore.similaritySearch(
                SearchRequest.builder()
                    .query(question)
                    .topK(5)
                    .build()
            );

        return Map.of("documents", documents);
    }
}
```

这是一个简化示例，具体 Spring AI API 以你项目采用的版本为准。

可以看到：

- LangGraph4j 决定什么时候执行 RetrievalNode。
- Spring AI 的 VectorStore 执行向量检索。
- 检索结果放回 State。
- 后续节点继续处理。

再比如 LLM 分析节点：

```
@Component
public class FinancialAnalysisNode
        implements NodeAction<AgentState> {

    private final ChatClient chatClient;

    public FinancialAnalysisNode(ChatClient.Builder builder) {
        this.chatClient = builder.build();
    }

    @Override
    public Map<String, Object> apply(AgentState state) {

        String context =
            state.value("financialContext").orElse("");

        String result = chatClient.prompt()
            .system("你是一名专业的金融分析师。")
            .user(context)
            .call()
            .content();

        return Map.of(
            "financialAnalysis", result
        );
    }
}
```

这里要理解一个关键设计原则：

Spring AI 负责完成 AI 能力调用，LangGraph4j 负责决定什么时候调用、调用后去哪里。

两者并不冲突，而是互补关系。

# 三、结合你的金融 RAG，设计一个完整的 LangGraph4j 工作流

前面讲了 8 个知识点，现在我们把它们组合起来。

我建议你最终以这个工作流作为学习项目。

## 金融研报 Agent 工作流

START

接收用户问题

IntentRouterNode

识别任务类型、分析范围

CompanyResolveNode

确定公司主体、证券代码

FinancialSubGraph

财务数据检索与分析

IndustrySubGraph

行业资料检索与分析

NewsSubGraph

新闻与公告分析

CompetitorSubGraph

竞争对手分析

RiskAnalysisNode

综合风险分析

ValuationNode

估值分析

ReportGenerationNode

生成研报

QualityCheckNode

检查数据、结论、引用完整性

合格

END

不合格

返回指定节点重试

这个工作流可以覆盖我们前面学习的：

| LangGraph4j 能力 | 在金融 Agent 中的应用            |
| ---------------- | -------------------------------- |
| State            | 保存用户问题、检索结果、分析结论 |
| Node             | 财务分析、行业分析、风险分析     |
| Edge             | 定义执行顺序                     |
| Conditional Edge | 根据分析结果动态路由             |
| Reducer          | 合并多个分析分支结果             |
| Checkpoint       | 保存研报生成进度                 |
| Thread           | 区分不同的工作流执行历史         |
| Interrupt        | 等待人工审核                     |
| Parallel Branch  | 财务、行业、新闻并行分析         |
| SubGraph         | 封装独立分析模块                 |

这才是你学习 LangGraph4j 最终要实现的东西。

# 四、版本选择：建议你先确认这一点

我查了一下 LangGraph4j 官方仓库，截至 2026 年 10 月，项目已经进入 1.9.x 系列。

官方目前的版本策略是：

| 版本  | 定位                                      | 建议             |
| ----- | ----------------------------------------- | ---------------- |
| 1.9.x | 新功能开发，包含实验性能力，为 2.0 做准备 | 关注新特性       |
| 1.8.x | LTS 维护版本                              | 生产环境优先考虑 |

官方 README 标注了 Java 17+ 的基线要求，并说明 1.8.x 会作为长期维护分支。

GitHub+1



我的建议：

- 如果你当前项目使用 1.8.x，就先按照 1.8.x 学习，不要为了追新版本而升级。
- 如果准备新建学习项目，可以对比 1.8.x 和 1.9.x 的 API，再决定采用哪个版本。
- 尤其要注意 Checkpoint、Interrupt、SubGraph 等功能的版本差异。

官方入口：

- [LangGraph4j 官方文档](https://langgraph4j.github.io/langgraph4j/?utm_source=chatgpt.com)
- [GitHub 源码仓库](https://github.com/langgraph4j/langgraph4j?utm_source=chatgpt.com)
- [PostgreSQL Saver](https://github.com/langgraph4j/langgraph4j/blob/main/langgraph4j-postgres-saver/README.md?utm_source=chatgpt.com)

# 五、我建议采用的实际学习方式

不要一次性把所有知识点看完。

我建议我们采用一种方式：每学完一个知识点，就在一个独立的 Java Demo 中实现，然后逐渐组合成完整的金融 Agent。

## 建议的学习计划

8 个阶段，每个阶段都包含原理、源码/API、代码实践和项目应用。

第一阶段：Graph 基础

优先开始

- StateGraph 的构建
- NodeAction
- EdgeAction
- compile()
- invoke()
- 工作流执行顺序

实践：完成一个公司信息查询工作流。

第二阶段：State 与 Reducer

- AgentState
- State Schema
- Channel
- Default、Appender
- 自定义 Reducer
- 并发状态更新

实践：多个节点共享和更新金融分析数据。

第三阶段：条件路由与循环

- Conditional Edge
- 条件入口
- 动态路由
- 循环执行
- 最大重试次数

实践：实现 RAG 检索质量检查与自动重试。

第四阶段：Checkpoint 与 Thread

- Checkpoint 原理
- CheckpointSaver
- PostgreSQL 持久化
- Thread 管理
- 状态恢复

实践：服务器重启后恢复未完成的研报任务。

第五阶段：Interrupt

- 工作流中断
- 人工审批
- Resume
- 中断状态持久化

实践：研报生成后等待人工审核。

第六阶段：并行执行

- Parallel Branch
- AsyncNodeAction
- CompletableFuture
- 并行结果合并
- 异常处理

实践：财务、行业、新闻并行分析。

第七阶段：SubGraph

- 子图构建
- 子图复用
- 父子状态传递
- 子图持久化

实践：将财务分析封装成独立子图。

第八阶段：Spring AI 集成

- ChatClient
- VectorStore
- Tool Calling
- 流式输出
- SseEmitter
- 生产环境异常处理和可观测性

实践：完成金融研报 Agent。

# 六、正式开始之前，我想先确认你的项目情况

因为你已经在自己的金融 RAG 项目中使用 LangGraph4j，我不希望再给你一个完全脱离项目的玩具教程。

我想按照你当前的实际代码来教学。

1. 你当前使用的 LangGraph4j 版本？

1.8.x

1.9.x

不确定

2. 目前使用哪种 AI 集成方式？

Spring AI

LangChain4j

暂时没有集成

3. 目前使用 LangGraph4j 到什么程度？

还没有正式使用

已经实现了基础 Graph

已经使用了条件路由

已经使用了 Checkpoint 和多节点工作流

4. 希望学习到什么深度？

掌握框架 API 和实际使用

深入源码和执行原理

源码原理 + 金融 Agent 生产级实战

确认，开始系统学习

我个人最推荐的学习深度是第三种：源码原理 + 金融 Agent 生产级实战。

尤其是 State、Reducer、Checkpoint、Thread 这几个知识点，不要只满足于会调用 API。你需要真正搞明白它内部是怎么运行的，才能在后面设计复杂的 Agent 工作流时不踩坑。