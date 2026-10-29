# 阶段 12 实施计划：体验补强（流式输出 / 引用原文高亮 / 切块可视化）

> 写作日期：2026-10-28　｜　前置：阶段 1~10 已完成（后端 529 测试全绿，前端四页可演示）
> 背景：`docs/竞品对比与技术债.md` 指出我们与 RAGFlow / FastGPT 的差距集中在「用户看得见」的三项：流式、引用可点开、切块可视。
> 编号说明：Agent 工作流仍是阶段 11（尚未开工）；本阶段按与用户确认的口径编为 **12**，属于「演示前补体验」，做完再回到 11。

## 0. 为什么做这一期

### 0.1 演示场景的三个具体痛点

| # | 现象 | 用户会怎么问 | 后果 |
| --- | --- | --- | --- |
| 1 | 提问后 6~10 秒屏幕完全静止 | 「是不是卡了？」 | 等待被当成故障 |
| 2 | 引用给了页码，但点不开 | 「你这页码是真的吗？」 | 只能口头解释，可信度靠嘴 |
| 3 | 切块只有数据库里的行 | 「你怎么切的？切坏了怎么办？」 | 工程能力无法自证 |

### 0.2 为什么先做这三项（而不是多格式 / 多知识库）

- 三项都是**现有数据的另一种呈现**：不改表、不改检索算法、不动评测集，风险最低；
- 三项都直接作用于「演示的那几分钟」，收益最直观；
- 多格式解析（Word/Excel/OCR）要重跑整条解析链路，属于另一个量级的工作，放 P1。

## 1. 范围

### 1.1 做（本期）

1. `POST /api/chat/stream`：SSE 逐字输出答案，事件含证据清单、增量文本、最终完整响应、错误。
2. `GET /api/documents/{id}/pages/{page}`：把 PDF 某一页渲染成 PNG（带 ACL 校验与 404 语义）。
3. 前端问答页：流式渲染 + 引用点开「原文」tab（页图 + bbox 高亮框）。
4. 前端失败回落：流式失败自动改走 `POST /api/chat`，演示不中断。
5. `GET /api/documents/{id}/chunks`：active 版本的切块树（只读，供可视化）。
6. 前端文档页：切块预览抽屉（Parent → Child，显示类型 / 页码 / token / 前 80 字）。

### 1.2 不做（及理由）

| 不做 | 理由 |
| --- | --- |
| WebSocket | 单向推送用 SSE 就够，WebSocket 要额外握手/心跳/重连状态机 |
| 前端 PDF.js 预览 | 多 1 MB 依赖，还要处理跨域与鉴权；后端渲页一张 ~150 KB，够用 |
| 页内文本选择 / 复制 | 本期只做「看得见的高亮」，选择复制属于阅读器功能 |
| 多格式解析 / OCR | P1，要重跑解析链路 |
| 切块编辑 | 只读预览；编辑要先有「重切影响面分析」 |
| 流式渲染引用卡片逐个出现 | 证据清单在 meta 事件里一次给全，卡片渲染成本可忽略 |
| 断线自动重连（Last-Event-ID） | 答案是一次性生成，重连语义应是「重新提问」，不是续传 |

## 2. 契约 C15（本期新增，落地后写进 `docs/关键契约.md`）

### 2.1 SSE 事件协议

| 事件 | 时机 | data（JSON） | 前端行为 |
| --- | --- | --- | --- |
| `meta` | 检索 + 上下文装配完成、模型开始生成前 | `{traceId, scope:{kind,documentIds}, evidence:[{no,childChunkId,documentId,pageStart,pageEnd,sectionPath,docTitle,evidenceType}], contextTokens}` | 先把证据面板/编号占位渲染出来 |
| `delta` | 模型每吐一段 | `{text}` | 追加到当前回答 |
| `final` | 全部校验完成 | 与 `POST /api/chat` 完全相同的 `ChatResponse` | 用最终对象替换本地消息（含 facts / warnings / trace / messageId） |
| `error` | 任何阶段失败 | `{code, message}`，code ∈ `BAD_REQUEST` / `UNAUTHORIZED` / `SCOPE_EMPTY` / `GENERATION_FAILED` / `INTERNAL` | 显示错误并允许重试 |
| `: ping` | 每 15 秒 | 注释行，无 data | 忽略（防中间层 60 秒超时断流） |

