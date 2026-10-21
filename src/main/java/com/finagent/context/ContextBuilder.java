package com.finagent.context;

import com.finagent.chunk.ChunkRepository;
import com.finagent.chunk.ChunkRepository.ChunkView;
import com.finagent.retrieval.RetrievedChunk;
import com.finagent.scope.ResolvedScope;
import com.finagent.understanding.QueryType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 证据装配（§9.8 Parent-Child Expansion / §9.9 Context Packing / §9.15 噪声控制）。
 *
 * <p><b>要解决的问题</b>：跨文档评测里 cite@1 是 12/12，但 val@1 只有 8/12 ——
 * 检索选对了话题，却没选中「最该当证据的那一块」。典型例子：问每股收益，
 * top-1 是「主要会计数据和财务指标」的叙述段（第 12 块），真正带数字的表格排第 2（第 13 块）。
 * 原因不是排序差，而是<b>一块证据被单独拿出来时缺少上下文</b>：表格没有表头、没有单位，
 * 数字落在哪一年也无从判断。
 *
 * <p>装配做四件事，顺序不能反：
 * <ol>
 *   <li><b>Parent 内二次选择</b>：只保留命中分 ≥ 0.6 × 组内最高分的块（§9.15）——</li>
 *   <li><b>补邻居</b>：命中块前后各 1 个 chunkIndex，保证句子不断在半句上；</li>
 *   <li><b>整段 Parent</b>：只有 COMPLEX_ANALYSIS 且 Parent ≤ 1500 token 才整段给；</li>
 *   <li><b>预算裁剪</b>：超预算整块出局，<b>绝不截断字符串</b>（截断会把数字切成两半）。</li>
 * </ol>
 *
 * <p><b>为什么编号而不是让模型输出 chunkId</b>：模型编 id 的能力极强、编对的能力极差。
 * 让它只回 [E1] 这样的序号，后端映射回真实 chunk，「引用必须存在」就成了结构性保证。
 */
@Component
public class ContextBuilder {

    private static final Logger log = LoggerFactory.getLogger(ContextBuilder.class);

    private static final String WARNING_TRUNCATED = "context_truncated";
    private static final String WARNING_NO_PARENT = "context_parent_missing";

    private final ChunkRepository chunks;
    private final ContextProperties props;
    private final ObjectMapper json;

    public ContextBuilder(ChunkRepository chunks, ContextProperties props, ObjectMapper json) {
        this.chunks = chunks;
        this.props = props;
        this.json = json;
    }

