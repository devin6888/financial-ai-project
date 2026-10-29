# LangGraph4j 工作机制：把一个 LLM 流程画成图

> 这是一份「读得懂 + 面试讲得出」的机制说明。所有 API 名称、常量、默认值都来自本机对 **langgraph4j 1.9.7** 的实物反编译（`javap`），不是凭印象写的；标注「计划验证」的地方是还没写代码验证的推断。
> 配套文档：`docs/superpowers/plans/ST11-2026-10-28-11-agent-workflow.md`（本项目怎么用它，契约 C17 草案）、`docs/术语表.md`。

## 怎么读这份文档（先认 8 个词）

| 词 | 一句话 | 类比 |
| --- | --- | --- |
| 图（Graph） | 一堆节点 + 有向边组成的执行计划 | 地铁线路图 |
| 状态（State） | 一个 Map，所有节点共享、可存档 | 一块写着进度的白板 |
| 通道（Channel） | 状态里某一个键的「类型 + 默认值 + 合并规则」 | 白板上某一栏的填写规范 |
| 归约器（Reducer） | 新旧两个值怎么合成一个 | 「追加」还是「覆盖」 |
| 节点（Node） | 一个函数：读状态 → 干活 → 返回要改的键 | 地铁一站 |
| 边（Edge） | 「下一步固定去哪」或「按返回值去哪」 | 固定轨道 / 岔道口 |
| 检查点（Checkpoint） | 每走完一个节点就存一次档 | 游戏存档点 |
| 中断（Interrupt） | 到某个节点前暂停，等人决定 | 暂停键 + 等审批签字 |

## 1. 它是什么，不是什么

**是什么**：一个 Java 库，把「多步、可能回退、可能需要人批、可能中途崩溃」的流程写成一张图，然后由它负责调度、存档、恢复。它是 LangGraph（Python）的 Java 生态实现，但代码独立、版本独立（当前 1.9.7）。

**不是什么**（这三句面试常被追问）：

| 常见误解 | 事实 |
| --- | --- |
| 「它是个 LLM 框架」 | 不是。它不碰提示词、不碰模型调用、不碰 token 计费。模型怎么调是你自己的事（我们用 Spring AI） |
| 「它是个 RAG 库」 | 不是。它不切块、不做向量检索。它只负责「什么时候调你的检索」 |
| 「它是个工作流引擎（像 Camunda/Temporal）」 | 也不是。它没有 BPMN 建模、没有定时器、没有人工表单、没有集群调度。它的定位是「进程内的图 + 可插拔存档」 |

一句话总结它的职责：**按图调度节点、按规则合并状态、按检查点存档与恢复**。

## 2. 为什么「一条直线」不够

我们现在的 `ChatService.ask()` 就是一条直线：理解问题 → 解析作用域 → 检索 → 装配上下文 → 调模型 → 四层校验 → 落库。300 行，读起来清楚，跑起来也没问题。

但只要出现下面四种需求，直线代码就开始变形：

| 需求 | 直线代码的写法 | 难受在哪 |
| --- | --- | --- |
| 分支（复杂问题要拆成 3 个子问题各查一遍） | `if` 里再套循环，循环里再套 `if` | 第一次还能读，第三次改就没人敢动 |
| 回退重试（证据不足 → 改写问题 → 再查一次） | 把检索那段抽成方法，外层 `for (int retry = 0; ...)` | 重试次数、改写后的 query、每轮的中间结果都散在局部变量里 |
| 人工审批（生成完先给人看，人点了才发布） | 方法里 `Thread.sleep` 等标志位，或者把状态塞进数据库再起个定时器扫 | 前者占着线程不放，后者等于自己手写一个状态机 |
| 崩溃恢复（审批隔了 10 分钟，服务重启了） | 把每一步的中间结果手动写表，重启后手动读回来、手动判断停在哪一步 | 这就是在重写 LangGraph4j 已经做好的事 |

图模型解决的就是这四件事：**分支写在边上、中间结果写进状态、存档交给检查点、等待交给中断**。

## 3. 六个核心概念（每个都配代码骨架 + 本项目落点）

