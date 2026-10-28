# 阶段 2：文档解析（Docling）实施计划

> **给执行者：** 按任务逐个执行，每个任务结束后运行该任务的验证命令并确认输出符合预期，再进入下一个任务。步骤用复选框（- [ ]）语法跟踪进度。
> 注：本环境未安装 superpowers 执行子技能，采用「每个任务由一个子代理独立执行 + 主代理在任务间验收」替代。

> 📖 术语：本文出现的缩写（Parent/Child、embedding、RRF、QU、HNSW、e2e……）在 `docs/术语表.md` 有中文全称、为什么需要它、在本项目里长什么样。写法约定见 `docs/文档写作规范.md`。

**Goal:** 把一份 181 页真实年报，变成**带页码、带章节路径**的 NormalizedDocument，并落盘到 COS，为阶段 3 的切块提供输入。

**Architecture:** Docling 跑在独立的 Python 服务里（venv 已就绪），通过 FastAPI 暴露 `POST /parse`；服务只负责「看懂 PDF」，不碰数据库。Java 侧用 Parser Adapter 调它，保持解析引擎可替换。

**Tech Stack:** Python 3.14 · Docling 2.138.0 · torch 2.14.1+cpu · FastAPI · Pydantic v2 · Uvicorn

---

## 阶段 2 开工前的四个实测发现（本计划的设计依据）

这些是**已经跑出来的事实**，不是推测。它们直接决定了下面的设计：

| # | 发现 | 证据 | 对设计的影响 |
|---|---|---|---|
| 1 | **Docling 的标题层级是扁平的** | 17 个 `section_header` 全部是 L1；`body.children` 是 89 个平铺引用，不是嵌套章节树 | **必须自建章节树**（Task 3），不能指望 Docling 给 `section_path` |
| 2 | chunker 给出 headings 面包屑，但**只有一层** | `HierarchicalChunker` 43/43、`HybridChunker` 41/41 都有 headings，但形如 `['一、公司信息']`，不是 `['第二节', '五、']` | 可用它定位「最近标题」，但多级路径仍需自建 |
| 3 | **表格在 chunk 文本里被序列化成 `键, 序 = 值` 形式** | 释义 chunk 输出成 `中国证监会, 指 = 指. 中国证监会, 释义内容 = ...` | 这是 chunker 序列化**表格**的方式。⚠️ **评审修正**：初版计划把它误判成 Docling 的 `key_value_items`，缓存复验 `len(doc.key_value_items) == 0`（PDF 管线根本不产出该类型）→ 该字段已从模型与代码中删除 |
| 3b | **bbox 未归一化，且坐标原点是混的** | 文本块 `l=244.0, t=686.37, coord_origin=BOTTOMLEFT`；表格单元格却是 `TOPLEFT`；页尺寸 595.2×842.04 | 必须显式归一化，并把 `coord_origin` 记进模型（阶段 5 前端高亮依赖它） |
| 4 | 解析速度 **8.2 s/页**（CPU） | 8 页 65.5s | 181 页约 **25 分钟**；本阶段需实测全量耗时并决定是否缓存 |

另外两个环境事实：

- **huggingface.co 不可达，hf-mirror.com 可达** → 所有解析进程必须设 `HF_ENDPOINT=https://hf-mirror.com`
- Docling 的标题序列**本身可能乱序**：实测 `一、公司信息`（p6）出现在 `第二节 公司简介和主要财务指标`（p6）**之前**，章节树重建必须容错并把异常暴露出来，而不是假装输入是干净的

---

## File Structure

```
financial-ai-project/
├── docker/postgres/Dockerfile          # pgvector + zhparser（多阶段，652MB）
├── docker-compose.yml                  # 只起 postgres；schema 交给 Flyway
├── document-parser-service/            # Python 解析服务（独立进程，无 DB/COS 依赖）
│   ├── .venv/
│   ├── requirements.txt
│   ├── README.md
│   ├── app/
│   │   ├── config.py                   # 环境变量与 HF 镜像
│   │   ├── models.py                   # NormalizedDocument 等 Pydantic 模型
│   │   ├── section_tree.py             # 【核心】自建章节树
│   │   ├── section_tree_report.py      # 决策门报告入口（Task 3 交付）
│   │   ├── converter.py                # Docling 封装
│   │   └── main.py                     # FastAPI：/health /parse /jobs
│   ├── scripts/parse_full.py           # 诊断工具（非主路径）
│   └── tests/
└── src/
    ├── main/
    │   ├── java/com/finagent/
    │   │   ├── FinAgentApplication.java
    │   │   ├── storage/                # ObjectStorage 抽象 + COS 实现 + CosCli
    │   │   └── document/
    │   │       ├── ParserAdapter.java          # 接口（设计文档 §8.3）
    │   │       ├── ParsedDocument.java         # 结果骨架
    │   │       ├── DoclingParserAdapter.java   # HTTP：提交 + 轮询
    │   │       ├── DocumentRepository.java     # document 表仓储
    │   │       ├── DocumentParseService.java   # 编排：Adapter + COS + DB
    │   │       └── DocumentParseRecovery.java  # 启动恢复在途作业
    │   └── resources/
    │       ├── application.yml
    │       └── db/migration/
    │           ├── V1__baseline.sql            # 阶段 1 的扩展 + 4 张表
    │           └── V2__document_parse.sql      # document 的 9 个解析列
    └── test/java/com/finagent/
        ├── VectorStoreSmokeTest.java
        ├── CosObjectStorageTest.java
        └── FullDocumentPipelineTest.java       # 端到端（@Tag e2e，默认排除）
```

**为什么章节树单独一个文件：** 它是本阶段唯一有算法挑战、且最容易被后续需求改动的地方。独立成模块后可以用纯函数测试，不依赖 Docling 和模型加载。

---

## Task 1: 服务骨架与依赖锁定

**Files:**
- Create: `document-parser-service/requirements.txt`
- Create: `document-parser-service/app/__init__.py`
- Create: `document-parser-service/app/config.py`

- [ ] **Step 1: 写 requirements.txt（锁定已验证的版本）**

```text
# 阶段 2 实测通过的版本组合（Python 3.14 / Windows / CPU）
docling==2.138.0
fastapi==0.143.0
uvicorn[standard]==0.54.0
pydantic==2.14.1
pydantic-settings==2.15.0
python-multipart==0.0.32
# 评审补充（B4）：venv 实测未装 pytest，而 Task 3/4 全部命令依赖它
pytest==9.1.1
# 评审补充（M4）：测内存峰值用 RSS，比 tracemalloc 真实
psutil==7.2.2
```

> 版本号取自本机实际安装结果（`pip list` 输出）。**不要用无版本约束的包名**——Docling 生态版本耦合紧，漂移会直接报错。

- [ ] **Step 2: 安装新增依赖**

Run:
```powershell
cd document-parser-service
.\.venv\Scripts\python.exe -m pip install -r requirements.txt -i https://pypi.tuna.tsinghua.edu.cn/simple
```

Expected: 结尾为 `Successfully installed ...` 或 `Requirement already satisfied`，无 ERROR。

- [ ] **Step 3: 写 app/config.py**

```python
"""服务配置：集中处理环境变量，尤其是 HuggingFace 镜像。

本机 huggingface.co 不可达、hf-mirror.com 可达（阶段 2 实测），
因此默认值直接指向镜像，避免模型下载卡死。
"""
from __future__ import annotations

import os

# 必须在导入 docling 之前设置，否则拿不到
os.environ.setdefault("HF_ENDPOINT", "https://hf-mirror.com")

from pydantic_settings import BaseSettings, SettingsConfigDict  # noqa: E402


class Settings(BaseSettings):
    """服务配置，可用环境变量覆盖。"""

    hf_endpoint: str = "https://hf-mirror.com"
    host: str = "127.0.0.1"
    port: int = 8000
    # 允许的最大上传字节数（默认 64MB，够放几百页年报）
    max_upload_bytes: int = 64 * 1024 * 1024
    # Docling 解析页数上限；0 表示不限制
    max_pages: int = 0

    # 评审 L1：pydantic v2 已弃用 class Config（会报 PydanticDeprecatedSince20）
    model_config = SettingsConfigDict(env_prefix="PARSER_")


settings = Settings()
```

- [ ] **Step 4: 写 app/__init__.py（空文件）并验证导入**

```python
"""金融研报解析服务。"""
```

Run:
```powershell
cd document-parser-service
.\.venv\Scripts\python.exe -c "from app.config import settings; print('HF_ENDPOINT =', settings.hf_endpoint); print('port =', settings.port)"
```

Expected: 输出 `HF_ENDPOINT = https://hf-mirror.com` 与 `port = 8000`。

---

## Task 2: NormalizedDocument 数据模型

**Files:**
- Create: `document-parser-service/app/models.py`

- [ ] **Step 1: 写 models.py**

设计要点：**每一个元素都带页码**（设计文档 §9.12 要求引用可定位），章节路径单独一层（Task 3 产出），并且如实记录 Docling 的键值项，不假装它们不存在。

```python
"""NormalizedDocument：解析服务的统一输出格式。

它是 Parser Adapter 契约的一部分（设计文档 §8.3/§8.4）：无论底层用 Docling 还是别的引擎，
下游看到的都是这个结构，因此换引擎不会波及业务层。

与设计文档 §8.4 的差异（阶段 2 实测后调整）：
  - Docling 不给多级标题层级，因此 section_path 由本项目自建（见 section_tree.py），
    并在 Heading 上额外记录 numbering / anomaly 供审计。
  - 初版曾为 Docling 的 key_value_items 预留字段，缓存复验 len(doc.key_value_items) == 0
    （PDF 管线不产出该类型），已按评审删除，避免死代码。
"""
from __future__ import annotations

from typing import Literal

from pydantic import BaseModel, Field


class BBox(BaseModel):
    """归一化到 [0,1] 的页面坐标，便于前端按比例还原高亮。"""

    left: float
    top: float
    right: float
    bottom: float


class Provenance(BaseModel):
    page_no: int = Field(description="1 起始的页码")
    bbox: BBox | None = None


class Heading(BaseModel):
    """一个标题及其在自建章节树中的位置。"""

    text: str
    level: int = Field(description="1 最高；由编号规则推断")
    order: int = Field(description="在标题序列中的序号")
    numbering: str = Field(description="chapter/section/subsection/item/subitem/none")
    path: list[str] = Field(default_factory=list, description="自根到本标题的路径")
    page_no: int | None = None
    anomaly: str | None = Field(default=None, description="层级跳变等异常说明")


class Block(BaseModel):
    """正文元素：段落 / 列表项 / 页眉页脚 / 脚注等。"""

    block_id: str
    label: str = Field(description="Docling 标签，如 text / list_item / footnote / page_header")
    text: str
    provenance: Provenance
    section_path: list[str] = Field(default_factory=list)


class TableCell(BaseModel):
    text: str
    row: int
    col: int
    row_span: int = 1
    col_span: int = 1


class Table(BaseModel):
    table_id: str
    page_start: int
    page_end: int
    num_rows: int
    num_cols: int
    cells: list[TableCell] = Field(default_factory=list)
    caption: str | None = None
    section_path: list[str] = Field(default_factory=list)


class Picture(BaseModel):
    picture_id: str
    page_no: int
    caption: str | None = None


class PageDim(BaseModel):
    page_no: int
    width: float
    height: float


class DocumentMeta(BaseModel):
    source_name: str
    content_hash: str
    parser: str = "docling"
    parser_version: str
    page_count: int
    pages_parsed: list[int]
    module: Literal["quarterly", "annual", "unknown"] = "unknown"


class NormalizedDocument(BaseModel):
    """解析服务对外输出。"""

    meta: DocumentMeta
    headings: list[Heading] = Field(default_factory=list)
    blocks: list[Block] = Field(default_factory=list)
    tables: list[Table] = Field(default_factory=list)
    pictures: list[Picture] = Field(default_factory=list)
    pages: list[PageDim] = Field(default_factory=list)
    markdown: str = Field(default="", description="Docling 的 markdown 导出，便于人工核对")
    warnings: list[str] = Field(default_factory=list)
```

