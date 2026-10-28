package com.finagent.answer;

import com.finagent.answer.AnswerDto.FactDto;
import com.finagent.citation.Citation;
import com.finagent.citation.CitationValidator;
import com.finagent.context.ContextBlock;
import com.finagent.context.ContextPackage;
import com.finagent.memory.MemoryContext;
import com.finagent.understanding.QueryUnderstanding;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 答案生成：一次 LLM 调用 + 三层后端校验。
 *
 * <p>三层都只在「模型输出与证据对不上」时降级，从不改写数字：
 * <ol>
 *   <li>{@link FactGuard}：同指标跨文档冲突 → 降置信、加 note；</li>
 *   <li>{@link CitationValidator}：编号越界 → 剔除，剔除过多 → 降置信；</li>
 *   <li>解析失败 → confidence=error，<b>绝不</b>退回「无引用的自由文本」。</li>
 * </ol>
 */
@Component
public class AnswerService {

    private static final Logger log = LoggerFactory.getLogger(AnswerService.class);

    public static final int MAX_FACTS = 8;
    public static final String WARNING_NO_CITATION = "answer_without_citation";

    private final AnswerGenerator generator;
    private final AnswerParser parser;
    private final CitationValidator citationValidator;
    private final FactGuard factGuard;

    public AnswerService(AnswerGenerator generator, AnswerParser parser,
                         CitationValidator citationValidator, FactGuard factGuard) {
        this.generator = generator;
        this.parser = parser;
        this.citationValidator = citationValidator;
        this.factGuard = factGuard;
    }

    public AnswerResult answer(String question, QueryUnderstanding qu, ContextPackage pkg) {
        return answer(question, qu, pkg, null);
    }