### 3.1 状态（State）：一块所有节点共用的白板

`org.bsc.langgraph4j.state.AgentState` 本质就是一个 `Map<String, Object>` 的包装类。节点读它、返回「我要改哪几个键」，框架负责把改动合并进去。

```java
public class AnalysisState extends AgentState {

    /** 每个键 = 一个 Channel：默认值 + 合并规则 */
    public static final Map<String, Channel<?>> SCHEMA = Map.of(
            "question", Channels.base(() -> ""),
            "steps",    Channels.appender(ArrayList::new)
    );

    public AnalysisState(Map<String, Object> data) { super(data); }

    public String question() { return this.value("question", ""); }
}
```

为什么状态要「显式声明」而不是随手一个 Map：

1. **能存档**：状态要么能被序列化（默认 JDK 序列化），要么什么都存不下来。
2. **能断言**：测试里直接看 `state.data()`，比翻日志可靠。
3. **能防错**：谁写了哪个键、这个键是覆盖还是追加，都有地方查（就是我们契约里那张「键 → 写入者」表）。

### 3.2 通道与归约器（Channel / Reducer）：这个键怎么合并

这是这个库最容易被忽略、也最能体现设计水平的一层。节点返回的 `Map` **不会直接覆盖状态**，而是走 `Channel.update(key, 旧值, 新值)`。

`Channel` 只有三个能力：

| 方法 | 作用 |
| --- | --- |
| `getDefault()` | 这个键第一次出现时的初始值 |
| `getReducer()` | 新旧值怎么合并（`Reducer<T> extends BiFunction<T,T,T>`） |
| `update(key, oldValue, newValue)` | 真正执行合并的入口 |

现成的两种通道（`org.bsc.langgraph4j.state.Channels` 工厂）：

```java
// 覆盖型：新值直接替换旧值（适合 answer / status / confidence 这种「当前值」）
Channel<String> answer = Channels.base(() -> "");

// 追加型：旧列表 + 新列表（适合 steps / blocks 这种「流水账」）
Channel<List<String>> steps = Channels.appender(ArrayList::new);

// 自定义型：自己决定怎么合（例如重试次数要累加，不是覆盖）
Channel<Integer> retryCount = Channels.base((oldV, newV) -> oldV + newV, () -> 0);
```

`AppenderChannel` 还自带几个「追加语义的变体」，用来处理更细的合并需求：

| 变体 | 语义 |
| --- | --- |
| `ReducerAllowDuplicate` | 允许重复追加 |
| `ReducerDisallowDuplicate` | 重复项不追加 |
| `RemoveByHash` | 追加时把内容相同的旧项去掉 |
| `RemoveIdentifier` | 按某个标识字段去重 |
| `ReplaceAllWith` | 新值整体替换旧列表 |
| `RemoveData` | 追加的同时支持「删除某条」 |

**为什么这层重要**：多个节点可能都往 `steps` 里写，如果没人管合并规则，那最后一步就把前几步的日志冲掉了。追加语义让「过程」可以完整回放；覆盖语义让「结论」永远是最新的。

> 我们踩过的真实教训（阶段 5）：`AnswerService` 里拼 `blocks` 时如果用 `List.copyOf` 覆盖，后一个节点就会把前一个节点补的证据块丢掉。用图的追加通道，这类错误在结构上就不可能发生。

### 3.3 节点（Node）：一个返回「要改的键」的函数

节点的函数签名是 `AsyncNodeAction<S> extends Function<S, CompletableFuture<Map<String, Object>>>`，也就是说：**输入状态，输出一个「补丁」**（不是输出完整状态）。

```java
.addNode("understand", AsyncNodeAction.node_async(state -> {
    QueryUnderstanding qu = router.understand(state.question(), state.turnContext());
    return Map.of(
            "queryType", qu.queryType().name(),
            "companyCodes", qu.companyCodes()
    );
}))
```

三个要点：

