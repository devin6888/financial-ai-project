# ST17 · 答案版式：从「固定四段」到「自适应章节 + 阅读层级」（2026-10-30）

## 0. 怎么读这份计划

阶段 16 把答案从「一句话」改成了「四段骨架」，数字全对、结构可断言。但用户拿第二个问题实测后给了两条反馈：

> 第一感觉是 ima 回答的内容并不是固定的版式，他非常灵活，而我们的回答好像就限定好了：结论、数据、归因。
> 第二是他的排版布局更好，展示的更清晰，我们的全压缩到一起了

这份计划处理这两条。**不改的东西先说清楚**：引用可点（C7）、数字零幻觉（C21）、系统算派生指标（C21）、
口径诚实（C20）——这些是阶段 16 的成果，一个字都不回退；改的只是「章节怎么写」与「页面怎么排」。

术语见 `docs/术语表.md`。上一份计划：`ST16-2026-10-30-16-answer-quality-facts.md`。
对照原文：`docs/temp/竞品回答2.md`（问题「分析下2024年的营收情况」）。

---

## 1. 现象与证据

### 1.1 同一个问题，两版答案的骨架

我们的（`docs/temp/竞品回答2.md` 第一段）：

```text
结论：东方财富2024年营业总收入为11,604,343,090.16元，较2023年增长4.72%……
数据：| 指标 | 2024年 | 2023年 | 同比增减 |（一张 1 行表）
归因：
### 分行业
### 分服务
### 经营指标归因
口径：单位为人民币元……
```

ima 的（同文件后半）：

```text
## 一、营业总收入总体情况      ← 粗体引导句 + 「> 注：」引用块
## 二、分行业/分产品结构       ← 6 行表（带 ├ └ 层级行）+ 分地区项目符号列表
## 三、核心业务经营数据        ← 「### 1. 证券业务」两个子节，项目符号
## 四、成本与费用情况          ← 4 行表（销售/管理/财务/研发费用）
## 五、总结                    ← 4 条编号结论
```

差别不在「写得好不好」，在**壳**：我们只有三个槽位（数据 / 归因 / 口径），ima 有五个槽位，而且槽位名是按内容起的。

### 1.2 四个缺陷（每条都有实测证据）

| # | 缺陷 | 证据 | 后果 |
| --- | --- | --- | --- |
| D1 | 版式同质：所有问题都被塞进「结论 / 数据 / 归因 / 口径」 | `AnswerPrompts.java:37-45`（骨架）与 `:47-56`（「四个段标签是骨架的一部分，必须保留」） | 取数题、结构题、原因题长得一样；用户一眼看出是模板 |
| D2 | 章节缺位：「经营数据」「成本与费用」「总结」没有槽位 | ima 的第三、四、五节在我们的骨架里无处安放；素材其实在库里：`chunk 1650`（净利润 96.10 亿 +17.29%、投资收益 33.71 亿 +50.79%）、`chunk 1651`（天天基金 160 家 / 20,638 只 / 保有 6,113.62 亿）、`chunk 1665`（销售/管理/财务/研发费用表） | 模型只能把「经营数据」硬塞进「归因」，或者干脆不写 |
| D3 | 视觉密度：层级看不出来 | 前端 `D:/Project/myproject/financial-ai-web/src/components/AnswerText.vue:101` `.para { margin: 0 0 6px }`；`:105-110` `.heading { font-size: 16.5px }` 与正文同号 | 段与段只差 6px，小标题和正文一样大 → 「全压缩到一起」 |
| D4 | 结构判定漏判：「分析…营收情况」不命中任何形态 | 2026-10-30 08:4x 复跑：`stage(plan).detail = {"route":"DIRECT","subQuestions":0}`、`stage(retrieve).detail = {"hits":8,"queries":1,"lanes":["full"]}`；证据 1658/1919/1627/1950/1946/1626/2051/1975 | 不加章节车道（没有「第三节」那一路）、不加【回答要求】，检索面窄、结构也就没有约束 |

另外三个**顺带发现**的问题（都在同一份实测里）：

