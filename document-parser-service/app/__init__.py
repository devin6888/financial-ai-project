"""金融研报解析服务。

把 PDF 变成与解析引擎无关的 NormalizedDocument。
本服务**不接触数据库与对象存储** —— 持久化由 Java 侧负责（Task 6/7/8）。
"""
