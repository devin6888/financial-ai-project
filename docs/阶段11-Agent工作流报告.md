# 阶段 11 · Agent 工作流报告

> 执行日期：2026-10-29 ｜ 计划：`docs/superpowers/plans/ST11-2026-10-28-11-agent-workflow.md`（§1~§17）
> 依赖库：LangGraph4j **1.9.7**（core + postgres-saver），其 parent POM 的 `spring-ai.version = 2.0.1` 与本项目一致
> 数字来源：`agent_run_step` 表、`target/stream-timing.json`、`mvn -B test` 输出、headless Chrome 探针 `target/ui-agent-check.js`

## 怎么读这份报告（先看这 8 个词）

| 词 | 一句话解释（完整版见 docs/术语表.md 第 12 节） |
| --- | --- |
| 图 / 节点 | 把分析拆成可调度的步骤，用边规定谁能走到谁 |
| 状态 / 通道 | 节点之间唯一的共享白板；通道决定同一个键被写第二次时怎么合并 |
| 检查点 | 每跑完一个节点存一份状态快照，中断与重启靠它 |
| 中断 / 人批（HITL） | 在 `answer` 之前停住，等 APPROVE / EDIT / REJECT |
| 深度分析 | 前端一个开关：打开走图，关掉走阶段 13 的直线 |
| 恢复（resume） | 带着决定、用同一个 threadId 再跑一次 |
| 身份传递 | `TenantContext` 是 ThreadLocal，图在自己的线程上跑，必须显式搬 |
| 断点续跑 | 进程重启后从最后一个检查点继续，而不是从头再算 |

## 1. 一句话结论

前十个阶段那条「理解 → 检索 → 装配 → 生成 → 校验」的直线，在阶段 11 被搬进一张 **八节点图**：分支、回退、人批、重启续跑四个需求全部落地，
业务逻辑**一行没重写**（节点只是把既有组件包一层）。代价是两条新链路与三类新坑 —— 其中最危险的一条（ThreadLocal 过不去图）在假节点测试里 26 个用例全绿，只有真跑才炸。

- 后端非 e2e：**627 通过 / 0 失败 / 1 跳过**（阶段 10 末 513 → 阶段 11 +114）。
- Agent 相关用例：`AnalysisGraphTest` 11、`AgentRunServiceDbTest` 5、`AgentHitlDbTest` 10、`AgentStreamServiceTest` 2、`AgentStreamE2ETest` 3、`RealAnalysisNodesTest` 10、`GraphSpikeTest` 6、`AgentRunRepositoryTest` 7。
- 前端：vitest **24**、eslint 0 error、`npm run build` 通过；真浏览器一条完整「提问 → 审批 → 通过 → 答案」。

## 2. 为什么还要做这一步

### 2.1 直线跑得挺好，问题在三个做不到

| 需求 | 直线（阶段 5~13）为什么做不到 |
| --- | --- |
| 证据不够时换检索式再试 | 回退要跨越多层方法往回跳；写成 if-else 就是十几处互相调用的私有方法 |
| 复杂分析先看计划再花钱 | 没有停顿点，更没有「暂停—存档—继续」 |
| 进程重启后接着跑 | 状态全在方法栈里，进程一停就没了 |

### 2.2 图怎么解这三件事

```
START → understand → plan ─┬─ DIRECT / DECOMPOSE ─▶ retrieve
                           └─ REFUSE ─────────────▶ respond_insufficient
retrieve → verify ─┬─ ENOUGH ──────────────▶ answer
                   ├─ RETRY（retryCount<1）──▶ rewrite → retrieve
                   └─ INSUFFICIENT ─────────▶ respond_insufficient
answer → cite_check ─┬─ 通过 ────▶ END
                     └─ 不过且未重试 ─▶ answer
```

- **分支**：`plan` 三路（直答 / 拆子问题 / 拒答）、`verify` 三路（够 / 重试一次 / 拒答）、`cite_check` 两路（通过 / 回写一次）。
- **回退**：条件边 + `retryCount` 归约器，重试次数是状态而不是局部变量。
- **人批**：`interruptsBefore("answer")`，停下时把计划与证据发给人。
- **续跑**：每步检查点落 PostgreSQL（`LG4JThread` / `LG4JCheckpoint`），`thread_name = agent_run.id`。

### 2.3 为什么不自己写状态机

