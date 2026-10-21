
import os, sys, time
os.environ.setdefault("HF_ENDPOINT", "https://hf-mirror.com")
sys.path.insert(0, r"undefined\temp\plancheck_tree")
from docling.document_converter import DocumentConverter
from docling_core.types.doc import TextItem
from section_tree import build_section_tree, path_lookup

t0=time.time()
doc = DocumentConverter().convert(r"D:\Project\myproject\financial-ai-project\docs\东方财富2025年年度报告.pdf", page_range=(60,74)).document
print("parse %.1fs" % (time.time()-t0))
heads=[]
for it, lv in doc.iterate_items():
    if isinstance(it, TextItem) and str(it.label)=="section_header" and it.text.strip():
        heads.append((it.text.strip(), it.prov[0].page_no if it.prov else 0))
print("section_headers:", len(heads))
from collections import Counter
c = Counter(t for t,_ in heads)
dups = {k:v for k,v in c.items() if v>1}
print("distinct:", len(c), "duplicated texts:", len(dups))
for k,v in dups.items(): print("   DUP x%d: %s" % (v, k[:60]))
tree = build_section_tree([t for t,_ in heads])
lk = path_lookup(tree)
print("tree entries:", len(tree), "lookup entries:", len(lk), "=> lost to key collision:", len(tree)-len(lk))
for t,_ in heads[:30]: print("   p%s %s" % (_, t[:58]))
