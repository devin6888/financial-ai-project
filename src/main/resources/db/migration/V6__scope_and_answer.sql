-- 阶段 5（ST5 Task 1）：作用域与答案所需的元数据
--
-- 背景：阶段 5 要把「检索结果」变成「有作用域、可引用的答案」。作用域解析需要
--       知道「一份报告覆盖哪段时间」，也需要能按公司/年份/类型筛出可检索文档。
--       当前 document 只有 doc_year，company 没有 industry，且 doc_type 存在脏值
--       （ANNUAL_REPORT 与 ANNUAL 混用），因此补这一版。

-- ---------- 1) 公司行业（B 模式筛选项） ----------
ALTER TABLE company ADD COLUMN industry varchar(64);

-- ---------- 2) 报告类型收敛 ----------
-- 存量：doc 2 是 'ANNUAL_REPORT'，其余是 'ANNUAL'。统一成 ANNUAL 才能加 CHECK。
UPDATE document SET doc_type = 'ANNUAL' WHERE doc_type = 'ANNUAL_REPORT';

ALTER TABLE document ADD CONSTRAINT document_doc_type_check
    CHECK (doc_type IN ('ANNUAL', 'SEMI_ANNUAL', 'Q1', 'Q3'));

-- ---------- 3) 报告期 ----------
-- 用途：判定「这条证据是不是本期」，从而处理「年报里的上期对比列」陷阱
--       —— 2025 年报的「主要会计数据」表里就有 2024 那一列，
--          答案如果引用了那一列，数字对但出处错。
ALTER TABLE document ADD COLUMN period_start date;
ALTER TABLE document ADD COLUMN period_end   date;

-- 存量回填：只处理年报。半年报/季报的报告期无法从 doc_year 推出，
-- 需要解析产物里的报告期，留空由解析流程补（不猜）。
UPDATE document
   SET period_start = make_date(doc_year, 1, 1),
       period_end   = make_date(doc_year, 12, 31)
 WHERE doc_type = 'ANNUAL'
   AND doc_year IS NOT NULL
   AND period_start IS NULL;

-- ---------- 4) 文档身份唯一 ----------
-- 为什么带 version：同一份年报被更正重发时 version 会 +1，旧版本仍要保留，
-- 供历史回答做引用回放。所以唯一键是「同一公司 + 同一期间 + 同一类型 + 同一版本」。
-- 用部分索引：doc_year 为 NULL 的行（历史脏数据 doc 4）不参与唯一性判定。
CREATE UNIQUE INDEX uq_document_identity
    ON document (company_id, doc_year, doc_type, version)
 WHERE company_id IS NOT NULL AND doc_year IS NOT NULL;

-- ---------- 5) 注释 ----------
COMMENT ON COLUMN company.industry IS '所属行业（B 模式筛选项，可空）';
COMMENT ON COLUMN document.period_start IS '报告期起始日（年报=1/1），用于判定本期/上期证据';
COMMENT ON COLUMN document.period_end IS '报告期截止日（年报=12/31）';
COMMENT ON COLUMN document.doc_type IS '报告类型：ANNUAL/SEMI_ANNUAL/Q1/Q3';
