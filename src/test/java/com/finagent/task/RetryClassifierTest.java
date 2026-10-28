package com.finagent.task;

import com.qcloud.cos.exception.CosClientException;
import com.qcloud.cos.exception.CosServiceException;
import org.junit.jupiter.api.Test;
import tools.jackson.core.JacksonException;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.util.NoSuchElementException;

import static com.finagent.task.RetryClassifier.ErrorClass.BUSINESS;
import static com.finagent.task.RetryClassifier.ErrorClass.DATA;
import static com.finagent.task.RetryClassifier.ErrorClass.TRANSIENT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * 三分类纯函数测试：重点验证「沿 cause 链」分类 —— 顶层是 NoSuchElementException、
 * 但 cause 是 SocketTimeoutException 时必须判成 TRANSIENT（因为 CosObjectStorage.get
 * 会把 COS SDK 的瞬时异常包成 NoSuchElementException）。
 */
class RetryClassifierTest {

    @Test
    void socketTimeoutIsTransient() {
        assertThat(RetryClassifier.classify(new SocketTimeoutException("read timed out")))
                .isEqualTo(TRANSIENT);
    }

    @Test
    void cosServiceAndClientExceptionsAreTransient() {
        assertThat(RetryClassifier.classify(new CosServiceException("Service Unavailable")))
                .isEqualTo(TRANSIENT);
        assertThat(RetryClassifier.classify(new CosClientException("connection reset")))
                .isEqualTo(TRANSIENT);
    }

    @Test
    void transientCauseWrappedInNoSuchElementIsStillTransient() {
        NoSuchElementException wrapped = new NoSuchElementException(
                "对象不存在或读取失败: dev/parsed/x",
                new SocketTimeoutException("read timed out"));
        assertThat(RetryClassifier.classify(wrapped)).isEqualTo(TRANSIENT);
    }

    @Test
    void jacksonExceptionIsData() {
        assertThat(RetryClassifier.classify(mock(JacksonException.class))).isEqualTo(DATA);
    }

    @Test
    void jacksonCauseWrappedInIllegalStateIsData() {
        IllegalStateException wrapped = new IllegalStateException(
                "反序列化 document.parse 消息失败", mock(JacksonException.class));
        assertThat(RetryClassifier.classify(wrapped)).isEqualTo(DATA);
    }

    @Test
    void plainBusinessExceptionIsBusiness() {
        assertThat(RetryClassifier.classify(new IllegalStateException("文档没有 PDF 对象键")))
                .isEqualTo(BUSINESS);
        assertThat(RetryClassifier.classify(new IllegalArgumentException("参数错误")))
                .isEqualTo(BUSINESS);
    }

    @Test
    void nullCauseChainSafe() {
        assertThat(RetryClassifier.classify(new RuntimeException())).isEqualTo(BUSINESS);
    }

    @Test
    void transientTypesExposesTheWhitelist() {
        assertThat(RetryClassifier.transientTypes()).containsExactly(
                SocketTimeoutException.class,
                ConnectException.class,
                CosServiceException.class,
                CosClientException.class);
    }
}