`ChatService.ask()` 那套 if-else 已经跑过 9 个阶段，说明「固定步骤」本来够用。真正逼我们上图的是三件事同时出现：**回退 + 人批 + 恢复**。
自己写不是不行，但要自己实现「状态快照 / 条件路由 / 中断恢复 / 序列化」，那正是 LangGraph4j 的全部内容（机制细节见 `docs/LangGraph4j-工作机制.md`）。

## 3. 交付物（九个任务）

| # | 任务 | 关键产物 |
| --- | --- | --- |
| 1 | 依赖与 spike | `pom.xml`（core / postgres-saver 1.9.7）、`GraphSpikeTest` 6 例（推翻「中断抛异常」的假设） |
| 2 | 迁移与表 | `V12__langgraph_checkpoint.sql`（照抄库脚本）、`V13__agent_run.sql`（`agent_run` / `agent_run_step`，均带 RLS） |
| 3 | 状态与图骨架 | `AnalysisState`（19 键）、`AnalysisNodes`、`AnalysisGraph`（fast / hitl 两张图） |
| 4 | 节点接真组件 | `RealAnalysisNodes`、`ScopeMapper`、`AnswerStreamBridge`、`AgentRunService` |
| 5 | 人批 | `resume()`、APPROVE / EDIT / REJECT、超时不自动通过、幂等 |
| 6 | 检查点落库 | `PostgresSaver` + 独立不池化 `DriverManagerDataSource` |
| 7 | REST + SSE | `AgentStreamService`、`AgentController`（stream / resume / get） |
| 8 | 前端 | 「深度分析」开关、`AgentTimeline.vue`、`ApprovalCard.vue`、`api/agent.ts` |
| 9 | 契约与文档 | C17、术语表第 12 节、README、本报告 |

## 4. 契约 C17 摘要（完整见 docs/关键契约.md）

| 条款 | 内容 |
| --- | --- |
| 17.1 | 状态只用 JDK 类型；节点只写自己的键；`question` 永不改写；身份四键是「ThreadLocal 搬运工」 |
| 17.2 | 两张图（编译期 `interruptsBefore` 无法按请求切）；中断不抛异常；必须 `releaseThread(false)` |
| 17.3 | LG4J 两张表由 Flyway 建（本仓库 schema 规则的唯一例外）；检查点连接不复用主池 |
| 17.4 | 三决定与四条硬规则（EDIT/REJECT 必填理由、超时不自动通过、幂等、停下即写库） |
| 17.5 | 事件协议复用 C15/C16，多一个 `approval`；`final` 不是 `ChatResponse` |
| 17.6 | 与 C12 / C15 / C16 的关系 |

## 5. 实测

### 5.1 一次深度分析的节点耗时（真模型真库，`agent_run_step`，总 18.8 秒）

| 节点 | 耗时 | 摘要 |
| --- | --- | --- |
| understand | 6,467 ms | 问题已结构化：EXACT_FACT |
| plan | 136 ms | 单轮检索 |
| retrieve | 1,107 ms | 召回 8 条证据 |
| verify | 281 ms | 证据充足 |
| approval | 0（SKIPPED → OK） | 等待人工审批 → 人工批准 |
| answer | 9,035 ms | 答案已生成（80 字） |
| cite_check | 410 ms | 引用校验：3 条通过 |

**读法**：`understand` 6.5 秒几乎全是 Query Understanding 的那一次 LLM 调用（与阶段 13 的 `quMs=3,385` 同源，这次是冷路径）；`answer` 9 秒是模型流式生成的真实耗时。
图本身的开销可以忽略：`plan` 136 ms + `verify` 281 ms + `cite_check` 410 ms 全是规则判断。

### 5.2 真浏览器一条完整链路（headless Chrome）

```
APPROVAL track=[理解问题, 规划路径, 检索证据, 核对证据, 人工审批] answerText="" plan=5 条
         证据="已检索到 8 条候选证据" 按钮=[通过(可用), 按意见修改(禁用), 驳回(禁用)]
FINAL    track=[…, 人工审批, 生成答案, 校验引用] chips=6 status=已完成 approvalGone=true
         answer=东方财富2025年营业总收入为16,067,741,039.51元，归属于上市公司股东的净利润为12,084,594,947.22元 E1E2E3。
CONSOLE  clean
```

两个细节值得记：审批卡出现时 **`answerText` 是空串**（确实还没生成）；EDIT / REJECT 在没写理由时是 `disabled`（与后端强校验一致）。

### 5.3 测试