四条硬规则：

1. **`final` 是唯一真相**：前端不得用 delta 拼接结果自行推断引用、置信度、警告；delta 只用于「看到字在动」。
2. **顺序固定**：`meta` 只出现一次且在第一个 `delta` 之前；`final` 或 `error` 有且仅有一个，且必须是最后一个事件。
3. **delta 拼接必须等于 `final.answer`**（后端保证：delta 来自同一次模型输出的 `answer` 字段，不是二次生成）。
4. **错误必须在流内表达**：HTTP 头一旦发出就无法再改状态码，所以失败只能发 `error` 事件；只有「请求体非法 / 未认证」这种发生在开流之前的错误才用 400/401。

### 2.2 流式与结构化输出如何兼得

模型仍然输出阶段 5 定下的 JSON（`sufficient / answer / facts / usedEvidence / confidence / note`），后端在**同一次调用的字节流**里增量提取 `answer` 字段的字符串值推给前端；流结束后再用完整 JSON 走原有的四层校验（事实冲突、引用越界、数字落地、置信度降级）。

为什么不做「流式用纯文本提示词、非流式用 JSON 提示词」：两套提示词等于两套答案质量，评测只覆盖非流式那条，线上就会被流式那条坑；而且纯文本没有 `usedEvidence`，引用只能靠正则猜。

### 2.3 租户上下文必须跨线程传递

`TenantContext` 是 ThreadLocal，`SseEmitter` 的生成逻辑在独立线程执行。**必须在请求线程取出 `CurrentUser`，在生成线程 `TenantContext.set(user)`，结束时 `clear()`**；否则 RLS 会 fail closed 返回 0 行，表现为「流式回答永远说没有证据」。

### 2.4 PDF 页渲染与访问控制

| 规则 | 说明 |
| --- | --- |
| 归属与可见性 | 先按 `document.id` 查行（RLS 保证租户），再过 `ScopeAuthorizer.filterReadable`；任一不过 → **404**（不是 403，不确认存在性） |
| 页码 | 1-based，超出 `page_count` → 404 |
| 渲染 | PDFBox 渲染为 PNG，`scale` 允许 1.0~3.0，默认 1.6（A4 约 952×1347 px） |
| 缓存 | 进程内 LRU，最多 32 页；键 = `documentId:page:scale` |
| 响应头 | `Content-Type: image/png`、`Cache-Control: private, max-age=600`、`ETag`（docId+page+scale+contentHash 前 8 位） |

### 2.5 bbox 坐标约定

`document_chunk.bbox` 已是**归一化 0..1、原点左上**的 `{top,left,right,bottom}`（例：`{"top":0.167,"left":0.095,"right":0.905,"bottom":0.880}`）。前端直接按百分比定位高亮框，**后端不做 pt↔px 换算**；若某块没有 bbox，只显示页图不给框，并提示「该块无坐标」。

## 3. 文件结构

### 3.1 后端新增

| 文件 | 职责 |
| --- | --- |
| `chat/ChatStreamEvent.java` | `record ChatStreamEvent(String event, Object data)` + 静态工厂 `meta/delta/finalEvent/error` |
| `chat/ChatStreamSink.java` | 函数式接口 `void send(ChatStreamEvent)`，让 ChatService 不依赖 Servlet |
| `answer/AnswerFieldExtractor.java` | 增量 JSON 里提取 `answer` 字符串（处理转义与跨 chunk 断开） |
| `document/PdfPageRenderer.java` | PDFBox 渲染 + LRU 缓存 |
| `document/DocumentPageController.java` | `GET /api/documents/{id}/pages/{page}` |
| `document/DocumentChunkController.java` | `GET /api/documents/{id}/chunks` |

### 3.2 后端修改