1. `node_async(...)` 把普通同步 lambda 包成异步（返回 `CompletableFuture`）；需要拿 `RunnableConfig` 就用 `AsyncNodeActionWithConfig`。
2. **返回空 Map 表示「我不改任何东西」**，不是报错。
3. 节点里抛异常 = 这次运行失败（有 checkpointSaver 时会被记下来，可用 `releaseOnError` 标记线程）。

### 3.4 边（Edge）：固定下一步 / 岔路口

```java
.addEdge(StateGraph.START, "understand")
.addEdge("understand", "plan")

// 岔路口：函数的返回值必须能在 Map 里找到目标
.addConditionalEdges("plan",
        AsyncEdgeAction.edge_async(state -> switch (state.queryType()) {
            case "COMPLEX_ANALYSIS" -> "decompose";
            case "REFUSED" -> "refuse";
            default -> "direct";
        }),
        Map.of("decompose", "retrieve_n",
               "refuse", "respond_insufficient",
               "direct", "retrieve"))
```

为什么要显式声明这张 `Map`：**编译期就能发现「有一条路没接上」**。如果靠 `if` 写，漏一个分支只有真跑到那一刻才知道。

还有一种「节点自己决定去哪」的写法：`addNode(name, AsyncCommandAction, Map)`，节点返回 `action.Command(gotoNode, updateMap)`。它适合「下一步取决于内容而不是分类」的场景（例如模型决定调哪个工具）。我们这期的六步是确定的，用条件边更直观。

### 3.5 编译（compile）：校验 + 冻结 + 打包

```java
CompiledGraph<AnalysisState> app = graph.compile(CompileConfig.builder()
        .graphId("report-qa")                       // 图的名字（日志、图表导出用）
        .checkpointSaver(saver)                     // 存档器，不给就只有内存
        .interruptsBefore(Set.of("answer"))         // 到 answer 之前暂停（HITL）
        .recursionLimit(12)                         // 最多走 12 步，防死循环
        .build());
```

`compile()` 会做图校验（不合法抛 `GraphStateException`），并把配置冻结进 `CompiledGraph.compileConfig`（public final 字段，运行时可读）。

`CompileConfig` 的全部字段（1.9.7）：`graphId`、`checkpointSaver`、`interruptsBefore`、`interruptsAfter`、`releaseThread`、`interruptBeforeEdge`、`recursionLimit`。

### 3.6 两种运行入口

| 入口 | 返回 | 什么时候用 |
| --- | --- | --- |
| `invoke(input, cfg)` | `Optional<State>` | 只要最终状态（跑完一次拿结果） |
| `invokeFinal(input, cfg)` | `Optional<NodeOutput<State>>` | 要最终状态 + 最后是哪个节点 |
| `stream(input, cfg)` | `AsyncGenerator<NodeOutput<State>>` | 每走完一个节点就推一次（**流式展示、阶段进度**都用它） |
| `streamSnapshots(input, cfg)` | 同上，但每次带 `checkpointId` / `nextNodeId` | 要展示「历史版本 / 可以回到哪一步」 |

`NodeOutput` 上有四个东西：`node()`（哪个节点）、`state()`（当前完整状态）、`isSTART()`、`isEND()`。

这正好对上我们阶段 13 的 `stage` 事件：**一个 `NodeOutput` 就是一次「某阶段完成」**，把它翻译成 SSE 的 `stage` 事件即可，不需要在业务代码里手埋点。

## 4. 状态合并规则（这张表要背下来）

| 节点返回 | 结果 |
| --- | --- |
| 不写某个键 | 该键原样保留 |
| 写某个键（覆盖型通道） | 新值替换旧值 |
| 写某个键（追加型通道） | 旧列表 + 新列表 |
| 某个键的值 = `AgentState.MARK_FOR_RESET` | 回到该键的默认值 |
| 某个键的值 = `AgentState.MARK_FOR_REMOVAL` | 该键被移除（`data()` 里查不到） |

另外两条工程规则（写进我们的契约 C17）：

1. **节点只写自己负责的键**（每个键只有一个「写入者」）。多写者 + 追加通道 = 顺序决定结果，测试会变得不可复现。
2. **原始输入永不被改写**（`question` 只读，改写写 `rewrittenQuery`）。这样任何时候都能回答「用户到底问了什么」。