- [ ] **Step 2: 验证模型可导入且能实例化最小对象**

```powershell
cd document-parser-service
.\.venv\Scripts\python.exe -c "from app.models import NormalizedDocument, DocumentMeta; m=DocumentMeta(source_name='x.pdf', content_hash='h', parser_version='2.138.0', page_count=1, pages_parsed=[1]); d=NormalizedDocument(meta=m); print('OK', d.model_dump_json()[:120])"
```

Expected: 输出 `OK {"meta":{...`。

---

## Task 3: 自建章节树（本阶段核心）

**为什么必须自己做：** 实测发现 Docling 的 17 个 `section_header` **全部是 L1**，`body.children` 是平铺的 89 项，chunker 的 `headings` 面包屑**只有一层**。设计文档 §8.5 需要的 `section_path` 拿不到，必须由本项目根据中文编号惯例重建。

**Files:**
- Create: `document-parser-service/app/section_tree.py`
- Create: `document-parser-service/tests/test_section_tree.py`

- [ ] **Step 1: 先写测试（用真实标题序列，不是编造的）**

```python
"""章节树重建的回归测试。

标题序列取自真实年报（东方财富 2025 年年度报告，前 8 页），
不是编造的示例 —— 真实数据里存在 Docling 的乱序问题，必须被覆盖到。
"""
from app.section_tree import build_section_tree, detect_level, path_lookup


REAL_HEADINGS = [
    "东方财富信息股份有限公司",
    "2025年年度报告",
    "第一节 重要提示、目录和释义",
    "目录",
    "备查文件目录",
    "释义",
    "一、公司信息",
    "二、联系人和联系方式",
    "三、信息披露及备置地点",
    "四、其他有关资料",
    "第二节 公司简介和主要财务指标",
    "五、主要会计数据和财务指标",
    "六、分季度主要财务指标",
    "七、报告期主要会计政策、会计估计变更和差错更正",
    "1、同时按照国际会计准则与按中国会计准则披露的财务报告中净利润和净资产差异情况",
    "2、同时按照境外会计准则与按中国会计准则披露的财务报告中净利润和净资产差异情况",
]


def test_detect_level_by_numbering():
    assert detect_level("第一节 重要提示")[:2] == (1, "chapter")
    assert detect_level("一、公司信息")[:2] == (2, "section")
    assert detect_level("（一）基本情况")[:2] == (3, "subsection")
    assert detect_level("1、同时按照国际会计准则")[:2] == (4, "item")
    assert detect_level("（1）明细")[:2] == (5, "subitem")
    assert detect_level("目录")[1] == "none"


def test_numbered_headings_get_correct_path():
    tree = build_section_tree(REAL_HEADINGS)
    lookup = path_lookup(tree)
    # 第二节 之后的五、，路径应完整
    assert lookup["五、主要会计数据和财务指标"] == ["第二节 公司简介和主要财务指标", "五、主要会计数据和财务指标"]
    # 四级小项挂在二级之下
    # 评审修正 H4：初版是恒真式三元表达式（条件引用了未出现在输入中的标题，
    # 恒为 False，else 分支断言"路径末项等于自身"，永远成立，什么都没验证）。
    assert lookup["1、同时按照国际会计准则与按中国会计准则披露的财务报告中净利润和净资产差异情况"] == [
        "第二节 公司简介和主要财务指标",
        "七、报告期主要会计政策、会计估计变更和差错更正",
        "1、同时按照国际会计准则与按中国会计准则披露的财务报告中净利润和净资产差异情况",
    ]


def test_unnumbered_heading_does_not_pollute_numbered_context():
    """无编号标题不应成为后续编号标题的父级。"""
    tree = build_section_tree(REAL_HEADINGS)
    lookup = path_lookup(tree)
    # 释义 是无编号的，它出现在 第一节 之下
    assert lookup["释义"][0] == "第一节 重要提示、目录和释义"
    # 但 一、公司信息 不应被挂到 释义 之下
    assert "释义" not in lookup["一、公司信息"]


def test_reading_order_anomaly_is_recorded():
    """真实数据里「第二节」的标题出现在它自己的 一、~四、 之后 —— 必须被标记。

    评审修正 H1：初版只断言「存在 anomaly」，但修好正则后 anomaly 全部来自
    「六、→1、」的层级跳变，与本用例 docstring 说的乱序无关（因错误原因通过）。
    这里改为定位到具体章标题断言。
    """
    tree = build_section_tree(REAL_HEADINGS)
    ch2 = next(h for h in tree if h.text.startswith("第二节"))
    assert ch2.anomaly, "章标题排在自己子节之后，必须记录 anomaly"
    assert "之后" in ch2.anomaly

    # 如实锁定当前无法自动修正的行为：一、公司信息 归属错误
    lookup = path_lookup(tree)
    assert lookup["一、公司信息"][0] == "第一节 重要提示、目录和释义", (
        "已知缺陷：一、公司信息 被挂到 第一节 下。此断言锁定现状；"
        "若将来用目录页反查修正了归属，这里会失败，提醒同步更新计划与设计文档。"
    )
```

- [ ] **Step 2: 运行测试，确认失败**

Run:
```powershell
cd document-parser-service
.\.venv\Scripts\python.exe -m pytest tests/test_section_tree.py -q
```

Expected: FAIL，报 `ModuleNotFoundError: No module named 'app.section_tree'`。

- [ ] **Step 3: 实现 section_tree.py**

设计规则（针对 A 股年报的编号惯例）：

| 编号形态 | level | numbering |
|---|---|---|
| `第X节` / `第X章` | 1 | chapter |
| `一、` | 2 | section |
| `（一）` | 3 | subsection |
| `1、` / `1.` | 4 | item |
| `（1）` | 5 | subitem |
| 其他 | 由上下文推断 | none |

两条关键规则，都是为了扛住真实数据的脏：

1. **无编号标题不进入编号栈** —— 它只挂在当前上下文下方，不作为后续编号标题的父级。否则 `释义` 会变成 `一、公司信息` 的父级，路径错得离谱。
2. **层级跳变记录 anomaly** —— Docling 的阅读顺序会把 `第二节` 排到 `一、~四、` 之后。这种乱序无法从扁平序列中完全恢复，但**必须被标记并计数**，作为本阶段的量化指标暴露出来，而不是静默产出错误路径。

```python
"""从扁平标题序列重建章节树。

背景（阶段 2 实测）：
    Docling 的 iterate_items() 把 17 个 section_header 全部标成 L1，
    chunker 给出的 headings 面包屑也只有一层。因此设计文档 §8.5 所需的
    section_path 必须由本项目根据中文编号惯例自行重建。

已知局限：
    Docling 的阅读顺序可能把章标题排到其子节之后（实测：第二节 出现在
    一、~四、 之后）。本模块无法从扁平序列中完全恢复正确归属，但会把
    这类情况记录为 anomaly，供上层统计与后续修正。
"""
from __future__ import annotations

import re
from dataclasses import dataclass, field

# 顺序即优先级：更具体的模式在前
_PATTERNS: list[tuple[re.Pattern[str], int, str]] = [
    (re.compile(r"^第[一二三四五六七八九十百零〇]+[节章]"), 1, "chapter"),
    (re.compile(r"^[一二三四五六七八九十]+[、．.]"), 2, "section"),
    (re.compile(r"^[（(][一二三四五六七八九十]+[）)]"), 3, "subsection"),
    (re.compile(r"^\d+[、．]\s*"), 4, "item"),
    (re.compile(r"^[（(]\d+[）)]"), 5, "subitem"),
]

MAX_LEVEL = 5


@dataclass
class Heading:
    """一个标题及其在章节树中的位置。"""

    text: str
    level: int
    order: int
    numbering: str
    path: list[str] = field(default_factory=list)
    page_no: int | None = None
    anomaly: str | None = None


def detect_level(text: str) -> tuple[int | None, str]:
    """按编号惯例推断层级。返回 (level, numbering)；无法判断时 level 为 None。"""
    t = text.strip()
    for pattern, level, kind in _PATTERNS:
        if pattern.match(t):
            return level, kind
    return None, "none"


def build_section_tree(heading_texts: list[str]) -> list[Heading]:
    """把按阅读顺序排列的标题文本转成带 path 的标题列表。

    Args:
        heading_texts: 标题文本，按文档阅读顺序。

    Returns:
        标题列表，每项带 level / numbering / path / anomaly。
    """
    result: list[Heading] = []
    # 编号标题栈：只有带编号的标题才进入，避免无编号标题污染层级
    stack: list[Heading] = []

    for order, raw in enumerate(heading_texts):
        text = (raw or "").strip()
        if not text:
            continue

        level, numbering = detect_level(text)
        anomaly: str | None = None

        if level is None:
            # 无编号标题：挂在当前上下文下一层，但不进入编号栈
            level = (stack[-1].level + 1) if stack else 1
            path = [h.text for h in stack] + [text]
            result.append(Heading(text=text, level=level, order=order,
                                  numbering=numbering, path=path))
            continue

        # 【评审修正 H1】必须在弹栈之前检测「章标题出现在其子节之后」——
        # 这是本样本真实存在的乱序（一、~四、 之后才出现「第二节」）。
        # 初版实现只在 while 弹栈后比较期望层级，此时 expected == level，检测不到，
        # 导致真实的归属错误报 0% anomaly，决策门反而放行。
        if numbering == "chapter" and stack and stack[-1].numbering == "section":
            anomaly = (
                f"章标题出现在其子节之后：'{stack[-1].text}' 等二级小节"
                f"可能实际属于本章 '{text}'，归属存疑"
            )

        # 回溯到父级
        while stack and stack[-1].level >= level:
            stack.pop()

        # 层级跳变检测（父级标题缺失）——仅在没有更具体的乱序结论时使用
        expected = (stack[-1].level + 1) if stack else 1
        if level > expected and anomaly is None:
            anomaly = (
                f"层级跳变：level={level} 但当前上下文期望 level<={expected}"
                f"（父级标题可能出现在其后，属 Docling 阅读顺序问题）"
            )

        path = [h.text for h in stack] + [text]
        heading = Heading(text=text, level=level, order=order,
                          numbering=numbering, path=path, anomaly=anomaly)
        result.append(heading)
        stack.append(heading)

    return result


def path_lookup(tree: list[Heading]) -> dict[str, list[str]]:
    """标题文本 -> 路径。供 chunk 的 headings 面包屑反查完整路径。

    ⚠️ 已知局限（评审 M6）：以标题文本为键，**同名标题会互相覆盖** —— 真实年报
    存在重复标题。当前够用，因为 Docling 的 chunker 面包屑本身也只给最近的标题文本；
    阶段 3 若需要精确定位，应改为按 order 键控并返回 list。
    """
    return {h.text: h.path for h in tree}
```

- [ ] **Step 4: 运行测试，确认通过**

Run:
```powershell
cd document-parser-service
.\.venv\Scripts\python.exe -m pytest tests/test_section_tree.py -q
```

Expected: 全部 PASS。

- [ ] **Step 5【决策门】: 用 181 页真实标题跑一遍，统计 anomaly**

这是本阶段最重要的量化指标。把它跑出来再决定是否需要更复杂的修正（例如用文档自带的 `目录` 页反查章节归属）。

```powershell
cd document-parser-service
.\.venv\Scripts\python.exe -m app.section_tree_report "..\docs\东方财富2025年年度报告.pdf" 1 8
```

（**评审 B5 修正**：初版引用的 `app/section_tree_report.py` 没有任何任务创建，它读的 `_tmp_doc.json` 也没有任何任务产出，决策门根本跑不起来；而且它排在 Task 4/6 之前，时间上也拿不到解析产物。现在把它作为 **Task 3 的交付物之一**（Files 里补上 `app/section_tree_report.py`）：脚本**自己解析**指定页范围、取出全部 `section_header`、跑 `build_section_tree`、打印每个标题的路径与 anomaly。）

