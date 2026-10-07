CREATE TABLE live_player_pairings (
    id BIGSERIAL PRIMARY KEY,
    tenant_id BIGINT NOT NULL REFERENCES tenants(id),
    session_id BIGINT NOT NULL REFERENCES live_sessions(id) ON DELETE CASCADE,
    token_hash VARCHAR(64) NOT NULL UNIQUE,
    expires_at TIMESTAMPTZ NOT NULL,
    revoked_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by BIGINT NOT NULL REFERENCES users(id)
);
CREATE INDEX idx_live_player_pairing_session ON live_player_pairings(tenant_id, session_id);
ALTER TABLE live_player_pairings ENABLE ROW LEVEL SECURITY;
ALTER TABLE live_player_pairings FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON live_player_pairings
    USING (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::bigint)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::bigint);
GRANT SELECT, INSERT, UPDATE, DELETE ON live_player_pairings TO growth_app;
GRANT USAGE, SELECT ON SEQUENCE live_player_pairings_id_seq TO growth_app;
