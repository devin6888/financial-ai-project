# ST6 · 阶段 6：冒烟评测与量化验收

> 日期：2026-10-22　前置：阶段 5（`docs/阶段5-答案生成报告.md`）
> 目标一句话：**把「效果不错」换成可复现的数字** —— Recall@10、引用准确率、拒答正确率，并给出修改前后的对比。

> 📖 术语：本文出现的缩写（Parent/Child、embedding、RRF、QU、HNSW、e2e……）在 `docs/术语表.md` 有中文全称、为什么需要它、在本项目里长什么样。写法约定见 `docs/文档写作规范.md`。

## 0. 为什么这一阶段值得单独做

阶段 4 的跨文档评测回答了「检索有没有混文档」，阶段 5 的端到端回答了「答案有没有引用」。
两者都只验了**结构**，没回答三个真正会被面试官追问的问题：

1. **该找到的证据，找到了吗？**（Recall@10，按题型分）
2. **引用指向的那一块，真的含答案里的数字吗？**（引用准确率）
3. **没有证据时，它会不会瞎编？**（拒答正确率）

没有这三个数，前面所有工程决策（RRF、rerank、Parent 展开、配额）都只是「我觉得有用」。

## 1. 范围

### 1.1 本阶段做

| # | 交付 |
|---|---|
| 1 | 评测集（golden set）：30 题，JSON 落库，覆盖 6 指标 × 2 年 + 叙述题 + 对比题 + 陷阱题 |
| 2 | 检索层指标 runner：Recall@5/10、DocRecall@10、MRR，**不调 LLM**，可高频跑 |
| 3 | 端到端指标 runner：引用准确率、数值准确率、拒答正确率、期间标注率 |
| 4 | 阶段 5 遗留修复 A：**对比列期间标注**（引用文档年份 ≠ 问题年份时必须留痕） |
| 5 | 阶段 5 遗留修复 B：**对比题 facts 按年份覆盖校验** |
| 6 | 人工核验表生成（Markdown，供逐题打勾） |
| 7 | 消融对比：QU 开/关、Rerank 开/关、Parent 展开开/关 |
| 8 | 回归门槛测试 `EvalGateTest`（阈值不达标即失败） |
| 9 | 报告 `docs/阶段6-评测报告.md` |

### 1.2 本阶段不做

- **不做 LLM-as-judge 的自动打分**：答案对不对必须看数字，自然语言打分不稳定还会掩盖问题；
- 不做多轮指代 / 会话记忆（阶段 9）；
- 不引入 RAGAS / TruLens 等框架：指标只有四个，自己算比配框架快，也更能讲清每个数的口径；
- 不扩语料：仍是 doc 7（2025 年报）与 doc 38（2024 年报）。

## 2. 契约 C8 · 评测集与指标口径

### 2.1 golden set 的字段

`src/test/resources/eval/golden-set.json`（每项）：

```json
{
  "id": "Q01",
  "type": "METRIC",                     // METRIC | NARRATIVE | COMPARISON | TRAP
  "question": "2024年营业总收入是多少？",
  "scope": { "kind": "DOCUMENTS", "documentIds": [38] },
  "expectedDocIds": [38],               // 应当被引用的文档
  "expectedValues": ["11,604,343,090.16"],
  "evidenceChunkIds": [1627],           // SQL 反查出来的、真正含该数值的块
  "expectRefusal": false,
  "note": "2024 年报「主要会计数据」表"
}
```

**两条硬规矩**：

1. `evidenceChunkIds` 必须**由 SQL 反查得到**（`content LIKE '%数值%'` 且 ACTIVE），不能手写 ——
   手写等于用「我以为的证据」测「实际检索」，自欺欺人；
2. 对比列会命中多份文档的块（例如 2024 的数值同时出现在 doc 38 的 1627 与 doc 7 的 818），
   `evidenceChunkIds` 记录**全部**命中块，`expectedDocIds` 记录**应当引用**的那份。两者分开才测得出「混文档」。

### 2.2 指标定义（口径写死，避免各说各话）