**判据（评审 H1 修正后）：**

- 要量的是 **「章标题排在自己子节之后」导致的错误归属数量**（例如 `一、公司信息` 被挂到 `第一节` 下）
- 占比 **>= 5%** → 停下来，追加任务：解析文档自带「目录」页，用章标题 + 页码反查归属
- 占比 **< 5%** → 接受，继续 Task 4，并把数字记进 `docs/阶段2-解析结果报告.md`

> ⚠️ **不要**再拿「anomaly 总数 < 5%」当判据。评审 H1 已实测证明：旧实现下 anomaly 全部来自 `六、→1、` 这类层级跳变，与真实归属错误无关 —— **真实错误报 0%，门会放行**。

#### 决策门实测结果（2026-10-13 执行）

前 8 页 / 17 个标题：

| 指标 | 基线（Docling 阅读顺序） | 修正后（坐标重排） |
|---|---|---|
| **错误归属** | **5.9%**（1/17） | **0.0%** |
| 层级跳变 | 2（11.8%） | 2（11.8%） |
| 被重排的标题 | — | 5/17 |

**门判定为「>= 5% → 需处理」，但原计划的处理方式（解析目录页反查）实际上解决不了这个问题：**

> 目录只给「章 -> 页码」，而本样本里 `一、~四、` 与 `第二节` **同在第 6 页**，
> 按页码根本无法判定页内先后。

**真正的解法在坐标里。** 实测第 6 页的 `bbox.t`：

```
t=751.1   第二节 公司简介和主要财务指标   <-- 页内最高，本就该排最前
t=710.6   一、公司信息
t=453.1   二、联系人和联系方式
t=309.3   三、信息披露及备置地点
t=214.2   四、其他有关资料
```

Docling 的阅读顺序把 `第二节` 排到了最后 —— **是阅读顺序错了，不是文档结构问题**。

**处置：新增 `section_tree.sort_by_position()`**，在构建章节树**之前**按「页码 + 垂直位置（自上而下）」重排标题。
5 行代码，错误归属降到 0%，比写一个目录解析器可靠得多。

附加收获：重排后剩下的 2 处「层级跳变」是 `七、境内外会计准则下会计数据差异`
下面直接跟 `1、`/`2、`（文档本身省略了中间层级）—— 路径是正确的，只是层级号偏深。
**这种不是错误**，报告里已与「错误归属」分开统计。

---

## Task 4: Docling 解析封装

**Files:**
- Create: `document-parser-service/app/converter.py`

- [ ] **Step 1: 写 converter.py**

```python
"""Docling 封装：把 PDF 转成 NormalizedDocument。

只做转换与结构映射，不做切块（切块是阶段 3 的事）。
"""
from __future__ import annotations

import hashlib
import time
from pathlib import Path

from docling.document_converter import DocumentConverter
from docling_core.types.doc import DoclingDocument, PictureItem, TableItem, TextItem

from app.models import (
    BBox,
    Block,
    DocumentMeta,
    Heading,
    NormalizedDocument,
    PageDim,
    Picture,
    Provenance,
    Table,
    TableCell,
)
from app.section_tree import build_section_tree, path_lookup

# 不作为正文块输出的标签。
# 评审 M5 更正：实测 iterate_items() 默认**不会**遍历 page_header/page_footer
# （它们在 furniture 层）——本次样本出现的标签只有 picture / section_header / text。
# 保留过滤是防御性的：将来改配置或换文档类型把它们带进来时会被挡掉，
# 但不要指望它会产生"忽略 page_header"的告警。
_NOISE_LABELS = {"page_header", "page_footer"}


def _bbox(item, doc: DoclingDocument) -> BBox | None:
    """把 Docling 的 bbox 归一化到 [0,1]，并统一为左上原点。

    评审修正 H3：初版直接返回 b.l/b.t/b.r/b.b —— 缓存复验那是 **PDF 点坐标**
    （样例 l=244.0, t=686.37, r=412.67, b=626.71，页尺寸 595.2×842.04），
    与 BBox 模型注释声称的 [0,1] 直接矛盾，阶段 5 的前端高亮会用到错坐标。
    且实测坐标原点是**混的**：文本块 BOTTOMLEFT、表格单元格 TOPLEFT，故须显式处理。
    """
    if not getattr(item, "prov", None):
        return None
    prov = item.prov[0]
    page = doc.pages.get(prov.page_no)
    if page is None or getattr(prov, "bbox", None) is None:
        return None

    b = prov.bbox
    w, h = float(page.size.width), float(page.size.height)
    if str(getattr(b, "coord_origin", "")).endswith("BOTTOMLEFT"):
        # PDF 原点在左下，翻成左上原点
        top, bottom = (h - float(b.t)) / h, (h - float(b.b)) / h
    else:
        top, bottom = float(b.t) / h, float(b.b) / h
    return BBox(left=float(b.l) / w, top=top, right=float(b.r) / w, bottom=bottom)


def _page(item) -> int:
    return item.prov[0].page_no if getattr(item, "prov", None) else 0


def parse_pdf(pdf_path: str | Path, page_range: tuple[int, int] | None = None) -> NormalizedDocument:
    """解析 PDF，返回与解析引擎无关的 NormalizedDocument。"""
    pdf_path = Path(pdf_path)
    content = pdf_path.read_bytes()
    content_hash = hashlib.sha256(content).hexdigest()

    t0 = time.time()
    converter = DocumentConverter()
    kwargs = {"page_range": page_range} if page_range else {}
    doc: DoclingDocument = converter.convert(str(pdf_path), **kwargs).document
    parse_seconds = time.time() - t0

    warnings: list[str] = []

    # ---------- 章节树 ----------
    # 评审 M6：先滤掉空标题再 zip —— build_section_tree 会跳过空标题，
    # 一旦有空 section_header，原先的 zip(tree, heading_items) 就会整体错位。
    heading_pairs = [
        ((it.text or "").strip(), _page(it))
        for it in doc.texts
        if str(it.label) == "section_header" and (it.text or "").strip()
    ]
    tree = build_section_tree([t for t, _ in heading_pairs])
    for h, (_, page) in zip(tree, heading_pairs):
        h.page_no = page
    lookup = path_lookup(tree)

    def resolve_path(nearest: str | None) -> list[str]:
        """把 Docling 给的单层 headings 面包屑换成完整路径。"""
        return lookup.get(nearest, []) if nearest else []

    # ---------- 正文块 / 表格 / 图片 ----------
    # 评审 H2：表格必须与正文在同一遍遍历里处理。初版把表格单独放在循环之后，
    # 那时 current_heading 已停在全篇最后一个标题上，导致所有表格共享同一个错误路径。
    blocks: list[Block] = []
    pictures: list[Picture] = []
    tables: list[Table] = []
    seen_table_refs: set[str] = set()

    current_heading: str | None = None
    heading_set = {h.text for h in tree}

    def add_table(tbl: TableItem) -> None:
        pages = [p.page_no for p in getattr(tbl, "prov", [])] or [0]
        data = tbl.data
        cells: list[TableCell] = []
        try:
            for r, row in enumerate(data.grid):
                for c, cell in enumerate(row):
                    if cell is None:
                        continue
                    cells.append(TableCell(text=str(cell.text or ""), row=r, col=c,
                                           row_span=getattr(cell, "row_span", 1),
                                           col_span=getattr(cell, "col_span", 1)))
        except Exception as exc:  # 网格结构异常不应让整篇解析失败
            warnings.append(f"表格 {len(tables)} 网格解析失败: {type(exc).__name__}: {exc}")
        tables.append(Table(
            table_id=f"t{len(tables)}",
            page_start=min(pages), page_end=max(pages),
            num_rows=getattr(data, "num_rows", 0), num_cols=getattr(data, "num_cols", 0),
            cells=cells, section_path=resolve_path(current_heading),
        ))

    for item in doc.iterate_items():
        node = item[0] if isinstance(item, tuple) else item
        if isinstance(node, TextItem):
            label = str(node.label)
            text = (node.text or "").strip()
            if not text:
                continue
            if label == "section_header":
                current_heading = text if text in heading_set else current_heading
                continue
            if label in _NOISE_LABELS:
                # 记入 warnngs 统计即可，不作为检索块
                warnings.append(f"忽略{label}(p{_page(node)})")
                continue
            blocks.append(Block(
                block_id=f"b{len(blocks)}",
                label=label,
                text=text,
                provenance=Provenance(page_no=_page(node), bbox=_bbox(node, doc)),
                section_path=resolve_path(current_heading),
            ))
        elif isinstance(node, TableItem):
            add_table(node)
            seen_table_refs.add(getattr(node, "self_ref", ""))
        elif isinstance(node, PictureItem):
            pictures.append(Picture(picture_id=f"pic{len(pictures)}", page_no=_page(node)))

    # 兜底：若 iterate_items() 不产出 TableItem（遍历口径可能随版本变化），用 doc.tables 补齐。
    # 执行时请先确认真实行为：若本兜底从不触发（warnings 里没有"兜底"字样），这段可删。
    for tbl in doc.tables:
        if getattr(tbl, "self_ref", None) in seen_table_refs:
            continue
        warnings.append("表格未出现在 iterate_items() 中，已用 doc.tables 兜底")
        add_table(tbl)

    # ---------- 页面尺寸 ----------
    pages = [PageDim(page_no=p.page_no, width=float(p.size.width), height=float(p.size.height))
             for p in doc.pages.values()]

    meta = DocumentMeta(
        source_name=pdf_path.name,
        content_hash=content_hash,
        parser="docling",
        parser_version=_docling_version(),
        page_count=len(pages),
        pages_parsed=sorted(p.page_no for p in pages),
    )
    warnings.append(f"解析耗时 {parse_seconds:.1f}s")

    return NormalizedDocument(
        meta=meta, headings=[Heading(**h.__dict__) for h in tree],
        blocks=blocks, tables=tables,
        pictures=pictures, pages=pages,
        markdown=doc.export_to_markdown(), warnings=warnings,
    )


def _docling_version() -> str:
    try:
        from importlib.metadata import version

        return version("docling")
    except Exception:
        return "unknown"
```

- [ ] **Step 2: 加 smoke 测试**

```python
"""用真实年报的前几页验证转换全链路。"""
from pathlib import Path

import pytest

from app.converter import parse_pdf

PDF = Path(__file__).resolve().parents[2] / "docs" / "东方财富2025年年度报告.pdf"


@pytest.mark.skipif(not PDF.exists(), reason="样本 PDF 不在仓库中")
def test_parse_first_pages():
    doc = parse_pdf(PDF, page_range=(1, 4))
    assert doc.meta.page_count == 4
    assert doc.meta.content_hash
    assert len(doc.blocks) > 10, "正文块太少，可能解析失败"
    assert any(h.level == 1 for h in doc.headings), "至少要有章级标题"
    assert all(b.provenance.page_no >= 1 for b in doc.blocks), "每个块都必须有页码"
```

- [ ] **Step 3: 运行**

Run:
```powershell
cd document-parser-service
.\.venv\Scripts\python.exe -m pytest tests/ -q
```

Expected: 全部 PASS（耗时约 1 分钟，含模型加载）。

---

## Task 5: FastAPI 接口

**Files:**
- Create: `document-parser-service/app/main.py`

- [ ] **Step 1: 写 main.py**

