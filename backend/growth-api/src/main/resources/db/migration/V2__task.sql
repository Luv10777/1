-- ============================================================
-- V2 · 任务表
--
-- 这张表是 api 和 worker 之间唯一的协作点。两者不互相调接口。
--   api    : INSERT 一行，立刻返回
--   worker : SELECT ... FOR UPDATE SKIP LOCKED 抢一行
--
-- 因为抢占靠的是数据库行锁，worker 想起几个起几个，不会重复消费，
-- 也不需要 RabbitMQ、不需要 ShedLock 这类分布式锁。
--
-- 系统级表：不加 RLS。worker 启动时没有租户上下文，
-- 抢到任务后再用行里的 tenant_id 设置上下文去访问业务表。
-- ============================================================

CREATE TABLE tasks (
    id              BIGSERIAL    PRIMARY KEY,
    tenant_id       BIGINT       NOT NULL REFERENCES tenants(id),
    created_by      BIGINT       REFERENCES users(id),

    queue           VARCHAR(32)  NOT NULL DEFAULT 'DEFAULT',
    type            VARCHAR(64)  NOT NULL,
    status          VARCHAR(20)  NOT NULL DEFAULT 'PENDING',
    priority        INT          NOT NULL DEFAULT 0,

    -- 幂等键：同一个业务动作重复提交，不重复扣费
    idempotency_key VARCHAR(120),

    payload         JSONB        NOT NULL DEFAULT '{}'::jsonb,
    result          JSONB,
    error_code      VARCHAR(64),
    error_message   TEXT,

    attempts        INT          NOT NULL DEFAULT 0,
    max_attempts    INT          NOT NULL DEFAULT 3,

    -- 延迟任务 / 退避重试 / 供应商状态轮询，全靠这一列
    run_after       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    -- 抢占后写入；用于回收 worker 崩溃后卡住的任务
    lease_expires_at TIMESTAMPTZ,

    started_at      TIMESTAMPTZ,
    finished_at     TIMESTAMPTZ,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT uk_tasks_idem UNIQUE (tenant_id, idempotency_key)
);

-- worker 抢任务走的就是这个索引
CREATE INDEX idx_tasks_claim ON tasks (queue, status, run_after, priority DESC, id)
    WHERE status = 'PENDING';
CREATE INDEX idx_tasks_tenant ON tasks (tenant_id, created_at DESC);
CREATE INDEX idx_tasks_lease ON tasks (lease_expires_at) WHERE status = 'RUNNING';
