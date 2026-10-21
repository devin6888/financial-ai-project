"""NormalizedDocument：解析服务的统一输出格式。

它是 Parser Adapter 契约的一部分（设计文档 §8.3/§8.4）：无论底层用 Docling 还是别的引擎，
下游看到的都是这个结构，因此换引擎不会波及业务层。

与设计文档 §8.4 的差异（阶段 2 实测后调整）：
  - Docling 不给多级标题层级，因此 section_path 由本项目自建（见 section_tree.py），
    并在 Heading 上额外记录 numbering / anomaly 供审计。
  - 初版曾为 Docling 的 key_value_items 预留字段，缓存复验 len(doc.key_value_items) == 0
    （PDF 管线不产出该类型），已按评审删除，避免死代码。
  - bbox 一律归一化到 [0,1] 并统一为左上原点：实测文本块是 BOTTOMLEFT、
    表格单元格是 TOPLEFT，坐标原点是混的（评审 H3）。
"""
from __future__ import annotations

from typing import Literal

from pydantic import BaseModel, Field


class BBox(BaseModel):
    """归一化到 [0,1]、左上原点的页面坐标，便于前端按比例还原高亮。"""

    left: float
    top: float
    right: float
    bottom: float


class Provenance(BaseModel):
    """元素在原文中的位置 —— 引用可定位的前提（设计文档 §9.12）。"""

    page_no: int = Field(description="1 起始的页码")
    bbox: BBox | None = None


class Heading(BaseModel):
    """一个标题及其在自建章节树中的位置。"""

    text: str
    level: int = Field(description="1 最高；由中文编号规则推断")
    order: int = Field(description="在标题序列中的序号")
    numbering: str = Field(description="chapter/section/subsection/item/subitem/none")
    path: list[str] = Field(default_factory=list, description="自根到本标题的路径")
    page_no: int | None = None
    anomaly: str | None = Field(default=None, description="层级跳变或乱序等异常说明")


class Block(BaseModel):
    """正文元素：段落 / 列表项 / 脚注等。"""

    block_id: str
    label: str = Field(description="Docling 标签，如 text / list_item / footnote")
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
    # 表格区域（归一化 [0,1] 左上原点），供阶段 5 引用高亮使用。
    # 多页表格只取**首页**区域 —— 单个 bbox 表达不了跨页范围，需配合 page_start/page_end 使用。
    bbox: BBox | None = None


class Picture(BaseModel):
    picture_id: str
    page_no: int
    caption: str | None = None
    # 图片区域，供阶段 5 定位图表
    bbox: BBox | None = None


class PageDim(BaseModel):
    """页面尺寸，用于把 bbox 反算成绝对坐标。"""

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
    """解析服务对外的统一输出。"""

    meta: DocumentMeta
    headings: list[Heading] = Field(default_factory=list)
    blocks: list[Block] = Field(default_factory=list)
    tables: list[Table] = Field(default_factory=list)
    pictures: list[Picture] = Field(default_factory=list)
    pages: list[PageDim] = Field(default_factory=list)
    markdown: str = Field(default="", description="Docling 的 markdown 导出，便于人工核对")
    warnings: list[str] = Field(default_factory=list)