```python
"""解析服务的 HTTP 接口。

设计取舍：接收 multipart 上传的 PDF 字节，而不是让 Python 去 COS 取文件。
理由：解析服务的职责边界是「看懂文档」，不该同时持有对象存储凭据；
文件从哪来（COS / 本地 / 未来其他源）由 Java 侧决定。
"""
from __future__ import annotations

import logging
import tempfile
from pathlib import Path

from fastapi import FastAPI, File, Form, HTTPException, UploadFile
from fastapi.responses import JSONResponse

from app.config import settings
from app.converter import parse_pdf

logger = logging.getLogger(__name__)

app = FastAPI(title="金融研报解析服务", version="0.1.0")


@app.get("/health")
def health() -> dict:
    return {"status": "ok", "hf_endpoint": settings.hf_endpoint}


@app.post("/parse")
def parse(   # 评审 M1：刻意用同步 def —— parse_pdf 是 25 分钟级 CPU 任务，
             # async def 会占住事件循环，把 /health 和并发请求全卡死；
             # FastAPI 会把同步端点丢进线程池执行。
    file: UploadFile = File(...),
    page_start: int | None = Form(None),
    page_end: int | None = Form(None),
) -> JSONResponse:
    """解析上传的 PDF，返回 NormalizedDocument。"""
    raw = file.file.read()   # 同步端点，不再 await
    if not raw:
        raise HTTPException(status_code=400, detail="上传文件为空")
    if len(raw) > settings.max_upload_bytes:
        raise HTTPException(
            status_code=413,
            detail=f"文件超过上限 {settings.max_upload_bytes} 字节",
        )
    if not raw.startswith(b"%PDF"):
        raise HTTPException(status_code=400, detail="不是合法 PDF（缺少 %PDF 魔数）")

    page_range = None
    if page_start is not None and page_end is not None:
        if page_start < 1 or page_end < page_start:
            raise HTTPException(status_code=400, detail="页范围非法")
        page_range = (page_start, page_end)

    with tempfile.TemporaryDirectory() as tmp:
        # 评审 M2：不能直接用 file.filename —— 可含 ../ 穿越临时目录
        path = Path(tmp) / Path(file.filename or "upload.pdf").name
        path.write_bytes(raw)
        try:
            doc = parse_pdf(path, page_range=page_range)
        except Exception as exc:
            logger.exception("解析失败")
            raise HTTPException(status_code=500, detail=f"解析失败: {type(exc).__name__}: {exc}") from exc

    return JSONResponse(content=doc.model_dump(mode="json"))
```

- [ ] **Step 2: 启动服务并验证健康检查**

Run（后台）:
```powershell
cd document-parser-service
.\.venv\Scripts\python.exe -m uvicorn app.main:app --host 127.0.0.1 --port 8000
```

另一个终端:
```powershell
curl.exe -s http://127.0.0.1:8000/health
```

Expected: `{"status":"ok","hf_endpoint":"https://hf-mirror.com"}`

- [ ] **Step 3: 用真实 PDF 调一次 /parse（抽 3 页，避免等 25 分钟）**

Run:
```powershell
cd document-parser-service
# 评审 M3：初版用 cmd 的 ^ 续行，本计划其余命令都是 PowerShell，^ 会被当参数报错
curl.exe -s -o _tmp_parse.json -w "HTTP %{http_code}  %{size_download} bytes\n" `
  -F "file=@..\docs\东方财富2025年年度报告.pdf" `
  -F "page_start=1" -F "page_end=3" `
  http://127.0.0.1:8000/parse
```

Expected: `HTTP 200`，字节数 > 50000。

```powershell
.\\.venv\\Scripts\\python.exe -c "import json;d=json.load(open('_tmp_parse.json',encoding='utf-8'));print('blocks',len(d['blocks']),'tables',len(d['tables']),'headings',len(d['headings']));print('sample path',d['blocks'][0]['section_path'])"
```

Expected: 打印块/表/标题数量，且 `sample path` 非空（证明确实挂上了章节路径）。

---

- [ ] **Step 4: 作业模式接口（A 方案，用户 + 评审确认）**

**为什么必须要它：** 181 页约 25 分钟。同步 HTTP 在这个量级上是**缺陷** ——
网络抖动就全部重来、Python worker 被占死、客户端看不到任何进度。
@application.yml@ 里那个 45 分钟的 @read-timeout@ 本身就是设计有问题的信号。

**做法：** 保留同步 @POST /parse@（小页范围、手工验证用），**新增作业接口**。
阶段 2 用「提交 + 轮询」；阶段 7 会用 Kafka 把这一层替换成事件驱动流水线 ——
**这是同一问题的演进版，不是替代品。**

先把校验逻辑抽出来给两个端点复用：

```python
def _validate(file: UploadFile, page_start: int | None, page_end: int | None):
    """同步端点与作业端点的共用校验。返回 (bytes, page_range)。"""
    raw = file.file.read()
    if not raw:
        raise HTTPException(status_code=400, detail="上传文件为空")
    if len(raw) > settings.max_upload_bytes:
        raise HTTPException(status_code=413,
                            detail=f"文件超过上限 {settings.max_upload_bytes} 字节")
    if not raw.startswith(b"%PDF"):
        raise HTTPException(status_code=400, detail="不是合法 PDF（缺少 %PDF 魔数）")
    page_range = None
    if page_start is not None and page_end is not None:
        if page_start < 1 or page_end < page_start:
            raise HTTPException(status_code=400, detail="页范围非法")
        page_range = (page_start, page_end)
    return raw, page_range
```

再追加作业存储与两个端点：

```python
# ---------- 作业模式 ----------
# 阶段 2 的作业状态在**进程内存**里，因此：
#   1) uvicorn 必须以单 worker 运行（--workers 默认 1，不要调大）
#   2) 服务重启后作业丢失 —— 刻意简化，阶段 7 换成 Kafka + 状态表
_JOB_DIR = Path(tempfile.gettempdir()) / "finagent-parse-jobs"
_JOB_DIR.mkdir(parents=True, exist_ok=True)


@dataclass
class Job:
    job_id: str
    source_name: str
    status: str = "pending"        # pending | running | done | failed
    submitted_at: float = field(default_factory=time.time)
    started_at: float | None = None
    finished_at: float | None = None
    page_range: tuple[int, int] | None = None
    pages_parsed: int | None = None
    result: dict | None = None
    error: str | None = None

    @property
    def elapsed_seconds(self) -> float:
        end = self.finished_at or time.time()
        return round(end - (self.started_at or self.submitted_at), 1)


_JOBS: dict[str, Job] = {}


def _run_job(job: Job, pdf_path: Path) -> None:
    """后台线程里跑解析。

    Docling 是 CPU 密集任务，用线程就够（单机、人工触发，并发极低）。
    关键：后台线程的异常**必须**写回 job —— 否则调用方会一直轮询到超时，
    永远不知道实际早已失败。
    """
    job.status = "running"
    job.started_at = time.time()
    try:
        doc = parse_pdf(pdf_path, page_range=job.page_range)
        job.result = doc.model_dump(mode="json")
        job.pages_parsed = doc.meta.page_count
        job.status = "done"
    except Exception as exc:
        logger.exception("作业 %s 解析失败", job.job_id)
        job.error = f"{type(exc).__name__}: {exc}"
        job.status = "failed"
    finally:
        job.finished_at = time.time()
        pdf_path.unlink(missing_ok=True)


@app.post("/jobs", status_code=202)
def submit_job(
    file: UploadFile = File(...),
    page_start: int | None = Form(None),
    page_end: int | None = Form(None),
) -> dict:
    """提交解析作业，**立即**返回 job_id。长文档走这条。"""
    raw, page_range = _validate(file, page_start, page_end)
    job_id = uuid.uuid4().hex
    path = _JOB_DIR / f"{job_id}.pdf"
    path.write_bytes(raw)      # 必须落到请求范围之外：请求结束临时目录会被清掉
    job = Job(job_id=job_id, source_name=file.filename or "upload.pdf", page_range=page_range)
    _JOBS[job_id] = job
    threading.Thread(target=_run_job, args=(job, path), daemon=True).start()
    return {"job_id": job_id, "status": job.status}


@app.get("/jobs/{job_id}")
def get_job(job_id: str) -> dict:
    """查询作业。result 只在 done 时返回。"""
    job = _JOBS.get(job_id)
    if job is None:
        raise HTTPException(status_code=404, detail=f"作业不存在: {job_id}")
    return {
        "job_id": job.job_id,
        "status": job.status,
        "elapsed_seconds": job.elapsed_seconds,
        "pages_parsed": job.pages_parsed,
        "error": job.error,
        "result": job.result if job.status == "done" else None,
    }
```

文件头补 import：

```python
import threading
import uuid
from dataclasses import dataclass, field
```

> **已知局限（要写进阶段 2 报告）**：作业状态在内存、无细粒度进度（只有 status + elapsed）、
> 无取消能力。Docling 不提供页级回调，做真实进度须改成分片解析再拼接 ——
> 那会破坏章节树，本阶段不做。

- [ ] **Step 5: 验证作业接口能"立刻返回"**

启动服务:
```powershell
cd document-parser-service
$env:HF_ENDPOINT = 'https://hf-mirror.com'
.\\.venv\\Scripts\\python.exe -m uvicorn app.main:app --host 127.0.0.1 --port 8000
```

另一个终端提交 181 页作业:
```powershell
cd document-parser-service
$j = curl.exe -s -F "file=@..\\docs\\东方财富2025年年度报告.pdf" http://127.0.0.1:8000/jobs | ConvertFrom-Json
Write-Output ("job_id = " + $j.job_id)
```

Expected: **立刻**打印 job_id（不是等 25 分钟）。这就是作业化要解决的问题。

轮询:
```powershell
$id = "<上一步的 job_id>"
while ($true) {
  $s = curl.exe -s "http://127.0.0.1:8000/jobs/$id" | ConvertFrom-Json
  Write-Output ("[" + (Get-Date -Format HH:mm:ss) + "] " + $s.status + "  " + $s.elapsed_seconds + "s")
  if ($s.status -in @('done','failed')) { break }
  Start-Sleep -Seconds 15
}
```

Expected: 状态 pending -> running -> done，done 时带 result 与 pages_parsed = 181。

---

## Task 6: 数据库迁移与文档仓储（Flyway）

**为什么在这里插入：** 已确认「**Java 独占持久化，Python 只解析 PDF**」。
本任务先把 DB 层立起来；Task 7 的 Adapter 只管 HTTP；Task 8 再把两者编排到一起。

**合并决策（已拍板）：** @document@ 与 @parse_job@ **合并**，不开新表。理由：
阶段 1 建表时把 @parse_status@ 放在 @document@ 上，答案就已经给出了；而且 parse-specific
的作业表在阶段 7 会被**通用**异步流水线取代 —— 中间没有它独立存在的窗口（详见文末「追加变更 B」）。

**Files:**
- Create: `src/main/resources/db/migration/V1__baseline.sql`
- Create: `src/main/resources/db/migration/V2__document_parse.sql`
- Create: `src/main/java/com/finagent/document/DocumentRepository.java`
- Modify: `pom.xml`（Flyway 依赖）
- Modify: `docker-compose.yml`（删除 init 挂载）
- Delete: `docker/postgres/init/`（内容搬进 V1）

- [ ] **Step 1: pom.xml 加 Flyway**

**Spring Boot 4 的模块拆分坑（执行期实测修正）**：需要**三个** artifact，缺一不可。

| artifact | 作用 | 缺了会怎样 |
|---|---|---|
| `spring-boot-flyway` | Spring Boot 4 把自动配置从 `spring-boot-autoconfigure` 拆成了独立模块（类比 `spring-boot-jdbc`） | **迁移静默不执行** —— 没有任何报错，只是表一直没建，测试报 relation does not exist |
| `flyway-core` | 迁移引擎本体 | 类找不到 |
| `flyway-database-postgresql` | Flyway 10+ 拆出的数据库方言模块 | `No database found to handle jdbc:postgresql:...` |

> ⚠️ **最容易踩的是第一个**：初版计划只写了后两个（那是 Spring Boot 3 的经验），
> 结果 Flyway 完全没被激活，而错误信息里**一个字都没提 Flyway**。

```xml
    <!-- 数据库版本化迁移，三件套 -->
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-flyway</artifactId>
    </dependency>
    <dependency>
      <groupId>org.flywaydb</groupId>
      <artifactId>flyway-core</artifactId>
    </dependency>
    <dependency>
      <groupId>org.flywaydb</groupId>
      <artifactId>flyway-database-postgresql</artifactId>
    </dependency>