| 指标 | 定义 | 分母 |
|---|---|---|
| Recall@K | top-K 检索结果里**至少含一个** `evidenceChunkIds` 的题数 / 题数 | 全部可答题（TRAP 除外） |
| DocRecall@K | top-K 里出现**任一** `expectedDocIds` 的题数 / 题数 | 同上 |
| MRR | 第一个命中证据块的名次倒数的平均 | 有命中的题 |
| 引用准确率 | `citations` 里满足「documentId ∈ expectedDocIds」**且**「该块正文含 expectedValues 之一」的比例 | 全部引用条数 |
| 引用覆盖题数 | 至少有一条正确引用的题数 / 可答题数 | 可答题 |
| 数值准确率 | `answer` 或 `facts.value` 中出现 expectedValues（去千分位比较）的题数 | 可答题 |
| 拒答正确率 | TRAP 题里 sufficient=false 或 answer 明确说明没有该数据的比例 | TRAP 题 |
| 期间标注率 | 引用文档 docYear ≠ 问题年份时，响应带 `evidence_period_mismatch` 的比例 | 跨列题 |

**为什么引用准确率按「条」算再按题平均**：按条算能暴露「一条对三条错」的手忙脚乱；
只看题会把它算成命中。

### 2.3 门槛（`EvalGateTest` 的断言）

| 指标 | 门槛 | 来由 |
|---|---|---|
| Recall@10 | ≥ 0.90 | 30 题里错 3 题以上，说明召回首段就不可信 |
| DocRecall@10 | ≥ 0.90 | 混文档是阶段 4 已定位的最严重问题 |
| 引用准确率 | ≥ 0.90 | 引用错会让用户跳到无关页 |
| 拒答正确率 | = 1.00 | 编数字在金融场景不可接受，必须零容忍 |
| 期间标注率 | = 1.00 | 这是阶段 5 明确留的债，修复后必须 100% |

门槛写在测试里，不写在文档里 —— 文档会过期，测试不会。

## 3. 任务分解

### 依赖图

```
Task 1 (golden set) ──> Task 2 (检索指标) ──┐
        │                                  ├──> Task 7 (消融)
        └──> Task 3 (端到端指标) ──────────┘
Task 4 (期间标注) ─┐
Task 5 (facts 按年) ─┴──> Task 3 依赖这两项才能测「期间标注率」
Task 6 (人工核验表) <── Task 3
Task 8 (门槛测试) <── Task 2 + 3
Task 9 (报告) <── 全部
```

### File Structure

```
src/test/resources/eval/
  golden-set.json                      # Task 1
src/test/java/com/finagent/eval/
  GoldenSet.java                       # Task 1：加载 + 校验（evidence 必须能在库里查到）
  GoldenSetTest.java                   # Task 1：schema + 数值真在块里
  RetrievalMetrics.java                # Task 2：Recall/DocRecall/MRR 纯函数
  RetrievalEvalTest.java               # Task 2：runner（不调 LLM）
  AnswerMetrics.java                   # Task 3：引用准确率/数值准确率/拒答率
  AnswerEvalTest.java                  # Task 3：runner（调 LLM，@Tag e2e）
  HumanReviewExporter.java             # Task 6：生成 Markdown 核验表
  EvalGateTest.java                    # Task 8：门槛断言
src/main/java/com/finagent/answer/
  PeriodLabeler.java                   # Task 4：期间标注（新）
  FactGuard.java                       # Task 5：按年份覆盖校验（改）
docs/阶段6-评测报告.md                  # Task 9
docs/阶段6-人工核验表.md                 # Task 6 产物
```

### 任务细节

**Task 1 · golden set**：30 题 = 12 指标题（6 指标 × 2 年）+ 6 叙述题（章节定位）+ 6 对比题 +
6 陷阱题（2023/2022 单年、库里没有的指标如「员工总数分部门」、错公司代码）。
每题的 `evidenceChunkIds` 用 SQL 反查（`document_chunk` ACTIVE 且 content 含该数值），
`GoldenSetTest` 断言：每个 id 在库里存在、每份 expectedValue 真在 evidence 块正文里。

