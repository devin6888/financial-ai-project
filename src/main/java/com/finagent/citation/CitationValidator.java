package com.finagent.citation;

import com.finagent.context.ContextBlock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 把模型输出的证据编号校验成引用。
 *
 * <p>三条硬规则：
 * <ol>
 *   <li>编号必须落在 {@code [1, blocks.size()]}，越界直接剔除（模型最常见的错误就是编一个 E9）；</li>
 *   <li>去重保序 —— 引用顺序反映模型的论证顺序，不按分数重排；</li>
 *   <li>页码 / bbox / 表名全部从库里读来的 block 取，模型说什么都不采信。</li>
 * </ol>
 */
@Component
public class CitationValidator {

    private static final Logger log = LoggerFactory.getLogger(CitationValidator.class);

    public static final String WARNING_DROPPED_PREFIX = "citation_dropped:";
    public static final String WARNING_MOSTLY_DROPPED = "citations_mostly_dropped";

    private final ObjectMapper objectMapper;

    public CitationValidator(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * @param emitted 模型输出的证据编号（[E3] → 3），可为 null
     * @param blocks  本轮上下文里的块，下标 0 对应 [E1]
     * @return 校验后的引用 + 被剔除的编号
     */
    public Result validate(List<Integer> emitted, List<ContextBlock> blocks) {
        List<Citation> citations = new ArrayList<>();
        List<Integer> dropped = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        if (emitted == null || emitted.isEmpty()) {
            return new Result(List.of(), List.of(), List.of());
        }
        List<ContextBlock> safeBlocks = blocks == null ? List.of() : blocks;

        Set<Integer> seen = new LinkedHashSet<>(emitted);
        for (Integer no : seen) {
            if (no == null) {
                dropped.add(null);
                continue;
            }
            if (no < 1 || no > safeBlocks.size()) {
                dropped.add(no);
                continue;
            }
            citations.add(toCitation(safeBlocks.get(no - 1)));
        }
        if (!dropped.isEmpty()) {
            warnings.add(WARNING_DROPPED_PREFIX + dropped.size());
        }
        // 剔除过半说明模型在「凭印象引用」，即使剩下的引用是真的，答案的可信度也该降级
        if (!seen.isEmpty() && dropped.size() * 2 > seen.size()) {
            warnings.add(WARNING_MOSTLY_DROPPED);
        }
        log.debug("引用校验：输出 {} 个编号 → 保留 {} 条 / 剔除 {} 个", seen.size(), citations.size(), dropped.size());
        return new Result(List.copyOf(citations), List.copyOf(dropped), List.copyOf(warnings));
    }

    /** 单块 → 单条引用（也用于测试与答案生成后的补全）。 */
    public Citation toCitation(ContextBlock b) {
        return new Citation(
                b.documentId(),
                parentIdOf(b),
                b.chunkId(),
                b.pageStart(),
                b.pageEnd(),
                toBBox(b),
                null,
                evidenceType(b),
                blankToNull(b.unit()),
                detectScope(b),
                b.docTitle(),
                b.sectionPath());
    }

    /** PARENT_WHOLE 块的 chunkId 就是父块本身，此时不该再报一个「父 id」。 */
    private static Long parentIdOf(ContextBlock b) {
        if (b.parentId() <= 0 || b.parentId() == b.chunkId()) {
            return null;
        }
        return b.parentId();
    }

    private static String evidenceType(ContextBlock b) {
        if (b.isTable()) {
            return Citation.TYPE_TABLE;
        }
        return Citation.TYPE_TEXT;
    }

    /**
     * 库里的 bbox 是单框归一化坐标 {top,left,right,bottom} → {x,y,w,h}。
     * 解析不了就返回 null —— 宁可没有高亮，也不要画错框。
     */
    private List<BBox> toBBox(ContextBlock b) {
        String json = b.bboxJson();
        if (json == null || json.isBlank() || b.pageStart() == null) {
            return null;
        }
        try {
            JsonNode node = objectMapper.readTree(json);
            if (!node.hasNonNull("top") || !node.hasNonNull("left")
                    || !node.hasNonNull("right") || !node.hasNonNull("bottom")) {
                return null;
            }
            double top = node.get("top").asDouble();
            double left = node.get("left").asDouble();
            double right = node.get("right").asDouble();
            double bottom = node.get("bottom").asDouble();
            return List.of(new BBox(b.pageStart(), left, top, right - left, bottom - top));
        } catch (RuntimeException e) {
            log.warn("bbox 解析失败，引用不带高亮框：{}", e.getMessage());
            return null;
        }
    }

    /**
     * 合并 / 母公司：只认正文里的显式措辞，判断不了写 null。
     * 不靠表头或页码猜 —— 猜错会让「合并净利润」变成「归母净利润」。
     */
    private static String detectScope(ContextBlock b) {
        String content = b.content() == null ? "" : b.content();
        if (content.contains("归属于母公司") || content.contains("归属于上市公司股东")) {
            return Citation.SCOPE_PARENT;
        }
        if (content.contains("合并资产负债表") || content.contains("合并利润表")
                || content.contains("合并现金流量表") || content.contains("合并口径")) {
            return Citation.SCOPE_CONSOLIDATED;
        }
        return null;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }

    /** 校验结果。dropped 里保留原始编号，便于离线统计模型爱编哪个号。 */
    public record Result(List<Citation> citations, List<Integer> dropped, List<String> warnings) {
        public boolean hasCitations() {
            return !citations.isEmpty();
        }
    }
}
