
import json, sys
sys.path.insert(0, r"undefined\temp\plancheck_tree")
from section_tree import build_section_tree, detect_level, path_lookup

REAL = json.load(open(r"undefined\temp\plancheck_tree\real_headings.json", encoding="utf-8"))
results = []
def check(name, cond, detail=""):
    results.append((name, cond, detail))

# test_detect_level_by_numbering
check("detect 第一节", detect_level("第一节 重要提示")[:2] == (1, "chapter"), str(detect_level("第一节 重要提示")))
check("detect 一、", detect_level("一、公司信息")[:2] == (2, "section"), str(detect_level("一、公司信息")))
check("detect （一）", detect_level("（一）基本情况")[:2] == (3, "subsection"), str(detect_level("（一）基本情况")))
check("detect 1、", detect_level("1、同时按照国际会计准则")[:2] == (4, "item"), str(detect_level("1、同时按照国际会计准则")))
check("detect （1）", detect_level("（1）明细")[:2] == (5, "subitem"), str(detect_level("（1）明细")))
check("detect 目录", detect_level("目录")[1] == "none", str(detect_level("目录")))

tree = build_section_tree(REAL)
lookup = path_lookup(tree)

# test_numbered_headings_get_correct_path (as literally written)
try:
    a = lookup["1、同时按照国际会计准则与按中国会计准则披露的财务报告中净利润和净资产差异情况"]
    cond = (a == ["第二节 公司简介和主要财务指标","七、报告期主要会计政策、会计估计变更和差错更正",
                  "1、同时按照国际会计准则与按中国会计准则披露的财务报告中净利润和净资产差异情况"]) \
        if "七、报告期主要会计政策、会计估计变更和差错更正" in lookup else \
        (a[-1] == "1、同时按照国际会计准则与按中国会计准则披露的财务报告中净利润和净资产差异情况")
    check("numbered path ternary", cond, f"else-branch taken={('七' not in ''.join(lookup))}, actual={a}")
except Exception as e:
    check("numbered path ternary", False, f"EXC {e}")

# test_unnumbered...
check("释义 under 第一节", lookup["释义"][0] == "第一节 重要提示、目录和释义", str(lookup["释义"]))
check("释义 not in 一、公司信息", "释义" not in lookup["一、公司信息"], str(lookup["一、公司信息"]))

# test_reading_order_anomaly_is_recorded
anomalies = [h for h in tree if h.anomaly]
check("anomalies exist", bool(anomalies), f"anomaly count={len(anomalies)} of {len(tree)}")

print("=== Task3 test assertions vs plan implementation ===")
for n, c, d in results:
    print(("PASS " if c else "FAIL ") + n + "   | " + d)
print()
print("=== 五、 path ===", lookup.get("五、主要会计数据和财务指标"))
print("=== 一、公司信息 path ===", lookup.get("一、公司信息"))
print("=== 1、 path ===", lookup.get("1、同时按照国际会计准则与按中国会计准则披露的财务报告中净利润和净资产差异情况"))
