package com.finagent.answer;

import com.finagent.answer.AnswerDto.FactDto;
import com.finagent.citation.Citation;
import com.finagent.citation.CitationValidator;
import com.finagent.context.ContextBlock;
import com.finagent.context.ContextPackage;
import com.finagent.fact.FactPack;
import com.finagent.memory.MemoryContext;
import com.finagent.understanding.AnswerShape;
import com.finagent.understanding.QueryUnderstanding;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

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

    /** 事实卡里计算事实最多占几格，剩下的留给原文数值（见 {@code mergeFacts}）。 */
    public static final int MAX_COMPUTED_IN_ANSWER = 5;
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
        return answer(question, qu, pkg, null, FactPack.EMPTY);
    }

    /**
     * @param memory 背景信息（短期对话 / 摘要 / 结构化上下文 / 长期偏好）。
     *               <b>它只进 Prompt 的【背景信息】段，不进 {@code pkg.blocks()}</b> ——
     *               引用编号只由证据块产生，所以记忆不可能出现在 citations 里（D4）。
     */
    public AnswerResult answer(String question, QueryUnderstanding qu, ContextPackage pkg,
                               MemoryContext memory, FactPack factPack) {
        FactPack pack = factPack == null ? FactPack.EMPTY : factPack;
        if (pkg == null || pkg.isEmpty()) {
            // 拒答不调模型：没有证据时让模型「自由发挥」是金融场景最危险的路径
            log.debug("证据包为空，拒答：question={}", question);
            return AnswerResult.refuse(AnswerResult.WARNING_NO_EVIDENCE,
                    "在所选范围内没有找到相关证据，无法回答该问题。可以放宽研究范围后重试。");
        }

        AnswerDto dto;
        try {
            String raw = generator.complete(AnswerPrompts.SYSTEM,
                    AnswerPrompts.user(question, pkg, memory == null ? null : memory.text(),
                            shapesOf(question, qu), pack.prompt()));
            dto = parser.parse(raw);
        } catch (RuntimeException e) {
            log.warn("答案生成失败，降级为 error：{}", e.getMessage());
            return generationFailed();
        }
        return evaluate(dto, qu, pkg, pack);
    }

    /**
     * 流式答案：与 {@link #answer} <b>同一次调用、同一份 JSON</b>，只把 answer 字段边生成边推给前端。
     *
     * <p>三条不变量（都有单测）：
     * <ol>
     *   <li>delta 按顺序拼接 === 最终 answer 字段的值（提取器不做改写，只解码转义）；</li>
     *   <li>中间不校验、不下结论：引用越界 / 数字落空都在流结束后由 {@link #evaluate} 统一处理，
     *       前端收到 final 事件必须用最终结果替换已渲染文本；</li>
     *   <li>证据包为空时<b>不调模型</b>，与 {@link #answer} 的拒答路径完全一致。</li>
     * </ol>
     *
     * @param onDelta 增量回调，可能被调用 0 次（模型只给 note、或输出被截断）
     */
    public AnswerResult answerStreaming(String question, QueryUnderstanding qu, ContextPackage pkg,
                                        MemoryContext memory, Consumer<String> onDelta) {
        return answerStreaming(question, qu, pkg, memory, FactPack.EMPTY, onDelta);
    }

    /** @param factPack 事实包（compute 节点产出）；空包与旧行为完全一致。 */
    public AnswerResult answerStreaming(String question, QueryUnderstanding qu, ContextPackage pkg,
                                        MemoryContext memory, FactPack factPack, Consumer<String> onDelta) {
        FactPack pack = factPack == null ? FactPack.EMPTY : factPack;
        if (pkg == null || pkg.isEmpty()) {
            log.debug("证据包为空，拒答（不调模型）：question={}", question);
            return AnswerResult.refuse(AnswerResult.WARNING_NO_EVIDENCE,
                    "在所选范围内没有找到相关证据，无法回答该问题。可以放宽研究范围后重试。");
        }

        AnswerFieldExtractor extractor = new AnswerFieldExtractor();
        StringBuilder raw = new StringBuilder();
        AnswerDto dto;
        try {
            generator.stream(AnswerPrompts.SYSTEM,
                    AnswerPrompts.user(question, pkg, memory == null ? null : memory.text(),
                            shapesOf(question, qu), pack.prompt()),
                    chunk -> {
                        raw.append(chunk);
                        String delta = extractor.feed(chunk);
                        if (!delta.isEmpty()) {
                            onDelta.accept(delta);
                        }
                    });
            dto = parser.parse(raw.toString());
        } catch (RuntimeException e) {
            log.warn("流式答案生成失败，降级为 error：{}", e.getMessage());
            return generationFailed();
        }
        return evaluate(dto, qu, pkg, pack);
    }

    /**
     * 答案形态（ST16 Task 1）：由问题本身 + 年份区间决定，纯函数、不调模型。
     *
     * <p>只用于提示词里的【回答要求】段；verify 的要素覆盖用状态里那份，两者同一套词表。
     */
    private static Set<AnswerShape> shapesOf(String question, QueryUnderstanding qu) {
        return AnswerShape.detect(question, qu == null ? null : qu.yearRange());
    }

    /** 生成失败的统一降级：绝不退回「无引用的自由文本」。 */
    private static AnswerResult generationFailed() {
        return new AnswerResult(false,
                "答案生成失败：模型输出无法解析。请重试，或缩小问题范围。",
                List.of(), List.of(), AnswerResult.CONFIDENCE_ERROR, null,
                List.of(AnswerResult.WARNING_GENERATION_FAILED), 0);
    }

    /** 拿到结构化答案之后的四层校验（流式与非流式共用这一个实现，行为不可能分叉）。 */
    private AnswerResult evaluate(AnswerDto dto, QueryUnderstanding qu, ContextPackage pkg,
                                  FactPack factPack) {
        List<String> warnings = new ArrayList<>();
        if (!dto.sufficient()) {
            // 模型自己说「答不了」时，也必须给用户一句能读的话 + 一个机器可读的原因。
            // 2026-10-28 阶段 9 e2e 抓到：answer 与 note 都是空串，客户端只收到空文本，
            // 既没法展示也没法报警（confidence=insufficient 是唯一线索）。
            String text = dto.answer() == null || dto.answer().isBlank()
                    ? "证据不足：检索到的内容里没有能支撑这个问题的信息。"
                    : dto.answer();
            String note = dto.note() == null || dto.note().isBlank()
                    ? "模型判定现有证据不足以回答，且未给出原因。"
                    : dto.note();
            warnings.add(AnswerResult.WARNING_EVIDENCE_INSUFFICIENT);
            return new AnswerResult(false, text, List.of(), List.of(),
                    AnswerResult.CONFIDENCE_INSUFFICIENT, note, warnings, 0);
        }

        List<FactDto> facts = dto.facts() == null ? List.of() : dto.facts();
        if (facts.size() > MAX_FACTS) {
            facts = List.copyOf(facts.subList(0, MAX_FACTS));
            warnings.add(AnswerResult.WARNING_FACTS_TRUNCATED);
        }
        FactGuard.Result guarded = factGuard.guard(facts, pkg.blocks(), qu);
        warnings.addAll(guarded.warnings());

        // 引用编号 = 模型给的 usedEvidence + 正文里实际写了但漏登记的那些。
        // 只按 usedEvidence 建引用，正文里的编号就会落空：2026-10-30 的线上答案里
        // 模型写了「增长贡献最大[ E5 ][E3]」，usedEvidence 只有 [E1,E2,E3]，
        // 于是 [ E5 ] 既不被识别（括号里有空格）、也没有对应引用 —— 用户看到一段点不开的文本。
        List<Integer> cited = withBodyMarkers(parseEvidence(dto.usedEvidence()),
                dto.answer(), pkg.blocks().size());
        CitationValidator.Result citations = citationValidator.validate(cited, pkg.blocks());
        warnings.addAll(citations.warnings());

        // 把正文里的原始编号（可能跳号，如 [E2][E7][E11]）重排成 1..n，
        // 让「正文角标 = citations 下标 + 1」这条契约（C7）真正成立。
        // 不重排的话，前端点 [E7] 会去找第 7 条引用 —— 数组只有 4 条，点了没反应。
        String answerText = normalizeSkeleton(renumberCitations(dto.answer(), cited, pkg.blocks().size()));

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
        Grounding grounding = ground(answerText, guarded.facts(), citations.citations(), pkg.blocks(),
                factPack.allowedValues());
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

        List<AnswerResult.Fact> outFacts = mergeFacts(factPack, grounding.facts(), warnings);

        return new AnswerResult(true,
                answerText == null ? "" : answerText,
                outFacts,
                citations.citations(),
                confidence,
                mergeNote(dto.note(), guarded.note()),
                List.copyOf(warnings),
                citations.dropped().size());
    }

    // ------------------------------------------------------------ 事实三态合并

    /**
     * 事实三态（C21）：COMPUTED（系统算的）在前，REPORTED（模型照抄证据）在后；
     * 模型一条都没给时才用 EXTRACTED（后端从表格抽的）兜底，并留痕 {@code facts_backfilled}。
     *
     * <p>为什么计算事实排最前：facts 是「这一轮答了哪些数」的对外清单，
     * 用户最想核对的是新算出来的比率 —— 模型抄的原文数字在答案正文里本来就能读到。
     */
    private static List<AnswerResult.Fact> mergeFacts(FactPack pack, List<FactDto> modelFacts,
                                                      List<String> warnings) {
        List<AnswerResult.Fact> computed = new ArrayList<>();
        List<AnswerResult.Fact> extracted = new ArrayList<>();
        for (AnswerResult.Fact f : pack == null ? List.<AnswerResult.Fact>of() : pack.rows()) {
            if (f == null) {
                continue;
            }
            if (AnswerResult.ORIGIN_COMPUTED.equals(f.origin())) {
                computed.add(f);
            } else {
                extracted.add(f);
            }
        }

        List<AnswerResult.Fact> out = new ArrayList<>();
        int computedCount = 0;
        for (AnswerResult.Fact f : computed) {
            // 计算事实只占前面几格：实测一轮能算出 8 条比率，全放进事实卡就把
            // 「营业总收入 = 160.68 亿元」这类原文数值挤掉了（2026-10-30 端到端复验）
            if (out.size() >= MAX_FACTS || computedCount >= MAX_COMPUTED_IN_ANSWER) {
                break;
            }
            computedCount++;
            if (!hasKey(out, f)) {
                out.add(f);
            }
        }

        List<FactDto> model = modelFacts == null ? List.of() : modelFacts;
        if (!model.isEmpty()) {
            for (FactDto f : model) {
                if (out.size() >= MAX_FACTS) {
                    break;
                }
                // 模型会把单位写进 value（"38.46%" 且 unit="%" → 前端显示 "38.46%%"），
                // 这里统一把 value 尾部的单位剥掉，单位只认 unit 字段
                AnswerResult.Fact fact = new AnswerResult.Fact(f.metric(), f.period(),
                        cleanValue(f.value(), f.unit()), f.unit(),
                        f.scope(), f.evidence(), AnswerResult.Fact.SOURCE_PDF,
                        AnswerResult.ORIGIN_REPORTED, null);
                if (!hasKey(out, fact)) {
                    out.add(fact);
                }
            }
            return out;
        }
        if (extracted.isEmpty()) {
            return out;
        }
        // 模型 facts 为空 = 用户看不到任何可核对的清单，用后端抽的事实兜底（origin 标 EXTRACTED，诚实标注来源）
        for (AnswerResult.Fact f : extracted) {
            if (out.size() >= MAX_FACTS) {
                break;
            }
            if (hasKey(out, f)) {
                continue;
            }
            out.add(new AnswerResult.Fact(f.metric(), f.period(), f.value(), f.unit(), f.scope(),
                    f.evidence(), f.source(), AnswerResult.ORIGIN_EXTRACTED, null));
        }
        warnings.add(AnswerResult.WARNING_FACTS_BACKFILLED);
        return out;
    }

    /** 剥掉 value 末尾与 unit 重复的单位（"38.46%" + unit "%" → "38.46"）。 */
    static String cleanValue(String value, String unit) {
        if (value == null) {
            return "";
        }
        String v = value.trim();
        String u = unit == null ? "" : unit.trim();
        if (!u.isEmpty() && v.endsWith(u) && v.length() > u.length()) {
            v = v.substring(0, v.length() - u.length()).trim();
        }
        return v;
    }

    /** 同一「指标 + 期间」只留一条：模型常把同一个数既写进正文、又写进 facts。 */
    private static boolean hasKey(List<AnswerResult.Fact> facts, AnswerResult.Fact f) {
        for (AnswerResult.Fact x : facts) {
            if (java.util.Objects.equals(x.metric(), f.metric())
                    && java.util.Objects.equals(x.period(), f.period())) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------ 引用编号重排

    /**
     * 引用标记。括号里允许空白、允许全角括号 —— 模型确实会写「[ E5 ]」（2026-10-30 线上答案里出现过，
     * 前端 AnswerText.vue 只认「[E5]」，于是它渲染成一段点不开的纯文本：既拿不到引用，也说不清是几号）。
     * 这里先收口，再统一重排成标准的 [En]。
     */
    private static final java.util.regex.Pattern CITE_MARK =
            java.util.regex.Pattern.compile("[\\[［]\\s*E\\s*(\\d+)\\s*[\\]］]");

    /**
     * 事实清单的序号标记（{@code [F63]}）。
     *
     * <p>提示词里每条事实都带一个行首序号 {@code [F1]}，模型会把它当成引用编号写进正文
     * （2026-10-30 实测答案里出现过 {@code [F63][F64]}）。它不是证据号，前端也点不开，
     * 所以正文里一律删掉：留着一个点不开的死编号，比没有编号更糟。
     */
    private static final java.util.regex.Pattern FACT_MARK =
            java.util.regex.Pattern.compile("[\\[［]\\s*F\\s*\\d+\\s*[\\]］]");

    /**
     * 正文编号 → 连续编号。
     *
     * <p>模型引用的是「提示词里的证据号」（E1..E20），而我们只把用到的证据收进 citations，
     * 顺序是首次出现顺序。例如模型写 [E2][E7] 而 citations 只有 2 条时，
     * 必须改写成 [E1][E2]，否则前端点角标找不到对应证据。
     *
     * <p>被剔除的编号（越界 / 编造）在正文里直接删掉：留着等于给用户一个点不开的死链。
     */
    /**
     * 把「正文里写了、但 usedEvidence 里漏了」的编号补进去（按首次出现顺序，越界的不补）。
     *
     * <p>为什么按正文补：一句话后面跟着编号，是模型在说「这句出自第 5 块」。
     * 只信 usedEvidence 等于把这句话的出处扔掉，用户点不到任何东西；
     * 补进来以后它就是一个正常的引用（bbox / 页码仍然从库里取，不信模型）。
     */
    static List<Integer> withBodyMarkers(List<Integer> emitted, String answer, int blockCount) {
        List<Integer> out = new ArrayList<>();
        if (emitted != null) {
            for (Integer no : emitted) {
                if (no != null && !out.contains(no)) {
                    out.add(no);
                }
            }
        }
        if (answer == null || answer.isEmpty()) {
            return out;
        }
        java.util.regex.Matcher m = CITE_MARK.matcher(answer);
        while (m.find()) {
            int no = Integer.parseInt(m.group(1));
            if (no >= 1 && no <= blockCount && !out.contains(no)) {
                out.add(no);
            }
        }
        return out;
    }

    static String renumberCitations(String answer, List<Integer> emitted, int blockCount) {
        if (answer == null || answer.isEmpty()) {
            return answer;
        }
        // 事实序号（[F63]）不是引用，先删掉再重排；否则它会夹在 [E1][E2] 中间显示成死编号
        answer = FACT_MARK.matcher(answer).replaceAll("");
        List<Integer> order = new ArrayList<>();
        if (emitted != null) {
            for (Integer no : emitted) {
                if (no != null && no >= 1 && no <= blockCount && !order.contains(no)) {
                    order.add(no);
                }
            }
        }
        java.util.regex.Matcher m = CITE_MARK.matcher(answer);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            int old = Integer.parseInt(m.group(1));
            int index = order.indexOf(old);
            String replacement = index >= 0 ? "[E" + (index + 1) + "]" : "";
            m.appendReplacement(out, java.util.regex.Matcher.quoteReplacement(replacement));
        }
        m.appendTail(out);
        return out.toString();
    }

    /**
     * 段标签断行：把接在句末的「归因：」「数据：」「口径：」「结论：」提成独立段落。
     *
     * <p>为什么要在代码里做而不是只靠提示词：实测四次同类问题，三次按四段骨架输出、
     * 一次把「归因：」接在结论段句末（模型对排版指令的服从率不是 100%）。
     * 骨架是产品承诺（契约 C20），承诺不能靠运气，所以在后处理里兜一道。
     * 只在这四个标签前断行，不动正文的其它标点。
     */
    static String normalizeSkeleton(String answer) {
        if (answer == null || answer.isEmpty()) {
            return answer;
        }
        String out = answer.replaceAll("([。；;！!？?\\]）])\\s*(数据：|归因：|口径：|结论：)", "$1\n\n$2");
        // 标签后面紧跟表格或小标题时也要断行：实测模型会写成「数据：| 指标 | 2025年 |」一行，
        // 前端解析器只认「行首是竖线」的表格，不断行就整张表退化成一段乱码文本。
        out = out.replaceAll("(数据：|归因：|口径：|结论：)[ \\t]*(?=(?:\\||#{2,4}\\s))", "$1\n\n");
        return out.replaceAll("\n{3,}", "\n\n");
    }

    // ------------------------------------------------------------ 数字落地校验

    /**
     * 报告里出现过的数字形态：千分位（5,560 / 16,067,741,039.51）、四位以上的纯数字、以及带小数点的数。
     *
     * <p>小数点形态是 2026-10-30 补的：原正则只认四位以上整数，于是「同比增长 91.23%」这种
     * 编出来的比率一次都没被查到（< 100 的两位小数全部漏检）。比率恰恰是模型最容易编的数字。
     * 白名单（{@code FactPack.allowedValues}）里的系统计算值不受影响，仍算已核对。
     */
    private static final java.util.regex.Pattern NUMBER =
            java.util.regex.Pattern.compile("\\d{1,3}(?:,\\d{3})+(?:\\.\\d+)?|\\d{4,}(?:\\.\\d+)?|\\d+\\.\\d+");

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
                     List<ContextBlock> blocks, java.util.Set<String> allowedValues) {
        java.util.Set<String> allowed = allowedValues == null ? java.util.Set.of() : allowedValues;
        // 「可以由两个证据数字精确相减得到」的值也算落地：模型经常把「增量」写成两列之差，
        // 那不是编造，是算术。只放开金额级（绝对值 ≥ 1000），比率与期数仍走白名单。
        java.util.Set<String> derived = derivedValues(allowed);
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
                    || text.contains(normalizeNumbers(f.value()))
                    || allowed.contains(normalizeNumbers(f.value()))
                    || derived.contains(normalizeNumbers(f.value()))) {
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
            if (!text.contains(normalizeNumbers(n)) && !allowed.contains(normalizeNumbers(n))
                    && !derived.contains(normalizeNumbers(n))) {
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

    /**
     * 由白名单里的金额两两相减得到的结果集。
     *
     * <p>为什么只做减法不做加总：实测「A−B」是模型最爱自己算的一步（增长额、某业务的增量），
     * 而加总几乎总能对应到证据里的合计行；把加总也放进来，等于给任意凑数开口子。
     * 规模门槛（≥ 1000）用来排除百分比与期数：38.46−21.95 这种差值没有意义。
     */
    static java.util.Set<String> derivedValues(java.util.Set<String> allowed) {
        java.util.List<java.math.BigDecimal> amounts = new ArrayList<>();
        for (String value : allowed) {
            try {
                java.math.BigDecimal v = new java.math.BigDecimal(value.replace(",", ""));
                if (v.abs().compareTo(java.math.BigDecimal.valueOf(1000)) >= 0) {
                    amounts.add(v);
                }
            } catch (NumberFormatException ignored) {
                // 白名单里混进非数字（如「160.68 亿元」）就跳过
            }
        }
        java.util.Set<String> out = new java.util.HashSet<>();
        for (int i = 0; i < amounts.size(); i++) {
            for (int j = i + 1; j < amounts.size(); j++) {
                java.math.BigDecimal diff = amounts.get(i).subtract(amounts.get(j)).abs();
                out.add(diff.stripTrailingZeros().toPlainString());
                out.add(diff.setScale(2, java.math.RoundingMode.HALF_UP).toPlainString());
            }
        }
        return out;
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