| # | 现象 | 证据 |
| --- | --- | --- |
| E1 | 有序列表渲染错：连续 3 行「1. 2. 3.」会渲染成三个 `<ol>`，每项都从 1 开始 | `AnswerText.vue:58-67` 每个 block 一个 `<ol>`；`answer.ts:186-189` 每行 push 一个 ordered block |
| E2 | 表格数字全左对齐、不换行 | `AnswerText.vue:138-144`：`text-align: left` + `white-space: nowrap` |
| E3 | 缺口声明可能说错：答案写「证据未披露交易额…」，但同一份年报第 11 页（chunk 1650）就写了股基交易额 | 用户那份答案的「口径：」段；`chunk 1650` 内容含「投资收益及公允价值变动收益 33.71 亿元」等；这条要加校验，不能靠模型自觉 |

### 1.3 什么不该跟着改

- **不许把「结论先行」去掉**：ima 的第一节也是先给结论（116.04 亿 + 4.72%）。没有结论段的答案不可验收。
- **不许把「口径 / 缺口」段去掉**：这是我们比 ima 强的地方（Q4 净利率明确写不可得）。
- **不许恢复模型自己算数**：ima 的「+17.29%」「+50.79%」是从原文抄的，不是它算的；我们自己算的必须继续走【已计算事实】。
---

## 2. 目标与非目标

### 目标（可验收）

1. **三条硬承诺不变**：① 第一段是结论句（含数字与引用）；② 每个数字有出处；③ 最后一段是「注 / 口径」段。
2. **章节由「形态 + 证据」决定**：同一套渲染，能让「分析营收情况」出现 5 节、让「同比增长多少」只有 1 段。
3. **页面看得出层级**：章节标题明显大于正文、段间留白足够、表格数字右对齐、引用块与分隔线可用、有序列表编号正确。
4. **结构可断言**：`stage(plan).detail.shapes` 与答案里的章节数/章节名能被测试读出来，不靠人眼。

### 非目标

- 不做多轮对话的结构继承（阶段 9 的 Memory 已经够用，不属于本阶段）。
- 不做富文本编辑器式的排版（颜色、字号自选、脚注）。
- 不改检索算法本身（车道在 ST16 Task 3 已定；本阶段只把「章节需要什么素材」翻译成车道）。
- 不引入 markdown 库（C22.4 的四条触发条件一条都没命中）。
- 不做「答案长度自选」「风格切换（简洁/详细）」这类产品开关。

---

## 3. 任务清单

### Task 1 · 结构自适应：三承诺 + 章节计划（1 天）

#### 1.1 形态补两个

`src/main/java/com/finagent/understanding/AnswerShape.java` 现在是 `ATTRIBUTION / BREAKDOWN / COMPARISON`。补：

| 新形态 | 触发词 | 为什么需要 |
| --- | --- | --- |
| `OVERVIEW` | 营收情况 / 经营情况 / 整体表现 / 概况 / 业绩 / 分析下…情况 | 本阶段用户问的就是这种（「分析下2024年的营收情况」），现在一条都不命中 |
| `COST` | 成本 / 费用 / 毛利率 / 毛利 / 期间费用 | 「成本与费用」是 ima 的第四节，我们连触发都没有 |

词表仍然**只收明确的词**：「分析」「情况」单独出现不触发（「分析下这个数字」不该变成概览题）。

#### 1.2 章节计划（新增 `AnswerSectionPlan`，2026-10-30 按外部评审加结构）

新类 `src/main/java/com/finagent/understanding/AnswerSectionPlan.java`：

@@@java
public record AnswerSectionPlan(List<Section> sections, String requirement) {

    public record Section(String id, String title, String purpose, String layout, List<String> evidenceNos) {}

    public static AnswerSectionPlan of(Set<AnswerShape> shapes, EvidenceProfile profile, List<String> evidenceHints) { ... }
}
@@@

