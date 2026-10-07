ALTER TABLE image_creations
    ADD COLUMN concurrency_permit_held BOOLEAN NOT NULL DEFAULT FALSE;

DROP POLICY IF EXISTS tenant_quotas_isolation ON tenant_quotas;
CREATE POLICY tenant_quotas_isolation ON tenant_quotas
    USING (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::BIGINT)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::BIGINT);
ALTER TABLE tenant_quotas FORCE ROW LEVEL SECURITY;
GRANT SELECT, INSERT, UPDATE, DELETE ON tenant_quotas TO growth_app;
