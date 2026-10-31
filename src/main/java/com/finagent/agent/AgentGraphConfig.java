package com.finagent.agent;

import org.bsc.langgraph4j.checkpoint.BaseCheckpointSaver;
import org.bsc.langgraph4j.checkpoint.PostgresSaver;
import org.bsc.langgraph4j.serializer.std.ObjectStreamStateSerializer;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.jdbc.autoconfigure.DataSourceProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;

/**
 * Agent 图的装配。
 *
 * <p>检查点用 {@link PostgresSaver}（表由 Flyway 的 V12 建，{@code createTables(false)}）：
 * 存内存的话「进程重启 = 所有人批任务失联」。
 *
 * <p>它用一个<b>专属的 owner 连接</b>，不走 {@code TenantAwareDataSource}：主池借连接时会
 * {@code SET ROLE finagent_app}，而 LG4J 表没有授予运行时角色（也不该授：那两张表没有 tenant_id、
 * 没有 RLS）。
 *
 * <p>刻意用不池化的 {@link DriverManagerDataSource}：检查点每次写一行、开一个连接就关，
 * 8 个节点约 80 ms；若每建一个 Spring 上下文就多一个 Hikari 池（tests 有 7 种上下文），
 * PostgreSQL 的 100 个连接配额会被打满（实测报 FATAL: sorry, too many clients already）。
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AgentProperties.class)
public class AgentGraphConfig {

    @Bean
    DataSource agentCheckpointDataSource(@Qualifier("dataSourceProperties") DataSourceProperties props) {
        DriverManagerDataSource ds = new DriverManagerDataSource();
        ds.setUrl(props.getUrl());
        ds.setUsername(props.getUsername());
        ds.setPassword(props.getPassword());
        return ds;
    }

    @Bean
    BaseCheckpointSaver agentCheckpointSaver(
            @Qualifier("agentCheckpointDataSource") DataSource dataSource) throws Exception {
        PostgresSaver.Builder builder = PostgresSaver.builder();
        builder.datasource(dataSource);
        // 与 StateGraph 默认的 JDK 序列化一致（不显式给会抛 IllegalArgumentException: no stateSerializer provided）
        builder.stateSerializer(new ObjectStreamStateSerializer<>(AnalysisState::new));
        builder.createTables(false);   // V12 已建，迁移只由 Flyway 管（D4）
        return builder.build();
    }

    @Bean
    AnalysisGraph analysisGraph(AnalysisNodes nodes, BaseCheckpointSaver saver, AgentProperties props) {
        return new AnalysisGraph(nodes, saver, props.recursionLimit());
    }
}