- `sections`：按顺序的候选章节，最多 5 个；`title` 用于提示词里的 `## 一、xxx`，`purpose` 是给模型的「这节写什么」，
  `layout` 取 `paragraph` / `table` / `mixed`（**建议**，不是必须），`evidenceNos` 是「本节优先看哪几条」（见 1.8）；
- `requirement`：写进【回答要求】的那段话（「本题按概览写，建议章节…，证据里没有素材的章节删掉」）；
- `EvidenceProfile`：证据画像，由检索结果算出来（有没有费用表 / 有没有经营指标 / 有没有多期），决定哪些章节**允许**出现。

形态 → 候选章节（**允许**，不是**必须**）：

| 形态 | 候选章节（顺序即建议顺序） | layout |
| --- | --- | --- |
| OVERVIEW | 总体情况 → 收入结构（分行业/分产品）→ 经营数据 → 成本与费用 → 总结 | paragraph / table / mixed / table / paragraph |
| BREAKDOWN | 结构表 → 分项说明 → 口径 | table / paragraph / paragraph |
| ATTRIBUTION | 驱动因素（按业务线）→ 经营指标 → 口径 | paragraph / mixed / paragraph |
| COMPARISON | 对比表 → 变化说明 → 口径 | table / paragraph / paragraph |

闸门规则（写死在 `AnswerSectionPlan` 里，可单测）：

1. 「成本与费用」只在 `profile.hasCostTable()` 时出现；
2. 「经营数据」只在 `profile.hasOperatingMetrics()` 时出现（交易额 / 保有规模 / 销售额 / 客户数 / 产品数 任一命中）；
3. 「总结」只在候选章节 ≥3 时出现；
4. 交集为空（形态空 且 profile 空）→ 返回空计划，答案回到「一段话 + 口径」。

`EvidenceProfile` 由什么算：`ContextPackage` 的块标题与首行（费用表标题含「销售费用/管理费用/研发费用」；经营指标块含「交易额/保有/销售额/只基金」等）。
放在 `com.finagent.context` 里算，避免 agent 包再依赖 context 内部结构。
#### 1.7 章节计划作为 IR 落盘（2026-10-30 外部评审采纳）

计划不只要驱动本次生成，还要能事后检查：「这题为什么只有两节」「成本节为什么没出现」不能靠猜。

| 落点 | 内容 |
| --- | --- |
| `AnalysisState.ANSWER_PLAN` | 新增通道 `Channels.<List<Map<String,Object>>>base(List::of)`，存 `id/title/purpose/layout/evidenceNos`（全是字符串与数字，可序列化 → 不违反 C18.2「结果不进状态」） |
| `stage(compute).detail` | 加 `plan` 数组（只带 `title`，不带 `purpose`，减少前端无用载荷）；`AgentStreamService.stageEvent` 的 `NODE_COMPUTE` 分支补这个键 |
| `ChatTrace` | `AnswerService` 把整份计划写进 trace（`sections` 全字段），用于复盘与面试演示 |
| 前端 | **不展示**计划，只在 trace 视图里可查（`GET /api/agent/runs/{id}` 的 steps 已能看到 compute 摘要） |

#### 1.8 章节优先证据（软提示，不硬切上下文）

现实问题：阶段 16 Q1 的归因段写成「妙想 AI」，而手续费/利息净收入的块（7531）就在上下文里 —— 20 个块平铺，
模型没有「这节该看哪几块」的提示。

做法（**软**约束）：

- 对每个 `Section`，用「章节关键词 ↔ 块标题/首行」匹配出 0~3 个 `E编号`，写进 `Section.evidenceNos`；
- 【回答要求】里逐节带一行：`三、经营数据：优先看 E5、E7（交易额、保有规模、销售额）`；
- 匹配不到时该行为空，不写「无」；
- **不裁剪证据**：上下文仍全量给模型。硬切分（按章节拆成互不相通的几份）被否掉，理由：父块整段展开 + 邻居补全（`ContextBuilder` 第 3c 步）
  本来就要跨块引用，硬切会让跨节对比失效、token 预算碎片化。

验收：`AnswerSectionPlanTest` 两例（有对应块 → 非空且编号落在上下文范围内；无对应块 → 空）。
#### 1.3 提示词去标签化

