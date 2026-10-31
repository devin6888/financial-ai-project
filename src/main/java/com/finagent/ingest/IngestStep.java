package com.finagent.ingest;

/**
 * 入库流程里的一个步骤（阶段 19）。
 *
 * <p>枚举是「步骤字典」，顺序与归属由 {@link IngestFlow} 决定 ——
 * 同一个步骤在不同流程里的序号可以不同（例如 CHUNKING 在 PARSE 是第 5 步、在 REINDEX 是第 1 步）。
 */
public enum IngestStep {

    RECEIVED("已入队"),
    SUBMITTED("提交解析作业"),
    PARSING("解析中"),
    PARSED("解析完成"),
    CHUNKING("切块中"),
    CHUNKED("切块完成"),
    EMBEDDING("向量化"),
    SWITCHED("切换 ACTIVE"),
    SKIPPED("已存在，跳过");

    private final String label;

    IngestStep(String label) {
        this.label = label;
    }

    /** 前端展示用的中文名；后端只发 label，不让前端维护一份对照表（两份迟早不一致）。 */
    public String label() {
        return label;
    }
}