## 5. 线程与运行控制

| 概念 | 说明 | 实测默认值 |
| --- | --- | --- |
| `threadId` | 一次运行（一条会话/一个任务）的身份证，检查点按它归档 | `BaseCheckpointSaver.THREAD_ID_DEFAULT` |
| `recursionLimit` | 最多走多少步（防止条件边形成死循环） | **25**（`CompileConfig` 构造器里 `bipush 25`） |
| `releaseThread` | 跑完后是否释放线程（释放后同一个 threadId 不能续） | **true** |
| `interruptBeforeEdge` | 是否也支持「边之前中断」 | **false** |
| `checkPointId` | 指定从哪个检查点开始 | 空 = 最新 |
| `streamMode` | `VALUES`（每步完整状态）/ `SNAPSHOTS`（带存档信息） | 随入口不同 |

两个实践点：

1. `recursionLimit` 不要设太大。我们的图最长路径是 `understand → plan → retrieve ×3 → verify → retry → retrieve → verify → answer → cite_check`，12 步够用；设 100 只会让死循环更难被发现。
2. 同一个 `CompiledGraph` 可以被多个线程同时 `stream`（每次运行都从自己的状态副本开始，`CompiledGraph.cloneState` 可见）——**计划在 Task 1 spike 里用两个并发 threadId 验证**，验证前不当成定论。

## 6. 检查点（Checkpoint）：存档与续跑

### 6.1 接口只有四个动作

```java
public interface BaseCheckpointSaver {
    Collection<Checkpoint> list(RunnableConfig cfg);
    Optional<Checkpoint> get(RunnableConfig cfg);
    RunnableConfig put(RunnableConfig cfg, Checkpoint cp) throws Exception;
    Tag release(RunnableConfig cfg) throws Exception;
}
```

### 6.2 三个现成实现

| 实现 | 存哪 | 适合 |
| --- | --- | --- |
| `MemorySaver` | 进程内 Map | 单测、演示（重启就丢） |
| `FileSystemSaver` | 本地目录 | 单机调试 |
| `PostgresSaver` | 数据库两张表 | 生产（我们选它） |

`PostgresSaver` 的两张表（库自带 DDL，`createTables(true)` 会自动建）：

| 表 | 关键列 |
| --- | --- |
| `LG4JThread` | `thread_id`、`thread_name`、`is_released` |
| `LG4JCheckpoint` | `checkpoint_id`、`parent_checkpoint_id`、`thread_id`、`node_id`、`next_node_id`、`state_data`（jsonb）、`state_content_type`、`saved_at` |

> 注意 `is_released`：`releaseThread = true`（默认）意味着**跑完就释放**，之后同一个 threadId 不能再续。要做「审批完继续」就必须让它在中断处不被释放，或者用 `releaseThread(false)`。这是我们 ST11 里 D5 要特别确认的一条。

### 6.3 续跑的三种入口

```java
GraphInput.resume();                     // 从最后一次检查点继续，不改状态
GraphInput.resume(Map.of("decision", "APPROVE"));   // 继续，并写入这批键
GraphInput.resume(checkpoint);           // 从指定检查点重放
```

三个常见问题：

| 问题 | 答案 |
| --- | --- |
| 恢复时会不会从头再跑？ | 不会。检查点的粒度是「节点边界」，已完成的节点不重跑，从 `next_node_id` 开始 |
| 中断的那个节点会重跑吗？ | **会**。所以中断点之前的写操作必须幂等（我们的检索是只读，天然幂等；写库的节点要自己防重） |
| 状态太大存得下吗？ | `state_data` 是 jsonb，存的是序列化后的字节（base64）；把 `blocks` 全量塞进状态会让检查点迅速膨胀 → 我们只存 id 与摘要，正文按需回查 |

## 7. HITL（人工介入）：中断与恢复

### 7.1 三种做法，只有一种是对的

