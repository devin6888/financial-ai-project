package com.finagent.document;

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

    /** 实现名，便于日志与降级判断。 */
    String name();
}
