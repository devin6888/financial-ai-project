-- ============================================================
-- V4__column_comments.sql
-- 补齐全部表与字段的中文注释
--
-- 为什么单独一个迁移而不是改 V1~V3：
--   Flyway 会校验已应用迁移的 checksum，改动历史文件会让启动直接失败。
--   注释属于 schema 的一部分，所以用新迁移补 —— 这也是 Flyway 的常规做法。
--
-- 幂等：COMMENT ON 覆盖写，重复执行无副作用。
-- ============================================================

-- ---------- company ----------
COMMENT ON TABLE company IS '公司主表：上市公司或研报主体';

COMMENT ON COLUMN company.id         IS '主键';
COMMENT ON COLUMN company.code       IS '股票代码，如 300059（唯一）';
COMMENT ON COLUMN company.name       IS '公司简称，如 东方财富';
COMMENT ON COLUMN company.market     IS '交易市场：SZ 深交所 / SH 上交所 / BJ 北交所';
COMMENT ON COLUMN company.created_at IS '记录创建时间';

-- ---------- document ----------
COMMENT ON TABLE document IS '文档主表：一份 PDF 的元数据、解析状态与解析产物指针';

COMMENT ON COLUMN document.id                     IS '主键';
COMMENT ON COLUMN document.company_id             IS '所属公司；行业研报等无明确主体时可为空';
COMMENT ON COLUMN document.doc_type               IS '文档类型：ANNUAL_REPORT 年报 / QUARTERLY_REPORT 季报 / RESEARCH 研报';
COMMENT ON COLUMN document.doc_year               IS '文档所属年度，如 2025';
COMMENT ON COLUMN document.title                  IS '文档标题，用于拼接 chunk 的 embedding 上下文';
COMMENT ON COLUMN document.object_key             IS '原始 PDF 在对象存储中的 key（不含环境前缀，前缀由 CosStorage 自动加）';
COMMENT ON COLUMN document.content_hash           IS 'PDF 内容的 SHA-256：幂等去重，同一份文件重复上传会命中同一行';
COMMENT ON COLUMN document.parse_status           IS '解析状态：PENDING 待解析 / RUNNING 解析中 / DONE 已完成 / FAILED 失败';
COMMENT ON COLUMN document.version                IS '文档版本号：同一份报告重新发布时递增，用于区分不同版次';
COMMENT ON COLUMN document.created_at             IS '记录创建时间';
COMMENT ON COLUMN document.parsed_object_key      IS '解析产物（NormalizedDocument JSON）在对象存储中的 key。这是「原始 block 持久化」的载体 —— 重切块只读它，不必再解析 PDF';
COMMENT ON COLUMN document.parser                 IS '解析引擎名，如 docling';
COMMENT ON COLUMN document.parser_version         IS '解析引擎版本，如 2.138.0。换版本意味着产物结构可能变化，需要 FULL_REPARSE';
COMMENT ON COLUMN document.pages_parsed           IS '已解析页数（实测 181 页年报耗时的度量基准）';
COMMENT ON COLUMN document.parsed_at              IS '解析完成时间';
COMMENT ON COLUMN document.parse_job_id           IS '解析服务返回的作业 id；服务重启时据此恢复在途作业，避免重复提交';
COMMENT ON COLUMN document.parse_error            IS '解析失败原因（仅 FAILED 时有值）';
COMMENT ON COLUMN document.parse_started_at       IS '本次解析开始时间';
COMMENT ON COLUMN document.parse_finished_at      IS '本次解析结束时间（成败都记录）';
COMMENT ON COLUMN document.active_chunk_version   IS '当前生效的 chunk 版本。检索只读该版本的 ACTIVE chunk；重索引时原子切换，失败则保留旧值';
COMMENT ON COLUMN document.chunk_strategy_version IS '产出 active_chunk_version 的切块策略版本，用于判断是否需要 RECHUNK';

-- ---------- document_chunk ----------
COMMENT ON TABLE document_chunk IS '切块表：Parent（上下文单元）与 Child（检索单元）同表，靠 chunk_level 区分、parent_id 自引用关联';

