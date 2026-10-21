"""章节树重建的回归测试。

标题序列取自真实年报（东方财富 2025 年年度报告，前 8 页），**不是编造的示例** ——
真实数据里存在 Docling 的乱序问题（章标题排在自己的子节之后），必须被覆盖到。
"""
from app.section_tree import build_section_tree, detect_level, path_lookup


REAL_HEADINGS = [
    "东方财富信息股份有限公司",
    "2025年年度报告",
    "第一节 重要提示、目录和释义",
    "目录",
    "备查文件目录",
    "释义",
    "一、公司信息",
    "二、联系人和联系方式",
    "三、信息披露及备置地点",
    "四、其他有关资料",
    "第二节 公司简介和主要财务指标",
    "五、主要会计数据和财务指标",
    "六、分季度主要财务指标",
    "七、报告期主要会计政策、会计估计变更和差错更正",
    "1、同时按照国际会计准则与按中国会计准则披露的财务报告中净利润和净资产差异情况",
    "2、同时按照境外会计准则与按中国会计准则披露的财务报告中净利润和净资产差异情况",
    "八、非经常性损益项目及金额",
]


def test_detect_level_by_numbering():
    assert detect_level("第一节 重要提示")[:2] == (1, "chapter")
    assert detect_level("一、公司信息")[:2] == (2, "section")
    assert detect_level("（一）基本情况")[:2] == (3, "subsection")
    assert detect_level("1、同时按照国际会计准则")[:2] == (4, "item")
    assert detect_level("（1）明细")[:2] == (5, "subitem")
    assert detect_level("目录")[1] == "none"


def test_numbered_headings_get_correct_path():
    lookup = path_lookup(build_section_tree(REAL_HEADINGS))

    # 第二节 之后的五、，路径完整
    assert lookup["五、主要会计数据和财务指标"] == [
        "第二节 公司简介和主要财务指标",
        "五、主要会计数据和财务指标",
    ]

    # 四级小项挂在二级之下
    # 评审修正 H4：初版是恒真式三元表达式（条件引用了未出现在输入中的标题，
    # 恒为 False，else 分支断言"路径末项等于自身"，永远成立，什么都没验证）。
    assert lookup["1、同时按照国际会计准则与按中国会计准则披露的财务报告中净利润和净资产差异情况"] == [
        "第二节 公司简介和主要财务指标",
        "七、报告期主要会计政策、会计估计变更和差错更正",
        "1、同时按照国际会计准则与按中国会计准则披露的财务报告中净利润和净资产差异情况",
    ]


def test_unnumbered_heading_does_not_pollute_numbered_context():
    """无编号标题不应成为后续编号标题的父级。"""
    lookup = path_lookup(build_section_tree(REAL_HEADINGS))
    # 释义 是无编号的，挂在 第一节 之下
    assert lookup["释义"][0] == "第一节 重要提示、目录和释义"
    # 但 一、公司信息 不应被挂到 释义 之下
    assert "释义" not in lookup["一、公司信息"]


def test_reading_order_anomaly_is_recorded():
    """真实数据里「第二节」的标题出现在它自己的 一、~四、 之后 —— 必须被标记。

    评审修正 H1：初版只断言「存在 anomaly」，但修好正则后 anomaly 全部来自
    「六、→1、」的层级跳变，与本用例 docstring 说的乱序无关（因错误原因通过）。
    这里改为定位到具体章标题断言。
    """
    tree = build_section_tree(REAL_HEADINGS)
    ch2 = next(h for h in tree if h.text.startswith("第二节"))
    assert ch2.anomaly, "章标题排在自己子节之后，必须记录 anomaly"
    assert "之后" in ch2.anomaly

    # 如实锁定当前无法自动修正的行为：一、公司信息 归属错误
    lookup = path_lookup(tree)
    assert lookup["一、公司信息"][0] == "第一节 重要提示、目录和释义", (
        "已知缺陷：一、公司信息 被挂到 第一节 下。此断言锁定现状；"
        "若将来用目录页反查修正了归属，这里会失败，提醒同步更新计划与设计文档。"
    )

def test_sort_by_position_fixes_reading_order():
    """实测数据：第二节 的 t 最大（页内最上），Docling 的阅读顺序却把它排在最后。"""
    from app.section_tree import sort_by_position

    items = [
        ("一、公司信息", 6, 710.6),
        ("二、联系人和联系方式", 6, 453.1),
        ("三、信息披露及备置地点", 6, 309.3),
        ("四、其他有关资料", 6, 214.2),
        ("第二节 公司简介和主要财务指标", 6, 751.1),
    ]
    assert [t for t, _, _ in sort_by_position(items)] == [
        "第二节 公司简介和主要财务指标",
        "一、公司信息",
        "二、联系人和联系方式",
        "三、信息披露及备置地点",
        "四、其他有关资料",
    ]


def test_sort_by_position_keeps_page_order():
    from app.section_tree import sort_by_position

    items = [("b", 2, 100.0), ("a", 1, 100.0)]
    assert [t for t, _, _ in sort_by_position(items)] == ["a", "b"]


def test_positional_order_removes_misfiling():
    """重排后，同样的标题序列不再产生「错误归属」。"""
    from app.section_tree import sort_by_position

    items = [
        ("第一节 重要提示、目录和释义", 2, 751.1),
        ("一、公司信息", 6, 710.6),
        ("二、联系人和联系方式", 6, 453.1),
        ("四、其他有关资料", 6, 214.2),
        ("第二节 公司简介和主要财务指标", 6, 751.1),
        ("五、主要会计数据和财务指标", 7, 721.8),
    ]
    tree = build_section_tree([t for t, _, _ in sort_by_position(items)])
    lookup = path_lookup(tree)

    assert lookup["一、公司信息"][0] == "第二节 公司简介和主要财务指标"
    assert lookup["四、其他有关资料"][0] == "第二节 公司简介和主要财务指标"
    assert not any(h.anomaly for h in tree), "重排后不应再有错误归属"

