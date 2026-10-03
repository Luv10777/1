-- Logout invalidates all access tokens as well as refresh tokens for the account.
ALTER TABLE users ADD COLUMN token_version BIGINT NOT NULL DEFAULT 0;
CREATE INDEX idx_assets_pending_cleanup ON assets(tenant_id, created_at, id) WHERE status = 'PENDING';