    /**
     * @param memory 背景信息（短期对话 / 摘要 / 结构化上下文 / 长期偏好）。
     *               <b>它只进 Prompt 的【背景信息】段，不进 {@code pkg.blocks()}</b> ——
     *               引用编号只由证据块产生，所以记忆不可能出现在 citations 里（D4）。
     */
    public AnswerResult answer(String question, QueryUnderstanding qu, ContextPackage pkg,
                               MemoryContext memory) {
        if (pkg == null || pkg.isEmpty()) {
            // 拒答不调模型：没有证据时让模型「自由发挥」是金融场景最危险的路径
            log.debug("证据包为空，拒答：question={}", question);
            return AnswerResult.refuse(AnswerResult.WARNING_NO_EVIDENCE,
                    "在所选范围内没有找到相关证据，无法回答该问题。可以放宽研究范围后重试。");
        }

        AnswerDto dto;
        try {
            String raw = generator.complete(AnswerPrompts.SYSTEM,
                    AnswerPrompts.user(question, pkg, memory == null ? null : memory.text()));
            dto = parser.parse(raw);
        } catch (RuntimeException e) {
            log.warn("答案生成失败，降级为 error：{}", e.getMessage());
            return new AnswerResult(false,
                    "答案生成失败：模型输出无法解析。请重试，或缩小问题范围。",
                    List.of(), List.of(), AnswerResult.CONFIDENCE_ERROR, null,
                    List.of(AnswerResult.WARNING_GENERATION_FAILED), 0);
        }

        List<String> warnings = new ArrayList<>();
        if (!dto.sufficient()) {
            return new AnswerResult(false, dto.answer() == null ? "" : dto.answer(),
                    List.of(), List.of(), AnswerResult.CONFIDENCE_INSUFFICIENT, dto.note(), warnings, 0);
        }

        List<FactDto> facts = dto.facts() == null ? List.of() : dto.facts();
        if (facts.size() > MAX_FACTS) {
            facts = List.copyOf(facts.subList(0, MAX_FACTS));
            warnings.add(AnswerResult.WARNING_FACTS_TRUNCATED);
        }
        FactGuard.Result guarded = factGuard.guard(facts, pkg.blocks(), qu);
        warnings.addAll(guarded.warnings());

        CitationValidator.Result citations =
                citationValidator.validate(parseEvidence(dto.usedEvidence()), pkg.blocks());
        warnings.addAll(citations.warnings());

        String confidence = normalizeConfidence(dto.confidence());
        confidence = lower(confidence, guarded.confidenceCap());
        if (!citations.dropped().isEmpty()) {
            confidence = lower(confidence, AnswerResult.CONFIDENCE_MEDIUM);
            warnings.add(AnswerResult.WARNING_CITATIONS_DROPPED);
        }
        if (citations.citations().isEmpty()) {
            // 没有引用的答案在金融场景等于谣言 —— 不拦截，但一定降级并留痕
            confidence = lower(confidence, AnswerResult.CONFIDENCE_MEDIUM);
            warnings.add(WARNING_NO_CITATION);
        }

        // 第四层：数字必须能回到被引证据。
        // 2026-10-22 评测抓到 —— 问「2024年员工总数」，模型答「5,560 人」并给了引用，
        // 但库里 0 个 chunk 含 5,560 / 1,226 / 4,334：引用是真的，数字是编的。
        // 引用校验管不了这件事（编号合法），只有拿数字回查正文才能发现。
        Grounding grounding = ground(dto.answer(), guarded.facts(), citations.citations(), pkg.blocks());
        warnings.addAll(grounding.warnings());
        confidence = lower(confidence, grounding.confidenceCap());
        if (grounding.allUngrounded()) {
            return new AnswerResult(false,
                    "答案中的数字无法在引用证据中核对（可能是模型推算或编造），已拒答。请换一种问法，或直接查看原始报告。",
                    toFacts(grounding.facts()), citations.citations(),
                    AnswerResult.CONFIDENCE_INSUFFICIENT,
                    "引用块里没有出现答案中的数值，按无证据处理。",
                    List.copyOf(warnings), citations.dropped().size());
        }

        List<AnswerResult.Fact> outFacts = grounding.facts().stream()
                .map(f -> new AnswerResult.Fact(f.metric(), f.period(), f.value(), f.unit(), f.scope(),
                        f.evidence(), AnswerResult.Fact.SOURCE_PDF))
                .toList();

        return new AnswerResult(true,
                dto.answer() == null ? "" : dto.answer(),
                outFacts,
                citations.citations(),
                confidence,
                mergeNote(dto.note(), guarded.note()),
                List.copyOf(warnings),
                citations.dropped().size());
    }

    // ------------------------------------------------------------ 数字落地校验

    /** 报告里出现过的数字形态：千分位（5,560 / 16,067,741,039.51）或四位以上的纯数字。 */
    private static final java.util.regex.Pattern NUMBER =
            java.util.regex.Pattern.compile("\\d{1,3}(?:,\\d{3})+(?:\\.\\d+)?|\\d{4,}(?:\\.\\d+)?");

    record Grounding(List<FactDto> facts, List<String> warnings, String confidenceCap,
                     boolean allUngrounded) { }

