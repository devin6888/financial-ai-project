-- ============================================================
-- V5__sparse_index_upgrade.sql
-- 阶段 4：为混合检索升级 Sparse 路的索引能力
--
-- 两个改动：
--   1) chinese_zh 增加 m（数词）映射 —— 修「三百万」这类中文数字被丢弃
--   2) 引入 pg_trgm + GIN 索引 —— 供 Query Understanding 抽出的实体做精确子串召回
--      （zhparser 会把「每股收益」切成「收益」，精确子串才能保住原意）
--
-- 为什么只加 m 不加 r：实测语料里「其他」（代词 r）出现在 250/654 = 38% 的 chunk 中，
--   而「每股」只有 9 条。加 r 的噪声远大于收益，且没有 IDF 时不会自动降权。
--   「每股」的问题由 QU + 精确子串匹配解决，不靠分词。
--
-- 注意：改映射后已有的 content_tsv 是旧值（生成列在写入时算好），
--       必须强制重算，见最后一步。
-- ============================================================

-- ---------- 1) 给 chinese_zh 加 m（数词）映射 ----------
-- tokid 用的是「别名字符的 ASCII 码」：实测已映射的 97/101/105/106/108/110/118
-- 正好对应 a/e/i/j/l/n/v。所以 m 就是 ascii('m')=109，不要硬编码数字。
-- 用 DO 块保证幂等：ADD MAPPING 重复执行会报错
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_ts_config_map m
          JOIN pg_ts_config c ON c.oid = m.mapcfg
         WHERE c.cfgname = 'chinese_zh' AND m.maptokentype = ascii('m')
    ) THEN
        ALTER TEXT SEARCH CONFIGURATION chinese_zh ADD MAPPING FOR m WITH simple;
    END IF;
END $$;

-- ---------- 2) pg_trgm：精确子串召回 ----------
CREATE EXTENSION IF NOT EXISTS pg_trgm;

-- ---------- 3) 内容三元组索引 ----------
-- 支撑 LIKE '%实体%' 的索引扫描。实测「每股收益」精确匹配 6 条 vs 分词后 86 条。
CREATE INDEX IF NOT EXISTS idx_chunk_content_trgm
    ON document_chunk USING GIN (content gin_trgm_ops);

-- ---------- 4) 重算 content_tsv ----------
-- content_tsv 是 GENERATED ALWAYS AS ... STORED，只有行被更新时才会重算。
-- 不执行这一步，旧的 tsv 里仍然没有数词 —— 等于映射白改。
UPDATE document_chunk SET content = content;
