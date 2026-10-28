package com.finagent.memory;

import com.finagent.embedding.EmbeddingService;
import com.finagent.security.CurrentUser;
import com.finagent.security.TenantContext;
import com.finagent.chunk.TokenEstimator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 长期记忆召回（设计 §11.4 的六步）。
 *
 * <p>调用顺序是<b>先便宜后贵</b>：
 * <ol>
 *   <li>规则命中（问题里点名了「格式 / 语言 / 出处 / 关注」→ 直接按 key 取，不调 embedding）；</li>
 *   <li>查候选行（一条 SQL）；候选为空就直接结束 —— <b>一条记忆都没有时也不该调 embedding</b>；</li>
 *   <li>向量召回（一次 embedding + 一次带 «<=>» 的 SQL）；</li>
 *   <li>阈值 + 按 key 去重 + importance 排序 + 取前 N；</li>
 *   <li>最后记访问（失败只记日志）。</li>
 * </ol>
 *
 * <p>阈值方向是最容易写反的一处：pgvector 的 «<=>» 返回<b>距离</b>，0 表示最相似，
 * 所以「相似度 ≥ 0.6」要写成「距离 ≤ 0.4」。
 * @author 67564
 */
@Component
public class LongTermMemoryRecaller {

    private static final Logger log = LoggerFactory.getLogger(LongTermMemoryRecaller.class);

    /** 问题里出现这些词 → 认为它点名了某个 key（规则命中，不调 embedding）。 */
    private static final Map<String, List<String>> KEY_HINTS = Map.ofEntries(
            Map.entry("格式", List.of("answer.style", "answer.format")),
            Map.entry("风格", List.of("answer.style")),
            Map.entry("结论", List.of("answer.style")),
            Map.entry("语言", List.of("answer.language")),
            Map.entry("中文", List.of("answer.language")),
            Map.entry("英文", List.of("answer.language")),
            Map.entry("出处", List.of("analysis.must_cite")),
            Map.entry("引用", List.of("analysis.must_cite")),
            Map.entry("来源", List.of("analysis.must_cite")),
            Map.entry("关注", List.of("focus.company.")),
            Map.entry("盯着", List.of("focus.company.")));

    private final LongTermMemoryRepository repository;
    private final EmbeddingService embedding;
    private final MemoryProperties props;

    public LongTermMemoryRecaller(LongTermMemoryRepository repository, EmbeddingService embedding,
                                  MemoryProperties props) {
        this.repository = repository;
        this.embedding = embedding;
        this.props = props;
    }

    /** 召回最多 «longTermMaxItems» 条；任何异常都不该让这一轮问答失败。 */
    public List<LongTermMemory> recall(String question) {
        if (!props.enabled() || TenantContext.optional().isEmpty()) {
            return List.of();
        }
        CurrentUser user = TenantContext.require();
        List<LongTermMemory> candidates = repository.listCandidates(user.tenantId(), user.userId(), 100);
        if (candidates.isEmpty()) {
            return List.of();
        }
        Map<Long, Double> scoreById = new LinkedHashMap<>();
        List<LongTermMemory> hits = new ArrayList<>();

        // 1) 规则命中：问题点名了某类 key
        Set<String> mentioned = keyPrefixes(question);
        for (LongTermMemory memory : candidates) {
            if (matchesAnyPrefix(memory.memoryKey(), mentioned)) {
                hits.add(memory);
                scoreById.put(memory.id(), 1.0);
            }
        }

        // 2) 向量补充：只有规则命中不够数、且库里有记忆时才花一次 embedding
        if (hits.size() < props.longTermMaxItems()) {
            float[] vector = embedQuestion(question);
            if (vector != null) {
                double maxDistance = 1.0 - props.longTermMinScore();
                for (LongTermMemoryRepository.VectorHit hit : repository.searchByVector(
                        user.tenantId(), user.userId(), vector, maxDistance, props.longTermCandidates())) {
                    if (!scoreById.containsKey(hit.memory().id())) {
                        hits.add(hit.memory());
                        scoreById.put(hit.memory().id(), 1.0 - hit.distance());
                    }
                }
            }
        }
        if (hits.isEmpty()) {
            return List.of();
        }

        // 3) 去重（同 key 只留分数最高）+ 排序（importance → 分数 → id）+ 截断
        Map<String, LongTermMemory> byKey = new LinkedHashMap<>();
        for (LongTermMemory memory : hits) {
            LongTermMemory existing = byKey.get(memory.memoryKey());
            if (existing == null || scoreById.get(memory.id()) > scoreById.get(existing.id())) {
                byKey.put(memory.memoryKey(), memory);
            }
        }
        List<LongTermMemory> ordered = new ArrayList<>(byKey.values());
        ordered.sort(Comparator.comparingInt(LongTermMemory::importance).reversed()
                .thenComparing(Comparator.comparingDouble(
                        (LongTermMemory m) -> scoreById.getOrDefault(m.id(), 0.0)).reversed())
                .thenComparing(Comparator.comparingLong(LongTermMemory::id).reversed()));
        List<LongTermMemory> top = ordered.size() <= props.longTermMaxItems()
                ? ordered : ordered.subList(0, props.longTermMaxItems());

        touch(user, top);
        return List.copyOf(top);
    }

    /** 把召回结果渲染成进 Prompt 的短句（带类型与时间，便于模型区分「规则」与「情境」）。 */
    public static String renderLine(LongTermMemory memory) {
        String content = memory.content() == null ? "" : memory.content().strip();
        return content + "（" + label(memory.memoryType()) + "）";
    }

    static String label(String memoryType) {
        return switch (memoryType) {
            case LongTermMemory.TYPE_WATCHLIST -> "长期关注";
            case LongTermMemory.TYPE_CONTEXTUAL -> "情境";
            default -> "你的明确要求";
        };
    }

    private void touch(CurrentUser user, List<LongTermMemory> items) {
        for (LongTermMemory memory : items) {
            try {
                repository.touchAccessed(user.tenantId(), memory.id());
            } catch (RuntimeException e) {
                log.debug("记忆访问计数更新失败（忽略）：id={} {}", memory.id(), e.toString());
            }
        }
    }

    private float[] embedQuestion(String question) {
        try {
            List<float[]> vectors = embedding.embedAll(List.of(question));
            return vectors.isEmpty() ? null : vectors.get(0);
        } catch (RuntimeException e) {
            log.warn("记忆召回向量化失败，本轮只靠规则命中：{}", e.toString());
            return null;
        }
    }

    private static Set<String> keyPrefixes(String question) {
        Set<String> prefixes = new LinkedHashSet<>();
        if (question == null) {
            return prefixes;
        }
        for (Map.Entry<String, List<String>> entry : KEY_HINTS.entrySet()) {
            if (question.contains(entry.getKey())) {
                prefixes.addAll(entry.getValue());
            }
        }
        return prefixes;
    }

    private static boolean matchesAnyPrefix(String memoryKey, Set<String> prefixes) {
        for (String prefix : prefixes) {
            if (memoryKey != null && memoryKey.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }
}