| 文件 | 改动 |
| --- | --- |
| `pom.xml` | 加 `org.apache.pdfbox:pdfbox`（版本 3.0.x，Boot BOM 不托管，显式写） |
| `answer/AnswerGenerator.java` | 加 `default void stream(system, user, Consumer<String> onDelta)`（默认退化为一次性 complete，测试假实现不用改） |
| `answer/SpringAiAnswerGenerator.java` | 覆写 `stream`，用 `chatClient.prompt()...stream().content()` |
| `answer/AnswerService.java` | 抽出 `evaluate(AnswerDto, qu, pkg)`；新增 `answerStreaming(..., Consumer<String> onDelta)` |
| `chat/ChatService.java` | 抽出 `prepare(request)` 与 `compose(prepared, answer, timings)`；新增 `askStreaming(request, sink)`；`ask()` 改为复用同一套 |
| `chat/ChatController.java` | 加 `POST /api/chat/stream`，返回 `SseEmitter`，用虚拟线程执行器跑生成 |
| `application.yml` | 加 `finagent.stream`（timeout-ms 180000、ping-ms 15000、enabled true） |

### 3.3 前端（仓库 `D:\Project\myproject\financial-ai-web`）

| 文件 | 改动 |
| --- | --- |
| `src/api/chat.ts` | 新增 `askStream(request, handlers)`：fetch + ReadableStream + SSE 帧解析；`onMeta/onDelta/onFinal/onError` |
| `src/api/documents.ts` | 新增 `fetchPageImage(id, page, scale)` → blob URL；`fetchChunks(id)` |
| `src/components/EvidencePanel.vue` | 新增「原文」tab：页图 + bbox 覆盖层 + 缩放 |
| `src/components/ChunkPreviewDrawer.vue` | 新增：Parent 折叠列表 + Child 行 |
| `src/views/ChatView.vue` | 发送改走 `askStream`，delta 追加；失败回落 `ask()`；打开原文时把 citation 传进面板 |
| `src/views/DocumentView.vue` | 详情弹窗加「查看切块」按钮 |
| `src/utils/sse.ts` | 纯函数 `parseSseFrames(buffer)`（可单测） |

## 4. Task 1：SSE 流式输出

### 4.1 后端步骤

1. `AnswerFieldExtractor`：状态机（找 key → 进字符串 → 解码转义 → 遇未转义引号结束）。只认 `"answer"` 后紧跟 `:` 的形式，避免把 `note` 里的单词 answer 当成 key。
2. `AnswerGenerator.stream(...)`：默认方法退化为 `onDelta.accept(complete(...))`；`SpringAiAnswerGenerator` 覆写成真流式。
3. `AnswerService`：抽出 `evaluate(dto, qu, pkg)`（把现有 79~147 行原样搬进去，行为不变）；新增 `answerStreaming`：`pkg` 空 → 直接 refuse（不调模型）；否则边流边提取、结束后 `parser.parse(全文)` → `evaluate`；解析失败 → 与现有 `answer()` 相同的降级（`confidence=error` + `answer_generation_failed`）。
4. `ChatService`：把 87~199 行拆成
   - `prepare(request)` → `record Prepared(conversation, question, scope, memory, qu, pkg, contextMs, retrievalWarnings, quWarnings, memoryWarnings, refusal)`；
   - `compose(prepared, answer, answerMs)` → 期间标注 + 结构化上下文 + 合并警告 + trace + `ChatResponse`（与现在 167~198 行逐行等价）；
   - `ask()` = prepare → answer → compose → finishTurn；`askStreaming()` = prepare → `meta` → `answerStreaming`（delta 转发）→ compose → `final` → finishTurn。拒答/blocked 路径也要发 `meta`? **不发**，直接 `final`（没有证据可展示）。
5. `ChatController.stream`：取 `CurrentUser` → `executor.execute` → `TenantContext.set` → `chatService.askStreaming` → `emitter.complete()`；异常 → `error` 事件 + complete；`onTimeout`/`onError` 里 complete。
6. 心跳：`ScheduledExecutorService` 每 15 秒 `emitter.send(SseEmitter.event().comment("ping"))`，结束就取消。

### 4.2 前端步骤