| 做法 | 问题 |
| --- | --- |
| 节点里 `Thread.sleep` 等标志位 | 占着线程（虚拟线程也占连接、占内存），重启全丢 |
| 节点里轮询数据库标志位 | 不占线程，但等于自己实现「停在哪一步、怎么回来」，还得处理超时与并发 |
| **中断 + 恢复**（库的方案） | 中断时把状态存档、线程结束；人点了再 `resume`，从存档处继续 |

### 7.2 机制

1. 编译时声明 `interruptsBefore(Set.of("answer"))`（也可以是 `interruptsAfter`）。
2. 运行到该节点**之前**，框架抛 `GraphInterruptException`（**这不是错误，是控制流**，业务代码要单独 catch）。
3. 中断的「为什么停」用 `InterruptionMetadata<State>` 表达：`nodeId()`、`state()`（当前完整状态，前端就靠它渲染草稿）、`reason()`、`metadata(key)`。它由 `PostgresSaver` 一并存档（`javap` 里只有 checkpoint 相关类引用它），**具体从哪个入口取（snapshot 的 metadata 还是 checkpoint 的 state）要在 Task 1 spike 里确认**——这一点不写死，避免照文档写出不存在的 API。
4. 用户点「通过 / 修改 / 驳回」→ 服务端用**同一个 threadId** 调 `stream(GraphInput.resume(Map.of("decision", ...)), cfg)`。

```java
try {
    app.stream(Map.of("question", q), cfg);
} catch (GraphInterruptException e) {
    // 已确认存在：GraphInterruptException.of(Throwable) 可取回中断异常
    // 当前停在哪、状态是什么：app.getState(cfg) / app.getStateHistory(cfg)
    StateSnapshot<AnalysisState> snap = app.getState(cfg);
    approvalService.save(snap.node(), snap.state().data());
    // 立刻返回 WAITING_APPROVAL，不阻塞任何线程
}
```

### 7.3 四条硬规则（血泪向）

1. **中断点必须幂等**：`resume` 会把该节点再跑一遍。
2. **状态里要有 `WAITING_APPROVAL` 标志位**，否则前端只知道「卡住了」，不知道「在等人」。
3. **超时不自动通过**：审批是人的责任，自动通过等于没有审批。超时只能「作废」。
4. **一个 threadId 同时只允许一个未决中断**：两个未决中断会让 `resume` 的目标不确定。

## 8. 序列化：这个库最容易踩的坑

| 序列化器 | 依赖 | 状态里能放什么 |
| --- | --- | --- |
| `ObjectStreamStateSerializer`（**默认**） | 无（JDK） | 任何 `Serializable` 对象 |
| `GsonStateSerializer` | `gson`（库标成 provided） | JSON 友好的结构 |
| `JacksonStateSerializer` | **Jackson 2**（`com.fasterxml.jackson.databind`） | JSON 友好的结构 |

默认序列化器的来源可以实证：`StateGraph(AgentStateFactory)` 构造器内部就是 `new ObjectStreamStateSerializer(stateFactory)`（`javap -c` 可见）。

**我们为什么只用默认的**：本项目是 Spring Boot 4，Jackson 是 3（`tools.jackson`），而 `JacksonStateSerializer` 引的是 `com.fasterxml.jackson.databind`（Jackson 2）——用它就是 `NoClassDefFoundError`。这条在 ST11 Task 1 的 spike 里是第一个要验的断言。

代价与对策：

| 代价 | 对策 |
| --- | --- |
| 状态里不能放 `ChatMessage` 这类第三方对象（不一定可序列化） | 只放 JDK 类型；要传消息就转成 `Map` / `String` |
| 不能放 `float[]`（1024 维向量） | 存 chunkId，正文与向量按需回查 |
| record 能不能放？ | 能，record 只要字段都可序列化就自动可序列化；但**不要把我们的领域 record 直接塞进去**，字段一改历史检查点就反序列化失败 |

## 9. 子图与并行

| 能力 | API | 什么时候值得 |
| --- | --- | --- |
| 嵌子图 | `addNode(name, StateGraph)` / `addNode(name, CompiledGraph)`；包装类 `SubStateGraphNode` / `SubCompiledGraphNode` / `SubGraphNode` | 同一段流程要在两处复用（例如「检索-校验」在问答与报告里都用） |
| 并行 | `ParallelNode`（`ParallelNode.AsyncParallelNodeAction`） | 分支之间**没有数据依赖**且都是 I/O 等待（同时查 3 家公司的年报） |

