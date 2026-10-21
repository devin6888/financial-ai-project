
import json, re, sys
sys.path.insert(0, r"undefined\temp\plancheck_tree")
from section_tree import build_section_tree
from docling_core.types.doc import TextItem

d = json.load(open(r"D:\Project\myproject\financial-ai-project\_tmp_doc.json", encoding="utf-8"))
texts = d.get("texts", [])
heads = [(t.get("text","").strip(), (t.get("prov") or [{}])[0].get("page_no")) for t in texts if t.get("label")=="section_header"]
print("texts:", len(texts), " tables:", len(d.get("tables",[])), " pictures:", len(d.get("pictures",[])), " key_value_items present:", "key_value_items" in d)
print("section_header count:", len(heads))
print("\n--- real heading sequence (text | page) ---")
for t,p in heads:
    print(f"  p{p}: {t[:60]}")

tree = build_section_tree([t for t,_ in heads])
anom = [h for h in tree if h.anomaly]
print(f"\n[broken regex] headings={len(tree)} anomalies={len(anom)} ({len(anom)/max(len(tree),1)*100:.1f}%)")
for h in tree:
    if h.anomaly: print("   !", h.order, h.text[:50], "|", h.anomaly[:60], "| path:", [x[:18] for x in h.path])

# fixed regex variant
import section_tree as st
st._PATTERNS = [
    (re.compile(r"^第[一二三四五六七八九十百零〇]+[节章]"), 1, "chapter"),
    (re.compile(r"^[一二三四五六七八九十]+[、．.]"), 2, "section"),
    (re.compile(r"^[（(][一二三四五六七八九十]+[）)]"), 3, "subsection"),
    (re.compile(r"^\d+[、．]\s*"), 4, "item"),
    (re.compile(r"^[（(]\d+[）)]"), 5, "subitem"),
]
tree2 = build_section_tree([t for t,_ in heads])
anom2 = [h for h in tree2 if h.anomaly]
print(f"\n[fixed regex]  headings={len(tree2)} anomalies={len(anom2)} ({len(anom2)/max(len(tree2),1)*100:.1f}%)")
for h in tree2:
    if h.anomaly: print("   !", h.order, h.text[:50], "| path:", [x[:18] for x in h.path])