```

版本由 Spring Boot BOM 管理，**不要手写版本号**（实测解析到 Flyway 11.14.1）。

- [ ] **Step 1b: 注意「非空 schema 无历史表」会让 Flyway 拒绝启动**

实测踩到：某次失败的测试跑先在 public 建了 `vector_store_test`（测试 profile 里 pgvector 自建表），
下一次启动 Flyway 直接报：

```text
Found non-empty schema(s) "public" but no schema history table.
Use baseline() or set baselineOnMigrate to true to initialize the schema history table.
```

**不要**用 `baselineOnMigrate=true` 绕过 —— 那会把 V1 标记为"已应用"而**跳过建表**，后面全部失败。
正确做法是 `docker compose down -v` 得到真正干净的库；一旦 Flyway 跑成功过一次，
后续启动都有历史表，就不会再遇到这个问题。

- [ ] **Step 2: 把阶段 1 的建表脚本搬进 V1__baseline.sql**

`V1__baseline.sql` = `docker/postgres/init/01-extensions.sql` + `02-schema.sql` 的**原样拼接**。

两点注意：
1. **@CREATE EXTENSION@ 保留在 V1 里** —— Flyway 用 @finagent@ 连接，它在 postgres 镜像里是超级用户，可以执行。
2. 这是**建表**而不是改表，因为下一步会 @down -v@ 得到全新库。

Run:
```powershell
Set-Location 'D:\Project\myproject\financial-ai-project'
New-Item -ItemType Directory -Force src\main\resources\db\migration | Out-Null
Get-Content docker\postgres\init\01-extensions.sql, docker\postgres\init\02-schema.sql |
  Set-Content src\main\resources\db\migration\V1__baseline.sql -Encoding UTF8
Get-ChildItem src\main\resources\db\migration
```

Expected: 出现 `V1__baseline.sql`。

- [ ] **Step 3: V2__document_parse.sql**

```sql
-- 合并方案：解析状态直接落在 document 上，不单独建 parse_job 表。
-- 分两组命名，因为它们的寿命不同：
--   上面 5 列是「关于文档的事实」，阶段 7 上 Kafka 后依然要保留；
--   下面 4 列是「在途状态」，阶段 7 会被通用异步流水线取代。

ALTER TABLE document
    ADD COLUMN IF NOT EXISTS parsed_object_key  VARCHAR(512),
    ADD COLUMN IF NOT EXISTS parser             VARCHAR(32),
    ADD COLUMN IF NOT EXISTS parser_version     VARCHAR(32),
    ADD COLUMN IF NOT EXISTS pages_parsed       INT,
    ADD COLUMN IF NOT EXISTS parsed_at          TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS parse_job_id       VARCHAR(64),
    ADD COLUMN IF NOT EXISTS parse_error        TEXT,
    ADD COLUMN IF NOT EXISTS parse_started_at   TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS parse_finished_at  TIMESTAMPTZ;

COMMENT ON COLUMN document.parse_status IS 'PENDING / RUNNING / DONE / FAILED';

-- 启动恢复扫描走这条（部分索引，只覆盖在途行，几乎不占空间）
CREATE INDEX IF NOT EXISTS idx_document_parse_active
    ON document (parse_status)
    WHERE parse_status IN ('PENDING', 'RUNNING');
```

- [ ] **Step 4: 删掉 init 挂载 + 重建库（**只在这一次需要 down -v**）**

`docker-compose.yml` 里删掉这一行（schema 从此只有一个来源）：
```yaml
      - ./docker/postgres/init:/docker-entrypoint-initdb.d:ro
```

Run:
```powershell
docker compose down -v
docker compose up -d
Start-Sleep -Seconds 15
docker compose ps --format '{{.Name}}  {{.Status}}'
```

Expected: healthy。注意此时库里**还没有任何业务表** —— 表由 Flyway 在应用启动时创建。

- [ ] **Step 5: 删除 init 目录**

Run:
```powershell
Remove-Item -Recurse -Force docker\postgres\init
```

保留它会让 schema 出现两个来源，以后必然对不上。

- [ ] **Step 6: application.yml 配置 Flyway**

```yaml
spring:
  flyway:
    enabled: true
    locations: classpath:db/migration
    # 全新库（Step 4 已 down -v），不需要 baseline-on-migrate
```

- [ ] **Step 7: DocumentRepository**

用 @JdbcTemplate@（@spring-boot-starter-jdbc@ 已在依赖里），**不引 JPA** —— 本项目 SQL 都是手写的，
多一层 ORM 只是负担。

```java
package com.finagent.document;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * document 表的仓储。
 *
 * <p>解析状态与解析结果都在这张表上（合并方案），没有独立的 parse_job 表。
 */
@Repository
public class DocumentRepository {

    private final JdbcTemplate jdbc;

    public DocumentRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 幂等登记文档：content_hash 已存在时复用该行。
     *
     * @return document id
     */
    public long upsert(long companyId, String docType, Integer docYear, String title,
                       String objectKey, String contentHash) {
        Long id = jdbc.queryForObject("""
                INSERT INTO document (company_id, doc_type, doc_year, title, object_key,
                                      content_hash, parse_status)
                VALUES (?, ?, ?, ?, ?, ?, 'PENDING')
                ON CONFLICT (content_hash) DO UPDATE
                    SET object_key = EXCLUDED.object_key,
                        title      = EXCLUDED.title
                RETURNING id
                """, Long.class, companyId, docType, docYear, title, objectKey, contentHash);
        return id == null ? -1L : id;
    }

    public void markRunning(long documentId, String jobId) {
        jdbc.update("""
                UPDATE document
                   SET parse_status = 'RUNNING', parse_job_id = ?,
                       parse_started_at = now(), parse_error = NULL
                 WHERE id = ?
                """, jobId, documentId);
    }

    public void markDone(long documentId, String parsedObjectKey, String parser,
                         String parserVersion, int pagesParsed) {
        jdbc.update("""
                UPDATE document
                   SET parse_status = 'DONE', parsed_object_key = ?, parser = ?,
                       parser_version = ?, pages_parsed = ?, parsed_at = now(),
                       parse_finished_at = now(), parse_error = NULL
                 WHERE id = ?
                """, parsedObjectKey, parser, parserVersion, pagesParsed, documentId);
    }

    public void markFailed(long documentId, String error) {
        jdbc.update("""
                UPDATE document
                   SET parse_status = 'FAILED', parse_error = ?, parse_finished_at = now()
                 WHERE id = ?
                """, error, documentId);
    }

    /** 在途文档：应用启动时用它恢复。 */
    public List<InFlightDocument> findInFlight() {
        return jdbc.query("""
                SELECT id, content_hash, parse_job_id
                  FROM document
                 WHERE parse_status IN ('PENDING', 'RUNNING')
                 ORDER BY id
                """, IN_FLIGHT_MAPPER);
    }

    public Long findCompanyIdByCode(String code) {
        List<Long> ids = jdbc.queryForList("SELECT id FROM company WHERE code = ?", Long.class, code);
        return ids.isEmpty() ? null : ids.get(0);
    }

    private static final RowMapper<InFlightDocument> IN_FLIGHT_MAPPER = (rs, n) ->
            new InFlightDocument(rs.getLong("id"), rs.getString("content_hash"),
                    rs.getString("parse_job_id"));

    /** 在途文档的最小投影。 */
    public record InFlightDocument(long documentId, String contentHash, String jobId) {
    }
}
```

- [ ] **Step 8: 验证迁移真的跑起来了**

Run:
```powershell
mvn -B test
docker compose exec -T postgres psql -U finagent -d finagent -c "select version, description, success from flyway_schema_history order by installed_rank;"
docker compose exec -T postgres psql -U finagent -d finagent -c "\d document"
```

Expected:
- @mvn test@ BUILD SUCCESS（Flyway 在上下文启动时执行 V1、V2）
- 迁移历史两行（V1、V2），@success@ 均为 @t@
- @\d document@ 里能看到新增的 9 列

---

## Task 7: Java 侧 Parser Adapter

**Files:**
- Create: `src/main/java/com/finagent/document/ParserAdapter.java`
- Create: `src/main/java/com/finagent/document/DoclingParserAdapter.java`
- Create: `src/test/java/com/finagent/DoclingParserAdapterTest.java`

- [ ] **Step 1: 定义接口**

设计文档 §8.3 要求解析引擎可替换，这是那个替换点。

```java
package com.finagent.document;

/**
 * 文档解析器抽象（设计文档 §8.3）。
 *
 * <p>把「PDF -> NormalizedDocument」这一步隔离出来，使 Docling 可被替换，
 * 也让业务层在测试中可以注入假实现，不必真的跑解析服务。
 */
public interface ParserAdapter {

    /** 解析 PDF，返回规范化文档。 */
    ParsedDocument parse(byte[] pdf, String sourceName, Integer pageStart, Integer pageEnd);

    /** 实现名，便于日志与降级判断。 */
    String name();
}
```

- [ ] **Step 2: 定义 ParsedDocument 与 DoclingParserAdapter**

```java
package com.finagent.document;

/**
 * 解析结果的最小骨架。
 *
 * <p>只保留本阶段 Java 侧真正会用到的字段；完整结构以 Python 服务的
 * NormalizedDocument 为准，落 COS 保存。不做全字段映射 —— 映射用不到的
 * 字段是纯粹的维护负担（YAGNI）。
 */
public record ParsedDocument(
        String contentHash,
        String parserVersion,
        int pageCount,
        int blockCount,
        int tableCount,
        int headingCount,
        int anomalyCount,
        String rawJson
) {
}
```

```java
package com.finagent.document;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

import java.time.Duration;

/**
 * 通过 HTTP 调用 Python 解析服务。
 */
@Component
public class DoclingParserAdapter implements ParserAdapter {

    private static final Logger log = LoggerFactory.getLogger(DoclingParserAdapter.class);

    private final RestClient client;
    private final ObjectMapper mapper;
    private final ParserProperties props;

