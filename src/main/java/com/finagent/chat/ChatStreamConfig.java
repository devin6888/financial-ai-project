package com.finagent.chat;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 流式问答的执行器。
 *
 * <p>两个线程池都是<b>必须</b>的：
 * <ul>
 *   <li>生成池：{@link } 要求 controller 立刻返回，模型调用必须挪到别的线程；</li>
 *   <li>心跳池：单线程即可，只用来每 15 秒发一行注释，防中间层把空闲连接掐掉。</li>
 * </ul>
 *
 * <p>用虚拟线程而不是固定大小线程池：一次演示可能有 3~5 条流同时开，
 * 而每条流 99% 的时间在等模型 —— 平台线程池要么开太大浪费，要么开太小排队。
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(StreamProperties.class)
public class ChatStreamConfig {

    @Bean(destroyMethod = "close")
    public ExecutorService chatStreamExecutor() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }

    @Bean(destroyMethod = "shutdownNow")
    public ScheduledExecutorService chatStreamPinger() {
        AtomicInteger seq = new AtomicInteger();
        return Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "chat-stream-ping-" + seq.incrementAndGet());
            t.setDaemon(true);
            return t;
        });
    }
}