COMMENT ON COLUMN document_chunk.id                     IS '主键';
COMMENT ON COLUMN document_chunk.document_id            IS '所属文档';
COMMENT ON COLUMN document_chunk.parent_id              IS '所属 Parent 的 id；PARENT 行自身为 NULL（自引用外键）';
COMMENT ON COLUMN document_chunk.chunk_level            IS '层级：PARENT 上下文单元（不建向量）/ CHILD 检索单元（建向量）';
COMMENT ON COLUMN document_chunk.chunk_type             IS '内容形态：TEXT 正文 / TABLE 表格 / LIST 列表';
COMMENT ON COLUMN document_chunk.section_path           IS '自根到本文小节的完整路径，用 " § " 分隔（如 第八节 财务报告 § 五、会计政策）。用于 embedding 上下文与元数据过滤';
COMMENT ON COLUMN document_chunk.title                  IS '本节标题（section_path 的最后一段）';
COMMENT ON COLUMN document_chunk.content                IS '原始标准化内容 —— 事实源。不含为 embedding 拼接的标题与章节路径（那些在 embedding_text 里，不落库）';
COMMENT ON COLUMN document_chunk.page_start             IS '引用页码范围起点（1 起始），用于生成带页码的引用';
COMMENT ON COLUMN document_chunk.page_end               IS '引用页码范围终点；跨页 chunk 用它表达范围';
COMMENT ON COLUMN document_chunk.source_block_ids       IS '回溯解析产物的 block_id / table_id，逗号分隔。支撑引用溯源与「局部重切不重新解析」';
COMMENT ON COLUMN document_chunk.token_count            IS 'token 估算值（中文按一字一 token 近似）';
COMMENT ON COLUMN document_chunk.chunk_index            IS '版本内全局序号，PARENT 与 CHILD 各自从 0 递增。sibling 扩展时按 parent_id 过滤后再按它排序';
COMMENT ON COLUMN document_chunk.status                 IS '生命周期：ACTIVE 可检索 / SUPERSEDED 被新版本取代（保留供审计回滚）/ DELETED 逻辑删除。检索只取 ACTIVE';
COMMENT ON COLUMN document_chunk.parser_version         IS '产出本 chunk 的解析器版本（与 document.parser_version 对应）';
COMMENT ON COLUMN document_chunk.chunk_strategy_version IS '产出本 chunk 的切块策略版本；与当前策略不同则说明需要 RECHUNK';
COMMENT ON COLUMN document_chunk.embedding              IS '1024 维向量（百炼 text-embedding-v4）。V1 只有 CHILD 有值，PARENT 恒为 NULL';
COMMENT ON COLUMN document_chunk.embedding_model        IS '服务商模型名，如 text-embedding-v4';
COMMENT ON COLUMN document_chunk.embedding_version      IS '本项目的接入版本，如 v1。模型未换但调用参数变了时递增，用于精确圈定重嵌入范围';
COMMENT ON COLUMN document_chunk.metadata               IS '预留扩展字段（JSONB），供后续检索策略附加信息，不参与核心链路';
COMMENT ON COLUMN document_chunk.created_at             IS '记录创建时间';
COMMENT ON COLUMN document_chunk.content_tsv            IS '中文全文检索列（chinese_zh 分词配置），由 title + content 自动生成，供 Sparse 检索路使用';
COMMENT ON COLUMN document_chunk.chunk_version          IS '所属 chunk 版本；配合 document.active_chunk_version 实现原子切换';
COMMENT ON COLUMN document_chunk.bbox                   IS '归一化到 [0,1] 的页面矩形，供前端高亮。跨页 chunk 只代表成员块的并集，必须配合 page_start/page_end';
COMMENT ON COLUMN document_chunk.source_offset          IS '字符级偏移。【本阶段只建列不写入，恒为 NULL】—— 解析产物没有该粒度；高亮已由 bbox + 页码满足';
COMMENT ON COLUMN document_chunk.caption                IS '表格题注，如「单位：元」。有题注时会拼进表格的 embedding 文本';
COMMENT ON COLUMN document_chunk.unit                   IS '计量单位，如 元 / 万元。从题注中抽取，抽不出则留 NULL（不猜，避免口径错误）';
COMMENT ON COLUMN document_chunk.currency               IS '币种，如 CNY / USD。仅当题注明确提及币种时填写';
COMMENT ON COLUMN document_chunk.headers_json           IS '表头单元格的 JSON 数组，供阶段 4 的 Context Packing 与 Rerank 使用；TABLE 行必填';
COMMENT ON COLUMN document_chunk.related_footnote_ids   IS '关联脚注的 block_id。【本阶段只建列不写入，恒为 NULL】—— 脚注关联需要解析侧的语义链接';
COMMENT ON COLUMN document_chunk.restatement_of         IS '重述/追溯调整指向的原 chunk。【本阶段只建列不写入，恒为 NULL】';
COMMENT ON COLUMN document_chunk.embedding_text_hash    IS '送进模型的 embedding_text 的 SHA-256。模板或模型变化时可精确圈出哪些 chunk 需要重嵌入';

-- ---------- financial_fact ----------
COMMENT ON TABLE financial_fact IS '结构化财务事实：从 PDF 抽取的关键指标，带证据引用与置信度（V1 只写 PDF_EXTRACT）';

COMMENT ON COLUMN financial_fact.id             IS '主键';
COMMENT ON COLUMN financial_fact.document_id    IS '证据所在文档';
COMMENT ON COLUMN financial_fact.chunk_id       IS '证据所在 chunk，用于回溯到原文位置';
COMMENT ON COLUMN financial_fact.company_id     IS '指标所属公司';
COMMENT ON COLUMN financial_fact.metric         IS '指标名，如 营业收入 / 归属于母公司股东的净利润';
COMMENT ON COLUMN financial_fact.period         IS '期间，如 2025 / 2025Q3';
COMMENT ON COLUMN financial_fact.value          IS '指标数值（原值，单位见 unit）';
COMMENT ON COLUMN financial_fact.unit           IS '计量单位，如 元 / 万元';
COMMENT ON COLUMN financial_fact.scope          IS '口径：合并 / 母公司。避免「数值正确但比较口径错误」';
COMMENT ON COLUMN financial_fact.evidence_pages IS '证据页码，如 "45,46"，用于生成引用';
COMMENT ON COLUMN financial_fact.confidence     IS '抽取置信度 0~1；低于阈值的建议人工复核';
COMMENT ON COLUMN financial_fact.created_at     IS '记录创建时间';