1. `parseSseFrames(text)`：按 `\n\n` 切帧，忽略 `:` 注释行，解析 `event:` 与多行 `data:`。
2. `askStream`：`fetch('/api/chat/stream', {method:'POST', headers:{Authorization, 'Content-Type':'application/json'}, body, signal})`；读流 → 累计 buffer → 切帧 → 分发；`final` 后主动 `reader.cancel()`。
3. `ChatView.send`：先 push 空助手消息（`streaming: true`）→ `onMeta` 设证据 → `onDelta` 追加 `text` 并滚动到底（仅当用户已在底部）→ `onFinal` 用完整对象替换 → `onError` 显示错误；catch 里**只回落一次**到 `ask()`。
4. `MessageBubble`：`streaming` 时末尾显示一个细光标；`pending` 骨架与流式互斥。

### 4.3 验收

| # | 验收 | 方法 |
| --- | --- | --- |
| 1 | delta 拼接 === final.answer | 单测（假 generator 分 3 段吐出 JSON） |
| 2 | 事件顺序 meta → delta+ → final | `ChatServiceStreamingTest` 收集事件序列 |
| 3 | 无证据时只发 final（拒答），不发 delta | 同上（pkg 空） |
| 4 | 跨线程租户不丢 | e2e：登录 → 流式提问 → 断言有引用（否则会是 0 行证据） |
| 5 | 首字延迟 < 2.5 s | e2e 打印 `firstDeltaMs`（真模型，目标值而非门禁） |
| 6 | 流式失败自动回落 | 前端单测：mock fetch 抛错 → 断言调用了 `ask()` |

### 4.4 测试清单

- `AnswerFieldExtractorTest`（8）：单 chunk 完整 / 跨 chunk 断开在 key 中间 / 断开在转义中间 / `\u4e2d` 转义 / 引号转义 / 无 answer 字段 / note 里出现 answer 字样 / 空输入。
- `AnswerServiceStreamingTest`（3）：正常流 → delta 拼接等于 answer 且校验同非流式 / pkg 空 → 不调模型 / 模型输出非法 JSON → error 降级。
- `ChatServiceStreamingTest`（3）：事件顺序 / 拒答无 delta / 警告合并与非流式一致。
- `ChatStreamE2ETest`（1，`@Tag("e2e")`）：真模型流式提问，断言 final 有引用且 delta 非空。

## 5. Task 2：引用点开原文并高亮

### 5.1 后端步骤

1. `pom.xml` 加 PDFBox（`pdfbox` 3.0.x；渲染不需要 `pdfbox-tools`）。
2. `PdfPageRenderer`：`byte[] render(long documentId, int page, float scale)`；内部用 `PDDocument.load(byte[])`（`MemoryUsageSetting.setupTempFileOnly()`，防大 PDF 吃堆）+ `PDFRenderer.renderImageWithDPI(page-1, 96*scale/1.0)`；LRU 32 项（`LinkedHashMap` accessOrder + synchronized）。
3. `DocumentPageController`：路径 `/{id}/pages/{page}`；校验顺序 = 文档存在（RLS）→ `filterReadable` → page 在 1..pageCount → 渲染 → 200 PNG；任一失败 404 `{"error":"NOT_FOUND"}`。PDF 字节从 `ObjectStorage.get(objectKey)` 取，进程内再缓存 4 份 PDF 字节（LRU）。
4. 认证：路径以 `/api/` 开头，过滤器自动生效；无 token → 401。

### 5.2 前端步骤

1. `fetchPageImage`：`fetch` 带 Bearer → `blob()` → `URL.createObjectURL`；组件卸载/切换页时 `revokeObjectURL`。
2. `EvidencePanel` 的「原文」tab：`<img>` 铺满宽度 + 绝对定位高亮框（`left/top/width/height` 用 bbox 百分比）；框内半透明朱砂描边 + 外发光；`scale` 提供 1.0/1.6/2.2 三档。
3. 无 bbox 的引用：显示页图 + 顶部提示「该证据块没有坐标，只能定位到页」。
4. 加载态：`el-skeleton`；失败态：可点「重试」。

### 5.3 验收

