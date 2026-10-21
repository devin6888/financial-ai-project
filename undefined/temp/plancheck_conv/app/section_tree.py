
from __future__ import annotations
import re
from dataclasses import dataclass, field

_PATTERNS: list[tuple[re.Pattern[str], int, str]] = [
    (re.compile(r"^第[一二三四五六七八九十百零〇]+[节章]"), 1, "chapter"),
    (re.compile(r"^[一二三四五六七八九十]+[、．.]"), 2, "section"),
    (re.compile(r"^[（(][一二三四五六七八九十]+[）)]"), 3, "subsection"),
    (re.compile(r"^d+[、．]s*"), 4, "item"),
    (re.compile(r"^[（(]d+[）)]"), 5, "subitem"),
]

MAX_LEVEL = 5

@dataclass
class Heading:
    text: str
    level: int
    order: int
    numbering: str
    path: list[str] = field(default_factory=list)
    page_no: int | None = None
    anomaly: str | None = None

def detect_level(text: str) -> tuple[int | None, str]:
    t = text.strip()
    for pattern, level, kind in _PATTERNS:
        if pattern.match(t):
            return level, kind
    return None, "none"

def build_section_tree(heading_texts):
    result = []
    stack = []
    for order, raw in enumerate(heading_texts):
        text = (raw or "").strip()
        if not text:
            continue
        level, numbering = detect_level(text)
        anomaly = None
        if level is None:
            level = (stack[-1].level + 1) if stack else 1
            path = [h.text for h in stack] + [text]
            result.append(Heading(text=text, level=level, order=order, numbering=numbering, path=path))
            continue
        while stack and stack[-1].level >= level:
            stack.pop()
        expected = (stack[-1].level + 1) if stack else 1
        if level > expected:
            anomaly = (f"层级跳变：level={level} 但当前上下文期望 level<={expected}")
        path = [h.text for h in stack] + [text]
        heading = Heading(text=text, level=level, order=order, numbering=numbering, path=path, anomaly=anomaly)
        result.append(heading)
        stack.append(heading)
    return result

def path_lookup(tree):
    return {h.text: h.path for h in tree}
