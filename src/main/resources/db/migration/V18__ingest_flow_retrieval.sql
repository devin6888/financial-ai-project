-- V18: 入库执行记录支持「只重建检索数据」（阶段 20 技术债 A3）
--
-- 为什么需要改：V17 把 flow 锁成 PARSE/REINDEX/RESUME 三种，
-- 而 ReindexMode.RETRIEVAL_ONLY 与 REEMBED 走的是第三条路 ——
-- 不切块、直接沿用旧版本的块重算 tsv 与向量，流程模板叫 RETRIEVAL（3 步）。
-- 不放开这个 CHECK，整条流程在 tracker.start 就会被数据库拒掉（写不进 run，后面全连带失败）。

ALTER TABLE document_ingest_run DROP CONSTRAINT chk_ingest_run_flow;

ALTER TABLE document_ingest_run
    ADD CONSTRAINT chk_ingest_run_flow
    CHECK (flow IN ('PARSE', 'REINDEX', 'RESUME', 'RETRIEVAL'));

COMMENT ON COLUMN document_ingest_run.flow IS
    'PARSE（上传解析）/ REINDEX（重切重嵌）/ RESUME（断点恢复）/ RETRIEVAL（只重建检索数据，不重新切块）';
COMMENT ON COLUMN document_ingest_run.total_steps IS
    '流程模板的步数：PARSE=8、REINDEX=4、RETRIEVAL=3。重试不增加';
