
import json, sys
sys.path.insert(0, r"undefined\temp\plancheck_conv")
from docling_core.types.doc import DoclingDocument
import app.converter as cv

DOC = DoclingDocument.model_validate_json(open(r"D:\Project\myproject\financial-ai-project\_tmp_doc.json", encoding="utf-8").read())

class FakeResult:
    def __init__(self, document): self.document = document
class FakeConverter:
    def __init__(self, *a, **k): pass
    def convert(self, path, **kw): return FakeResult(DOC)
cv.DocumentConverter = FakeConverter

d = cv.parse_pdf(r"D:\Project\myproject\financial-ai-project\docs\东方财富2025年年度报告.pdf", page_range=(1,8))
print("page_count      :", d.meta.page_count)
print("headings        :", len(d.headings), " anomalies:", sum(1 for h in d.headings if h.anomaly))
print("blocks          :", len(d.blocks))
print("tables          :", len(d.tables))
print("key_values      :", len(d.key_values))
print("pictures        :", len(d.pictures))
print("pages           :", [p.page_no for p in d.pages])
print("blocks empty path:", sum(1 for b in d.blocks if not b.section_path), "of", len(d.blocks))
print("blocks page_no=0:", sum(1 for b in d.blocks if b.provenance.page_no == 0))
print("\ntable section_paths (distinct):", {json.dumps(t.section_path, ensure_ascii=False) for t in d.tables})
print("table pages     :", [(t.page_start, t.page_end, t.num_rows, t.num_cols, len(t.cells)) for t in d.tables])
print("\nfirst 6 block paths:")
for b in d.blocks[:6]:
    print("  p%s [%s] %s -> %s" % (b.provenance.page_no, b.label, b.text[:28], [x[:20] for x in b.section_path]))
print("\nwarnings:", d.warnings[:6])
