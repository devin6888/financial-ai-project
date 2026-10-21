
from __future__ import annotations
from typing import Literal
from pydantic import BaseModel, Field

class BBox(BaseModel):
    left: float
    top: float
    right: float
    bottom: float

class Provenance(BaseModel):
    page_no: int = Field(description="1 起始的页码")
    bbox: BBox | None = None

class Heading(BaseModel):
    text: str
    level: int = Field(description="1 最高；由编号规则推断")
    order: int = Field(description="在标题序列中的序号")
    numbering: str = Field(description="chapter/section/subsection/item/subitem/none")
    path: list[str] = Field(default_factory=list, description="自根到本标题的路径")
    page_no: int | None = None
    anomaly: str | None = Field(default=None, description="层级跳变等异常说明")

class Block(BaseModel):
    block_id: str
    label: str = Field(description="Docling 标签")
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

class KeyValueItem(BaseModel):
    key: str
    value: str
    page_no: int
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
    meta: DocumentMeta
    headings: list[Heading] = Field(default_factory=list)
    blocks: list[Block] = Field(default_factory=list)
    tables: list[Table] = Field(default_factory=list)
    key_values: list[KeyValueItem] = Field(default_factory=list)
    pictures: list[Picture] = Field(default_factory=list)
    pages: list[PageDim] = Field(default_factory=list)
    markdown: str = Field(default="", description="Docling 的 markdown 导出，便于人工核对")
    warnings: list[str] = Field(default_factory=list)
