-- 灵感绘图「灵感方案」：看参考图、按绘图风格和一句话出 3 个画面描述。每次预占 1 烟火，成功扣除，失败退回。
CREATE TABLE image_inspirations (
    id BIGSERIAL PRIMARY KEY,
    tenant_id BIGINT NOT NULL REFERENCES tenants(id),
    created_by BIGINT REFERENCES users(id),
    request_key VARCHAR(80) NOT NULL,
    image_asset_ids JSONB NOT NULL DEFAULT '[]',
    style VARCHAR(40),
    idea TEXT,
    previous_id BIGINT REFERENCES image_inspirations(id),
    status VARCHAR(20) NOT NULL DEFAULT 'QUEUED' CHECK (status IN ('QUEUED', 'GENERATING', 'SUCCEEDED', 'FAILED')),
    task_id BIGINT REFERENCES tasks(id),
    billing_reservation_id VARCHAR(36),
    plans JSONB,
    error_message TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uk_image_inspiration_request UNIQUE (tenant_id, request_key)
);
CREATE INDEX idx_image_inspiration_tenant ON image_inspirations (tenant_id, created_at DESC);
ALTER TABLE image_inspirations ENABLE ROW LEVEL SECURITY;
ALTER TABLE image_inspirations FORCE ROW LEVEL SECURITY;
CREATE POLICY image_inspiration_tenant_isolation ON image_inspirations
    USING (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::bigint)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::bigint);
GRANT SELECT, INSERT, UPDATE ON image_inspirations TO growth_app;
GRANT USAGE, SELECT ON SEQUENCE image_inspirations_id_seq TO growth_app;