**Task 2 · 检索指标**：直接调 `HybridRetriever`，`topRerank` 固定 10，`useRerank` 可切。
输出每题一行 + 汇总表；不调 LLM，跑一次 ~15 s。

**Task 3 · 端到端指标**：调 `ChatService.ask`，14~30 次 LLM 调用（约 3 分钟）。
引用准确率按条算、数值准确率按题算、拒答率只算 TRAP。

**Task 4 · 期间标注**：`ChatService` 在答案之后比对「问题年份」与「每条引用的 docYear」，
不一致时加 warning `evidence_period_mismatch:{questionYear}@{docYear}` 并在 note 追加一句。
**不改答案数字** —— 数字是对的，要标的是它的归属期间。

**Task 5 · facts 按年覆盖**：`QueryUnderstanding.yearRange` 跨年（from != to）时，
`FactGuard` 检查每个年份是否至少有一条 fact；缺失记 `fact_year_missing:{year}`。
不重新生成（成本高、收益不确定），先让问题可见。

**Task 6 · 人工核验表**：每题一行：问题 / 作用域 / 答案 / 引用（doc+page+chunk）/ 正确引用前 300 字 /
自动判定（✅/❌）/ 人工结论（空栏，供逐个打勾）。

**Task 7 · 消融**：三组对比（QU 开/关、Rerank 开/关、Parent 展开开/关），只比 Recall@10 与引用准确率。
**必须说明**：关掉 Rerank 后 top-10 会变多（融合直出），所以对比的是「同预算下的有效召回」。

**Task 8 · 门槛测试**：`EvalGateTest` 读 Task 2/3 的输出（内存里直算，不读文件），
断言 §2.3 五条。默认 `@Tag("eval")`，不随 `mvn test` 跑（会烧 token），在 CI/交付前手动跑。

**Task 9 · 报告**：结构沿用阶段 5，但多一节「消融结果」与「人工核验结论」。

## 4. 总体验收

1. `mvn -B test` 全绿（新增 Task 1/2/4/5 的单测，不含 eval/e2e）；
2. `mvn -B test -Dgroups=eval` 全绿（门槛测试）；
3. 30 题评测一次跑完 < 10 分钟；
4. 报告里每个指标都有「口径 + 数字 + 分母」，不出现「效果良好」这类词；
5. 人工核验表 30 行齐全，自动判定与人工判定不一致的必须写明原因。

## 5. 风险

| 风险 | 概率 | 处理 |
|---|---|---|
| 陷阱题「正确拒答」与「拒答过度」混淆 | 中 | 同时统计「可答题被拒答」数，超过 2 题视为回归 |
| LLM 抖动导致数值准确率不稳定 | 高 | temperature 已固定；同一题跑 1 次，报告中注明「单次采样」 |
| 30 题覆盖不足，指标看起来太好 | 中 | 报告里明确写「本评测集只有 30 题，置信区间很宽」；门槛只是回归护栏，不是质量证明 |
| 对比列命中导致 evidenceChunkIds 跨文档 | 高 | 正是设计意图：DocRecall 与引用准确率会同时惩罚「引错文档」 |

## 6. 偏差登记（执行时填）

