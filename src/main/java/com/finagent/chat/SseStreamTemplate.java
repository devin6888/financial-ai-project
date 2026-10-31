package com.finagent.chat;

import com.finagent.scope.ScopeEmptyException;
import com.finagent.security.CurrentUser;
import com.finagent.security.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * SSE 流的统一外壳（2026-10-30 抽出，此前 ChatController 与 AgentController 各写一遍）。
 *
 * <p>它只管「怎么把一段业务代码包成一条 SSE 流」，不含任何问答逻辑：
 * <ol>
 *   <li><b>租户上下文要带过去</b>：{@code TenantContext} 是 ThreadLocal，业务跑在另一个线程，
 *       不重建的话 RLS 会 fail closed 返回 0 行，表现为「流式回答永远说没有证据」；</li>
 *   <li><b>开流之后的错误只能发 error 事件</b>：HTTP 头已经发出去了，改不了状态码；</li>
 *   <li><b>心跳不能省</b>：检索 + 生成可能十几秒没有新事件，中间层会当成空闲连接掐掉。</li>
 * </ol>
 *
 * <p>端点专属的异常（例如 agent 的 {@code AgentRunNotFoundException} 要映射成
 * {@code AGENT_RUN_NOT_FOUND}）由调用方在 {@link StreamBody} 里自己 catch，这里不做映射表：
 * 一张「异常类型 → 错误码」的表会把两个端点的差异藏起来，反而更难读。
 */
@Component
public class SseStreamTemplate {

    private static final Logger log = LoggerFactory.getLogger(SseStreamTemplate.class);

    /** 一段跑在流线程里的业务代码：拿 emitter 往里发事件即可。 */
    @FunctionalInterface
    public interface StreamBody {
        void run(SseEmitter emitter);
    }

    private final ExecutorService executor;
    private final ScheduledExecutorService pinger;
    private final StreamProperties properties;

    public SseStreamTemplate(@Qualifier("chatStreamExecutor") ExecutorService executor,
                             @Qualifier("chatStreamPinger") ScheduledExecutorService pinger,
                             StreamProperties properties) {
        this.executor = executor;
        this.pinger = pinger;
        this.properties = properties;
    }

    /**
     * 开一条流：建 emitter → 起心跳 → 换线程跑 {@code body} → 收尾。
     *
     * <p>{@link TenantContext#require()} 在读认证信息时就要失败（还没有 emitter，能回 401），
     * 所以放在建 emitter 之前。
     */
    public SseEmitter start(StreamBody body) {
        CurrentUser user = TenantContext.require();
        SseEmitter emitter = new SseEmitter(properties.timeoutMs());
        ScheduledFuture<?> ping = startPing(emitter);

        emitter.onCompletion(() -> ping.cancel(false));
        emitter.onTimeout(() -> {
            ping.cancel(false);
            emitter.complete();
        });
        emitter.onError(e -> ping.cancel(false));

        executor.execute(() -> {
            TenantContext.set(user);
            try {
                body.run(emitter);
                emitter.complete();
            } catch (IllegalArgumentException e) {
                send(emitter, ChatStreamEvent.error("BAD_REQUEST", e.getMessage()));
                emitter.complete();
            } catch (ScopeEmptyException e) {
                send(emitter, ChatStreamEvent.error("SCOPE_EMPTY", e.getMessage()));
                emitter.complete();
            } catch (RuntimeException e) {
                log.error("流式执行失败", e);
                send(emitter, ChatStreamEvent.error("INTERNAL", "服务器处理失败，请重试"));
                emitter.complete();
            } finally {
                ping.cancel(false);
                TenantContext.clear();
            }
        });
        return emitter;
    }

    /** 发一个事件；连接已断不是错误（答案已落库，前端重进会话即可看到）。 */
    public static void send(SseEmitter emitter, ChatStreamEvent event) {
        try {
            emitter.send(SseEmitter.event().name(event.event())
                    .data(event.data(), MediaType.APPLICATION_JSON));
        } catch (IOException | IllegalStateException e) {
            log.debug("流式事件发送失败（连接已断）：{}", event.event());
        }
    }

    /** 每 {@code StreamProperties#pingMs()} 发一行注释，防中间层把空闲连接当死连接。 */
    private ScheduledFuture<?> startPing(SseEmitter emitter) {
        long interval = Math.max(1000L, properties.pingMs());
        return pinger.scheduleAtFixedRate(() -> {
            try {
                emitter.send(SseEmitter.event().comment("ping"));
            } catch (IOException | IllegalStateException e) {
                // 客户端已经走了：不刷日志，等 onCompletion / onError 收尾
            }
        }, interval, interval, TimeUnit.MILLISECONDS);
    }
}