`src/main/java/com/finagent/answer/AnswerPrompts.java` 的 SYSTEM 骨架段（现 `:37-45`）与排版段（现 `:47-56`）重写：

- 删「每段以『结论：』『数据：』『归因：』『口径：』开头」「四个段标签是骨架的一部分」；
- 改为：**第一段是结论**（不用标签，直接一句话）；中间章节用 `## 一、标题`（最多 5 节）；最后一段以「注：」开头（**唯一保留的标签**，因为它承担可断言的口径承诺）；
- 允许 `### `子节（≤6 个）、`> 注：…` 引用块、`---` 分隔线、`- `项目符号、`1. `编号；
- 保留：表格规则（3 个以上并列数字必须用表格、≤6 列 12 行、单元格不许换行/嵌套）、[E…] 引用规则、禁止自算数字。
- 补三条风格约束（外部评审 §2 采纳，直接决定「像不像分析报告」）：
  1. **按信息量控制篇幅**：证据多的问题可写长，证据少的问题不要注水；
  2. **不为凑结构编内容**：没有素材的章节直接删，禁止用「该部分未披露」占位凑节数；
  3. **避免机械模板化措辞**：不要每节都用「综上所述」「值得注意的是」开头，不要重复同一句结论。

USER_TEMPLATE 的【回答要求】段由 `AnswerSectionPlan.requirement` 生成，例如：

```text
【回答要求】
本题属于「概览」类问题。建议按下面顺序分节（用 ## 一、二、三 编号），
证据里没有对应素材的章节直接删掉，不要为了凑结构编内容：
一、营业总收入总体情况
二、分行业与分产品结构
三、核心业务经营数据
四、成本与费用情况
五、总结
每节先用一句话给结论，再给数字与表格。
```

#### 1.4 verify 的覆盖检查改口径

`RealAnalysisNodes.verify` 现在只判「归因段存在」（`attributionCovered`）。改为：

- 计划 `sections` 为空 → 不判结构；
- 计划非空 → 答案里出现的 `##` 章节数 ≥ `min(3, sections.size())`，且**结论段非空**、**末段是注/口径段**；
- 缺 → `insufficient=true` + `warnings += "sections_missing:<缺哪节>"`，只触发一次 rewrite（沿用 `retryCount==0` 的规矩，见 ST16 偏差 5）。

#### 1.5 缺口声明落地校验（治 E3）

`AnswerService.evaluate` 之后新增一步 `gapClaims`：

- 正则找「(未披露|未提供|不可得|未列示|无相关数据|未说明)」后面 0~12 字内的指标名（交易额 / 保有规模 / 销售额 / 手续费及佣金 / 利息净收入 / 成本构成 / 研发投入…）；
- 若该指标名在上下文里**出现过** → `warnings += "gap_claim_contradicted:<指标>"`，confidence 降为 low（不拒答）；
- 单测：`AnswerServiceTest` 两例（真矛盾 / 真缺失不报警）。

#### 1.6 验收（Task 1 自己）

- `AnswerShapeTest` +6（OVERVIEW / COST 各 3）；
- `AnswerSectionPlanTest` 8（闸门 4 条 + 形态组合 4 条）；
- `AnswerPromptsTest` 改 3 例（不再要求四段标签、要求 ## 章节、保留注标签）；
- `RealAnalysisNodesTest` +3（章节检查通过 / 缺章节一次改写 / 计划空不判）；
- `AnswerServiceTest` +2（gap_claim_contradicted）；
- `AnswerSectionPlanTest` 再加 2 例：`evidenceNos` 命中上下文编号 / 无对应块时为空；
- `RealAnalysisNodesTest` 再加 1 例：`compute` 节点把计划写进 `ANSWER_PLAN` 且 `stage.detail.plan` 非空。

### Task 2 · 章节素材装配：让「经营数据」「成本与费用」真的有东西写（1 天）

Task 1 只是允许这两节出现；如果检索没给素材，模型还是写不出。这一节负责把素材送到位。