    /**
     * 装配证据包。
     *
     * @param queryType 决定是否整段取 Parent（COMPLEX_ANALYSIS 才取）
     * @param hits      精排后的命中（已按分数降序）
     * @param scope     本轮作用域；仅用于日志与包内元信息，过滤已在检索层完成
     */
    public ContextPackage build(String query, QueryType queryType, List<RetrievedChunk> hits,
                                ResolvedScope scope) {
        if (hits == null || hits.isEmpty()) {
            return ContextPackage.empty();
        }

        Map<Long, Double> scoreById = new LinkedHashMap<>();
        for (RetrievedChunk h : hits) {
            scoreById.put(h.chunkId(), h.rerankScore() == null ? 0.0 : h.rerankScore());
        }

        // 1) 两次批量查询：命中块本身 + 它们的 Parent（整段展开要用），以及所有 Parent 的 Child
        Set<Long> ids = new LinkedHashSet<>();
        for (RetrievedChunk h : hits) {
            ids.add(h.chunkId());
            if (h.parentId() > 0) {
                ids.add(h.parentId());
            }
        }
        Map<Long, ChunkView> viewById = new LinkedHashMap<>();
        for (ChunkView v : chunks.findViewsByIds(new ArrayList<>(ids))) {
            viewById.put(v.id(), v);
        }
        List<Long> parentIds = hits.stream().map(RetrievedChunk::parentId)
                .filter(id -> id > 0).distinct().toList();
        Map<Long, List<ChunkView>> childrenByParent = new LinkedHashMap<>();
        for (ChunkView v : chunks.findChildViewsByParents(parentIds)) {
            if (v.parentId() != null) {
                childrenByParent.computeIfAbsent(v.parentId(), k -> new ArrayList<>()).add(v);
            }
        }

        // 2) 按 Parent 分组（保持 hits 的顺序：它已经是精排结果）
        Map<Long, List<RetrievedChunk>> hitsByParent = new LinkedHashMap<>();
        for (RetrievedChunk h : hits) {
            hitsByParent.computeIfAbsent(h.parentId(), k -> new ArrayList<>()).add(h);
        }

        // 3) 每组做二次选择 + 补邻居 / 整段展开
        List<Group> groups = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        int parentsExpanded = 0;
        int siblingsAdded = 0;

        for (Map.Entry<Long, List<RetrievedChunk>> e : hitsByParent.entrySet()) {
            long parentId = e.getKey();
            List<RetrievedChunk> groupHits = e.getValue();
            double maxScore = groupHits.stream().mapToDouble(h -> scoreById.get(h.chunkId())).max().orElse(0.0);
            ChunkView parentView = parentId > 0 ? viewById.get(parentId) : null;
            List<ChunkView> children = childrenByParent.getOrDefault(parentId, List.of());

            boolean expanded = !children.isEmpty();
            if (!expanded) {
                // 命中块没有 Parent（历史数据或 PARENT 级命中）：只能原样用命中块，不假装展开
                warnings.add(WARNING_NO_PARENT);
            } else {
                parentsExpanded++;
            }

            // 3a) 整段 Parent：只有复杂分析且 Parent 本身不大，才值得整段给
            if (queryType == QueryType.COMPLEX_ANALYSIS && parentView != null
                    && parentView.tokenCount() <= props.getParentWholeMaxTokens()) {
                groups.add(new Group(maxScore, List.of(toBlock(parentView, scoreById.getOrDefault(parentView.id(), maxScore),
                        ContextBlock.ROLE_PARENT_WHOLE))));
                continue;
            }

            // 3b) 组内二次选择：>= 0.6 × 组内最高分。未精排（分数全 0）时阈值也是 0，
            //     等于保留该 Parent 的全部命中 —— 此时名次本来就是 RRF 给的，没有理由再砍。
            double threshold = maxScore * props.getMinScoreFactor();
            Set<Long> selected = new LinkedHashSet<>();
            for (RetrievedChunk h : groupHits) {
                if (scoreById.get(h.chunkId()) >= threshold) {
                    selected.add(h.chunkId());
                }
            }
            if (selected.isEmpty()) {
                selected.add(groupHits.get(0).chunkId());   // 兜底：至少留一条，否则这一组白展开
            }

            // 3c) 补邻居：同 Parent 内按 chunkIndex ± radius。
            //     children 为空时只能用命中块本身 —— 此时没有"邻居"可言，也不能补。
            List<ChunkView> ordered;
            if (expanded) {
                ordered = new ArrayList<>(children);
                ordered.sort(Comparator.comparingInt(ChunkView::chunkIndex));
            } else {
                ordered = new ArrayList<>();
                for (RetrievedChunk h : groupHits) {
                    ChunkView v = viewById.get(h.chunkId());
                    if (v != null) {
                        ordered.add(v);
                    }
                }
                selected.clear();
                ordered.forEach(v -> selected.add(v.id()));
            }
            Set<Long> keep = new LinkedHashSet<>(selected);
            if (expanded && props.getNeighborRadius() > 0) {
                for (int i = 0; i < ordered.size(); i++) {
                    if (!selected.contains(ordered.get(i).id())) {
                        continue;
                    }
                    for (int d = 1; d <= props.getNeighborRadius(); d++) {
                        if (i - d >= 0) {
                            keep.add(ordered.get(i - d).id());
                        }
                        if (i + d < ordered.size()) {
                            keep.add(ordered.get(i + d).id());
                        }
                    }
                }
            }

            List<ContextBlock> blocks = new ArrayList<>();
            for (ChunkView v : ordered) {
                if (!keep.contains(v.id())) {
                    continue;
                }
                String role = selected.contains(v.id()) ? ContextBlock.ROLE_HIT : ContextBlock.ROLE_SIBLING;
                blocks.add(toBlock(v, scoreById.getOrDefault(v.id(), 0.0), role));
            }
            siblingsAdded += blocks.size() - selected.size();
            groups.add(new Group(maxScore, blocks));
        }

        // 4) 组间按组内最高分降序（分数高的 Parent 先占预算），组内保持 chunkIndex 升序
        groups.sort(Comparator.comparingDouble((Group g) -> g.maxScore).reversed());

        // 5) 预算裁剪：整块进出，不截断字符串
        List<ContextBlock> accepted = new ArrayList<>();
        int used = 0;
        int evidenceNo = 0;
        boolean truncated = false;
        for (Group g : groups) {
            for (ContextBlock b : g.blocks) {
                int cost = b.tokenCount();
                if (used + cost > props.getMaxTokens()) {
                    truncated = true;
                    continue;
                }
                accepted.add(b.withEvidenceNo(++evidenceNo));
                used += cost;
            }
        }
        if (truncated) {
            warnings.add(WARNING_TRUNCATED);
        }

        String text = render(accepted);
        log.debug("上下文装配：{} 块 / {} token / 展开 {} 个 Parent / 补 {} 个邻居",
                accepted.size(), used, parentsExpanded, siblingsAdded);
        return new ContextPackage(text, accepted, used, warnings, parentsExpanded, siblingsAdded);
    }

