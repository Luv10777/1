CREATE TABLE video_analyses (
    id BIGSERIAL PRIMARY KEY,
    tenant_id BIGINT NOT NULL REFERENCES tenants(id),
    created_by BIGINT REFERENCES users(id),
    request_key VARCHAR(80) NOT NULL,
    name VARCHAR(200) NOT NULL,
    mode VARCHAR(8) NOT NULL CHECK (mode IN ('ai', 'real')),
    reverse_need TEXT,
    asset_id BIGINT NOT NULL REFERENCES assets(id),
    source_url TEXT,
    status VARCHAR(20) NOT NULL DEFAULT 'QUEUED',
    progress INT NOT NULL DEFAULT 0 CHECK (progress BETWEEN 0 AND 100),
    task_id BIGINT REFERENCES tasks(id),
    duration_ms INT,
    width INT,
    height INT,
    frames JSONB NOT NULL DEFAULT '[]',
    result JSONB,
    error_message TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uk_video_analysis_request UNIQUE (tenant_id, request_key)
);
CREATE INDEX idx_video_analysis_tenant ON video_analyses (tenant_id, created_at DESC);
ALTER TABLE video_analyses ENABLE ROW LEVEL SECURITY;
ALTER TABLE video_analyses FORCE ROW LEVEL SECURITY;
CREATE POLICY video_analysis_tenant_isolation ON video_analyses
    USING (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::bigint)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::bigint);
GRANT SELECT, INSERT, UPDATE, DELETE ON video_analyses TO growth_app;
GRANT USAGE, SELECT ON SEQUENCE video_analyses_id_seq TO growth_app;