#### 2.1 章节 → 车道（2026-10-30 改为「方向表」，不再写死两路）

`src/main/java/com/finagent/agent/RetrievalLanes.java` 现在按形态加「第三节」一路。改为按**检索方向**加，
方向由「答案形态 + 证据画像」选，**不是每次都跑满**：

| 方向 | 查询词（拼在原问题后） | 触发条件 | 车道 id |
| --- | --- | --- | --- |
| 核心指标 | 不拼，用原问题 | 总是 | `full` |
| 结构 | `分行业 分产品 分地区 占比` | 形态含 BREAKDOWN / OVERVIEW | `dir:structure` |
| 经营数据 | `交易额 保有规模 销售额 客户数` | `profile.hasOperatingMetrics()` | `dir:operation` |
| 成本费用 | `成本 费用 毛利率 期间费用` | `profile.hasCostTable()` | `dir:cost` |
| 盈利 | `净利润 归母 扣非 净利率` | 问题含 净利润 / 盈利 / 利润 | `dir:profit` |
| 同比 | `同比 增减 变动` | 形态含 COMPARISON | `dir:yoy` |

规则（写进 `RetrievalLanes`，可单测）：

1. 方向车道一律 `sectionPrefix = "第三节"`（「第三节 管理层讨论与分析」是经营叙述的所在，D4 的证据全在这里）；
2. 一次最多选 **4** 个方向，按「结构 → 经营数据 → 成本费用 → 盈利 → 同比」优先级截断；
3. 车道总数仍受 `MAX_LANES = 8` 约束（整句 1 + 子问题 ≤3 + 归因 1 + 每年 1 + 方向 ≤4 会超，按「整句 → 子问题 → 方向 → 年份」保序截断）；
4. 方向车道 `topK = 4`，与章节车道一致。

实测依据：这次复跑 8 条证据里 `1650`（经营数据叙述）、`1651`（天天基金）、`1665`（费用表）一条都没进；
它们都在「第三节 管理层讨论与分析 § 四、主营业务分析」。
#### 2.2 事实层补两类

`com.finagent.fact.FactCollector` 现在只吃 TABLE。补：

- **叙述型指标**：从 TEXT 块里用正则抽「(股基|基金)交易额 X 万亿元」「保有规模 X 亿元」「销售额 X 亿元」「上线 X 家 / X 只」→ `ReportedFact`（origin=EXTRACTED，unit 按原文）；
- **费用表**：费用表本身是 TABLE，`TableRowParser` 已能解析；要补的是「单年 + 同比列」这种形状（现在 `latestYear` 依赖两列年份，单年 + 同比列会 skipped，见 ST16 报告 9.8）。

#### 2.3 提示词：每节放什么

【回答要求】里给每节一句「这节放什么」，而不是只给标题：

```text
一、总体情况：一句话结论 + 营收/净利同比
二、结构：分行业、分产品、分地区的金额与占比（用表格）
三、经营数据：交易额、保有规模、销售额、客户数（有几条写几条）
四、成本与费用：销售/管理/财务/研发费用及同比（用表格）
五、总结：3 条以内的判断，每条能指回上面的数字
```

#### 2.4 验收（Task 2）

- `RetrievalLanesTest` +4（两个新车道、上限截断）；
- `FactCollectorTest` +3（叙述型指标 2 + 单年费用表 1）；
- 真库：新增 `OverviewEvidenceDbTest`，断言「分析下2024年的营收情况」的 hits 含 1650 / 1651 / 1665 中的至少 2 条；
- 真库：断言该问题的 lanes 里出现 `dir:operation` 与 `dir:cost`，且 lanes.size() ≤ 8。
### Task 3 · 前端阅读层级（1 天）

#### 3.1 解析器（`src/utils/answer.ts`）

补三类块 + 修一个 bug：