| # | 验收 | 方法 |
| --- | --- | --- |
| 1 | 有权限的页返回 200 且是合法 PNG | `PdfPageRendererTest` 用 `docs/东方财富2025年年度报告.pdf` 渲第 7 页，断言 `PNG` 魔数与宽高 |
| 2 | 跨租户 / 无权限 / 越界页一律 404 | `DocumentPageControllerTest` 3 例 |
| 3 | 前端高亮框落在 bbox 位置 | headless：读 `getBoundingClientRect()` 与页图尺寸算比例，误差 < 1% |
| 4 | 页图不泄漏对象键 | 404 响应体不含 `samples/`、不含标题 |

## 6. Task 3：切块可视化（P1，期中若时间不够可单独开一期）

1. `GET /api/documents/{id}/chunks?version=`：默认 `active_chunk_version`；返回 `{version, parents:[{id, sectionPath, chunkIndex, tokenCount, pageStart, pageEnd, children:[{id, chunkType, pageStart, tokenCount, preview, caption}]}]}`，上限 500 个 Child，超出截断并给 `truncated:true`。
2. `ChunkPreviewDrawer.vue`：左侧 Parent 列表（section_path + token），右侧 Child 卡片（类型胶囊 / 页码 / 前 80 字）；顶部显示版本号与「这是当前生效版本」。
3. 验收：切块树与数据库计数一致（Parent 104 / Child 654）；抽屉打开不阻塞页面；只读。

## 7. 验收（本期总）

| # | 项 | 门槛 |
| --- | --- | --- |
| 1 | 后端全量测试 | 0 失败，且既有 529 条不回归 |
| 2 | 前端构建 + 单测 | `npm run build` 通过、vitest 全绿 |
| 3 | 流式首字 | < 2.5 s（真模型实测，记录到报告） |
| 4 | 流式一致性 | delta 拼接 === final.answer（单测 + e2e） |
| 5 | 回落 | 断网 / 500 时自动走非流式，用户只看到一次重试 |
| 6 | 原文高亮 | 引用页可打开、框位置误差 < 1% |
| 7 | 越权 | 页图接口跨租户 404，响应体零泄漏 |
| 8 | 文档 | 契约 C15 + 术语表 3 个词 + README 演示脚本 + 阶段 12 报告 |

## 8. 决策（待执行时确认）

| # | 决策 | 结论 |
| --- | --- | --- |
| D1 | 传输协议 | `text/event-stream` + `SseEmitter`；前端 fetch 读流（EventSource 不支持 POST + Authorization） |
| D2 | 流式与结构化 | 单次调用、增量解析 JSON 的 `answer` 字段，保留全部四层校验 |
| D3 | 事件种类 | 4 种 + 心跳注释行，`final` 为唯一真相 |
| D4 | 线程模型 | 虚拟线程执行器；显式传 `CurrentUser` 重建 `TenantContext` |
| D5 | 渲染方式 | 后端 PDFBox 渲 PNG（不引 PDF.js） |
| D6 | 高亮坐标 | 直接用归一化 bbox 百分比，后端不换算 |
| D7 | 失败策略 | 流式失败回落非流式一次；页图失败显示重试 |
| D8 | 切块可视化 | 只读接口 + 抽屉，不做编辑 |

## 9. 执行顺序

```
Task 1 后端: extractor → generator.stream → AnswerService → ChatService 拆分 → controller
        ↓（后端单测全绿再动前端）
Task 1 前端: sse.ts → chat.ts → ChatView → MessageBubble
        ↓
Task 2 后端: pdfbox → renderer → page controller
        ↓
Task 2 前端: fetchPageImage → EvidencePanel 原文 tab → 高亮框
        ↓
Task 3: chunks 接口 → 抽屉 → 文档页入口
        ↓
收尾: e2e（1 条流式）+ 全量 + 截图 + 报告 + 契约 C15
```

## 10. 执行结果（2026-10-28 收尾）

### 10.1 三个任务的实际交付

