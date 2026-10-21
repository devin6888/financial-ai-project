"""Docling 封装：把 PDF 转成 NormalizedDocument。

只做转换与结构映射，不做切块（切块是阶段 3 的事）。
"""
from __future__ import annotations

import bisect
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
from app.section_tree import build_section_tree, path_lookup, sort_by_position

# 不作为正文块输出的标签。
# 评审 M5 更正：实测 iterate_items() 默认**不会**遍历 page_header/page_footer
#（它们在 furniture 层）—— 本次样本出现的标签只有 picture / section_header / text。
# 保留过滤是防御性的，但不要指望它会产生「忽略 page_header」的告警。
_NOISE_LABELS = {"page_header", "page_footer"}


def _page(item) -> int:
    return item.prov[0].page_no if getattr(item, "prov", None) else 0


def _raw_top(item) -> float | None:
    """原始（未归一化）bbox.t，用于按页内位置排序与归属判定。

    注意 Docling 的文本块实测是 **BOTTOMLEFT** 原点：**t 越大越靠上**。
    """
    if not getattr(item, "prov", None):
        return None
    bbox = item.prov[0].bbox
    return float(bbox.t) if bbox is not None else None


def _bbox(item, doc: DoclingDocument) -> BBox | None:
    """把 Docling 的 bbox 归一化到 [0,1]，并统一为左上原点。

    评审修正 H3：初版直接返回 b.l/b.t/b.r/b.b —— 缓存复验那是 **PDF 点坐标**
    （样例 l=244.0, t=686.37，页尺寸 595.2x842.04），与 BBox 模型注释声称的 [0,1]
    直接矛盾，阶段 5 的前端高亮会用到错坐标。
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
        # PDF 原点在左下 -> 翻成左上原点
        top, bottom = (h - float(b.t)) / h, (h - float(b.b)) / h
    else:
        top, bottom = float(b.t) / h, float(b.b) / h
    return BBox(left=float(b.l) / w, top=top, right=float(b.r) / w, bottom=bottom)


class _SectionResolver:
    """按**坐标**把一个元素映射到它所属的章节路径。

    为什么不能用遍历顺序（Task 3 决策门的连带发现）：
        Docling 的阅读顺序会把章标题排到它自己的子节之后。如果照旧用「遍历中遇到的
        最后一个标题」当归属，那 一、~四、 下面的正文块依然会挂到 第一节 下 ——
        标题树修好了，块的归属仍然是错的。

    做法：
        把标题按 (页码, -t) 排序后做二分查找，取「位置在元素之前且最靠后」的那个标题。
        这**不依赖遍历顺序**，即使块本身乱序也能各自拿到正确的路径。
    """

    def __init__(self, tree: list[Heading], items: list[tuple[str, int | None, float | None]]):
        self._keys: list[tuple[int, float]] = []
        self._paths: list[list[str]] = []
        for heading, (_text, page, top) in zip(tree, items):
            self._keys.append((page or 0, -(top if top is not None else 0.0)))
            self._paths.append(heading.path)

    def path_for(self, page: int, top: float | None) -> list[str]:
        if top is None or not self._keys:
            return []
        idx = bisect.bisect_right(self._keys, (page or 0, -top)) - 1
        return self._paths[idx] if idx >= 0 else []


def parse_pdf(pdf_path: str | Path,
              page_range: tuple[int, int] | None = None) -> NormalizedDocument:
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
    # 阶段 2 决策门实测：Docling 的阅读顺序把「第二节 公司简介」排到了它自己的
    # 「一、~四、」之后（实际第二节在页内最高处 t=751.1）。因此**先按坐标重排再建树**。
    # 评审 M6：先滤掉空标题再对齐页码，否则一旦有空 section_header 就会整体错位。
    heading_items = [
        ((it.text or "").strip(), _page(it), _raw_top(it))
        for it in doc.texts
        if str(it.label) == "section_header" and (it.text or "").strip()
    ]
    heading_items = sort_by_position(heading_items)
    tree = build_section_tree([text for text, _, _ in heading_items])
    for heading, (_text, page, _top) in zip(tree, heading_items):
        heading.page_no = page

    lookup = path_lookup(tree)
    resolver = _SectionResolver(tree, heading_items)

    def resolve(node) -> list[str]:
        """元素的章节路径：优先按坐标，取不到坐标时回落遍历顺序。"""
        page, top = _page(node), _raw_top(node)
        path = resolver.path_for(page, top)
        return path

    # ---------- 正文块 / 表格 / 图片 ----------
    # 评审 H2：表格必须与正文在**同一遍**遍历里处理。初版把表格放在循环之后，
    # 那时「当前标题」已停在全篇最后一个标题上，导致所有表格共享同一个错误路径。
    blocks: list[Block] = []
    pictures: list[Picture] = []
    tables: list[Table] = []
    seen_table_refs: set[str] = set()

    def add_table(tbl: TableItem) -> None:
        pages = [p.page_no for p in getattr(tbl, "prov", [])] or [0]
        data = tbl.data
        cells: list[TableCell] = []
        try:
            for r, row in enumerate(data.grid):
                for c, cell in enumerate(row):
                    if cell is None:
                        continue
                    cells.append(TableCell(
                        text=str(cell.text or ""), row=r, col=c,
                        row_span=getattr(cell, "row_span", 1) or 1,
                        col_span=getattr(cell, "col_span", 1) or 1))
        except Exception as exc:  # 网格结构异常不应让整篇解析失败
            warnings.append(f"表格 {len(tables)} 网格解析失败: {type(exc).__name__}: {exc}")
        caption = ""
        try:
            # FloatingItem.caption_text(doc) 是 docling-core 的解析入口（items/node.py）
            caption = (tbl.caption_text(doc) or "").strip()
        except Exception as exc:
            warnings.append(f"表格 {len(tables)} 取 caption 失败: {type(exc).__name__}")
        tables.append(Table(
            table_id=f"t{len(tables)}",
            page_start=min(pages), page_end=max(pages),
            num_rows=getattr(data, "num_rows", 0) or 0,
            num_cols=getattr(data, "num_cols", 0) or 0,
            cells=cells,
            caption=caption or None,
            section_path=resolve(tbl),
            bbox=_bbox(tbl, doc),
        ))

    for item in doc.iterate_items():
        node = item[0] if isinstance(item, tuple) else item
        if isinstance(node, TextItem):
            label = str(node.label)
            text = (node.text or "").strip()
            if not text or label == "section_header":
                continue
            if label in _NOISE_LABELS:
                warnings.append(f"忽略 {label}(p{_page(node)})")
                continue
            blocks.append(Block(
                block_id=f"b{len(blocks)}",
                label=label,
                text=text,
                provenance=Provenance(page_no=_page(node), bbox=_bbox(node, doc)),
                section_path=resolve(node),
            ))
        elif isinstance(node, TableItem):
            add_table(node)
            seen_table_refs.add(getattr(node, "self_ref", "") or "")
        elif isinstance(node, PictureItem):
            caption = ""
            try:
                caption = (node.caption_text(doc) or "").strip()
            except Exception:
                pass  # 取不到 caption 不影响主体解析
            pictures.append(Picture(
                picture_id=f"pic{len(pictures)}",
                page_no=_page(node),
                caption=caption or None,
                bbox=_bbox(node, doc),
            ))

    # 兜底：若 iterate_items() 不产出 TableItem（遍历口径可能随版本变化），用 doc.tables 补齐。
    # 执行时确认真实行为：若本兜底从不触发（warnings 里没有「兜底」字样），这段可删。
    for tbl in doc.tables:
        if (getattr(tbl, "self_ref", None) or "") in seen_table_refs:
            continue
        warnings.append("表格未出现在 iterate_items() 中，已用 doc.tables 兜底")
        add_table(tbl)

    # ---------- 页面尺寸 ----------
    pages = [PageDim(page_no=p.page_no, width=float(p.size.width), height=float(p.size.height))
             for p in doc.pages.values()]

    empty_paths = sum(1 for b in blocks if not b.section_path)
    warnings.append(f"解析耗时 {parse_seconds:.1f}s")
    if empty_paths:
        warnings.append(f"{empty_paths}/{len(blocks)} 个正文块没有章节路径（封面/前置页，正常）")

    meta = DocumentMeta(
        source_name=pdf_path.name,
        content_hash=content_hash,
        parser="docling",
        parser_version=_docling_version(),
        page_count=len(pages),
        pages_parsed=sorted(p.page_no for p in pages),
    )
    return NormalizedDocument(
        meta=meta,
        headings=[Heading(**h.__dict__) for h in tree],
        blocks=blocks, tables=tables, pictures=pictures, pages=pages,
        markdown=doc.export_to_markdown(),
        warnings=warnings,
    )


def _docling_version() -> str:
    try:
        from importlib.metadata import version

        return version("docling")
    except Exception:
        return "unknown"