| 项 | 现在 | 改成 |
| --- | --- | --- |
| 引用块 | 不认 | `> 注：…` → `kind: 'quote'` |
| 分隔线 | 不认 | 单独一行 `---` → `kind: 'divider'` |
| 列表 | 每行一个 block（E1，编号永远从 1 开始） | 连续同类行合并成一个 block，`items: Segment[][][]`（项 → 行 → 片段），缩进 2 空格为二级 |
| 表格 | 单元格禁止换行 | 解析层不动；样式层允许换行（见 3.2） |

#### 3.2 样式（`AnswerText.vue`）

| 元素 | 现在 | 改成 |
| --- | --- | --- |
| 正文段 | `margin: 0 0 6px` | `margin: 0 0 14px` |
| 章节标题 `##` | 与正文同号 16.5px | 20px / 600 / 上留白 22px，前置一条细分隔线 |
| 子标题 `###` | 16.5px | 17px / 600 / 上留白 14px |
| 表格 | 全左对齐 + `nowrap` | 数字列右对齐（`tabular-nums`）、隔行 4% 底色、首列 600 字重、允许换行 |
| 引用块 | — | 左侧 3px 竖线 + 浅底 + 14.5px |
| 分隔线 | — | 1px `var(--rule)` + 上下 16px |
| 列表 | 每项一个 `<ol>` | 一个列表一个容器，二级缩进 18px |

#### 3.3 复制为 Markdown（C23）

已有的 `copyAnswer.ts` 是「原文基底 + 空白归一」，新块类型不用改逻辑；但要补：

- 引用块保留 `> ` 前缀（现在会被当成普通段落，粘出去丢了「注」的语义）；
- 分隔线保留 `---`；
- 单测 +3（引用块 / 分隔线 / 有序列表编号不重排）。

#### 3.4 验收（Task 3）

- `answer.spec.ts` 16 → 24（引用块 2 / 分隔线 2 / 列表合并 2 / 嵌套 2）；
- `AnswerText.spec.ts` 3 → 6（有序列表编号 1..3 / 表格数字右对齐 class / 引用块）；
- `copyAnswer.spec.ts` 9 → 12；
- `vue-tsc` 0 错、lint 0 错、ChatView gzip 增幅 ≤ 6 KB（样式换可读性，验收线从 5 KB 放宽到 6 KB）。

### Task 4 · 验收与对照（0.5 天）

#### 4.1 三个固定问题

| # | 问题 | 期望 |
| --- | --- | --- |
| Q1 | 分析下2024年的营收情况 | ≥4 节（总体 / 结构 / 经营数据 / 成本费用），表格 ≥2 张，「注：」段存在，数字全部可回 chunk |
| Q2 | 2025年营业总收入同比增长多少 | 仍然只有一段（不许为了凑结构展开） |
| Q3 | 分析下2024年和2025年的营业总收入，然后看下是什么原因导致的 | 归因类结构（驱动因素 + 经营指标），跨年表格，注段 |

#### 4.2 与 ima 的并排对照

按 §1.1 的六维评（结构自由度 / 章节完整性 / 视觉层级 / 数字可回溯 / 口径诚实 / 排版细节），
结论写进报告，「仍然不如」的项要写清为什么（例如「我们不做外部宏观引用」）。

#### 4.3 回归

| 组 | 命令 | 通过标准 |
| --- | --- | --- |
| 非 db | `mvn -o test "-Dsurefire.excludedGroups=db,e2e"` | 0 失败，总数 ≥ 535 + 本阶段新增 |
| 真库 | `mvn -o test "-Dgroups=db" "-Dsurefire.excludedGroups="` | 0 失败 |
| e2e | `mvn -o test "-Dtest=ChatStreamE2ETest" "-Dgroups=e2e" "-Dsurefire.excludedGroups="` | 通过 |
| 前端 | `npm run test` / `vue-tsc` / `npm run build` | 0 失败 / 0 错 / 通过 |

---

## 4. 验收总表