    public DoclingParserAdapter(ParserProperties props, ObjectMapper mapper) {
        this.props = props;
        this.mapper = mapper;
        // 评审 B2：RestClient.Builder 只有 requestFactory(ClientHttpRequestFactory) 这一个重载
        //（javap spring-web-7.0.9 确认，无 Consumer/Function 版本）。初版写成 lambda
        // 且返回 SimpleClientHttpRequestFactory 而非 ClientHttpRequest → 编译不过。
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(10));
        factory.setReadTimeout(Duration.ofMinutes(props.readTimeoutMinutes()));
        this.client = RestClient.builder()
                .baseUrl(props.baseUrl())
                .requestFactory(factory)
                .build();
    }

    @Override
    public ParsedDocument parse(byte[] pdf, String sourceName, Integer pageStart, Integer pageEnd) {
        // A 方案：不直接死等一个 25 分钟的响应，而是「提交作业 + 轮询」。
        // 同步请求在故障时会让全部算力白费，而且客户端看不到任何进度。
        String jobId = submit(pdf, sourceName, pageStart, pageEnd);
        JsonNode root = awaitResult(jobId);

        int headings = root.path("headings").size();
        int anomalies = 0;
        for (JsonNode h : root.path("headings")) {
            if (h.hasNonNull("anomaly")) {
                anomalies++;
            }
        }
        return new ParsedDocument(
                root.path("meta").path("content_hash").asText(),
                root.path("meta").path("parser_version").asText(),
                root.path("meta").path("page_count").asInt(),
                root.path("blocks").size(),
                root.path("tables").size(),
                headings,
                anomalies,
                root.toString());
    }

    /** 提交解析作业，返回 job_id。 */
    private String submit(byte[] pdf, String sourceName, Integer pageStart, Integer pageEnd) {
        MultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
        form.add("file", new ByteArrayResource(pdf) {
            @Override
            public String getFilename() {
                return sourceName;
            }
        });
        if (pageStart != null && pageEnd != null) {
            form.add("page_start", String.valueOf(pageStart));
            form.add("page_end", String.valueOf(pageEnd));
        }

        String body = client.post()
                .uri("/jobs")
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(form)
                .retrieve()
                .body(String.class);
        try {
            String jobId = mapper.readTree(body).path("job_id").asText();
            if (jobId.isBlank()) {
                throw new IllegalStateException("解析服务未返回 job_id: " + body);
            }
            log.info("已提交解析作业 {}: {} ({} 字节)", jobId, sourceName, pdf.length);
            return jobId;
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("提交作业失败，返回的不是合法 JSON: " + body, e);
        }
    }

    /** 轮询直到作业完成，返回 result 节点。 */
    private JsonNode awaitResult(String jobId) {
        long deadline = System.nanoTime()
                + Duration.ofMinutes(props.jobTimeoutMinutes()).toNanos();
        String lastStatus = null;

        while (System.nanoTime() < deadline) {
            String body = client.get().uri("/jobs/{id}", jobId).retrieve().body(String.class);
            JsonNode job;
            try {
                job = mapper.readTree(body);
            } catch (Exception e) {
                throw new IllegalStateException("查询作业返回的不是合法 JSON: " + body, e);
            }
            String status = job.path("status").asText();

            if (!status.equals(lastStatus)) {
                log.info("解析作业 {} 状态: {} (已用 {}s)", jobId, status,
                        job.path("elapsed_seconds").asDouble());
                lastStatus = status;
            }
            if ("done".equals(status)) {
                return job.path("result");
            }
            if ("failed".equals(status)) {
                throw new IllegalStateException("解析作业失败: " + job.path("error").asText());
            }
            try {
                Thread.sleep(Duration.ofSeconds(props.pollIntervalSeconds()).toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("轮询被中断，作业 " + jobId + " 仍在服务端运行", e);
            }
        }
        throw new IllegalStateException("解析作业超时（" + props.jobTimeoutMinutes() + " 分钟）: "
                + jobId + " —— 作业可能仍在服务端运行，可用 GET /jobs/" + jobId + " 继续查");
    }

    @Override
    public String name() {
        return "docling";
    }
}
```

- [ ] **Step 3: 配置项**

```java
package com.finagent.document;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 解析服务配置。环境变量经宽松绑定注入：
 * FINAGENT_PARSER_BASE_URL / FINAGENT_PARSER_READ_TIMEOUT_MINUTES
 */
@ConfigurationProperties(prefix = "finagent.parser")
public record ParserProperties(
        String baseUrl,
        /** 单个 HTTP 请求的读超时；作业化之后不需要很长 */
        int readTimeoutMinutes,
        /** 轮询间隔 */
        int pollIntervalSeconds,
        /** 整个作业的最长等待（181 页约 25 分钟，留足余量） */
        int jobTimeoutMinutes) {
}
```

在 `application.yml` 追加：
```yaml
finagent:
  parser:
    # Python 解析服务（见 document-parser-service/）
    base-url: http://127.0.0.1:8000
    # 作业化之后单个请求都很短（提交 / 查询 / 取结果），不再需要 45 分钟读超时
    read-timeout-minutes: 5
    # 轮询间隔
    poll-interval-seconds: 10
    # 整个作业的最长等待（181 页约 25 分钟，留足余量）
    job-timeout-minutes: 60
```

并在 `FinAgentApplication` 上加 `@EnableConfigurationProperties(ParserProperties.class)`（或用一个 `docling.parser.enabled` 开关，未启动服务时不装配）。

- [ ] **Step 4: 写测试（服务未启动时自动跳过）**

```java
package com.finagent;

import com.finagent.document.ParsedDocument;
import com.finagent.document.ParserAdapter;
import com.finagent.storage.ObjectStorage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

@SpringBootTest
@ActiveProfiles("test")
class DoclingParserAdapterTest {

    private static final Path PDF = Path.of("docs/东方财富2025年年度报告.pdf");

    @Autowired
    private ParserAdapter parserAdapter;

    @Test
    void parsesFirstPagesThroughHttpService() throws Exception {
        assumeTrue(Files.exists(PDF), "样本 PDF 不在仓库中");
        assumeTrue(reachable(), "解析服务未启动，跳过");

        byte[] pdf = Files.readAllBytes(PDF);
        ParsedDocument doc = parserAdapter.parse(pdf, PDF.getFileName().toString(), 1, 3);

        assertThat(doc.parserVersion()).isNotBlank();
        assertThat(doc.pageCount()).isEqualTo(3);
        assertThat(doc.blockCount()).isGreaterThan(10);
        // 评审 B3：初版 contains(""headings"") 不是合法 Java，编译失败
        assertThat(doc.rawJson()).contains("\"headings\"");
    }

    private boolean reachable() {
        try (var socket = new java.net.Socket()) {
            socket.connect(new java.net.InetSocketAddress("127.0.0.1", 8000), 500);
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
```

- [ ] **Step 5: 运行**

Run:
```powershell
mvn -B test
```

Expected: BUILD SUCCESS；解析服务已启动时该用例真实执行，未启动时跳过。

---

## Task 8: 端到端全量解析（走 Java，不绕路）

**为什么这么排（用户指出、评审确认的缺口）：**

初版把「全量解析」放在 Python 脚本里，Java Adapter 只测 3 页 —— 结果 **181 页的真实文档
从来没有走过「Java → HTTP → Python」这条真正的路径**。而那条 Python 直连的路在生产里
根本不存在，等于测了一条死路。

现在：**主路径走 Java，Python 脚本降级为诊断工具**（端到端失败时用来定位问题在哪一侧）。

**本任务要验证的完整链路：**

```
COS(下载 PDF) → Java Adapter → POST /jobs → Python/Docling
                                    ↓ 轮询 GET /jobs/{id}
COS(上传 JSON) ← Java ← result
```

**Files:**
- Create: `src/test/java/com/finagent/FullDocumentPipelineTest.java`
- Modify: `pom.xml`（surefire 默认排除 e2e 标签）
- Keep: `document-parser-service/scripts/parse_full.py`（诊断工具，不再是主路径）

- [ ] **Step 1: DocumentParseService —— 把 Adapter / COS / DB 编排到一起**

Adapter 只管 HTTP、Repository 只管 DB、**编排单独一层**。这样换解析引擎不影响持久化，换存储也不影响解析。

```java
package com.finagent.document;

import com.finagent.storage.ObjectStorage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;

/**
 * 解析编排：登记文档 -> 提交作业 -> 轮询 -> 产物写 COS -> 更新 document。
 *
 * <p>持久化全部在这里完成；Python 解析服务不接触 DB 与 COS。
 */
@Service
public class DocumentParseService {

    private static final Logger log = LoggerFactory.getLogger(DocumentParseService.class);

    private final ParserAdapter parser;
    private final DocumentRepository documents;
    private final ObjectStorage storage;

    public DocumentParseService(ParserAdapter parser, DocumentRepository documents,
                                ObjectStorage storage) {
        this.parser = parser;
        this.documents = documents;
        this.storage = storage;
    }

    /** 解析并持久化，返回 document id。 */
    public long parseAndStore(long companyId, String docType, Integer docYear, String title,
                              String pdfObjectKey, byte[] pdf) {
        String contentHash = sha256(pdf);
        // 先落库拿 document_id —— 进程在此之后崩了，也留得下「这个文档待解析」的痕迹
        long documentId = documents.upsert(companyId, docType, docYear, title,
                pdfObjectKey, contentHash);
        log.info("文档已登记 document_id={} hash={}", documentId, contentHash);
        try {
            ParsedDocument parsed = parser.parse(pdf, title + ".pdf", null, null);
            persistResult(documentId, contentHash, parsed);
            return documentId;
        } catch (RuntimeException e) {
            documents.markFailed(documentId, e.getClass().getSimpleName() + ": " + e.getMessage());
            throw e;
        }
    }

    /** 恢复在途作业。由 DocumentParseRecovery 在启动时调用。 */
    public void recoverInFlight() {
        List<DocumentRepository.InFlightDocument> inFlight = documents.findInFlight();
        if (inFlight.isEmpty()) {
            return;
        }
        log.warn("发现 {} 个在途解析作业，开始恢复", inFlight.size());
        for (DocumentRepository.InFlightDocument doc : inFlight) {
            if (doc.jobId() == null || doc.jobId().isBlank()) {
                // 进程在「登记」与「提交」之间崩了，作业从未提交成功。
                // 不自动重提交：181 页要 25 分钟，开机静默重跑不合适。
                documents.markFailed(doc.documentId(),
                        "进程在提交作业前中断，作业从未提交（需重新提交）");
                log.warn("document_id={} 无 job_id -> FAILED", doc.documentId());
                continue;
            }
            try {
                ParsedDocument parsed = parser.fetchResult(doc.jobId());
                persistResult(doc.documentId(), doc.contentHash(), parsed);
                log.info("document_id={} 已从作业 {} 恢复完成", doc.documentId(), doc.jobId());
            } catch (RuntimeException e) {
                documents.markFailed(doc.documentId(), "恢复失败：" + e.getMessage());
                log.warn("document_id={} 恢复失败", doc.documentId(), e);
            }
        }
    }

    /** 产物写 COS 并更新 document。主流程与恢复流程共用。 */
    private void persistResult(long documentId, String contentHash, ParsedDocument parsed) {
        // key 里带 parser 版本：重解析写新对象，老产物不被覆盖
        String resultKey = "parsed/" + contentHash + "/" + parsed.parserName() + "-"
                + parsed.parserVersion() + "/normalized.json";
        storage.put(resultKey, parsed.rawJson().getBytes(StandardCharsets.UTF_8),
                "application/json");
        documents.markDone(documentId, resultKey, parsed.parserName(),
                parsed.parserVersion(), parsed.pageCount());
        log.info("解析完成 document_id={} 产物={}", documentId, resultKey);
    }

    static String sha256(byte[] data) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(data);
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("计算 SHA-256 失败", e);
        }
    }
}
```

> ⚠️ **前提**：这要求 @ParsedDocument@ 有 @parserName()@ 字段，且 Adapter 暴露 @fetchResult(jobId)@。
> **如果 Task 7 里没写这两个，先回 Task 7 补上** —— 它们是本任务的前置。

- [ ] **Step 2: 启动恢复扫描**

**这一步才让「durable」真正兑现。** 光把 job_id 落库只是"记录了作业"，没有东西去推进它 ——
不做这步，进程重启后那些行会永远停在 RUNNING。

用一个薄 ApplicationRunner，逻辑留在 Service 里（好测）：

```java
package com.finagent.document;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * 启动时在后台线程恢复在途解析作业。
 *
 * <p>刻意不阻塞启动：181 页要 25 分钟，不能让应用起不来。
 */
@Component
public class DocumentParseRecovery implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DocumentParseRecovery.class);

    private final DocumentParseService parseService;

    public DocumentParseRecovery(DocumentParseService parseService) {
        this.parseService = parseService;
    }

    @Override
    public void run(ApplicationArguments args) {
        Thread thread = new Thread(() -> {
            try {
                parseService.recoverInFlight();
            } catch (RuntimeException e) {
                log.warn("在途作业恢复过程出错（不影响应用启动）", e);
            }
        }, "parse-recovery");
        thread.setDaemon(true);
        thread.start();
    }
}
```

- [ ] **Step 3: 验证恢复逻辑（用假作业模拟，不烧 25 分钟）**

Run:
```powershell
Set-Location 'D:\Project\myproject\financial-ai-project'
docker compose exec -T postgres psql -U finagent -d finagent -c "INSERT INTO company (code, name) VALUES ('TEST','恢复测试') ON CONFLICT (code) DO NOTHING; INSERT INTO document (company_id, doc_type, title, object_key, content_hash, parse_status, parse_job_id) SELECT id, 'ANNUAL', '恢复测试文档', 'x.pdf', 'deadbeef', 'RUNNING', 'nonexistent-job-id' FROM company WHERE code='TEST' ON CONFLICT (content_hash) DO UPDATE SET parse_status='RUNNING', parse_job_id='nonexistent-job-id';"
mvn -B -Dtest=VectorStoreSmokeTest test
docker compose exec -T postgres psql -U finagent -d finagent -c "select content_hash, parse_status, left(parse_error,40) from document where content_hash='deadbeef';"
```

Expected: 应用启动后后台线程执行恢复；解析服务返回 404 时该行变为 @FAILED@ 且 @parse_error@ 含"恢复失败"。
**这说明"卡死"被消除了** —— 这正是恢复逻辑存在的意义。

- [ ] **Step 4: 端到端测试改走编排层**

把 Task 8 原有的 @FullDocumentPipelineTest@ 里 @parserAdapter.parse(...)@ 换成 @parseService.parseAndStore(...)@，
并在测试后人工核对产物指针：

```sql
SELECT parse_status, parser, parser_version, pages_parsed, parsed_object_key
  FROM document WHERE content_hash = '<PDF 的 sha256>';