| 层 | 结果 |
| --- | --- |
| 非 e2e 全量 | **627 通过 / 0 失败 / 1 跳过**（`DoclingParserAdapterTest` 需解析服务） |
| Agent e2e | `AgentStreamE2ETest` 3（真 HTTP + 真 SSE + 审批 + 404 + 401）、`AgentHitlDbTest` 10（含「换一个图实例仍能 resume」） |
| 前端 | vitest 24、eslint 0 error、build 通过 |

## 6. 抓到的八个问题

### 6.1 身份传不进图（最危险，只有真跑才炸）

- **现象**：第一次真跑 `/api/agent/runs/stream` → `final.status=FAILED`，错误 `CompletionException: GraphRunnerException: ExecutionException: UnauthorizedException: 缺少认证上下文`。
- **原因**：`TenantContext` 是 ThreadLocal；LangGraph4j 在自己的线程上执行节点，ThreadLocal 过不去。假节点测试不查库，所以 26 个用例全绿也照样漏。
- **修法**：身份四键（`TENANT_ID/USER_ID/USERNAME/ROLES`）进状态，`AnalysisGraph.node()` 进入前 `set`、退出后 `clear`；补 `AnalysisGraphTest.nodesRebuildTenantContextFromState` 守两条（进节点有身份、出节点清空）。
- **教训**：「单元测试全绿」不等于「链路能跑」。凡是被框架调度的代码，都要问一句：**它为谁在哪个线程上跑**。

### 6.2 中断不会抛异常

- **现象**：spike 里等 `GraphInterruptException` 等不到，流在 `answer` 前正常结束。
- **原因**：`interruptsBefore` 是「停下来」，不是「抛出来」；异常那条路是节点内部主动中断（`InterruptibleAction`）。
- **修法**：判据改成 `getState(cfg).nextNodeId()` 等于中断点。计划 §7 的表格与 LangGraph4j 文档 §7 已按实测改正。

### 6.3 `releaseThread` 默认 true，跑完就查不到状态

- **现象**：跑到 END 之后 `getState` 抛 `Missing Checkpoint!`。
- **原因**：库默认「跑完释放线程」，对一次性执行合理，对「刷新页面还能看」不合理。
- **修法**：配置里显式 `releaseThread(false)`。

### 6.4 迁移编号凭记忆推 → 全量 127 个错误

- **现象**：`mvn test` 从 574 通过变成 `Errors: 127`，每个 `@SpringBootTest` 都报 `Found more than one migration with version 11`。
- **原因**：`V11` 早被阶段 10 的 `V11__login_and_session.sql` 占用；改名后仍报错，因为 `target/classes` 里留着上次复制的旧文件（资源插件只增不删）。
- **修法**：新迁移前先看 `src/main/resources/db/migration` 实际文件名；加迁移当次跑 `-Pdb` 或全量（`-Pfast` 发现不了）。

### 6.5 `markDone` 把状态当节点写

- **现象**：库里 `current_node= DONE`，不是 `cite_check`。
- **原因**：`markDone(runId, tenant, last.status())` —— 参数传错位置；假 repository 不校验语义，只有真库测试抓到。
- **修法**：循环里单独记 `lastNode`，传它。

### 6.6 连接池打满：`FATAL: sorry, too many clients already`

- **现象**：全量跑到 `RlsEnforcementTest` 连挂 5 个。
- **原因**：`AgentStreamE2ETest` 又多一种 Spring 上下文（共 14 种），每池默认 10 条 > PG 的 100 连接上限。更要紧的是：`TenantDataSourceConfig` 手写 `new HikariDataSource` 绕过了 Boot 自动配置，**`spring.datasource.hikari.*` 从来没生效过**。
- **修法**：`rawDataSource` 上加 `@ConfigurationProperties("spring.datasource.hikari")`；test / rls profile 池压到 4。这条已写进 `docs/测试规范.md` 第 13 节「连接预算」。


### 6.7 拒答结论只写进了备注，没进控制流（阶段 11 收尾时被用户追问发现）

