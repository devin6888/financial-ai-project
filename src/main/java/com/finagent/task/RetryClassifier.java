package com.finagent.task;

import com.qcloud.cos.exception.CosClientException;
import com.qcloud.cos.exception.CosServiceException;
import tools.jackson.core.JacksonException;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.util.List;

/**
 * 消息处理异常的三分类（阶段 7，设计文档 §13.1「三分类错误」）。
 *
 * <p>三个桶的语义：
 * <ul>
 *   <li>{@link ErrorClass#TRANSIENT} —— 瞬时错误（网络抖动、COS 服务端瞬时故障），
 *       重试可能恢复，交给 {@code DefaultErrorHandler} 有限次重试；</li>
 *   <li>{@link ErrorClass#BUSINESS} —— 业务不可恢复（对象键缺失、文档状态不对等），
 *       重试无意义，直接进 DLQ 等人工介入；</li>
 *   <li>{@link ErrorClass#DATA} —— 数据本身坏（消息体反序列化失败），重试永远失败，
 *       直接进 DLQ 且必须人工修正。</li>
 * </ul>
 *
 * <p>关键点：分类必须沿 {@link Throwable#getCause()} 链往上找，不能只看顶层类型。
 * 因为 {@code CosObjectStorage.get()} 会把 COS SDK 的瞬时异常（{@link SocketTimeoutException}、
 * {@link CosServiceException}…）包成 {@link java.util.NoSuchElementException}，
 * 只判顶层就会把「网络超时」误判成「对象不存在」这种业务错误。
 *
 * <p>本类是纯函数（无状态），{@link #transientTypes()} 暴露瞬时白名单，
 * 供 {@link KafkaErrorHandlerConfig} 复用 —— 两处共享同一份白名单，避免漂移。
 */
public final class RetryClassifier {

    /** 瞬时错误白名单：重试可能恢复的异常类型（按 cause 链匹配）。 */
    private static final List<Class<? extends Exception>> TRANSIENT_TYPES = List.of(
            SocketTimeoutException.class,
            ConnectException.class,
            CosServiceException.class,
            CosClientException.class);

    private RetryClassifier() {
    }

    /** 瞬时错误白名单（供错误处理器配置复用，保证与分类逻辑同源）。 */
    public static List<Class<? extends Exception>> transientTypes() {
        return TRANSIENT_TYPES;
    }

    /** 沿 cause 链把异常分到 TRANSIENT / BUSINESS / DATA 三桶之一。 */
    public static ErrorClass classify(Throwable t) {
        if (contains(t, JacksonException.class)) {
            return ErrorClass.DATA;
        }
        for (Class<? extends Exception> type : TRANSIENT_TYPES) {
            if (contains(t, type)) {
                return ErrorClass.TRANSIENT;
            }
        }
        return ErrorClass.BUSINESS;
    }

    /** 异常本身或其 cause 链上任一节点是否为指定类型。 */
    private static boolean contains(Throwable t, Class<? extends Throwable> type) {
        for (Throwable c = t; c != null; c = c.getCause()) {
            if (type.isInstance(c)) {
                return true;
            }
        }
        return false;
    }

    /** 三分类结果。 */
    public enum ErrorClass {
        /** 瞬时错误：重试可能恢复。 */
        TRANSIENT,
        /** 业务不可恢复：重试无意义。 */
        BUSINESS,
        /** 数据本身坏：重试永远失败。 */
        DATA
    }
}
