"""从扁平标题序列重建章节树。

背景（阶段 2 实测）：
    Docling 的 iterate_items() 把 17 个 section_header 全部标成 L1，
    chunker 给出的 headings 面包屑也只有一层。因此设计文档 §8.5 所需的
    section_path 必须由本项目根据中文编号惯例自行重建。

两条关键规则，都是为了扛住真实数据的脏：

1. **无编号标题不进入编号栈** —— 它只挂在当前上下文下方，不作为后续编号标题的父级。
   否则「释义」（无编号）会变成「一、公司信息」的父级，路径错得离谱。
2. **乱序与层级跳变都要记录 anomaly** —— 实测「第二节」的标题会排到它自己的
   「一、~四、」之后。这种乱序无法从扁平序列完全恢复，但必须被标记并计数，
   作为量化指标暴露出来，而不是静默产出错误路径（评审 H1）。
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
    """按中文编号惯例推断层级。返回 (level, numbering)；无法判断时 level 为 None。"""
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

        # 层级跳变检测（父级标题缺失）—— 仅在没有更具体的乱序结论时使用
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


def sort_by_position(
    items: list[tuple[str, int | None, float | None]],
) -> list[tuple[str, int | None, float | None]]:
    """按「页码 + 垂直位置（自上而下）」重排标题。

    为什么必须做（阶段 2 决策门实测）：
        Docling 的阅读顺序会把章标题排到它自己的子节之后 —— 实测「第二节 公司简介」
        在第 6 页 bbox.t = 751.1（页内最高），却被排在「一、~四、」
        （t = 710.6 -> 214.2）**之后**。这不是文档结构问题，是阅读顺序错了。
        按坐标重排后 5/17 个标题的次序被修正，**错误归属从 5.9% 降到 0%**。

    这比"解析文档自带目录页反查归属"更可靠：
        目录只给章 -> 页码，而本样本里「一、~四、」与「第二节」**同在第 6 页**，
        页码根本无法判定页内先后。坐标可以。

    Args:
        items: [(标题文本, 页码, bbox.t)]。bbox.t 可空。

    Returns:
        重排后的同结构列表。

    Note:
        Docling 的 section_header 实测是 BOTTOMLEFT 原点，**t 越大越靠上**，故取负。
        若将来换了坐标原点，这里必须同步改。
    """
    return sorted(items, key=lambda it: (it[1] or 0, -(it[2] if it[2] is not None else 0.0)))


def path_lookup(tree: list[Heading]) -> dict[str, list[str]]:
    """标题文本 -> 路径。供 chunk 的 headings 面包屑反查完整路径。

    已知局限（评审 M6）：以标题文本为键，**同名标题会互相覆盖** —— 真实年报存在
    重复标题。当前够用，因为 Docling 的 chunker 面包屑本身也只给最近的标题文本；
    阶段 3 若需要精确定位，应改为按 order 键控并返回 list。
    """
    return {h.text: h.path for h in tree}