- **现象**：问「五粮液 2025 年净利润」，计划卡上写着「公司不在作用域 → 拒答」，但图继续检索，最后答出「五粮液 2025 年净利润为 12,084,594,947.22 元 [E1]」——这是东方财富的净利润。
- **原因**：`understand` 判出越界并写了 `REFUSE_REASON`，可 `plan` 的条件边只判「问题是否为空」；`retrieve` 拿到空 `companyCodes`，把它当成「用户没点名公司」（= 不加公司过滤），于是在唯一的东方财富语料里正常召回。**拒绝的结论没有变成控制流**，后面每一步都在忠实执行一个错误前提。
- **修法**：plan / retrieve / verify 三处条件边都先看 `REFUSE_REASON`；`answer` 节点开头兜底（拒答原因非空则不调模型）；`ScopeEmptyException` 不再被吞成「检索没命中」，而是保留原因并升级成 `SCOPE_EMPTY`（与 chat 的 409 同义）。契约写进 C17 §17.7。
- **教训**：**「判了吗」和「判了之后去哪」是两件事**。状态里存一个字符串不等于系统会按它行动；凡是「结论性字段」，必须有一条测试从入口跑到出口，断言它真的改变了路径（本次补了 2 条图路由测试 + 2 条节点测试 + 1 条事件测试）。

### 6.8 审批点挂在 answer 上，引用校验重试又弹一次审批

- **现象**：复杂问题在真链路上**批了两次**。时间线出现两行 `approval`：第一次是正常停下等人批，第二次出现在 `cite_check` 之后。
- **原因**：`interruptsBefore("answer")` 对**每次进入** answer 都生效，而 cite_check 发现引用编号越界会沿回边重跑 answer（自动重试），于是重试也撞上中断点。最坏情况（`citeAttempts` 上限 2）要批三次。
- **修法**：新增一个纯透传节点 `approve`（`verify → approve → answer`），中断点挂到它上面；cite_check 的回边仍然指向 `answer`、不再经过 approve。契约 C17 §17.4 第 5 条：**一次运行只批一次**。
- **教训**：**「拦截点」和「业务节点」不要绑在一起**。业务节点可能被重试、回边、幂等重放进入多次；人批要的是「流程进入某阶段的第一次」。凡是把控制流钩子挂在高频复用节点上，都要先问一句：这个节点会被走几次？

## 7. 已知限制

1. 深度分析轮次**不落** `conversation_message`（只在 `agent_run` / `agent_run_step`），刷新页面气泡没了，库里数据仍在。
2. agent 的证据不带 bbox，所以「原文」页签在这些轮次不可用。
3. 打开开关即人批，不区分问题类型（`interruptsBefore` 是编译期配置）。
4. 没有「我的运行」列表与自动重连；`GET /api/agent/runs/{id}` 存在但界面不轮询。
5. 子问题检索是**顺序循环**，不是并行（合并顺序必须可复现，否则引用编号会抖）。
6. plan 节点是规则拆分（按「和 / 与 / 、」切句），不做模型规划 —— 为了不让首屏再多一次 LLM 调用。
7. 图里没有工具调用（没有 `@Tool`、没有 ReAct）：本期是「固定图 + 人批」，不是自主 Agent。

## 8. 验收对照

| # | 计划验收项 | 结果 |
| --- | --- | --- |
| 1 | 六个节点 + 两个终态能跑通 | ✅ 两条路径 + 拒答 + 重试 |
| 2 | 状态只用 JDK 类型、能落检查点 | ✅ 换新图实例仍能 resume（`AgentHitlDbTest` 第 10 例） |
| 3 | 证据不足能改写一次 | ✅ `AnalysisGraphTest.insufficientRewritesOnceThenRecovers` |
| 4 | 引用校验不过能回写一次 | ✅ 两条用例 |
| 5 | HITL 三决定全部生效 | ✅ 9 例 + 前端审批卡 |
| 6 | 超时不自动通过 | ✅ `staleApprovalFailsInsteadOfAutoApproving` |
| 7 | 他人 run 一律 404 | ✅ e2e `foreignRunIsNotFound` |
| 8 | 事件复用 C15/C16 + 新增 approval | ✅ 前端时间线与审批卡 |
| 9 | 前端能开关、能审批、能看到节点轨迹 | ✅ 5.2 实测 |
| 10 | 两条仓库全绿 | ✅ 627 + 24 + build |
| 11 | 拒答必须进控制流（C17 §17.7） | ✅ 越界公司停在 respond_insufficient、无权限用户得到 SCOPE_EMPTY、复杂问题仍走审批 |

## 9. 下一步

1. **阶段 14**：把 agent 轮次落进 `conversation_message`，并让 evidence 带 bbox（原文页签可用）。
2. 「我的运行」列表 + 刷新恢复（`GET /api/agent/runs` 分页 + 前端轮询）。
3. 子问题并行检索（合并顺序用「子问题下标」固定，保证引用编号可复现）。
4. 评测扩容：现在只有 2 份年报 1 家公司，深度分析的价值（多子问题、跨文档）需要更多语料才看得出。
