
from __future__ import annotations

import hashlib
import time
from pathlib import Path

from docling.document_converter import DocumentConverter
from docling_core.types.doc import DoclingDocument, PictureItem, TableItem, TextItem

from app.models import (
    BBox, Block, DocumentMeta, Heading, KeyValueItem, NormalizedDocument,
    PageDim, Picture, Provenance, Table, TableCell,
)
from app.section_tree import build_section_tree, path_lookup

_NOISE_LABELS = {"page_header", "page_footer"}


def _bbox(item) -> BBox | None:
    if not getattr(item, "prov", None):
        return None
    b = item.prov[0].bbox
    return BBox(left=b.l, top=b.t, right=b.r, bottom=b.b)


def _page(item) -> int:
    return item.prov[0].page_no if getattr(item, "prov", None) else 0


def parse_pdf(pdf_path: str | Path, page_range: tuple[int, int] | None = None) -> NormalizedDocument:
    pdf_path = Path(pdf_path)
    content = pdf_path.read_bytes()
    content_hash = hashlib.sha256(content).hexdigest()

    t0 = time.time()
    converter = DocumentConverter()
    kwargs = {"page_range": page_range} if page_range else {}
    doc: DoclingDocument = converter.convert(str(pdf_path), **kwargs).document
    parse_seconds = time.time() - t0

    warnings: list[str] = []

    heading_items = [(it, _page(it)) for it in doc.texts if str(it.label) == "section_header"]
    tree = build_section_tree([it.text for it, _ in heading_items])
    for h, (_, page) in zip(tree, heading_items):
        h.page_no = page
    lookup = path_lookup(tree)

    def resolve_path(nearest: str | None) -> list[str]:
        return lookup.get(nearest, []) if nearest else []

    def nearest_heading_for(item) -> str | None:
        for h_text in lookup:
            pass
        return None

    blocks: list[Block] = []
    pictures: list[Picture] = []
    key_values: list[KeyValueItem] = []

    current_heading: str | None = None
    heading_set = {h.text for h in tree}

    for idx, item in enumerate(doc.iterate_items()):
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
                warnings.append(f"忽略{label}(p{_page(node)})")
                continue
            blocks.append(Block(
                block_id=f"b{len(blocks)}",
                label=label,
                text=text,
                provenance=Provenance(page_no=_page(node), bbox=_bbox(node)),
                section_path=resolve_path(current_heading),
            ))
        elif isinstance(node, PictureItem):
            pictures.append(Picture(picture_id=f"pic{len(pictures)}", page_no=_page(node)))

    tables: list[Table] = []
    for tbl in doc.tables:
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
        except Exception as exc:
            warnings.append(f"表格 {len(tables)} 网格解析失败: {type(exc).__name__}: {exc}")
        tables.append(Table(
            table_id=f"t{len(tables)}",
            page_start=min(pages), page_end=max(pages),
            num_rows=getattr(data, "num_rows", 0), num_cols=getattr(data, "num_cols", 0),
            cells=cells, section_path=resolve_path(current_heading),
        ))

    for kv in getattr(doc, "key_value_items", []) or []:
        try:
            key_values.append(KeyValueItem(
                key=str(kv.key or ""), value=str(kv.value or ""),
                page_no=_page(kv), section_path=resolve_path(current_heading),
            ))
        except Exception:
            pass

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
        blocks=blocks, tables=tables, key_values=key_values,
        pictures=pictures, pages=pages,
        markdown=doc.export_to_markdown(), warnings=warnings,
    )


def _docling_version() -> str:
    try:
        from importlib.metadata import version
        return version("docling")
    except Exception:
        return "unknown"
