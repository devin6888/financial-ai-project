# 本项目的工作规则（给未来的会话 / 给未来的自己）

## 测试：改哪个模块跑哪个，不要每次都全量

- 改完先定位「这次改动被哪些测试类覆盖」，只点名跑那 1~3 个类；
- 全量（`mvn -o test`，含 `-Dgroups=db`）只在：改 schema/迁移、改共享组件（图、`AnswerService`、`ContextBuilder`、`ApprovalGate`）、改依赖、阶段收尾；且**一次改动最多一次**；
- **会真调模型的测试必须先问**：`-Dgroups=e2e`、`AnswerEvalTest`、`RetrievalEvalTest`、`HybridRetrievalTest`、`ScopeFilteringTest`。一次 `RetrievalEvalTest` ≈ 54 次精排（约 1.5~2 元），一次 `AnswerEvalTest` ≈ 32 次作答（约 1 元）；
- 汇报结论要写「跑了哪些类」，不要只写「全量绿」。

细节与命令模板见 `docs/测试规范.md` §14（尤其 §14.7）。

## Git：代码写完就暂存

- 每次改完代码 `git add -A`，**不自动 commit / push / 改分支**；
- 复核 `git status --short`，确认没有 `target/`、`.env`、`*.log` 之类。

## 文档：大文件不要「读全文 → 拼字符串 → 整文件覆盖」

- 用带唯一锚点的 `edit`；追加走文件末尾。
- 改完核对**结构连续性**（标题编号、代码围栏个数），因为截断常发生在别处、diff 看起来很干净（事故记录：`docs/superpowers/plans/ST20-2026-10-31-20-tech-debt.md` §8.5.6）。
