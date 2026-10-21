package com.finagent.document;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * 阶段 2 产物（NormalizedDocument）的**最小读取视图**。
 *
 * <p>为什么不复用 {@link ParsedDocument}：那个是「解析作业完成的通知」（只有计数与 rawJson），
 * 这个是「切块的输入」（要逐块的结构）。用途不同，不要合并。
 *
 * <p><b>字段名刻意用蛇形</b>（page_no / block_id / section_path），与产物 JSON 的键一致 ——
 * record 组件名直接匹配 JSON 键，**不需要任何 @JsonProperty**。评审用真实 2.5MB 产物实测验证过：
 * page_count=181 / blocks=1220 / tables=345 全部映射正确。
 *
 * <p><b>⚠️ 注解包名是 com.fasterxml.jackson.annotation，不是 tools.jackson</b>：
 * 阶段 2 确认过 Spring Boot 4 默认用 Jackson 3（core/databind 在 tools.jackson），
 * 但**注解没有搬家**。评审真机 javac 验证：写成 tools.jackson.databind.annotation 会「找不到符号」。
 * 这是对阶段 2 那条结论的过度推广，特此更正。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record DocumentModel(Meta meta, List<Block> blocks, List<Table> tables) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Meta(int page_count, String source_name, String parser_version) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Block(String block_id, String label, String text,
                        Provenance provenance, List<String> section_path) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Table(String table_id, int page_start, int page_end,
                        int num_rows, int num_cols, List<Cell> cells,
                        String caption, List<String> section_path, BBox bbox) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Cell(String text, int row, int col, int row_span, int col_span) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Provenance(int page_no, BBox bbox) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record BBox(double left, double top, double right, double bottom) {
    }
}