| # | 验收项 | 标准 | 怎么测 |
| --- | --- | --- | --- |
| A1 | 结构自适应 | Q1 有 ≥4 节，Q2 仍是一段 | 人审 + e2e 抓取 |
| A2 | 章节闸门 | 证据里没有费用表时，答案不出现「成本与费用」节 | `AnswerSectionPlanTest` |
| A3 | 三承诺 | 每份答案第一段含数字与引用、末段是注段 | `AnswerServiceTest` + 人审 |
| A4 | 经营数据可用 | Q1 出现交易额 / 保有规模 / 销售额之一，能回 1650/1651 | 人审 + `OverviewEvidenceDbTest` |
| A5 | 成本费用可用 | Q1 出现费用表或费用数字，能回 1665 | 人审 |
| A6 | 缺口不撒谎 | 「未披露 X」但上下文有 X → `gap_claim_contradicted` | `AnswerServiceTest` |
| A7 | 视觉层级 | 章节标题字号 ≥ 正文 1.15 倍；段距 ≥ 12px | 截图人审 + CSS 断言 |
| A8 | 有序列表编号 | 连续 3 行编号渲染为 1/2/3 | `AnswerText.spec` |
| A9 | 表格可读 | 数字列右对齐；窄屏可横向滚动；表内引用可点 | `AnswerText.spec` + 人审 |
| A10 | 复制保真 | 复制体保留 `##` / `>` / `---` / 表格 / 编号 | `copyAnswer.spec` |
| A11 | 不加依赖 | `package.json` 无 markdown 库；gzip 增幅 ≤ 6 KB | `git diff` + build 输出 |
| A12 | 回归 | 四个组全绿 | §4.3 |
---

## 5. 偏差登记（执行时填）

| # | 计划怎么写 | 实际怎么做 | 为什么 | 影响 |
| --- | --- | --- | --- | --- |
|  |  |  |  |  |

---

## 6. 风险与回滚

| 风险 | 表现 | 兜底 |
| --- | --- | --- |
| 结构放开后模型又开始「一句话」 | 章节不出现，答案退化 | verify 的章节检查触发一次 rewrite；仍不足则 `sections_missing` 告警（不拒答） |
| 章节自由导致答案变长 | 更慢更贵 | 章节数硬上限 5，每节「先一句话结论」；实测记录首字时延 |
| 表格数字右对齐判断错 | 混排（「8,493,612,633.32（84.94亿）」）被判成数字列 | 只在**整列**都是数字/百分数/金额时才右对齐 |
| 引用块与注段重复 | 模型既写「> 注：」又写「口径：」 | 提示词二选一：口径段用「注：」 |
| 前端体积超线 | gzip > 6 KB | 样式优先，必要时去掉分隔线（收益最小） |
| 规则算的章节与问题不匹配 | 例如问「净利润变化」却给了「收入结构」节 | 章节计划只作**建议**，提示词允许删节；verify 只判「章节数 ≥ min(3, N)」不判标题 |
| 以后想让 LLM 参与章节计划 | 多一次调用 + 不可断言 | 折中：规则先给候选集，LLM 只在候选内挑子集并改标题（schema 约束，失败回退规则结果），**默认关闭**，规则版跑满 20 题再评估 |

回滚：本阶段改动只在「提示词 / 新增类 / 前端样式」三层，无 schema 变更、无数据迁移；
回滚 = 还原对应文件（后端 8 个 + 前端 3 个），不影响已入库数据。

---

## 7. 交付物

### 7.1 代码

| 文件 | 动作 |
| --- | --- |
| `src/main/java/com/finagent/understanding/AnswerShape.java` | 加 OVERVIEW / COST |
| `src/main/java/com/finagent/understanding/AnswerSectionPlan.java` | 新增 |
| `src/main/java/com/finagent/context/EvidenceProfile.java` | 新增 |
| `src/main/java/com/finagent/answer/AnswerPrompts.java` | 骨架段重写 |
| `src/main/java/com/finagent/agent/RealAnalysisNodes.java` | verify 章节检查；compute 写 `ANSWER_PLAN` |
| `src/main/java/com/finagent/agent/AnalysisState.java` | 新增 `ANSWER_PLAN` 通道 |
| `src/main/java/com/finagent/answer/AnswerService.java` | gapClaims；计划写进 trace |
| `src/main/java/com/finagent/agent/RetrievalLanes.java` | 章节车道 |
| `src/main/java/com/finagent/fact/FactCollector.java` | 叙述型指标 + 单年费用表 |
| `src/main/java/com/finagent/answer/AnswerService.java` | gapClaims |
| `D:/Project/myproject/financial-ai-web/src/utils/answer.ts` | quote / divider / 列表合并 |
| `D:/Project/myproject/financial-ai-web/src/components/AnswerText.vue` | 层级与表格样式 |
| `D:/Project/myproject/financial-ai-web/src/utils/copyAnswer.ts` | 引用块与分隔线保留 |

