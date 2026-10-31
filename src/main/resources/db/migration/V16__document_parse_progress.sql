-- V16: 解析入库进度（ST18 需求 2）
--
-- 为什么是「阶段检查点」而不是「页级百分比」：Docling 的转换是一次阻塞调用，
-- 解析服务（document-parser-service）在作业完成前拿不到任何页级回调，
-- 唯一能看到的增量只有「还在跑」与「跑了多少秒」。硬造页级进度只会变成假进度条，
-- 所以这里记录的是链路上真实存在的六个检查点（见 C28）。
--
-- 只增不减由 updateProgress 里的 GREATEST 保证：重试、回退阶段都不会把进度打回去。

ALTER TABLE document
    ADD COLUMN IF NOT EXISTS parse_progress smallint NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS progress_stage varchar(32);

ALTER TABLE document
    DROP CONSTRAINT IF EXISTS document_parse_progress_range;
ALTER TABLE document
    ADD CONSTRAINT document_parse_progress_range CHECK (parse_progress BETWEEN 0 AND 100);

COMMENT ON COLUMN document.parse_progress IS
    '解析入库总进度 0~100，阶段检查点口径（非页级）。只增不减，失败时保留最后值';
COMMENT ON COLUMN document.progress_stage IS
    '当前阶段文案：排队中 / 已提交作业 / 解析中 / 解析完成 / 切块中 / 向量化 / 已完成 / 失败';

-- 存量回填：已完成的历史文档直接 100；失败的停在「失败」；在途的从排队开始
UPDATE document SET parse_progress = 100, progress_stage = '已完成' WHERE parse_status = 'DONE';
UPDATE document SET progress_stage = '失败' WHERE parse_status = 'FAILED';
UPDATE document SET progress_stage = '排队中' WHERE parse_status IN ('PENDING', 'RUNNING');