并行有一个隐藏代价：**合并顺序不确定**。如果分支都往同一个追加通道写，结果顺序会抖，引用编号跟着抖，评测就无法重放。所以我们的 `retrieve ×N` 这期用顺序循环（理由写在 ST11 §2.2.2）。

## 10. 和 Spring AI 的关系（两个现成积木，我们知道但不用）

`langgraph4j-spring-ai`（70,955 字节）提供：

| 类 | 作用 |
| --- | --- |
| `spring.ai.generators.StreamingChatGenerator`（+`Builder`、`Metadata`） | 把 Spring AI 的流式输出包成「节点里可用的流」，边生成边推 |
| `spring.ai.tool.SpringAIToolService`（+`ScopedToolCallResult`、`ExecuteFunctionsResult`） | 把 `@Tool` 方法注册成工具调用，并把结果回灌状态 |
| `spring.ai.serializer.std.SpringAIStateSerializer` | 专门序列化 Spring AI 的消息类型（依赖 Jackson 2 的那套不可用） |
| `prebuilt.MessagesState` / `prebuilt.MessagesStateGraph` | 消息数组式状态（像 OpenAI 的 messages），配消息序列化器 |

我们不用它的三个理由：

1. 我们的状态是**业务字段**（question / scope / blocks / citations），不是消息数组；套 `MessagesState` 等于把业务数据塞进聊天记录。
2. 我们的「工具」只有一个（检索），直接方法调用比注册成 tool 调用更清楚、更可测。
3. 消息类的序列化依赖 Jackson 2，与 Boot 4 冲突。

但面试要能讲**它为什么存在**：`StreamingChatGenerator` 解决的是「流式 token 怎么在图里传递」——因为节点是「输入状态、输出补丁」，而 token 是连续到达的，需要一个通道把中间 token 推出去。

## 11. 预置的 AgentEx（ReAct + 审批）

`org.bsc.langgraph4j.agent.AgentEx` 是库自带的「智能体骨架」，常量就说明了它的形状：

| 常量 | 含义 |
| --- | --- |
| `CALL_MODEL_NODE` | 调模型的节点名 |
| `ACTION_DISPATCHER_NODE` | 根据模型输出决定「调哪个工具 / 结束」 |
| `APPROVAL_ACTION` / `APPROVAL_RESULT` | 审批动作与审批结果 |
| `CONTINUE_LABEL` / `END_LABEL` | 两个终止标签 |
| `ApprovalResultChannelEntry` | 审批结果用的通道定义 |

它适合「开放式任务」：模型自己决定下一步调什么工具、调到什么时候停。我们这期不用它，因为我们的流程是**确定的六步**——固定图更可控、更好测、也更容易解释给面试官听。

什么时候会换：如果将来要做「让模型自己决定查哪张表 / 要不要再查一次财报」，那才是 AgentEx 的场子。

## 12. 把图导出来（面试可以放图）

| 类 | 输出 |
| --- | --- |
| `diagram.MermaidGenerator` | Mermaid 文本（能直接贴进 md，GitHub/JetBrains 都渲染） |
| `diagram.PlantUMLGenerator` | PlantUML 文本 |

好处：**图是从代码生成的**。改了边就重导一次，不会出现「文档里的图和代码不一致」这种答辩现场翻车。

## 13. 十个坑（按踩到的概率排序）