### 7.2 测试

后端新增 `AnswerSectionPlanTest`、`OverviewEvidenceDbTest`，改 5 个既有类；前端改 3 个 spec。

### 7.3 文档

- `docs/阶段17-答案版式报告.md`（新）
- `docs/关键契约.md`：**C25 · 答案版式：三承诺 + 章节自由**、**C26 · 阅读层级与渲染子集**（C22 的补充，不是替代）
- `docs/术语表.md`：答案形态（补 OVERVIEW/COST）、章节计划、证据画像
- `README.md`、`docs/superpowers/plans/2026-10-05-demo-roadmap.md`

---

## 8. 契约更新

### 8.1 C25 · 答案版式：三承诺 + 章节自由

> **2026-10-30 按外部评审补一条**：章节计划是**中间表示（IR）**，不是临时字符串。
> 它必须落进 `AnalysisState.ANSWER_PLAN` 与 `ChatTrace`，前端不展示；每节带 `title` / `purpose` / `layout` / `evidenceNos`。
> `evidenceNos` 只是「本节优先看哪几条」的**软提示**，上下文仍全量给模型 —— 不按章节硬切证据（硬切会让跨节对比失效）。

- 25.1 三条硬承诺（结论先行 / 数字有出处 / 末段口径与缺口）不可省；
- 25.2 章节由 `AnswerSectionPlan` 决定，形态 + 证据画像双闸门；章节数 0~5；
- 25.3 章节名用 `## 一、标题`，子节 `### `；**「注：」是唯一保留的段标签**；
- 25.4 缺口声明必须可校验（`gap_claim_contradicted`），写「未披露」前先看上下文；
- 25.5 与 C20 的关系：**C20 的四段骨架作废**，改为三条承诺 + 章节计划；C20 其余内容（形态判定、覆盖检查、排版规则）继续有效。

### 8.2 C26 · 阅读层级与渲染子集

- 26.1 新增可渲染结构：`> 引用块`、`---` 分隔线、嵌套列表（二级）；
- 26.2 字号规则：正文 16.5px / 章节 20px / 子节 17px；段距 14px；
- 26.3 表格：整列数字才右对齐；允许单元格换行；表内 chip 可点；
- 26.4 列表编号必须连续（一个列表一个容器）；解析失败降级为段落（沿用 C22.3）。

---

## 9. 工期估算

| Task | 工期 | 主要不确定性 |
| --- | --- | --- |
| Task 1 结构自适应 | 1 天 | 提示词放开结构后模型是否稳定出章节（要靠 3 轮实测调措辞） |
| Task 2 章节素材 | 1 天 | 叙述型指标正则的准确率（误抽会把「亿元」当数值） |
| Task 3 前端层级 | 1 天 | 列表合并对既有 16 个用例的影响面 |
| Task 4 验收 | 0.5 天 | — |
| 合计 | **3.5 天** | |

---

## 10. 完成定义（DoD）

A1~A12 全绿，且：

1. 同一问题「分析下2024年的营收情况」的答案，章节数 ≥4、表格 ≥2、含「注：」段，数字逐个能回 chunk；
2. 「2025年营业总收入同比增长多少」仍然是一段话（结构没被滥用）；
3. 前端页面截图与 ima 并排，层级与留白不再「压缩到一起」；
4. 全量回归（非 db / db / e2e / 前端三件套）全绿；
5. 报告、契约、README、roadmap 同步，代码已 `git add`（不 commit）。