# 金融研报智能分析 Agent · 开发路线图

> **配套设计文档**：《金融研报智能分析 Agent 系统架构设计 v0.10》
> **建设范围**：按**完整 V1 功能范围**实施——Kafka、Redis、多租户 ACL、LangGraph4j Agent 与 HITL、四层 Memory、混合检索、完整评测集**全部要建**（这本身就是学习目标，砍掉组件等于没学到）。
> **唯一不纠结的**：生产运维指标（容量/SLO、容灾 RPO/RTO）不作为开工前置条件，相关设计保留在设计文档 §4.1、§21.3 作为未来目标。

**目标：** 在本地跑通「上传 PDF → 解析切块 → 混合召回 → 重排 → 带页码引用的回答」这条链路，并在此过程中掌握 Spring AI 的核心抽象。

**架构：** Spring Boot 单应用承载 API 与检索链路；Docling 作为独立 Python 服务只负责「看懂 PDF」；Embedding / Rerank / LLM 全部走阿里云百炼外部 API；元数据与向量统一存 PostgreSQL + pgvector。

**技术栈：** Java 21 · Spring Boot 4.0.x · Spring AI 2.0.x · PostgreSQL 16 + pgvector + zhparser · Docling（Python）· 阿里云百炼（text-embedding-v4 / qwen3-rerank / Qwen LLM）

---

## 阶段划分

按「每个阶段结束时都有可运行、可验证的东西」切分。**一个阶段一份详细实施计划**，不合并成一份巨型计划——否则任何一处返工都要重写整份文档。

| # | 阶段 | 交付物（验证方式） | 依赖 | 状态 |
|---|---|---|---|---|
| **1** | 基础设施与骨架 | 数据库能起来；pgvector 与中文分词经脚本断言可用；Spring Boot 能编译并通过集成测试 | — | 已完成 |
| **2** | 文档解析 | Docling 服务 + Parser Adapter；一份真实年报产出带页码的 NormalizedDocument | 1 | 已完成（`docs/阶段2-解析结果报告.md`） |
| **3** | 切块与入库 | Parent-Child 切块；百炼 text-embedding-v4（1024 维）写入 pgvector | 2 | 已完成（`docs/阶段3-切块与入库报告.md`） |
| **4** | 混合检索 | Dense + Sparse(zhparser) + RRF + qwen3-rerank；给定问题返回排序正确的 chunk | 3 | 已完成（`docs/阶段4-检索报告.md`） |
| **5** | 问答与引用 | 作用域解析 + Context Builder + 结构化答案 + 引用校验；`POST /api/chat` 端到端可问答 | 4 | 已完成（`docs/阶段5-答案生成报告.md`） |
| **6** | 冒烟评测 | 人工核验并记录 Recall@10 与引用准确率 | 5 | 已完成（`docs/阶段6-评测报告.md`） |
| **7** | 异步与任务化 | Kafka + Worker + 幂等 + DLQ；解析链路改为事件驱动 | 2,3 | 已完成（`docs/阶段7-异步任务化报告.md`） |
| **8** | 权限与多租户 | ACL 模型 + tenant 行级隔离 + 越权用例集（验收零泄漏） | 7 | 已完成（`docs/阶段8-权限与多租户报告.md`） |
| **9** | Memory 与会话 | 四层 Memory + Retrieval Gate + 按需 Long-term Memory 召回 | 8 | 计划已写，待确认执行（`ST9-2026-10-27-09-memory-session.md`） |
| **10** | Agent 工作流 | LangGraph4j State/Node/Reducer/Checkpoint + HITL interrupt/resume | 9 | 待写 |

## 阶段边界说明（容易踩的地方）