    /**
     * 拿答案里的数字回查被引证据。
     *
     * <p>只拦「报告式数字」（带千分位或四位以上）：增长率、百分比、期数这类
     * 模型自己算出来的小数字不在此列 —— 那些是允许的推理，不是编造。
     *
     * <p>为什么全部落空才拒答：一条答案里只要有一个数能对上，其余多半是折算/合计；
     * 一个都对不上，说明这段答案不是从证据里读出来的。
     */
    Grounding ground(String answer, List<FactDto> facts, List<Citation> citations,
                     List<ContextBlock> blocks) {
        java.util.Map<Long, String> byChunk = new java.util.HashMap<>();
        for (ContextBlock b : blocks) {
            byChunk.put(b.chunkId(), b.content() == null ? "" : b.content());
        }
        StringBuilder cited = new StringBuilder();
        for (Citation c : citations) {
            String content = byChunk.get(c.childChunkId());
            if (content != null) {
                cited.append(content).append('\n');
            }
        }
        String text = normalizeNumbers(cited.toString());

        List<FactDto> keptFacts = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        for (FactDto f : facts) {
            // 小数字（股数 3、期数 10）不做落地校验：在正文里必然到处能撞上，校验等于没校验
            if (f.value() == null || !isReportNumber(f.value())
                    || text.contains(normalizeNumbers(f.value()))) {
                keptFacts.add(f);
            } else {
                warnings.add(AnswerResult.WARNING_FACT_UNGROUNDED + f.metric());
            }
        }

        int total = 0;
        int ungrounded = 0;
        java.util.regex.Matcher m = NUMBER.matcher(answer == null ? "" : answer);
        while (m.find()) {
            String n = m.group();
            if (n.matches("(19|20)\\d{2}")) {
                continue;    // 年份不是事实数字
            }
            total++;
            if (!text.contains(normalizeNumbers(n))) {
                ungrounded++;
            }
        }

        boolean all = total > 0 && ungrounded == total;
        String cap = AnswerResult.CONFIDENCE_HIGH;
        if (ungrounded > 0) {
            warnings.add(AnswerResult.WARNING_ANSWER_UNGROUNDED + ":" + ungrounded);
            cap = AnswerResult.CONFIDENCE_LOW;
        }
        return new Grounding(List.copyOf(keptFacts), List.copyOf(warnings), cap, all);
    }

    private static boolean isReportNumber(String value) {
        return NUMBER.matcher(normalizeNumbers(value)).matches();
    }

    private static String normalizeNumbers(String s) {
        return s == null ? "" : s.replace(",", "").replace(" ", "");
    }

    private static List<AnswerResult.Fact> toFacts(List<FactDto> facts) {
        return facts.stream()
                .map(f -> new AnswerResult.Fact(f.metric(), f.period(), f.value(), f.unit(), f.scope(),
                        f.evidence(), AnswerResult.Fact.SOURCE_PDF))
                .toList();
    }

    /** "E12" / "[E12]" / "12" → 12；解析不了的编号直接丢弃（渲染时不会出现这种编号）。 */
    static List<Integer> parseEvidence(List<String> used) {
        if (used == null || used.isEmpty()) {
            return List.of();
        }
        List<Integer> out = new ArrayList<>();
        for (String s : used) {
            Integer no = FactGuard.evidenceNo(s);
            if (no != null && !out.contains(no)) {
                out.add(no);
            }
        }
        return List.copyOf(out);
    }

    private static String normalizeConfidence(String confidence) {
        if (confidence == null) {
            return AnswerResult.CONFIDENCE_HIGH;
        }
        return switch (confidence.trim().toLowerCase()) {
            case AnswerResult.CONFIDENCE_LOW -> AnswerResult.CONFIDENCE_LOW;
            case AnswerResult.CONFIDENCE_MEDIUM -> AnswerResult.CONFIDENCE_MEDIUM;
            case AnswerResult.CONFIDENCE_INSUFFICIENT -> AnswerResult.CONFIDENCE_INSUFFICIENT;
            default -> AnswerResult.CONFIDENCE_HIGH;
        };
    }

    private static String lower(String a, String b) {
        return rank(a) <= rank(b) ? a : b;
    }

    private static int rank(String confidence) {
        if (confidence == null) {
            return 0;
        }
        return switch (confidence) {
            case AnswerResult.CONFIDENCE_HIGH -> 3;
            case AnswerResult.CONFIDENCE_MEDIUM -> 2;
            case AnswerResult.CONFIDENCE_LOW -> 1;
            default -> 0;
        };
    }

    private static String mergeNote(String a, String b) {
        if (a == null || a.isBlank()) {
            return b;
        }
        if (b == null || b.isBlank()) {
            return a;
        }
        return a + "；" + b;
    }
}
