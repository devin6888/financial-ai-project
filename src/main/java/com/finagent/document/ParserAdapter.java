package com.finagent.document;

import java.util.function.Consumer;

/**
 * 文档解析器抽象（设计文档 §8.3）。
 *
 * <p>把「PDF -> NormalizedDocument」这一步隔离出来，使 Docling 可被替换，
 * 也让业务层在测试中可以注入假实现，不必真的跑解析服务。
 */
public interface ParserAdapter {

    /**
     * 提交解析作业，返回 job_id。
     *
     * <p><b>调用方必须把 jobId 落库</b>（document.parse_job_id）—— 否则客户端在轮询期间
     * 断开时，作业虽然还在服务端跑，恢复流程却无从知道该问哪个作业，只能判定为"从未提交"。
     * 这个状态是「durable」的支点。
     */
    String submit(byte[] pdf, String sourceName, Integer pageStart, Integer pageEnd);

    /**
     * 按已提交的作业号取回结果 —— 供应用重启后的恢复流程使用。
     *
     * <p>作业仍在运行时会等待其完成；作业已消失（解析服务重启）时抛异常。
     */
    ParsedDocument fetchResult(String jobId);

    /**
     * 同上，但每次轮询回调一次作业快照 —— 供执行记录写心跳（阶段 19）。
     *
     * <p>为什么用回调而不是让调用方自己轮询：轮询节奏（{@code poll-interval-seconds}）属于适配器，
     * 调用方再实现一遍就会有两套节奏；而「什么时候算一次心跳」应该由业务层决定（30 秒节流）。
     *
     * <p>默认实现直接调用 {@link #fetchResult(String)}：假适配器与不支持轮询的实现不必改。
     */
    default ParsedDocument fetchResult(String jobId, Consumer<ParseJobStatus> onPoll) {
        return fetchResult(jobId);
    }

    /** 实现名，便于日志与降级判断。 */
    String name();

    /**
     * 解析作业的轮询快照。
     *
     * @param status         queued / running / done / failed
     * @param elapsedSeconds 服务端已跑秒数（不是我方计时，避免两端时钟不一致）
     */
    record ParseJobStatus(String status, double elapsedSeconds) {
    }
}