| 任务 | 计划 | 实际 | 差异 |
| --- | --- | --- | --- |
| Task 1 流式 | 后端 SSE + 前端渲染 + 回落 | 新增 POST /api/chat/stream（SseEmitter，15 秒注释心跳）；前端 sse.ts 解析器 + askStream + 流式光标 + 失败回落 | 一致；额外规定「拒答只发 final」「final 整条替换本地消息」 |
| Task 2 原文高亮 | PDFBox 渲页 + 原文页签 | PdfPageRenderer（48 页 LRU）+ GET /api/documents/{id}/pages/{page} + EvidencePanel「原文」页签 + 点正文角标直接翻页 | 一致；额外把「不可读 / 页码越界 / 无对象键 / 不存在」合并成同一个 404 |
| Task 3 切块可视 | 嵌套树接口 + 抽屉 | 扁平列表 GET /api/documents/{id}/chunks + ChunkBrowser.vue（P/C/T 分组、正文、原文位置） | 返回形状改为扁平（limit 语义清楚），上限 800 而非 500 |

### 10.2 计划外的必要修复

| # | 问题 | 为什么必须现在修 | 落点 |
| --- | --- | --- | --- |
| 1 | 模型引用编号会跳号（正文 [E2][E7][E11][E13] 对 4 条引用） | 不修则「点引用看原文」直接失效：角标点不开 | AnswerService.renumberCitations() + 2 条单测 |
| 2 | GET /api/documents 列表没做 ACL | 租户内枚举泄漏，阶段 8 漏掉的同类问题 | DocumentQueryController 补 ScopeAuthorizer |
| 3 | 提示词没强制「每句带编号」 | 模型常整段不带 [E#]，原文高亮就没有入口 | AnswerPrompts.SYSTEM 第 3 条加硬要求 |
| 4 | 切块列表默认 200 把子块截掉 | 演示时会看到「子块 96 / 实际 630」 | 默认 800、服务端夹紧 1000 |

### 10.3 偏差登记

| # | 计划写的 | 实际做的 | 理由 |
| --- | --- | --- | --- |
| 1 | 切块接口返回 {version, parents:[{children:[]}]} | 返回扁平 ChunkBrief[]（Parent 在前、Child 紧随） | 嵌套结构下 limit 算 Parent 还是 Child 说不清；扁平列表 + 前端分组更稳 |
| 2 | 切块上限 500 个 Child | 默认 800、夹紧 1000 | doc 38 有 630 个 Child，500 会截断 |
| 3 | 流式首字门槛 < 2.5 秒 | 实测 7,870 ms | 差的是 QU / rerank / 首 token 的固定成本，不是流式实现；改为下一步「先发 stage 状态行」 |
| 4 | 页图 dpi 只有一个默认值 | 原文页签 150、切块抽屉 120 | 抽屉里图小，120 够看且省流量 |
| 5 | meta 里的 evidence 前端提前渲染 | 目前只用于占位与调试 | 先把链路跑通，证据提前出现留作下一步 |
| 6 | 计划没提「引用编号重排」 | 新增 renumberCitations | 见 10.2 第 1 条 |
| 7 | 计划没提「列表 ACL」 | 顺手补上 | 见 10.2 第 2 条 |

### 10.4 验收

| 项 | 结果 |
| --- | --- |
| 后端全量（非 e2e） | 560 通过 / 0 失败 / 1 跳过（阶段 12 新增 31 条） |
| 流式 e2e | 1/1：首字 7,870 ms、总 10,994 ms、60 个 delta、4 条引用 |
| 前端单测 | 16/16（answer tokenizer 7 + SSE 解析 9） |
| 前端构建 | 通过（含 vue-tsc），产物约 236 KB gzip |
| 页图 | 200 image/png、280,051 字节、1240×1754；页码越界 404 |
| 切块 | doc 38：104 Parent + 630 Child；doc 7：104 + 654；抽屉 838 行 |

### 10.5 遗留

1. 首字 7.9 秒（计划门槛 2.5 秒）：下一步在 meta 之前先发 stage 状态行。
2. meta 里的证据前端尚未提前渲染。
3. 切块抽屉一次拉 800 行、无「加载更多」。
4. bbox 只支持单框，跨栏块会框大。

## 11. 执行前修订（2026-10-28 晚）：证据面板改为可调宽、可收起的侧边栏

> 用户要求原话：「在查看原文的时候，我希望右侧面板可以变成侧边栏，并且可以有按钮可以收起，宽度用户可以自由调整，就跟 deepseek harness 的交互一样。」
> 本节只讲**这一次要改什么**，不改 §2 的契约 C15（SSE 协议与页图端点都不动）。

### 11.1 现状（§5 交付的形态）

| 现状 | 具体 |
| --- | --- |
| 定位 | `src/views/ChatView.vue` 的 `.panel-layer` 是 `position: absolute` 的**浮层**，`top:70px; right:16px; bottom:16px` |
| 宽度 | 固定 `width: var(--evidence-w)`，`src/assets/theme.css:32` 定死 `424px`；窄屏（≤1180px）走 `min(424px, 100% - 20px)` |
| 收起 | 只有面板头部的 ✕（`EvidencePanel.vue:164` 的 `emit('close')`）和顶栏按钮「证据面板」 |
| 副作用 | 浮层**不挤压正文**，所以正文宽度永远不变；用户无法把面板拉宽来看一张财报表格 |

### 11.2 目标交互（四条）

1. **真侧栏**：面板成为 `.chat-page` 的第三列（与会话栏、正文并列），打开时正文自动变窄而不是被盖住。
2. **拖动改宽**：面板左缘一条 6px 的拖动手柄，按住左右拖即可改宽，拖动时手柄高亮、正文不重排抖动。
3. **可收起**：面板头部 ✕ 收起，顶栏「证据面板」按钮展开并**恢复上次宽度**；收起后不保留窄条（与 DSH 一致）。
4. **记住宽度**：刷新页面后宽度仍然是上次拖到的值（`localStorage`）。

### 11.3 改哪些文件（全部在前端仓库 `financial-ai-web`）

| 文件 | 改动 | 为什么 |
| --- | --- | --- |
| `src/views/ChatView.vue` | 把 `.panel-layer`（浮层）换成 `.panel-dock`（flex 列）+ `.dock-resizer`（拖动手柄）；新增 `panelWidth` / `dragging` 状态与 `startResize`；删除 `@media` 里的浮层宽度 | 布局从「覆盖」变「并列」，这是交互改动的全部来源 |
| `src/components/EvidencePanel.vue` | 头部 ✕ 的 `title` 改为「收起侧栏（Esc）」；事件名仍是 `close`，不新增语义 | 收起动作本来就有，这次只改外部怎么摆 |
| `src/assets/theme.css` | `--evidence-w` 保留为**默认宽度**（424px），新增 `--evidence-w-min: 340px` / `--evidence-w-max: 760px` | 三个数字集中在一处，JS 与 CSS 都读它 |
| `src/utils/__tests__/panelWidth.spec.ts`（新增） | 纯函数 `clampPanelWidth(raw, viewport)` 的单测 4 条：下限、上限、视口过窄、非法值 | 拖动逻辑里唯一值得单测的是夹紧规则 |

### 11.4 五条硬口径（写进代码注释，也写进报告）

| # | 口径 | 理由 |
| --- | --- | --- |
| 1 | 宽度夹在 **340 ~ 760px**，且不超过「窗口宽 − 会话栏 264px − 正文最小 420px」 | 低于 340 财报表格看不清；高于 760 正文被压成一条缝 |
| 2 | 双击手柄 → 复位到默认 424px | 拖歪了有个确定的回家路 |
| 3 | 手柄 `role="separator" aria-orientation="vertical" tabindex="0"`，←/→ 每次 ±16px，Home/End 到最小/最大 | 不用鼠标也能调；顺带说明这不是装饰 |
| 4 | 拖动时给 `document.body` 加 `data-resizing`，禁选中 + `cursor: col-resize`；`pointerup` / `pointercancel` 一定移除 | 否则拖到正文上会把文字选中一片蓝 |
| 5 | 窗口宽度 ≤ 980px 时退回浮层（`position: absolute`、宽 `min(424px, 100% - 20px)`），手柄隐藏 | 窄屏再挤压就只剩 200px 正文，不如盖上去 |

### 11.5 验收

| # | 验什么 | 怎么验 | 通过标准 |
| --- | --- | --- | --- |
| 1 | 默认打开是侧栏 | headless 探针取 `.thread` 与 `.panel-dock` 的 `getBoundingClientRect()` | 两者 `left` 不重叠（面板在正文右侧），`document.querySelector('.panel-layer')` 为 null |
| 2 | 拖动真的改宽 | 探针在 `.dock-resizer` 上派发 `pointerdown` → `pointermove(-120px)` → `pointerup` | 面板宽度减少约 120px（±2），正文宽度相应增加 |
| 3 | 夹紧生效 | 拖动 −2000px / +2000px | 宽度停在 340 / 760（或「窗口宽−264−420」） |
| 4 | 记住宽度 | 拖到 600 → `localStorage['finagent.panelWidth']` = 600 → reload | reload 后面板仍 600 |
| 5 | 收起/展开 | 点 ✕ → 点顶栏「证据面板」 | 面板消失 → 回来且宽度仍是上次的值 |
| 6 | 双击复位 | 双击手柄 | 宽度回到 424 |
| 7 | 键盘 | 手柄聚焦后按 ←/→/End | 每次变化 16px / 到上限 |
| 8 | 不回归 | 原文页翻页 + 高亮框仍对准（`.hl` 数量 1、位置不变）；证据 chip 点击仍切换 | 阶段 12/13 的验收项全绿 |

### 11.6 明确不做

- 不做「拖到最左自动吸附关闭」（DSH 有，但我们的面板宽度范围本来就到不了左侧，加了只会误触）。
- 不做左侧会话栏的可调宽（这期只动右侧证据面板）。
- 不做面板三栏内部的二次分栏（证据列表 / 原文上下排）。
- 不改后端任何接口：面板宽度纯前端状态。

### 11.7 与阶段 12 的偏差登记

| # | 计划原文 | 实际 | 原因 |
| --- | --- | --- | --- |
| 1 | §5.2「右侧固定 424px 浮层」 | 改为可调宽侧栏 | 用户在看原文时要把面板拉宽看表格；浮层会盖住正文 |
| 2 | §11.4 第 1 条「窗口宽 − 会话栏 **264px** − 正文 420px」 | 实现用 **248px**（`--side-w`） | 真实布局里会话栏是 248px，照计划写会让上限多算 16px |
| 3 | §11.5 第 2 条「一次 evaluate 完成拖动并读宽度」 | 拆成「按下+移动」与「读宽度」两次 evaluate，中间 `sleep(200)` | Vue 要等渲染帧才把宽度刷进 DOM，同帧读会拿到上一帧（报告 6.7） |
| 4 | 未写明「向左拖是变宽还是变窄」 | 实测**向左拖 = 变宽**（424 → 543） | 手柄在面板左侧，宽度 = 手柄右沿固定 x − 指针 x |
| 5 | §11.3 文件表只列 `panelWidth.ts` / `ChatView.vue` / `theme.css` | 另加 `panelWidth.spec.ts` 7 例；顺带清掉 `ScopePicker.vue` 与 `ChatView.vue` 两处 eslint 未用符号 | 夹紧与持久化需要单测保护；两处 lint error 本来就在，改到这一片一并清掉 |

### 11.8 执行结果（2026-10-28 晚）

| 项 | 实测（headless Chrome，视口 1440×900） |
| --- | --- |
| 默认打开 | dock 424 / 正文 700 / 重叠 0 / 旧 `.panel-layer` 不存在 |
| 向左拖 120px | 424 → **543**，写盘 543；拖动中 `body[data-resizing]=1`，松手清除 |
| 两端夹紧 | **760** / **340** |
| 双击手柄 | **424**，写盘 424 |
| 键盘 `←` `→` `End` `Home` | 424 → 440 → 424 → 760 → 340 |
| 收起 / 展开 | 正文 784 → **1124**；再展开回到 555（同页状态保持） |
| 重新挂载（存储 555） | dock **555** / 正文 551 |
| 复位后再刷新 | dock **424**（读回写盘值） |
| 控制台 | clean |

前端：`vitest` **24/24**（tokenizer 7 + SSE 10 + 面板 7）、`eslint` **0 error**、`npm run build` 通过（962 ms，`ChatView` chunk 129.16 kB / gzip 43.65 kB）。截图 `docs/screenshots/evidence-dock-555.png`、`evidence-dock-424.png`；报告见 `docs/阶段12-体验补强报告.md` §5.6。