- **阶段 2 是整条链路风险最高的一环。** 设计文档 §8.5 假设「Parent = 完整语义小节」，但 Docling 实际切出来的章节边界未必如此。**必须先用真实年报验证页码定位与标题层级**，否则阶段 3 的切块策略是空中楼阁。
- **阶段 3 会被两个已核实的限制卡住**：`text-embedding-v4` 单请求最多 10 行；`pgvector` 的 `vector` 类型 HNSW 索引上限 2000 维（所以锁 1024 维，别用默认的 2048）。
- **阶段 4 的 Rerank 没有现成轮子。** Spring AI 官方没有 Rerank 抽象（[#4343](https://github.com/spring-projects/spring-ai/issues/4343)、[#5776](https://github.com/spring-projects/spring-ai/issues/5776) 仍是 feature request），需要自定义接口 + 调百炼原生 rerank API。
- **Sparse 路依赖 zhparser 编译**，是阶段 1 里最可能失败的一步（需要从源码编译 SCWS）。

## 每个阶段的面试可讲点

写这个项目是为了面试，所以每个阶段都要刻意留下「能讲的东西」：

| 阶段 | 可讲的技术点 |
|---|---|
| 1 | 容器化 PostgreSQL 扩展编译（pgvector + zhparser）；用脚本断言代替"我觉得装好了" |
| 2 | **跨语言服务边界**：为什么 Docling 只有 Python 版，Parser Adapter 如何隔离解析引擎 |
| 3 | **Parent-Child Chunking 的取舍**：为什么只给 Child 建向量、Parent 只做上下文扩展；版本化重切与 REEMBED |
| 4 | **Hybrid Retrieval**：Dense 与 Sparse 各自解决什么漏召回；RRF 为什么按排名融合而非分数；**自定义 RerankModel 扩展 Spring AI 抽象边界** |
| 5 | **Spring AI Advisors** 做上下文注入；结构化输出做 Query Understanding；引用可回溯的实现方式 |
| 6 | **量化验证**：不要说"效果不错"，要说"Recall@10 = 0.9x，引用准确率 0.9x" |
| 7 | **事件驱动**：幂等消费者、DLQ、重放；Kafka 与状态机为什么不能互相替代 |
| 8 | **多租户隔离**：RLS + 应用层双重防线、越权负样本测试 |
| 9 | **Memory 分层**：为什么长期记忆要按需召回而不是每轮检索；冲突与遗忘策略 |
| 10 | **Agent 编排**：显式 State + Reducer、Checkpoint、HITL interrupt/resume |

## 两个前置决策（开始阶段 1 之前需要确认）

1. **git 仓库隔离。** 当前仓库根是 `D:/Project/myproject`，其中混有"东江研学营 H5"等无关内容，且 `financial-ai-project/` 尚未被跟踪。建议在 `financial-ai-project/` 内 `git init` 独立成库——**干净的提交历史本身就是面试材料**，混在无关项目里会稀释它。
2. **版本锁定。** 本路线图按 Spring Boot `4.0.8` + Spring AI `2.0.1` 编写；实施时若已发布更新的 patch 版本，替换版本号即可，但**不要跨大版本**。

## 计划文件索引

- 路线图（本文）
- 阶段 1 详细计划：`ST1-2026-10-05-01-infrastructure.md`
- 阶段 2 详细计划：`ST2-2026-10-13-02-document-parsing.md`
- 阶段 3 详细计划：`ST3-2026-10-16-03-chunking-and-ingestion.md`
- 阶段 4 详细计划：`ST4-2026-10-18-04-hybrid-retrieval.md`
- 阶段 5 详细计划：`ST5-2026-10-21-05-context-and-answer.md`
- 阶段 6 详细计划：`ST6-2026-10-22-06-evaluation.md`
- 阶段 7 详细计划：`ST7-2026-10-26-07-async-task.md`
- 阶段 8 详细计划：`ST8-2026-10-27-08-auth-multitenant.md`（9 个任务全部完成；Task 5~9 的执行口径见其 §7 的 R1~R6 修订）
- 阶段 9 详细计划：`ST9-2026-10-27-09-memory-session.md`（9 个任务；§8 有 10 条待确认决策，确认后开工）
- 阶段 10 详细计划：待阶段 9 交付后编写（Agent 工作流的假设依赖会话与 Memory 的实际产出）

## 文档约定

- 所有缩写第一次出现时给「中文全称（English, 缩写）」+ 一句人话解释，解释本身统一放 `docs/术语表.md`，不在各报告里各写一版。
- 每份阶段报告开头放「怎么读这份报告」小节，列出该报告用到的 5~8 个词。
- 写法、结构、检查清单见 `docs/文档写作规范.md`。
- **计划先行（硬规则）**：任何代码改动之前，先把「改哪些文件 / 为什么 / 怎么验收」写进对应阶段计划（在该计划末尾追加 `## 执行前修订` 一节）或在对话里确认方案，**再动手**。方案与计划原文冲突时以修订节为准，原文保留以便对照「为什么改主意」。

## 与设计文档的偏差登记

实施过程中若发现设计文档的假设不成立，**不要偷偷改代码绕过**，登记在这里并回写设计文档：

| 日期 | 设计文档位置 | 原假设 | 实际情况 | 处理 |
|---|---|---|---|---|
| （待填） | | | | |