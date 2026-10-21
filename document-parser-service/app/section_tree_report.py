"""决策门报告：用真实标题序列验证章节树重建质量。

用法（在 document-parser-service 下）：
    .venv\Scripts\python.exe -m app.section_tree_report <pdf> [起始页] [结束页]

报告**重排前 vs 重排后**两组指标，用于回答本阶段最关键的问题：
    「Parent = 完整语义小节」所需的 section_path，实际错误率是多少？能不能压下去？

为什么需要它（评审 B5）：
    初版计划引用的 app/section_tree_report.py 没有任何任务创建，它读的 DoclingDocument
    缓存也没有任何任务产出，决策门根本跑不起来。现在由本脚本**自己解析** PDF，
    不依赖任何缓存产物。
"""
from __future__ import annotations

import os
import sys

os.environ.setdefault("HF_ENDPOINT", "https://hf-mirror.com")

from docling.document_converter import DocumentConverter  # noqa: E402

from app.section_tree import build_section_tree, sort_by_position  # noqa: E402


def _bbox_top(item) -> float | None:
    if not item.prov or item.prov[0].bbox is None:
        return None
    return float(item.prov[0].bbox.t)


def _metrics(tree, total: int) -> dict:
    anomalies = [h for h in tree if h.anomaly]
    misfiled = [h for h in anomalies if "之后" in (h.anomaly or "")]
    jumped = [h for h in anomalies if "跳变" in (h.anomaly or "")]
    return {
        "anomalies": len(anomalies),
        "misfiled": len(misfiled),
        "jumped": len(jumped),
        "rate": len(misfiled) / total,
    }


def main() -> None:
    pdf = sys.argv[1]
    start = int(sys.argv[2]) if len(sys.argv) > 2 else 1
    end = int(sys.argv[3]) if len(sys.argv) > 3 else 8

    doc = DocumentConverter().convert(pdf, page_range=(start, end)).document

    pairs = [
        ((t.text or "").strip(),
         t.prov[0].page_no if t.prov else None,
         _bbox_top(t))
        for t in doc.texts
        if str(t.label) == "section_header" and (t.text or "").strip()
    ]
    total = max(len(pairs), 1)

    out: list[str] = []
    out.append(f"解析页范围: {start}-{end}    标题总数: {len(pairs)}")

    # ---- 基线：Docling 阅读顺序 ----
    tree_reading = build_section_tree([text for text, _, _ in pairs])
    m1 = _metrics(tree_reading, total)
    out.append("")
    out.append("=== 基线：按 Docling 阅读顺序 ===")
    out.append(f"  错误归属: {m1['misfiled']} ({m1['rate']:.1%})   层级跳变: {m1['jumped']}   anomaly 合计: {m1['anomalies']}")

    # ---- 修正：按页码 + 垂直位置重排 ----
    ordered = sort_by_position(pairs)
    tree_pos = build_section_tree([text for text, _, _ in ordered])
    for heading, (_text, page, _top) in zip(tree_pos, ordered):
        heading.page_no = page
    m2 = _metrics(tree_pos, total)
    out.append("")
    out.append("=== 修正：按「页码 + 垂直位置」重排（section_tree.sort_by_position） ===")
    out.append(f"  错误归属: {m2['misfiled']} ({m2['rate']:.1%})   层级跳变: {m2['jumped']}   anomaly 合计: {m2['anomalies']}")
    out.append("")
    if m2["misfiled"] < m1["misfiled"]:
        out.append(f"  >>> 错误归属从 {m1['rate']:.1%} 降到 {m2['rate']:.1%}，修复 {m1['misfiled'] - m2['misfiled']} 处")
    else:
        out.append("  >>> 重排未带来改善，需另行调查")

    # ---- 顺序差异明细 ----
    out.append("")
    out.append("=== 被重排的标题（位置顺序 vs 阅读顺序） ===")
    by_pos = sorted(enumerate(pairs),
                    key=lambda kv: (kv[1][1] or 0, -(kv[1][2] if kv[1][2] is not None else 0.0)))
    moved = 0
    for rank, (orig_idx, (text, page, top)) in enumerate(by_pos):
        if rank != orig_idx:
            moved += 1
            t_disp = f"{top:.1f}" if top is not None else "?"
            out.append(f"  位置#{rank:2d} <- 阅读#{orig_idx:2d}  p{page} t={t_disp:>7s}  {text[:44]}")
    out.append(f"  共移动 {moved}/{len(pairs)}")

    # ---- 重排后的完整章节树 ----
    out.append("")
    out.append("=== 重排后的章节树 ===")
    for h in tree_pos:
        flag = ""
        if h.anomaly:
            tag = "[错误归属] " if "之后" in h.anomaly else "[层级跳变] "
            flag = "   <<< " + tag + h.anomaly
        out.append(f"  L{h.level} p{h.page_no} {h.numbering:9s} " + " > ".join(h.path) + flag)

    text = "\n".join(out)
    print(text)
    with open("_tmp_gate.txt", "w", encoding="utf-8") as f:
        f.write(text)


if __name__ == "__main__":
    main()
