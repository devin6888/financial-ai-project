package com.finagent.answer;

import com.finagent.answer.AnswerDto.FactDto;
import com.finagent.citation.Citation;
import com.finagent.citation.CitationValidator;
import com.finagent.context.ContextPackage;
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
        if (pkg == null || pkg.isEmpty()) {
            // 拒答不调模型：没有证据时让模型「自由发挥」是金融场景最危险的路径
            log.debug("证据包为空，拒答：question={}", question);
            return AnswerResult.refuse(AnswerResult.WARNING_NO_EVIDENCE,
                    "在所选范围内没有找到相关证据，无法回答该问题。可以放宽研究范围后重试。");
        }

        AnswerDto dto;
        try {
            String raw = generator.complete(AnswerPrompts.SYSTEM, AnswerPrompts.user(question, pkg));
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

        List<AnswerResult.Fact> outFacts = guarded.facts().stream()
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
