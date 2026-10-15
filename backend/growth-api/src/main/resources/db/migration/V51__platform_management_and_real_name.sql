-- Platform management audit trail and encrypted user identity applications.
ALTER TABLE information_publications ADD COLUMN platform_blocked BOOLEAN NOT NULL DEFAULT false;
ALTER TABLE merchant_applications
    DROP CONSTRAINT merchant_applications_status_check;
ALTER TABLE merchant_applications
    ADD CONSTRAINT ck_merchant_applications_status
    CHECK (status IN ('PENDING_REVIEW', 'APPROVED', 'REJECTED', 'SUSPENDED'));

CREATE TABLE real_name_applications (
    id BIGSERIAL PRIMARY KEY,
    tenant_id BIGINT NOT NULL REFERENCES tenants(id),
    user_id BIGINT NOT NULL REFERENCES users(id),
    legal_name_ciphertext BYTEA NOT NULL,
    id_number_ciphertext BYTEA NOT NULL,
    legal_name_masked VARCHAR(80) NOT NULL,
    id_number_masked VARCHAR(32) NOT NULL,
    status VARCHAR(24) NOT NULL DEFAULT 'PENDING_REVIEW'
        CHECK (status IN ('PENDING_REVIEW', 'VERIFIED', 'REJECTED')),
    review_note VARCHAR(500),
    reviewed_by BIGINT REFERENCES users(id),
    submitted_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    reviewed_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_real_name_applications_user UNIQUE (tenant_id, user_id)
);
CREATE INDEX idx_real_name_applications_queue ON real_name_applications (tenant_id, status, submitted_at DESC, id DESC);

CREATE TABLE platform_management_audit (
    id BIGSERIAL PRIMARY KEY,
    tenant_id BIGINT NOT NULL REFERENCES tenants(id),
    actor_user_id BIGINT NOT NULL REFERENCES users(id),
    target_type VARCHAR(24) NOT NULL CHECK (target_type IN ('INFORMATION', 'MERCHANT', 'IDENTITY')),
    target_id BIGINT NOT NULL,
    action VARCHAR(40) NOT NULL,
    note VARCHAR(500),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_platform_management_audit_target
    ON platform_management_audit (tenant_id, target_type, target_id, created_at DESC, id DESC);

DO $$
DECLARE table_name TEXT;
BEGIN
    FOREACH table_name IN ARRAY ARRAY['real_name_applications', 'platform_management_audit'] LOOP
        EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY', table_name);
        EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY', table_name);
        EXECUTE format(
            'CREATE POLICY tenant_isolation ON %I USING (tenant_id = NULLIF(current_setting(''app.tenant_id'', true), '''')::bigint) WITH CHECK (tenant_id = NULLIF(current_setting(''app.tenant_id'', true), '''')::bigint)',
            table_name
        );
        EXECUTE format('GRANT SELECT, INSERT, UPDATE, DELETE ON %I TO growth_app', table_name);
    END LOOP;
END $$;
GRANT USAGE, SELECT ON SEQUENCE real_name_applications_id_seq, platform_management_audit_id_seq TO growth_app;
REVOKE UPDATE, DELETE ON platform_management_audit FROM growth_app;
