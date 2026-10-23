-- 视频工作台「帮我出方案」：看参考图、按风格和一句话出 3 个版本的提示词与分镜。每次预占 1 烟火，成功扣除，失败退回。
CREATE TABLE video_plans (
    id BIGSERIAL PRIMARY KEY,
    tenant_id BIGINT NOT NULL REFERENCES tenants(id),
    created_by BIGINT REFERENCES users(id),
    request_key VARCHAR(80) NOT NULL,
    image_asset_ids JSONB NOT NULL DEFAULT '[]',
    reference_video BOOLEAN NOT NULL DEFAULT false,
    style VARCHAR(40),
    idea TEXT,
    previous_plan_id BIGINT REFERENCES video_plans(id),
    status VARCHAR(20) NOT NULL DEFAULT 'QUEUED' CHECK (status IN ('QUEUED', 'GENERATING', 'SUCCEEDED', 'FAILED')),
    task_id BIGINT REFERENCES tasks(id),
    billing_reservation_id VARCHAR(36),
    plans JSONB,
    error_message TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uk_video_plan_request UNIQUE (tenant_id, request_key)
);
CREATE INDEX idx_video_plan_tenant ON video_plans (tenant_id, created_at DESC);
ALTER TABLE video_plans ENABLE ROW LEVEL SECURITY;
ALTER TABLE video_plans FORCE ROW LEVEL SECURITY;
CREATE POLICY video_plan_tenant_isolation ON video_plans
    USING (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::bigint)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::bigint);
GRANT SELECT, INSERT, UPDATE ON video_plans TO growth_app;
GRANT USAGE, SELECT ON SEQUENCE video_plans_id_seq TO growth_app;
