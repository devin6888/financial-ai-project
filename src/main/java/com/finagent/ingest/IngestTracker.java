package com.finagent.ingest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 入库执行记录的埋点入口（阶段 19）。
 *
 * <p>三条设计约束：
 * <ul>
 *   <li><b>按 documentId 定位</b>，不把 runId 一路透传。入库链路横跨 HTTP、MQ、CLI 三种入口，
 *       多一个参数就要改一堆签名；而「一个文档同时只有一个 RUNNING run」是业务事实，查一次即可。</li>
 *   <li><b>永不抛异常</b>：埋点是旁路，写流水失败最多少一条记录，不能让一次 190 秒的解析白跑。
 *       所有方法内部 catch RuntimeException 只记 warn。</li>
 *   <li><b>心跳节流</b>在 {@link #heartbeat} 里做（默认 30 秒），因为解析器每 10 秒回调一次，
 *       直接写库等于 3 倍无效写。</li>
 * </ul>
 */
@Service
public class IngestTracker {

    private static final Logger log = LoggerFactory.getLogger(IngestTracker.class);

    /** 心跳最小间隔：解析器 10 秒轮询一次，这里压到 30 秒一次写库。 */
    static final Duration HEARTBEAT_GAP = Duration.ofSeconds(30);

    /**
     * 「刚失败」的时间窗：窗口内的失败被当作同一次入库的**内部重试**（MQ 重投、消费重试），
     * 复用原 run；超出窗口的失败再进来（用户隔了半天点重试、进程重启后恢复）才算新一次尝试。
     */
    static final Duration RETRY_GRACE = Duration.ofMinutes(5);

    /** detail / error 都是给人看的短句，超长截断避免把整段异常栈塞进表格。 */
    private static final int DETAIL_MAX = 300;

    private final IngestRunRepository runs;
    private final ObjectMapper json;

    /** documentId → 上次写心跳的时刻；进程内即可，重启后最多多写一次。 */
    private final Map<Long, Instant> lastHeartbeat = new ConcurrentHashMap<>();

    public IngestTracker(IngestRunRepository runs, ObjectMapper json) {
        this.runs = runs;
        this.json = json;
    }

    /**
     * 开一次新的入库记录（新 attempt）。
     *
     * @return runId；失败或文档不存在返回 -1（调用方不需要判空，后续埋点会自己忽略）
     */
    public long start(long documentId, IngestFlow flow) {
        try {
            // 上一轮若还挂在 RUNNING（进程被杀、MQ 丢消息），先收成 FAILED：
            // 否则会出现两条 RUNNING，接口按 attempt 取到新的那条，老的那条永远「进行中」
            runs.findRunning(documentId).ifPresent(stale ->
                    runs.fail(stale.id(), "被新的入库尝试取代（第 " + (stale.attempt() + 1) + " 次）"));
            long runId = runs.start(documentId, flow);
            if (runId > 0) {
                lastHeartbeat.remove(documentId);
            }
            return runId;
        } catch (RuntimeException e) {
            log.warn("开入库记录失败 documentId={} flow={}：{}", documentId, flow, e.toString());
            return -1L;
        }
    }

    /**
     * 有在跑的 run 就复用；刚失败（{@link #RETRY_GRACE} 内）的 run 也复用；都没有才开新的。
     *
     * <p>MQ worker 与断点恢复都会走到这里。为什么不一律新开：
     * <ul>
     *   <li>上传接口可能已经开了 run（RECEIVED 已写），再开一条会让页面闪一下「第 1 次」变「第 2 次」；</li>
     *   <li>Kafka 重投是**同一次入库的内部重试**，每投一次加一个 attempt，用户点一次上传会看到「第 4 次」。
     *       复用 + {@link IngestRunRepository#beginStep} 的 retry 计数，才能把「重试了几回」记在同一条流水里。</li>
     * </ul>
     */
    public void startOrResume(long documentId, IngestFlow flow) {
        try {
            if (runs.findRunning(documentId).isPresent()) {
                return;
            }
            Optional<IngestRun> latest = runs.findLatest(documentId);
            if (latest.isPresent() && recentlyFailed(latest.get())) {
                runs.reopen(latest.get().id());
                lastHeartbeat.remove(documentId);
                log.debug("复用刚失败的入库记录 documentId={} runId={}", documentId, latest.get().id());
                return;
            }
            start(documentId, flow);
        } catch (RuntimeException e) {
            log.warn("开/复用入库记录失败 documentId={}：{}", documentId, e.toString());
        }
    }

    /** 失败且发生在 {@link #RETRY_GRACE} 之内 —— 视为同一次入库的内部重试。 */
    static boolean recentlyFailed(IngestRun run) {
        if (!"FAILED".equals(run.status()) || run.finishedAt() == null) {
            return false;
        }
        return run.finishedAt().isAfter(OffsetDateTime.now().minus(RETRY_GRACE));
    }

    /** 进入下一步：自动收尾上一步。 */
    public void begin(long documentId, IngestStep step) {
        try {
            IngestRun run = running(documentId);
            if (run == null) {
                return;
            }
            int seq = run.flowEnum().seqOf(step);
            if (seq <= 0) {
                log.debug("步骤 {} 不属于流程 {}，忽略", step, run.flow());
                return;
            }
            runs.beginStep(run.id(), seq, step);
        } catch (RuntimeException e) {
            log.warn("推进入库步骤失败 documentId={} step={}：{}", documentId, step, e.toString());
        }
    }

    /** 收尾当前步骤并写附注（例如 job=xxx、12 页 · docling 2.138.0）。 */
    public void finish(long documentId, IngestStep step, String detail) {
        try {
            IngestRun run = running(documentId);
            if (run == null) {
                return;
            }
            runs.finishStep(run.id(), step, clip(detail));
        } catch (RuntimeException e) {
            log.warn("收尾入库步骤失败 documentId={} step={}：{}", documentId, step, e.toString());
        }
    }

    /** 心跳（30 秒节流）：只更新当前步骤的 detail。 */
    public void heartbeat(long documentId, String detail) {
        Instant now = Instant.now();
        Instant last = lastHeartbeat.get(documentId);
        if (last != null && Duration.between(last, now).compareTo(HEARTBEAT_GAP) < 0) {
            return;
        }
        heartbeatNow(documentId, detail);
    }

    /** 忽略节流的心跳：切换阶段、进入下个批次时用，保证界面立刻能看到新文案。 */
    public void heartbeatNow(long documentId, String detail) {
        try {
            IngestRun run = running(documentId);
            if (run == null) {
                return;
            }
            runs.heartbeat(run.id(), clip(detail));
            lastHeartbeat.put(documentId, Instant.now());
        } catch (RuntimeException e) {
            log.warn("写入库心跳失败 documentId={}：{}", documentId, e.toString());
        }
    }

    /** 成功：摘要写 summary_json（块数、版本号）；耗时由接口按 steps 现算，不冗余存。 */
    public void succeed(long documentId, Map<String, Object> summary) {
        try {
            IngestRun run = running(documentId);
            if (run == null) {
                return;
            }
            runs.succeed(run.id(), json.writeValueAsString(summary == null ? Map.of() : summary));
            lastHeartbeat.remove(documentId);
        } catch (RuntimeException e) {
            log.warn("收尾入库记录失败 documentId={}：{}", documentId, e.toString());
        }
    }

    /** 失败：当前步标 FAILED，整条 run 标 FAILED。error 同时进 run.error 与那一步的 detail。 */
    public void fail(long documentId, String error) {
        try {
            IngestRun run = running(documentId);
            if (run == null) {
                return;
            }
            runs.fail(run.id(), clip(error));
            lastHeartbeat.remove(documentId);
        } catch (RuntimeException e) {
            log.warn("标记入库失败记录失败 documentId={}：{}", documentId, e.toString());
        }
    }

    /** 供接口拼摘要：run 的 flow / 状态 / 步数。 */
    public Optional<IngestRun> latest(long documentId) {
        try {
            return runs.findLatest(documentId);
        } catch (RuntimeException e) {
            log.warn("读入库记录失败 documentId={}：{}", documentId, e.toString());
            return Optional.empty();
        }
    }

    /** 供接口取步骤列表；null runId 安全返回空表。 */
    public java.util.List<IngestStepRow> steps(long runId) {
        try {
            return runs.steps(runId);
        } catch (RuntimeException e) {
            log.warn("读入库步骤失败 runId={}：{}", runId, e.toString());
            return java.util.List.of();
        }
    }

    private IngestRun running(long documentId) {
        return runs.findRunning(documentId).orElse(null);
    }

    private static String clip(String text) {
        if (text == null) {
            return null;
        }
        String one = text.strip();
        return one.length() <= DETAIL_MAX ? one : one.substring(0, DETAIL_MAX) + "…";
    }

    /** 测试用：清掉节流状态，避免用例之间互相影响。 */
    void resetThrottle(long documentId) {
        lastHeartbeat.remove(documentId);
    }

    /** 组装摘要的小工具：Map.of 不允许 null 值，而「新嵌 0 / 复用 n」是常见情况。 */
    public static Map<String, Object> summary(Object... kv) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            if (kv[i + 1] != null) {
                map.put(String.valueOf(kv[i]), kv[i + 1]);
            }
        }
        return map;
    }
}
