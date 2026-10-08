CREATE TABLE voice_samples (
 id BIGSERIAL PRIMARY KEY,
 tenant_id BIGINT NOT NULL REFERENCES tenants(id),
 store_id BIGINT NOT NULL REFERENCES stores(id),
 name VARCHAR(100) NOT NULL,
 storage_key VARCHAR(500) NOT NULL,
 mime_type VARCHAR(120) NOT NULL,
 size_bytes BIGINT,
 status VARCHAR(30) NOT NULL DEFAULT 'PENDING_UPLOAD',
 provider_code VARCHAR(40),
 provider_voice_id VARCHAR(200),
 consent_at TIMESTAMPTZ NOT NULL,
 consent_by BIGINT NOT NULL REFERENCES users(id),
 consent_text VARCHAR(300) NOT NULL,
 error_message VARCHAR(500),
 created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
 updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
 version BIGINT NOT NULL DEFAULT 0,
 CONSTRAINT ck_voice_sample_status CHECK (status IN ('PENDING_UPLOAD','UPLOADED','CLONING','READY','FAILED','DELETING','DELETED'))
);
CREATE INDEX idx_voice_samples_store ON voice_samples(tenant_id,store_id,id);
ALTER TABLE voice_samples ENABLE ROW LEVEL SECURITY;
ALTER TABLE voice_samples FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON voice_samples
 USING (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::bigint)
 WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::bigint);
GRANT SELECT,INSERT,UPDATE,DELETE ON voice_samples TO growth_app;
GRANT USAGE,SELECT ON SEQUENCE voice_samples_id_seq TO growth_app;
