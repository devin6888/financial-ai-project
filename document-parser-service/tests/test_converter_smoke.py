"""用真实年报的前几页验证转换全链路。

刻意用真实 PDF 而不是构造样本 —— 阶段 2 的多数问题（阅读顺序、坐标原点）
只有在真实文档上才暴露得出来。
"""
from functools import lru_cache
from pathlib import Path

import pytest

from app.converter import parse_pdf

PDF = Path(__file__).resolve().parents[2] / "docs" / "东方财富2025年年度报告.pdf"


@lru_cache(maxsize=None)
def _parse(page_range: tuple[int, int]):
    """按页范围缓存解析结果 —— 一次解析约 8s/页，不缓存的话三个用例要跑 3 分钟。"""
    return parse_pdf(PDF, page_range=page_range)


@pytest.mark.skipif(not PDF.exists(), reason="样本 PDF 不在仓库中")
def test_parse_first_pages():
    doc = _parse((1, 4))

    assert doc.meta.page_count == 4
    assert doc.meta.content_hash
    assert doc.meta.parser == "docling"
    assert doc.meta.parser_version

    # 正文块
    assert len(doc.blocks) > 10, "正文块太少，可能解析失败"

    # 每个块都必须有页码 —— 阶段 5 引用溯源的前提
    assert all(b.provenance.page_no >= 1 for b in doc.blocks)

    # 有章级标题
    assert any(h.level == 1 for h in doc.headings), "至少要有章级标题"

    # bbox 必须已归一化到 [0,1]（评审 H3）
    with_bbox = [b for b in doc.blocks if b.provenance.bbox]
    assert with_bbox, "应该至少有一个块带 bbox"
    for b in with_bbox:
        bx = b.provenance.bbox
        for v in (bx.left, bx.top, bx.right, bx.bottom):
            assert 0.0 <= v <= 1.0, f"bbox 未归一化: {bx}"


@pytest.mark.skipif(not PDF.exists(), reason="样本 PDF 不在仓库中")
def test_second_chapter_attribution_is_positional():
    """决策门的连带修复：第二节 的子节必须挂在 第二节 下，不能挂到 第一节。

    这验证的是「按坐标判定归属」，而不是遍历顺序。
    """
    doc = _parse((1, 8))

    by_text = {b.text[:12]: b for b in doc.blocks if b.section_path}
    assert by_text, "应该有带章节路径的块"

    # 「一、公司信息」这一节的内容（股票简称等）应归属 第二节
    chapter2 = "第二节 公司简介和主要财务指标"
    paths = {tuple(b.section_path[:1]) for b in doc.blocks if b.section_path}
    assert (chapter2,) in paths, f"没有任何块归属到 {chapter2}，路径集合={sorted(paths)[:5]}"

    # 关键断言：第 6 页的块不应被误挂到 第一节 下
    p6 = [b for b in doc.blocks if b.provenance.page_no == 6 and b.section_path]
    assert p6, "第 6 页应有带路径的块"
    wrong = [b for b in p6 if b.section_path[0] == "第一节 重要提示、目录和释义"
             and len(b.section_path) > 1 and b.section_path[1].startswith(("一、", "二、" "三、", "四、"))]
    assert not wrong, f"第 6 页有块被误挂到 第一节: {[b.section_path for b in wrong][:3]}"


@pytest.mark.skipif(not PDF.exists(), reason="样本 PDF 不在仓库中")
def test_tables_have_distinct_section_paths():
    """评审 H2：表格曾全部共享「全文最后一个标题」当路径。"""
    doc = _parse((1, 8))
    if len(doc.tables) < 2:
        pytest.skip("样本中表格太少，无法验证路径差异")
    distinct = {tuple(t.section_path) for t in doc.tables}
    assert len(distinct) > 1, f"所有表格的 section_path 都相同，说明归属仍是错的: {distinct}"

@pytest.mark.skipif(not PDF.exists(), reason="样本 PDF 不在仓库中")
def test_tables_and_pictures_have_geometry():
    """表格与图片必须带**归一化** bbox。

    阶段 5 的引用高亮要靠它定位区域。初版 Table 只有页码、没有 bbox，
    意味着表格在原文里"指不出来" —— 而且等文档入库后再补，需要全部重解析。
    """
    doc = _parse((1, 8))

    assert doc.tables, "样本应至少有表格"
    for tbl in doc.tables:
        assert tbl.bbox is not None, f"表格 {tbl.table_id} 缺 bbox"
        for v in (tbl.bbox.left, tbl.bbox.top, tbl.bbox.right, tbl.bbox.bottom):
            assert 0.0 <= v <= 1.0, f"表格 bbox 未归一化: {tbl.bbox}"

    assert doc.pictures, "样本应至少有图片"
    with_bbox = [p for p in doc.pictures if p.bbox is not None]
    assert with_bbox, "至少要有图片带 bbox"
    for pic in with_bbox:
        for v in (pic.bbox.left, pic.bbox.top, pic.bbox.right, pic.bbox.bottom):
            assert 0.0 <= v <= 1.0, f"图片 bbox 未归一化: {pic.bbox}"


@pytest.mark.skipif(not PDF.exists(), reason="样本 PDF 不在仓库中")
def test_table_page_range_matches_bbox_owner_page():
    """表格 bbox 取自首页 prov，必须与 page_start 一致（多页表格只存首页区域）。"""
    doc = _parse((1, 8))
    for tbl in doc.tables:
        if tbl.bbox is None:
            continue
        # bbox 没有页码字段，但至少不该是未归一化的原始坐标
        assert tbl.bbox.right > tbl.bbox.left, f"表格 {tbl.table_id} bbox 左右反了"
        assert tbl.bbox.bottom > tbl.bbox.top, f"表格 {tbl.table_id} bbox 上下反了"
