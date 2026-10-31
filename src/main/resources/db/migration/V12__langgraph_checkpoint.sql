-- V12 · LangGraph4j 检查点表（阶段 11）
--
-- 为什么是 V12：V11 已被 V11__login_and_session.sql（阶段 10 的账号登录）占用，见 ST11 §12.5。
--
-- 来源：langgraph4j-postgres-saver-1.9.7.jar!db/migration/v1.1__init.sql —— 原文照抄，未增删任何列。
-- 为什么由 Flyway 建而不让库自己建（createTables(true)）：见 ST11 §12.1，守「schema 只由 Flyway 管」这条硬规则。
-- 运行时传 createTables(false)；升级 LangGraph4j 时把新 jar 的 db/migration/*.sql 抽出来与本文件 diff。
--
-- 注意：默认实现 PostgresSaver 读的是 v1.1 这套 SQL（javap 里 sqlInitResourcePath = db/migration/v1.1__init.sql）；
-- PostgresSaverV2 才是 v2.1（thread_id BIGINT IDENTITY + LG4JThreadTag）。如果将来换 V2，要另加一个迁移并迁移数据。
--
-- 这两张表不含 tenant_id，也不加 RLS：它们是基础设施表（与 flyway_schema_history 同类），
-- 只由应用侧 owner 连接读写（ST11 D5），租户隔离靠 agent_run.run_id = thread_name（UUID，不可猜）+ 应用层归属校验。

CREATE TABLE IF NOT EXISTS LG4JThread (
    thread_id   UUID PRIMARY KEY,
    thread_name VARCHAR(255),
    is_released BOOLEAN DEFAULT FALSE NOT NULL
);

CREATE TABLE IF NOT EXISTS LG4JCheckpoint (
    checkpoint_id        UUID PRIMARY KEY,
    parent_checkpoint_id UUID,
    thread_id            UUID NOT NULL,
    node_id              VARCHAR(255),
    next_node_id         VARCHAR(255),
    state_data           TEXT NOT NULL,
    state_content_type   VARCHAR(100) NOT NULL,
    saved_at             TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_thread FOREIGN KEY (thread_id)
        REFERENCES LG4JThread (thread_id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_lg4jcheckpoint_thread_id
    ON LG4JCheckpoint (thread_id);
CREATE INDEX IF NOT EXISTS idx_lg4jcheckpoint_thread_id_saved_at_desc
    ON LG4JCheckpoint (thread_id, saved_at DESC);
CREATE UNIQUE INDEX IF NOT EXISTS idx_unique_lg4jthread_thread_name_unreleased
    ON LG4JThread (thread_name) WHERE is_released = FALSE;

COMMENT ON TABLE LG4JThread IS
    $$LangGraph4j 的运行线程（一个 thread_name = 一次 Agent 运行；由库写入，我们只读）$$;
COMMENT ON COLUMN LG4JThread.thread_id IS
    $$库生成的 UUID 主键（v1.1 语义；v2.1 起改成 BIGINT IDENTITY）$$;
COMMENT ON COLUMN LG4JThread.thread_name IS
    $$我们传进去的 threadId，约定等于 agent_run.run_id$$;
COMMENT ON COLUMN LG4JThread.is_released IS
    $$是否已释放；唯一索引只约束未释放的行，所以同一个 thread_name 可以跑多轮、每轮一行$$;

COMMENT ON TABLE LG4JCheckpoint IS
    $$LangGraph4j 的检查点（每走完一个节点存一行；恢复时按 saved_at 倒序取最新）$$;
COMMENT ON COLUMN LG4JCheckpoint.checkpoint_id IS
    $$检查点主键（UUID，由库生成）$$;
COMMENT ON COLUMN LG4JCheckpoint.parent_checkpoint_id IS
    $$上一个检查点（形成链；恢复时用于判断是否同一分支）$$;
COMMENT ON COLUMN LG4JCheckpoint.thread_id IS
    $$指向 LG4JThread.thread_id$$;
COMMENT ON COLUMN LG4JCheckpoint.node_id IS
    $$刚跑完的节点 id$$;
COMMENT ON COLUMN LG4JCheckpoint.next_node_id IS
    $$下一个要跑的节点 id；中断时它就是「停在哪」（StateSnapshot.nextNodeId）$$;
COMMENT ON COLUMN LG4JCheckpoint.state_data IS
    $$序列化后的状态（默认 JDK 序列化字节，Base64 后以 TEXT 存；v2.1 起改 JSONB）$$;
COMMENT ON COLUMN LG4JCheckpoint.state_content_type IS
    $$序列化器标识（默认的 ObjectStreamStateSerializer 写的是 Java 序列化的 MIME）$$;
COMMENT ON COLUMN LG4JCheckpoint.saved_at IS
    $$写入时间；恢复取最新一行$$;