| # | 计划写的 | 实际做的 | 原因 |
|---|---|---|---|
| 1 | 30 题（12/6/6/6） | **32 题**：METRIC 14 / NARRATIVE 7 / COMPARISON 6 / TRAP 5 | 加 Q31/Q32（2023 年，数值只在 2024 年报的对比列里）——它们是唯一能产生「引用文档年份 ≠ 问题年份」样本的题，否则期间标注率没有分母 |
| 2 | evidenceChunkIds 人工标注 | **SQL 反查**：`content LIKE '%<期望值>%' AND status='ACTIVE' AND chunk_level='CHILD'` | 手写必然漂移；反查保证「标注的块里真的有这个数」，自检用例也才有意义 |
| 3 | Q30「2024年员工总数」是 TRAP | 改成 **NARRATIVE**（证据块 1758，值 5,560） | 实测 2024 年报（doc 38）这块表解析正常，`1,226 / 4,334 / 5,560` 齐全——题目本身标错了。**同一张表在 2025 年报（doc 7）的 946 块只剩重复表头、数值列丢失**，已记入未决问题 |
| 4 | Task 2 只跑生产路径（有 QU） | 每题跑 **QU 与 NO-QU 两道** | 没有基线就说不出「QU 值不值一次 LLM 调用」；实测两道指标完全相同（见报告 §5.1） |
| 5 | AnswerEvalTest 一次跑完 | 支持 `-Deval.resume=true`，逐题落 `target/eval-rows.jsonl` | 全量一次约 4 分钟，被工具超时打断过一次；断点续跑让「改一题重跑」变成几秒 |
| 6 | 只做评测，不改产品代码 | **修了两个评测抓到的真 bug** | ① `RouterService.validate` 静默丢弃未知公司代码 → 问五粮液时用东方财富的数字作答；改为保留代码 + `ChatService` 在检索前拒答（`company_out_of_scope:CODE`）；② `AnswerService` 新增 `ground()` 数字落地校验（全部落空即拒答、单条 fact 落空即丢）。两个 bug 都有单测与回归题 |
| 7 | FactGuard 按「指标」判冲突 | 改成按「指标 + 期间」判冲突 | 写跨年覆盖测试时发现：2024 年报给 2024 数、2025 年报给 2025 数，被当成 `fact_multi_doc_conflict` 降成 low，导致**每道对比题都被降级** |
| 8 | 门槛读代码里的常量 | 读 `target/eval-retrieval.json` / `target/eval-summary.json` | 评测每次结果不同，门槛必须落在同一份结果上。副作用：`EvalGateTest` 与两个评测类**有顺序依赖**（按类名排序它会先跑），不能放进同一条 `-Dtest=` |
| 9 | 期间标注样本充足 | 实际只有 **2 个样本**（Q31/Q32） | 其余题的问题年份与引用文档年份一致，本来就不该标注 |

### 实施结果（2026-10-22 实跑）

- 评测集自检 `GoldenSetTest`：3/3。
- 检索 `RetrievalEvalTest`：27 题 × 2 道，171.8 s。Recall@5/@10 = 1.000、DocRecall@10 = 1.000、MRR 0.779（QU 与 NO-QU 完全一致；名次分布 1 名 17 题 / 2 名 6 题 / 3 名 1 题 / 4~5 名 3 题）。
- 端到端 `AnswerEvalTest`：32 题，243.9 s。引用准确率 1.000、数值准确率 1.000、拒答 5/5、期间标注 2/2、平均 7.4 s（METRIC 7.3 / NARRATIVE 8.1 / COMPARISON 8.9 / TRAP 4.7）。
- 门槛 `EvalGateTest`：2/2。
- 人工核验表已生成：`docs/阶段6-人工核验表.md`（32 行，结论列待人工填）。
- 完整结论与四个发现见 `docs/阶段6-评测报告.md`。

## 7. 未决问题

1. 多轮评测（指代消解、追问）等阶段 9 的 Memory 落地后再设计。
2. 开放域问题的「答案完整性」没有自动指标，只能人工核验。
3. 评测集的年份只有 2024/2025 两年，跨公司（茅台/五粮液）要等新语料。
4. **解析缺陷**：2025 年报（doc 7）的「员工情况」表（946 块）只剩重复表头，`1,226 / 4,334 / 5,560` 等数值列丢失；同版式的 2024 年报（doc 38，1758 块）解析正常。属阶段 2 遗留，需单独排查 Docling 的表格结构识别。
5. **QU 的收益没有证据**：作用域锁定到 1~2 份文档时，QU 与 NO-QU 的检索指标完全相同，而每题多花 2.5~3 s。是否延后到多轮/跨文档场景再启用，等阶段 8 的跨文档评测给数据。
