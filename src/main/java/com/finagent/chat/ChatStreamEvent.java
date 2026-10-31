package com.finagent.chat;

import com.finagent.context.ContextBlock;
import com.finagent.context.ContextPackage;
import com.finagent.scope.ResolvedScope;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * SSE 事件（契约 C15）。
 *
 * <p>五种事件 + 心跳注释行。顺序是硬要求：{@code meta} 只在第一个 {@code delta} 之前出现一次，
 * {@code final} 或 {@code error} 有且仅有一个且必须是最后一个。
 *
 * <p>阶段 13（契约 C16）新增 {@code stage}：它只表达「正在做什么」，可以丢、可以被忽略；
 * 答案与证据仍然只走 {@code meta} / {@code delta} / {@code final}。
 *
 * <p>为什么 {@code final} 要带完整的 ChatResponse：<b>它是唯一真相</b>。
 * 前端用 delta 做「看到字在动」的展示，但引用、置信度、警告、trace、messageId
 * 全部以 final 为准 —— 否则流式与非流式会给出两套结论。
 */
public record ChatStreamEvent(String event, Object data) {

    public static final String STAGE = "stage";
    public static final String META = "meta";
    public static final String DELTA = "delta";
    public static final String FINAL = "final";
    public static final String ERROR = "error";
    /** Agent 路径专用：停在 answer 前，等人批（阶段 11，契约 C17）。 */
    public static final String APPROVAL = "approval";

    /** 阶段名：正在理解问题（QU，一次 LLM 调用）。 */
    public static final String PHASE_UNDERSTANDING = "understanding";
    /** 阶段名：正在检索证据（双路召回 + 融合）。 */
    public static final String PHASE_RETRIEVING = "retrieving";
    /** 阶段名：正在精排证据（外部 rerank 调用）。 */
    public static final String PHASE_RERANKING = "reranking";
    /** 阶段名：正在生成答案（模型出字）。 */
    public static final String PHASE_GENERATING = "generating";

    /**
     * 进度事件（阶段 13，契约 C16）。
     *
     * <p>三条硬规则：① 不带任何答案文本 / 证据正文 / 页码 / chunk id；
     * ② 必须由真实进入该段的代码发出，禁止补发或伪造；
     * ③ 发过 stage 就必须有终止事件（meta / final / error）。
     *
     * @param phase  {@link #PHASE_UNDERSTANDING} 等四个之一
     * @param label  给用户看的中文短语，前端直接显示，不自己编
     * @param detail 附加数字（文档数 / 候选数 / 证据数），可为空
     */
    public static ChatStreamEvent stage(String phase, String label, Map<String, Object> detail) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("phase", phase);
        data.put("label", label == null ? "" : label);
        data.put("detail", detail == null ? Map.of() : detail);
        return new ChatStreamEvent(STAGE, data);
    }

    /**
     * 检索 + 上下文装配完成、模型开始生成前。
     *
     * <p>证据清单在生成之前就是确定的：编号 E1..En 由上下文块的顺序决定，
     * 不依赖模型输出。所以前端可以在第一个字到达前就把证据面板渲染出来。
     */
    public static ChatStreamEvent meta(String traceId, ResolvedScope scope, ContextPackage pkg) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("traceId", traceId);
        data.put("scope", Map.of(
                "kind", scope == null || scope.kind() == null ? "CORPUS" : scope.kind().name(),
                "documentIds", scope == null ? List.of() : scope.documentIds()));
        List<Map<String, Object>> evidence = new ArrayList<>();
        for (ContextBlock b : pkg == null ? List.<ContextBlock>of() : pkg.blocks()) {
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("no", b.evidenceNo());
            e.put("childChunkId", b.chunkId());
            e.put("documentId", b.documentId());
            e.put("pageStart", b.pageStart());
            e.put("pageEnd", b.pageEnd());
            e.put("docTitle", b.docTitle());
            e.put("sectionPath", b.sectionPath());
            e.put("evidenceType", b.isTable() ? "TABLE" : "TEXT");
            evidence.add(e);
        }
        data.put("evidence", evidence);
        data.put("contextTokens", pkg == null ? 0 : pkg.tokenCount());
        return new ChatStreamEvent(META, data);
    }

    /**
     * 图路径的 meta：证据在 verify 节点之后就已经确定（retrieve 的 hits），不必等模型。
     *
     * <p>字段与 {@link #meta} 对齐（no / childChunkId / documentId / pageStart / pageEnd /
     * sectionPath / evidenceType），但只有命中块，没有上下文装配后的编号与 token 数 ——
     * 图里的编号在 answer 节点内部才产生（ST14 P4）。
     */
    public static ChatStreamEvent metaEvidence(String traceId, List<Map<String, Object>> evidence,
                                               int contextTokens) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("traceId", traceId);
        List<Map<String, Object>> items = new ArrayList<>();
        int no = 0;
        for (Map<String, Object> hit : evidence == null ? List.<Map<String, Object>>of() : evidence) {
            no++;
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("no", no);
            e.put("childChunkId", hit.get("chunkId"));
            e.put("documentId", hit.get("documentId"));
            e.put("pageStart", hit.get("page"));
            e.put("pageEnd", hit.get("page"));
            e.put("sectionPath", hit.get("sectionPath"));
            e.put("evidenceType", hit.get("type"));
            items.add(e);
        }
        data.put("evidence", items);
        data.put("contextTokens", contextTokens);
        return new ChatStreamEvent(META, data);
    }

    public static ChatStreamEvent delta(String text) {
        return new ChatStreamEvent(DELTA, Map.of("text", text == null ? "" : text));
    }

    public static ChatStreamEvent finalEvent(ChatResponse response) {
        return new ChatStreamEvent(FINAL, response);
    }

    /** Agent 的 final：载荷不是 ChatResponse（它有自己的 plan / steps / evidence）。 */
    public static ChatStreamEvent finalData(Object data) {
        return new ChatStreamEvent(FINAL, data);
    }

    /** Agent 停在 answer 前：给人看「打算怎么做」与「已经拿到什么证据」。 */
    public static ChatStreamEvent approval(Object data) {
        return new ChatStreamEvent(APPROVAL, data);
    }

    public static ChatStreamEvent error(String code, String message) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("code", code);
        data.put("message", message == null ? "" : message);
        return new ChatStreamEvent(ERROR, data);
    }
}