    // ---------------------------------------------------------------- 渲染

    /**
     * 渲染格式（决定引用的可读性）：
     * <pre>
     * [E1] 东方财富2025年年度报告 · 第 47 页 · 第二节 公司简介和主要财务指标 &gt; 五、主要会计数据和财务指标 · TABLE
     * 表：主要会计数据和财务指标（单位：元）
     * 表头：项目 | 2025年 | 2024年
     * 内容：
     * | 营业收入 | ... |
     * </pre>
     *
     * <p>块之间空一行；<b>不做任何改写、不做摘要</b> —— 改写过的证据无法被引用校验。
     */
    public String render(List<ContextBlock> blocks) {
        StringBuilder sb = new StringBuilder();
        for (ContextBlock b : blocks) {
            if (sb.length() > 0) {
                sb.append("\n\n");
            }
            sb.append(renderBlock(b));
        }
        return sb.toString();
    }

    private String renderBlock(ContextBlock b) {
        StringBuilder sb = new StringBuilder();
        sb.append("[E").append(b.evidenceNo()).append("] ")
                .append(blankTo(b.docTitle(), "未知文档"))
                .append(" · ").append(pageText(b.pageStart(), b.pageEnd()))
                .append(" · ").append(sectionText(b.sectionPath()))
                .append(" · ").append(blankTo(b.chunkType(), "TEXT"));

        if (b.isTable()) {
            String caption = b.caption();
            String unit = b.unit();
            if (notBlank(caption) || notBlank(unit)) {
                sb.append("\n表：").append(notBlank(caption) ? caption.strip() : "（无表名）");
                if (notBlank(unit)) {
                    sb.append("（单位：").append(unit.strip()).append("）");
                }
            }
            List<String> headers = headers(b.headersJson());
            if (!headers.isEmpty()) {
                sb.append("\n表头：").append(String.join(" | ", headers));
            }
            sb.append("\n内容：\n");
        } else {
            sb.append("\n\n");
        }
        sb.append(b.content() == null ? "" : b.content().strip());
        return sb.toString();
    }

    /** headers_json 是 JSON 数组；解析失败就当没表头，不让一个脏字段毁掉整包上下文。 */
    private List<String> headers(String headersJson) {
        if (!notBlank(headersJson)) {
            return List.of();
        }
        try {
            String[] arr = json.readValue(headersJson, String[].class);
            List<String> out = new ArrayList<>();
            for (String h : arr) {
                if (notBlank(h)) {
                    out.add(h.strip());
                }
            }
            return out;
        } catch (RuntimeException e) {
            log.debug("headers_json 解析失败，忽略：{}", headersJson);
            return List.of();
        }
    }

    /** 章节路径在库里用 " § " 分隔，给模型看用 " > " 更自然（与 embedding_text 的拼法一致）。 */
    static String sectionText(String sectionPath) {
        if (!notBlank(sectionPath)) {
            return "章节未知";
        }
        return sectionPath.replace(" § ", " > ").strip();
    }

    static String pageText(Integer start, Integer end) {
        if (start == null) {
            return "页码未知";
        }
        if (end == null || end.equals(start)) {
            return "第 " + start + " 页";
        }
        return "第 " + start + "-" + end + " 页";
    }

    // ---------------------------------------------------------------- 工具

    private ContextBlock toBlock(ChunkView v, double score, String role) {
        return new ContextBlock(0, v.id(), v.parentId() == null ? 0L : v.parentId(), v.documentId(),
                v.docTitle(), v.sectionPath(), v.title(), v.content(),
                v.pageStart(), v.pageEnd(), v.chunkType(), v.caption(), v.unit(),
                v.headersJson(), v.bboxJson(), score, v.chunkIndex(), v.tokenCount(), role);
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    private static String blankTo(String s, String fallback) {
        return notBlank(s) ? s.strip() : fallback;
    }

    private record Group(double maxScore, List<ContextBlock> blocks) {
    }
}
