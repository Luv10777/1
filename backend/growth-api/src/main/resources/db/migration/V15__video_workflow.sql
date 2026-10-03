-- V15 · 视频工作流
-- API 只创建 workflow 和 task；worker 通过 tasks 表推进供应商轮询、下载和 QA。

CREATE TABLE video_workflows (
    id                  BIGSERIAL PRIMARY KEY,
    tenant_id           BIGINT NOT NULL REFERENCES tenants(id),
    created_by          BIGINT REFERENCES users(id),
    request_key         VARCHAR(80) NOT NULL,
    request_hash        VARCHAR(64) NOT NULL,
    request             JSONB NOT NULL,
    model               VARCHAR(40) NOT NULL,
    ratio               VARCHAR(8) NOT NULL,
    duration_seconds    INT NOT NULL,
    resolution          VARCHAR(8) NOT NULL,
    status              VARCHAR(32) NOT NULL DEFAULT 'QUEUED',
    stage               VARCHAR(32) NOT NULL DEFAULT 'SUBMIT',
    progress            INT NOT NULL DEFAULT 0,
    task_id             BIGINT,
    provider_job_id     VARCHAR(200),
    provider_status     VARCHAR(32),
    provider_result_url TEXT,
    poll_round          INT NOT NULL DEFAULT 0,
    output_asset_id     BIGINT,
    output_storage_key  VARCHAR(500),
    error_code          VARCHAR(64),
    error_message       TEXT,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uk_video_workflow_request UNIQUE (tenant_id, request_key)
);

CREATE INDEX idx_video_workflow_tenant ON video_workflows (tenant_id, created_at DESC);
CREATE INDEX idx_video_workflow_status ON video_workflows (status, created_at DESC);

ALTER TABLE video_workflows ENABLE ROW LEVEL SECURITY;
ALTER TABLE video_workflows FORCE ROW LEVEL SECURITY;
CREATE POLICY video_workflow_tenant_isolation ON video_workflows
    USING (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::bigint)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::bigint);

CREATE TABLE video_provider_jobs (
    id                  BIGSERIAL PRIMARY KEY,
    tenant_id           BIGINT NOT NULL REFERENCES tenants(id),
    workflow_id         BIGINT NOT NULL REFERENCES video_workflows(id),
    provider            VARCHAR(80) NOT NULL,
    model               VARCHAR(40) NOT NULL,
    provider_job_id     VARCHAR(200) NOT NULL,
    status              VARCHAR(32) NOT NULL,
    poll_round          INT NOT NULL DEFAULT 0,
    result_url          TEXT,
    result_expires_at   TIMESTAMPTZ,
    last_error_code     VARCHAR(64),
    last_error_message  TEXT,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uk_video_provider_job UNIQUE (provider, provider_job_id)
);

CREATE INDEX idx_video_provider_job_workflow ON video_provider_jobs (tenant_id, workflow_id);

ALTER TABLE video_provider_jobs ENABLE ROW LEVEL SECURITY;
ALTER TABLE video_provider_jobs FORCE ROW LEVEL SECURITY;
CREATE POLICY video_provider_job_tenant_isolation ON video_provider_jobs
    USING (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::bigint)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::bigint);
