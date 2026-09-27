-- Image workflow: new migration; V1-V5 remain unchanged.
CREATE TABLE image_creations (
 id BIGSERIAL PRIMARY KEY, tenant_id BIGINT NOT NULL REFERENCES tenants(id),
 request_key VARCHAR(80) NOT NULL, request_hash VARCHAR(64) NOT NULL,
 parent_id BIGINT REFERENCES image_creations(id), created_by BIGINT REFERENCES users(id),
 request JSONB NOT NULL, plan JSONB, status VARCHAR(24) NOT NULL DEFAULT 'QUEUED',
 error TEXT, task_id BIGINT REFERENCES tasks(id), created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
 UNIQUE(tenant_id, request_key)
);
CREATE TABLE image_items (
 id BIGSERIAL PRIMARY KEY, tenant_id BIGINT NOT NULL REFERENCES tenants(id),
 creation_id BIGINT NOT NULL REFERENCES image_creations(id), ordinal INT NOT NULL,
 spec JSONB NOT NULL, status VARCHAR(24) NOT NULL DEFAULT 'QUEUED',
 task_id BIGINT REFERENCES tasks(id), provider_job_id VARCHAR(300),
 provider_code VARCHAR(80), model VARCHAR(200), usage JSONB,
 generation INT NOT NULL DEFAULT 0, poll_round INT NOT NULL DEFAULT 0,
 raw_key VARCHAR(500), output_key VARCHAR(500), error TEXT,
 created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
 UNIQUE(creation_id, ordinal)
);
CREATE INDEX idx_image_creations_history ON image_creations(tenant_id, id DESC);
CREATE INDEX idx_image_items_creation ON image_items(tenant_id, creation_id, ordinal);
ALTER TABLE image_creations ENABLE ROW LEVEL SECURITY;
ALTER TABLE image_creations FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON image_creations
 USING (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::bigint)
 WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::bigint);
ALTER TABLE image_items ENABLE ROW LEVEL SECURITY;
ALTER TABLE image_items FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON image_items
 USING (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::bigint)
 WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::bigint);
GRANT SELECT, INSERT, UPDATE, DELETE ON image_creations, image_items TO growth_app;
GRANT USAGE, SELECT ON SEQUENCE image_creations_id_seq, image_items_id_seq TO growth_app;