```

- [ ] **Step 5: 把年报 PDF 上传到 COS**

Run:
```powershell
Set-Location 'D:\Project\myproject\financial-ai-project'
. .\scripts\load-env.ps1
`$cp = "target\classes;" + (Get-Content target\cp.txt -Raw).Trim()
java -cp $cp com.finagent.storage.CosCli upload "docs\东方财富2025年年度报告.pdf" "samples/300059-2025-annual-report.pdf"
java -cp $cp com.finagent.storage.CosCli list samples/
```

Expected: 列表中出现 `dev/samples/300059-2025-annual-report.pdf`。

- [ ] **Step 6: pom.xml —— 让 e2e 用例默认不跑，但可显式运行**

**注意一个 surefire 坑**：`-Dgroups=e2e` 是**包含**，而配置里的 `excludedGroups` 仍然生效，
两者叠加会导致用例被排除、一条都不跑。所以要把排除项做成可覆盖的属性：

```xml
  <properties>
    <!-- 端到端用例要 25 分钟，默认排除。
         运行时用 -Dsurefire.excludedGroups= 清空排除，再 -Dgroups=e2e 选中：
         mvn -Dsurefire.excludedGroups= -Dgroups=e2e -Dtest=FullDocumentPipelineTest test -->
    <surefire.excludedGroups>e2e</surefire.excludedGroups>
  </properties>
```

build/plugins 里追加：
```xml
      <plugin>
        <groupId>org.apache.maven.plugins</groupId>
        <artifactId>maven-surefire-plugin</artifactId>
        <configuration>
          <excludedGroups>${surefire.excludedGroups}</excludedGroups>
        </configuration>
      </plugin>
```

- [ ] **Step 7: 写端到端测试**

刻意**从 COS 取 PDF、再写回 COS** —— 一条用例同时覆盖阶段 1 的对象存储与阶段 2 的解析链路。

```java
package com.finagent;

import com.finagent.document.ParsedDocument;
import com.finagent.document.ParserAdapter;
import com.finagent.storage.ObjectStorage;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 端到端：COS -> Java Adapter -> Python/Docling -> Java -> COS。
 *
 * <p>默认被 surefire 排除（要 25 分钟量级）。手动运行：
 * <pre>mvn -Dsurefire.excludedGroups= -Dgroups=e2e -Dtest=FullDocumentPipelineTest test</pre>
 */
@SpringBootTest
@ActiveProfiles("test")
@Tag("e2e")
@EnabledIfEnvironmentVariable(named = "FINAGENT_COS_SECRET_ID", matches = ".+")
class FullDocumentPipelineTest {

    private static final String SOURCE_KEY = "samples/300059-2025-annual-report.pdf";
    private static final String RESULT_KEY = "parsed/300059-2025-annual/normalized.json";

    @Autowired
    private ObjectStorage storage;

    @Autowired
    private ParserAdapter parserAdapter;

    @Test
    void cosToParserToCos() {
        assumeTrue(reachable(), "解析服务未启动，跳过");
        assumeTrue(storage.exists(SOURCE_KEY), "COS 上还没有年报，先执行 Task 8 Step 1");

        // 1) 从 COS 取 PDF
        byte[] pdf = storage.get(SOURCE_KEY);
        assertThat(pdf).startsWith("%PDF".getBytes(StandardCharsets.US_ASCII));

        // 2) 走 Java Adapter（内部是「提交作业 + 轮询」）
        long t0 = System.currentTimeMillis();
        ParsedDocument doc = parserAdapter.parse(pdf, "300059-2025-annual-report.pdf", null, null);
        long elapsedMs = System.currentTimeMillis() - t0;

        // 3) 结果写回 COS
        storage.put(RESULT_KEY, doc.rawJson().getBytes(StandardCharsets.UTF_8), "application/json");

        int emptyPaths = 0;
        if (doc.rawJson().contains("\"section_path\":[]")) {
            emptyPaths = doc.rawJson().split("\"section_path\":\\[\\]", -1).length - 1;
        }
        System.out.printf("""
                ===== 端到端结果 =====
                页数          : %d
                正文块        : %d
                表格          : %d
                标题          : %d (anomaly %d)
                空 section_path 块: %d
                耗时          : %.1f 分钟
                结果          : %s
                ======================
                """,
                doc.pageCount(), doc.blockCount(), doc.tableCount(),
                doc.headingCount(), doc.anomalyCount(), emptyPaths,
                elapsedMs / 60000.0, RESULT_KEY);

        // 4) 结构性断言：页码与章节路径是本阶段的核心交付
        assertThat(doc.pageCount()).isEqualTo(181);
        assertThat(doc.blockCount()).isGreaterThan(500);
        assertThat(doc.rawJson()).contains("\"section_path\"");
        assertThat(storage.exists(RESULT_KEY)).isTrue();
    }

    private boolean reachable() {
        try (var socket = new java.net.Socket()) {
            socket.connect(new java.net.InetSocketAddress("127.0.0.1", 8000), 500);
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
```

- [ ] **Step 8: 启动解析服务，跑端到端（预计 25 分钟）**

```powershell
cd document-parser-service
$env:HF_ENDPOINT = 'https://hf-mirror.com'
.\.venv\Scripts\python.exe -m uvicorn app.main:app --host 127.0.0.1 --port 8000
```

另一个终端:
```powershell
Set-Location 'D:\Project\myproject\financial-ai-project'
. .\scripts\load-env.ps1
mvn -B -Dsurefire.excludedGroups= -Dgroups=e2e -Dtest=FullDocumentPipelineTest test
```

Expected: 打印端到端结果表 + `BUILD SUCCESS`；日志里能看到作业状态 pending → running → done。

**若失败，按下表定位（这就是保留 Python 诊断脚本的原因）：**

| 现象 | 问题在哪一侧 | 怎么确认 |
|---|---|---|
| 提交就报错 / 连不上 | Java 或服务未启动 | `curl http://127.0.0.1:8000/health` |
| 作业 status=failed | **Python 侧** | `GET /jobs/{id}` 的 `error` 字段 |
| Java 侧 JSON 解析失败 | Java 侧映射 | 看服务端返回体 |

Python 侧单独复现（诊断用，非主路径）:
```powershell
cd document-parser-service
.\.venv\Scripts\python.exe scripts/parse_full.py "..\docs\东方财富2025年年度报告.pdf" "..\_tmp_full.json"
```

- [ ] **Step 9: 记录本阶段关键指标**

把 Step 4 输出（页数 / 耗时 / RSS / 标题数 / **错误归属数量与占比** / 表格数）
写进 `docs/阶段2-解析结果报告.md`。

---

## Task 9: 收尾与文档

- [ ] **Step 1: 更新 `README.md`**

追加「阶段 2：文档解析」小节：如何启动解析服务、如何调 `/parse`、HF 镜像的必要性。

- [ ] **Step 2: 更新 `docs/阶段1-环境搭建手册.md`**

在「启动依赖」里补上解析服务的启动步骤（因为阶段 2 之后它是常驻进程）。

- [ ] **Step 3: 写 `docs/阶段2-解析结果报告.md`**

内容 = Task 7 Step 4 记录的全部指标 + 本章「偏差登记」的结论。

- [ ] **Step 4: 清理临时文件**

Run:
```powershell
cd document-parser-service
Remove-Item -Force _tmp_*.json, _tmp_*.txt -ErrorAction SilentlyContinue
Remove-Item -Force ..\_tmp_*.json, ..\_tmp_*.txt -ErrorAction SilentlyContinue
```

Expected: 无残留 `_tmp_*`。

---

## 验收标准

| # | 检查 | 通过条件 |
|---|---|---|
| 1 | 解析服务可启动 | `GET /health` 返回 200 |
| 2 | 单测全绿 | `pytest tests/ -q` 全 PASS |
| 3 | **章节路径非空** | `/parse` 返回的 block 中，`section_path` 非空的比例达标。**评审 M6**：首个标题之前的正文（封面、目录前页）路径必然为空，必须显式统计空路径块数并规定上限，不能笼统要求「每个 block 都有」 |
| 4 | **页码可定位** | 每个 block / table 都有 `page_no`（阶段 5 引用溯源的前提） |
| 5 | 全量解析成功（**走 Java**） | 181 页经 `FullDocumentPipelineTest` 产出 JSON，无异常退出 |
| 6 | 结果已入 COS | `CosCli list parsed/` 能看到 `normalized.json`，且**由 Java 侧写入**（不是 Python 脚本） |
| 7 | **错误归属已量化** | 报告中给出「章标题排在自己子节之后」导致的错误归属数量与占比（**不是** anomaly 总数）；**占比 >= 5% 时必须先解决再进阶段 3** |
| 8 | Java 侧连通 | `mvn test`（默认，不含 e2e）BUILD SUCCESS；e2e 用 `-Dsurefire.excludedGroups= -Dgroups=e2e` 单独跑 |
| 9 | 耗时已记录 | 报告中给出 s/页 与内存峰值，用于阶段 3 排期 |
| 10 | **重启可恢复** | 用假作业模拟在途行，重启后该行被推进为 FAILED/DONE，不会永远停在 RUNNING |
| 11 | **产物指针落库** | document.parsed_object_key 非空且指向 COS 上真实存在的对象；parse_status=DONE |

## 本阶段的边界

- **不做切块**（Parent/Child 划分是阶段 3）
- **不做入库**（`document` / `document_chunk` 写入是阶段 3）
- **不做 embedding**（阶段 3）
- 表格质量只**记录**不修复：释义表被解析成键值对是已发现事实，是否需要在阶段 3 做特殊处理，取决于阶段 4 的召回评估结果

## 偏差登记（需回写设计文档 v0.10）

本阶段实测与设计文档不一致之处，完成后需回写：

| 设计文档原述 | 实测事实 | 建议改法 |
|---|---|---|
| §8.5 假设 Parent = 完整语义小节，隐含 Docling 提供层级 | Docling 的 `section_header` 全在 L1，`body.children` 平铺；chunker 的 `headings` 只有一层 | 在 §8.5 增加一句：**章节树由本项目自建**（`section_tree.py`），Docling 只提供扁平标题序列 |
| §8.8 `section_path TEXT` 未说明来源 | 同上 | 补充：`section_path` 由编号规则重建，并记录 `anomaly` 供审计 |
| §4.1 未提解析性能预期 | CPU 实测约 8.2 s/页，181 页约 25 分钟 | 补充解析性能基线；若未来要提速，再评估 GPU 或并行分片 |

| 阶段 1 手册说「改完 init SQL 必须 down -v」 | 已引入 Flyway，schema 只有一个来源 | 改为：改表只需新增 V*.sql，不再需要 down -v |

## 未决问题（留给阶段 3）

