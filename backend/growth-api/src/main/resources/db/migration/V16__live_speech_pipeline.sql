-- ============================================================
-- V16 · 直播播报流水与自动讲解状态
--
-- 播报队列原先保存在 api 进程内存里：重启即丢、多实例不共享，
-- worker 进程也没法把合成好的音频交给播报端。
--
-- 现在一条播报从提交到播完都落在 live_speech_items：
--   api    : 写入 PENDING（或自动讲解的 GENERATING），同事务提交任务
--   worker : 生成话术 → 合成语音 → 写回 READY
--   api    : 把 READY 推给播报端，收到确认后改为 PLAYED
-- ============================================================

CREATE TABLE live_speech_items (
    id                 BIGSERIAL    PRIMARY KEY,
    tenant_id          BIGINT       NOT NULL REFERENCES tenants(id),
    session_id         BIGINT       NOT NULL REFERENCES live_sessions(id) ON DELETE CASCADE,

    -- 播报端按它去重；同一场次内唯一，重复提交返回同一条
    command_id         VARCHAR(100) NOT NULL,
    kind               VARCHAR(20)  NOT NULL,
    mode               VARCHAR(20)  NOT NULL,
    status             VARCHAR(20)  NOT NULL,

    text               TEXT         NOT NULL DEFAULT '',
    -- sample:<声音样本 ID> 或 builtin:<系统音色>
    voice              VARCHAR(140),

    -- 自动讲解专用：第几段、讲哪件商品、讲哪个环节
    seq                INT,
    product_id         BIGINT       REFERENCES products(id),
    beat               VARCHAR(40),

    -- 同一 command_id 换了内容再提交要拒绝，靠它判断
    fingerprint        VARCHAR(64)  NOT NULL,

    audio_key          VARCHAR(500),
    duration_millis    BIGINT,
    pause_offsets      JSONB,
    first_audio_millis BIGINT,
    error_message      VARCHAR(500),

    created_by         BIGINT       REFERENCES users(id),
    created_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    ready_at           TIMESTAMPTZ,
    finished_at        TIMESTAMPTZ,

    CONSTRAINT uk_live_speech_command UNIQUE (session_id, command_id),
    CONSTRAINT ck_live_speech_kind CHECK (kind IN ('MANUAL', 'SCRIPT', 'REPLY', 'TEST')),
    CONSTRAINT ck_live_speech_mode CHECK (mode IN ('APPEND', 'INTERRUPT', 'CLEAR_REPLAY')),
    CONSTRAINT ck_live_speech_status
        CHECK (status IN ('GENERATING', 'PENDING', 'READY', 'PLAYED', 'FAILED', 'DISCARDED'))
);
CREATE INDEX idx_live_speech_queue ON live_speech_items (tenant_id, session_id, status, id);

-- 自动讲解的运行状态。不放进 live_sessions，是为了不触碰草稿配置的乐观锁版本。
CREATE TABLE live_script_states (
    session_id  BIGINT       PRIMARY KEY REFERENCES live_sessions(id) ON DELETE CASCADE,
    tenant_id   BIGINT       NOT NULL REFERENCES tenants(id),
    enabled     BOOLEAN      NOT NULL DEFAULT FALSE,
    -- 下一段的序号；商品、环节和音色都由它推算，任务重试不会错位
    next_seq    INT          NOT NULL DEFAULT 0,
    -- 连续失败次数，到上限自动停止，避免对着坏配置反复调用付费接口
    failures    INT          NOT NULL DEFAULT 0,
    last_error  VARCHAR(500),
    enabled_by  BIGINT       REFERENCES users(id),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);

DO $$ DECLARE t text; BEGIN
    FOREACH t IN ARRAY ARRAY['live_speech_items', 'live_script_states'] LOOP
        EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY', t);
        EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY', t);
        EXECUTE format('CREATE POLICY tenant_isolation ON %I '
            || 'USING (tenant_id = NULLIF(current_setting(''app.tenant_id'', true), '''')::bigint) '
            || 'WITH CHECK (tenant_id = NULLIF(current_setting(''app.tenant_id'', true), '''')::bigint)', t);
    END LOOP;
END $$;

GRANT SELECT, INSERT, UPDATE, DELETE ON live_speech_items, live_script_states TO growth_app;
GRANT USAGE, SELECT ON SEQUENCE live_speech_items_id_seq TO growth_app;