| # | 坑 | 现象 | 原因 | 规避 |
| --- | --- | --- | --- | --- |
| 1 | Jackson 2 序列化器 | `NoClassDefFoundError: com/fasterxml/jackson/databind/...` | Boot 4 用 Jackson 3 | 只用默认 JDK 序列化 |
| 2 | 状态里放了不可序列化的对象 | 第一次跑正常，一启用 checkpoint 就 `NotSerializableException` | 默认序列化器要求 `Serializable` | 状态只放 JDK 类型 / id |
| 3 | `releaseThread` 默认 true | 审批完想 `resume` 却报线程已释放 | 默认跑完即释放 | 需要续跑时用 `releaseThread(false)` 或同一 threadId 不释放 |
| 4 | 中断点不幂等 | 恢复后同一条记录写了两遍 | resume 会重跑该节点 | 写操作带幂等键 / 唯一约束 |
| 5 | 条件边漏声明目标 | `compile()` 抛 `GraphStateException` | Map 里没有这个返回值 | Map 覆盖全部返回值（含默认分支） |
| 6 | 用 `if` 代替条件边 | 图看起来通了，但 `streamSnapshots` 看不到分支 | 分支没进图，检查点不知道有过岔路 | 分支必须写成边 |
| 7 | `recursionLimit` 设太大 | 死循环跑了 90 步才失败，账单很难看 | 默认 25，有人图省事改成 1000 | 按最长路径 + 2 设置 |
| 8 | 并发共用状态 | 两个用户的答案串了 | 状态如果被当成员变量持有 | 每次运行用新的输入 Map + 不同 threadId |
| 9 | 检查点无限增长 | 表几十 GB | 每步都存，永不清理 | 定时清理 + 只存摘要 |
| 10 | 把大对象放状态 | 检查点单行几十 MB，jsonb 写入变慢 | `state_data` 存的是序列化字节 | 状态只放 id，正文按需回查 |

## 14. 与其它方案对比（面试高频）

| 方案 | 定位 | 状态在哪 | 持久化 | 人工任务 | 适合 |
| --- | --- | --- | --- | --- | --- |
| **LangGraph4j** | LLM 流程的图 | 显式 Map（可序列化） | 可插拔（PG/文件/内存） | 原生 interrupt/resume | 多步 LLM 流程、要可恢复的人批 |
| LangGraph（Python） | 同上，生态更成熟 | 同上 | 同上 | 同上 | 同上，Python 栈 |
| Temporal | 分布式工作流引擎 | 代码里的局部变量（靠事件重放重建） | 服务端事件历史 | 支持（signal） | 跨服务、长事务、强一致重试 |
| Camunda / Flowable | BPMN 流程引擎 | 流程变量 | 数据库 | 原生（用户任务） | 业务审批流（人不写代码建模） |
| Spring AI 手写 if-else | 你自己 | 局部变量 | 没有 | 自己写 | 流程稳定、步数少（**我们阶段 5~13 就是这样，也完全够用**） |

我们的口径（面试可以直接说）：**不是所有流程都该上图**。阶段 5 那条直线跑了 8 个阶段没出问题，说明它对「固定六步」是合适的。真正逼我们上图的是三件事——复杂问题要拆子问题并行/串行检索、证据不足要回退重试、生成完要人批。这三件事用直线写，就会变成「手写状态机 + 手写存档」。

## 15. 本项目落地速查（1.9.7 实测）

### 15.1 坐标与体积

| 项 | 值 |
| --- | --- |
| 依赖坐标 | `org.bsc.langgraph4j:langgraph4j-core` / `-spring-ai` / `-postgres-saver`，版本 **1.9.7** |
| jar 体积 | core 319,804 B / spring-ai 70,955 B / postgres-saver 36,165 B |
| 编译级别 | 库自身 `maven.compiler.source=17`（我们跑 21，无问题） |
| 与 Spring AI 的版本关系 | `langgraph4j-parent:1.9.7` 里 `spring-ai.version = 2.0.1`，**与本项目一致** |
| 传递依赖 | `org.bsc.async:async-generator`、`jspecify`、`slf4j-api`；`gson` 与 `jackson-databind` 都是 `provided`（不传递，也不会污染我们） |

### 15.2 我们要写的六个节点

| 节点 | 复用 | 一句话 |
| --- | --- | --- |
| `understand` | `RouterService` | 把问题变成结构化的 QueryUnderstanding |
| `plan` | `QueryUnderstanding.queryType()` | 决定直答 / 拆解 / 拒答（纯规则，不调模型） |
| `retrieve` | `HybridRetriever` + `ContextBuilder` | 检索并装配证据（可被调用 N 次） |
| `verify` | `AnswerResult.confidence` | 判断证据够不够（三条出口） |
| `answer` | `AnswerService.answerStreaming` | 生成答案（复杂路径前有中断） |
| `cite_check` | `CitationValidator` | 编号与引用回查（不通过回 answer） |