1. `section_path` 的 anomaly 若集中在「章标题乱序」，是接受，还是用文档自带目录页反查修正？
2. 释义表的键值化是否需要还原成表格？影响「释义类查询」的召回。
3. DoclingDocument 原始 JSON（181 页约 5MB）与 NormalizedDocument 是否都存 COS，还是只存后者？

---

## 附：评审修订记录（2026-10-13）

计划初版经 plan-document-reviewer 评审。评审在本机同一套 venv、以及 spring-web 7.0.9 的真实 classpath 上
**实测复现**后给出结论，共 6 个阻塞项 + 4 个高阶正确性项 + 若干中低项。处置如下。

### 阻塞级（已全部修入正文）

| # | 问题 | 处置 |
|---|---|---|
| B1 | Task 3 正则的 `\d`/`\s` 转义丢失，数字编号永远匹配不上，测试必挂 | 已改为 `r"^\d+[、．]\s*"` 等 |
| B2 | `RestClient.Builder.requestFactory` 只有 `ClientHttpRequestFactory` 一个重载（`javap` 确认），lambda 写法**编译不过** | 已改为先构造实例再传入 |
| B3 | `contains(""headings"")` 不是合法 Java，`mvn test` 编译失败 | 已改为 `contains("\"headings\"")` |
| B4 | requirements 漏了 pytest，而 Task 3/4 的命令全靠它 | 已补 `pytest>=8.3,<9` |
| B5 | 决策门引用的 `section_tree_report.py` 与 `_tmp_doc.json` **都没有任何任务产出** | 已改为 Task 3 交付 `section_tree_report.py`，由脚本自行解析 |
| B6 | `kv.key`/`kv.value` 是**不存在的 API**；且 PDF 管线**根本不产出** `key_value_items` | 已删除整条链路（模型/字段/循环/报告行/偏差登记行），并改写「发现 #3」 |

### 高阶正确性（已修）

| # | 问题 | 处置 |
|---|---|---|
| H1 | 异常检测**抓不到**计划自己点名的乱序，真实归属错误报 **0%**，决策门反而放行 | 改为**弹栈前**检测「章标题出现在其子节之后」；判据重写 |
| H2 | 所有表格的 `section_path` 都取「全文最后一个标题」，**结构性错误** | 表格移入同一遍 `iterate_items()` 处理 |
| H3 | `_bbox` 未归一化，与模型注释直接矛盾；且文本块与表格单元格**坐标原点不同** | 新增归一化，BOTTOMLEFT/TOPLEFT 分别处理 |
| H4 | `test_numbered_headings_get_correct_path` 的断言是**恒真式**，什么都没验证 | 改为直接断言真实路径 + 锁定已知缺陷 |

### 中低（已修）

M1 同步端点防阻塞 · M2 文件名防路径穿越 · M3 PowerShell 续行 · M4 tracemalloc→psutil ·
M5 `_NOISE_LABELS` 注释与行为不符 · M6 标题页对齐错位、`path_lookup` 同名覆盖 ·
M9 配置注释变量名写错 · L1 pydantic v2 弃用 `class Config` · L3 文档与实现对齐

### 执行时必须先复验的两件事（评审明确要求）

1. **`iterate_items()` 是否产出 `TableItem`** —— 代码里加了兜底分支：若产出，兜底应**从不触发**（可删）；若不产出，warnings 里会出现「已用 doc.tables 兜底」。这是决定 H2 修法是否落地的前提。
2. **`section_tree_report` 跑出的真实错误归属占比** —— 本阶段最重要的量化数字，也是「是否需要追加目录页反查任务」的唯一判据。

> 评审同时确认了若干**不必改**的点（避免误改）：`page_range` 语义正确、`TableData.grid`/`TableCell` API 正确、
> `docling_core.types.doc` 导入路径正确、`str(label)` 返回 `section_header`、
> Spring 的 `ByteArrayResource` 覆盖 `getFilename()` 与 multipart 用法正确。

### 追加变更 B：document 与 parse_job 合并（用户决策，2026-10-13）

**问题：** 作业化之后，解析状态与结果放哪？要不要为它单开一张 `parse_job ` 表？

**结论：合并**，不建 `parse_job `。两条理由：

1. **阶段 1 建表时答案已经给出** —— `document ` 里本来就有 `parse_status `，说明「这份文档解析到
   什么程度」被认定为 document 的属性。开新表反而是偏离原设计。
2. **`parse_job ` 注定是"专表"，而未来需要的是"通表"** —— 阶段 7 的 Kafka 流水线要统一调度
   解析/切块/向量化，那时正确的形态是通用的 `async_job ` 表或直接交给 Kafka。
   parse-specific 的表在"现在"和"未来"之间没有独立存在的窗口。

**分开的唯一强论据**是建模正确性：结果 = f(PDF 内容, parser 版本)，名义上是 1:N。但它被两点化解：

- 本项目的重解析是**替换**而非**共存**（唯一场景是 Task 3 决策门判定章节树错误率过高后重跑）
- **产物版本历史由 COS 免费提供** —— key 里带 parser 版本
  （`parsed/{hash}/docling-2.138.0/normalized.json `），重解析写新对象、老产物不删。
  丢的只是"库内索引"，不是数据本身

**何时拆出去（可逆决策）：** 出现任一情况就拆 —— (1) 多份解析结果需在库内共存并对比；
(2) 解析要支持并发/批量调度；(3) 多种异步任务需统一调度。
但**第 3 条一旦成立，该建的是通用作业表，不是 `parse_job `**。

**顺带引入 Flyway：** 新增列需要迁移，而原先的 Docker init 脚本方式要求每次改表都 `down -v `，
在阶段 3~10 会反复改 schema 的情况下不可行。现在 schema 只有一个来源（Flyway），
阶段 1 手册里「改完 init SQL 必须 down -v」的说明需要回写。


### 追加变更 C：用坐标重排替代「目录页反查」（执行期发现，2026-10-13）

**原计划：** Task 3 决策门 < 5% 则接受，>= 5% 则追加任务「解析文档自带目录页，用章标题 + 页码反查归属」。

**实测：** 错误归属 5.9%，触发门槛。但**原定解法不可行** —— 目录只提供章到页码的映射，
而问题出在**同一页内**（`一、~四、` 与 `第二节` 都在第 6 页），页码无法区分先后。

**改用坐标：** Docling 的 `section_header` 带 `bbox`，实测 `第二节` 的 `t=751.1` 是页内最高，
按「页码 + 垂直位置」重排后错误归属降到 **0%**，5/17 个标题次序被修正。

**教训（值得记）：** 决策门本身是对的 —— 它逼出了真实数字。但**门槛通过后该用什么解法，
写计划时是想当然的**（"目录页里不是有章标题吗"）。真正执行时才发现维度选错了：
需要的是**页内位置**，而目录给的是**页间位置**。

**对设计文档的影响：** §8.5 除了「章节树由本项目自建」，还要补一句
「**标题阅读顺序需按坐标重排，不能直接信任 Docling 的 iterate_items() 顺序**」。


### 追加变更 D：Spring Boot 4 的平台差异（执行期发现，2026-10-15）

阶段 2 是本项目第一次真正用 Spring Boot 4，踩到两处与 Boot 3 经验不符的地方。
它们的共同特征是：**错误信息完全不指向真正的原因**。

**D1. Flyway 需要第三个 artifact**（已修正到 Task 6 Step 1）

| artifact | 缺了会怎样 |
|---|---|
| `spring-boot-flyway` | **迁移静默不执行** —— 无任何报错，只是表一直没建 |
| `flyway-core` | 类找不到 |
| `flyway-database-postgresql` | `No database found to handle jdbc:postgresql:...` |

Boot 4 把自动配置从 `spring-boot-autoconfigure` 拆成了独立模块（`spring-boot-jdbc`、
`spring-boot-flyway` 这种命名）。加了后两个之后报的是 `relation "company" does not exist` ——
**一个字都没提 Flyway**。

**D2. Spring Boot 4 默认用 Jackson 3，ObjectMapper 的包名变了**

实测依赖树：

```text
org.springframework.boot:spring-boot-starter-jackson
  \- org.springframework.boot:spring-boot-jackson
      \- tools.jackson.core:jackson-databind:3.1.5     <-- 自动配置的是这个（Jackson 3）
com.fasterxml.jackson.core:jackson-databind:2.21.5      <-- Jackson 2，Spring AI 传递进来，没有 Bean
```

所以注入 `com.fasterxml.jackson.databind.ObjectMapper` 会报
`No qualifying bean of type ... ObjectMapper`，**而且它是能编译过的**（类在 classpath 上），
只有启动时才炸。正确写法是：

```java
import tools.jackson.databind.JsonNode;        // 不是 com.fasterxml.jackson.databind
import tools.jackson.databind.ObjectMapper;
```

> 后续阶段若还要手写 JSON 处理，一律用 `tools.jackson`；如果确实需要 Jackson 2，
> 要显式引入 `spring-boot-jackson2` 模块，否则拿不到 Bean。


**D3. 加了编排层之后，没凭据的机器起不来了**（Task 8 执行期发现）

`DocumentParseService` 依赖 `ObjectStorage`，而后者是条件装配的（要有 COS 凭据）。
结果是：**没有 .env 的机器上整个 Spring 上下文启动失败**，连 `VectorStoreSmokeTest` 都跑不了。

这违反了阶段 1 定下的原则「缺凭据时应用仍可正常启动，相关测试自动跳过」。

**修法**：给 `DocumentParseService` 和 `DocumentParseRecovery` 加上与 `ObjectStorageConfig` **完全相同**的
`@ConditionalOnProperty(prefix = "finagent.cos", ...)`。

> 用`@ConditionalOnBean` 也能写，但它是**顺序敏感**的（取决于 Bean 定义的处理顺序），
> 而 `@ConditionalOnProperty` 两个地方独立求值、结果一致，更稳。
>
> 代价是条件判断重复了三处 —— 换取的是「加新的依赖 COS 的组件时不会忘」这个显式约定。

### 追加变更 E：job_id 必须落库，否则断线无法恢复（执行期发现，2026-10-15）

**最隐蔽、也最有价值的一个发现。**

初版实现里，适配器把提交与轮询包成了一个方法：

```java
ParsedDocument parsed = parser.parse(pdf, ...);   // 内部 submit + poll
```

**job_id 从未传出适配器**，编排层拿不到，也就无法调用 `markRunning(documentId, jobId)` 落库。后果：客户端在轮询期间断开时，数据库里那行是 `PENDING` 且 job_id 为空，恢复流程只能判定「作业从未提交」—— **而实际上作业正在服务端跑得好好的**。

**这个缺陷只有真跑长链路才会暴露**：3 页的测试 16 秒就跑完，根本来不及断线。

**修法**：把 parse() 拆回两步，让编排层显式持有 job_id：

```java
String jobId = parser.submit(pdf, "...", null, null);
documents.markRunning(documentId, jobId);     // <-- durable 的支点
ParsedDocument parsed = parser.fetchResult(jobId);
```

> **教训**：作业化不只是「提交 + 轮询」两个动作，**中间那个 job_id 是必须持久化的状态**。
> 把它封装进一个方法看起来很干净，实际上把 durable 的支点藏掉了。
>
> 计划里其实写了 markRunning 这一步，但实现时被「接口更简洁」的直觉带偏了 ——
> 这也说明：**计划里写了什么不重要，实现时有没有照着做才重要。**

### 实测性能修正（2026-10-15）

| 指标 | 计划估算（前 8 页外推） | **实测（181 页）** |
|---|---|---|
| 单页耗时 | 8.2 s/页 | **30.5 s/页** |
| 总耗时 | 约 25 分钟 | **92.0 分钟** |
| 内存 | 未估 | 约 940 MB |

偏差 **3.7 倍**。前 8 页是封面/目录/短章节，正文的密集财报表格（345 张）才是主要开销。
job-timeout-minutes 已从 60 放宽到 180 —— 92 分钟的实测值让 60 分钟险象环生。