### 15.3 两条条件边

| 边 | 出口 |
| --- | --- |
| `plan` 之后 | `direct` / `decompose` / `refuse` |
| `verify` 之后 | `enough` / `retry` / `insufficient` |

## 16. 面试问答 12 题

**Q1：为什么用 LangGraph4j，而不是继续用 if-else？**
一句话：因为需求从「固定六步」变成了「可回退 + 可人批 + 可恢复」。展开：直线代码要把中间结果存在局部变量里，一旦要恢复就得把这些变量全部落库并手写「停在哪一步」的判断；图把这三件事变成框架能力。同时要诚实补一句：**如果流程一直是直的，就不该上图**。

**Q2：状态为什么显式声明，而不是随手一个 Map？**
一句话：为了「可存档、可断言、可追责」。展开：显式状态能序列化（检查点前提）、测试能直接断言、每个键的写入者和合并规则有据可查。

**Q3：Channel 和 Reducer 解决什么问题？举个例子。**
一句话：解决「多个节点写同一个键时怎么合」。例子：`steps` 用追加通道（过程完整保留）、`answer` 用覆盖通道（永远是最新结论）、`retryCount` 用自定义累加（不是覆盖成 1）。如果全是覆盖，第 3 步就会把第 1、2 步的日志冲掉。

**Q4：检查点存在哪？粒度多大？重跑会不会重复执行？**
一句话：存在 `LG4JThread` / `LG4JCheckpoint` 两张表（我们选 PostgresSaver），粒度是**节点边界**，恢复时已完成的节点不重跑、中断的那个节点会重跑。所以中断点必须幂等。

**Q5：HITL 怎么实现？中断期间线程在干什么？**
一句话：编译时声明 `interruptsBefore`，跑到该节点前抛 `GraphInterruptException`，状态存档、**线程直接结束**（不占用任何资源），人点了再 `resume`。

**Q6：为什么不能在节点里 sleep 等审批？**
一句话：会占住线程和数据库连接，重启后全部丢失，还得处理超时与并发。中断+恢复是把「等待」变成「一次数据库写入」。

**Q7：resume 之后从哪继续？**
一句话：从检查点记录的 `next_node_id` 继续；调用方只需传同一个 `threadId` 加 `GraphInput.resume(...)`。

**Q8：状态里为什么不能放 Spring AI 的 ChatMessage？**
一句话：默认序列化器要求 JDK `Serializable`，第三方消息类不保证；而且字段一升级历史检查点就反序列化失败。我们只放 JDK 类型与 id。

**Q9：死循环怎么防？**
一句话：`recursionLimit`（默认 25，我们设 12）+ 条件边必须有终止出口 + 重试计数写在状态里。

**Q10：多个用户同时跑同一张图会串状态吗？**
一句话：不会——每次运行有自己的状态副本与 `threadId`（这一点计划在 spike 里用两个并发 threadId 验证后再对外说）。

**Q11：这个库和自己写状态机比，多给你什么？**
一句话：三样东西——把「分支」变成可校验的边、把「中间结果」变成可序列化的状态、把「等待与恢复」变成检查点。自己写这三样，等于重写一个简化版。

**Q12：如果流程要「模型自己决定下一步调哪个工具」，你怎么改？**
一句话：从「条件边」换成「`AsyncCommandAction` + `Command(goto, update)`」，或者直接用预置的 `AgentEx`（ReAct + 审批）。我们这期不用，因为六步是确定的，固定图更好测。

## 17. 一句话总结

LangGraph4j 不教你怎么调模型，它只解决三个工程问题：**分支写成边、中间结果写成状态、等待写成检查点**。判断该不该用它，标准只有一条——你的流程里有没有「回退、人批、恢复」这三件事中的至少一件；有，它值；没有，一条直线更好维护。
