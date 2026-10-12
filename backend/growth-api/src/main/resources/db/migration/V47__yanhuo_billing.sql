-- Production billing starts with empty accounts and an empty catalogue. No trial grants or demo plans.
CREATE TABLE billing_plans (
 id BIGSERIAL PRIMARY KEY, code VARCHAR(60) NOT NULL, name VARCHAR(100) NOT NULL,
 description VARCHAR(600) NOT NULL, price_minor BIGINT NOT NULL CHECK (price_minor > 0),
 duration_days INT NOT NULL CHECK (duration_days BETWEEN 1 AND 3660),
 gift_credits BIGINT NOT NULL CHECK (gift_credits >= 0), image_enabled BOOLEAN NOT NULL,
 video_enabled BOOLEAN NOT NULL, published BOOLEAN NOT NULL DEFAULT false,
 created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE TABLE billing_accounts (
 tenant_id BIGINT PRIMARY KEY REFERENCES tenants(id),
 gift_available BIGINT NOT NULL DEFAULT 0 CHECK (gift_available >= 0),
 purchased_available BIGINT NOT NULL DEFAULT 0 CHECK (purchased_available >= 0),
 held BIGINT NOT NULL DEFAULT 0 CHECK (held >= 0),
 plan_snapshot JSONB, member_until TIMESTAMPTZ, membership_order_id VARCHAR(36),
 updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE TABLE billing_orders (
 id VARCHAR(36) PRIMARY KEY, tenant_id BIGINT NOT NULL REFERENCES tenants(id),
 request_key VARCHAR(80) NOT NULL, kind VARCHAR(20) NOT NULL, amount_minor BIGINT NOT NULL CHECK (amount_minor > 0),
 credits BIGINT NOT NULL CHECK (credits >= 0), plan_snapshot JSONB,
 state VARCHAR(24) NOT NULL DEFAULT 'PENDING_PAYMENT', receipt_reference VARCHAR(160),
 payment_method VARCHAR(16) NOT NULL, payment_code_url TEXT,
 confirmed_by BIGINT REFERENCES users(id), paid_at TIMESTAMPTZ,
 expires_at TIMESTAMPTZ NOT NULL, created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
 UNIQUE(tenant_id, request_key), UNIQUE(receipt_reference)
);
CREATE TABLE billing_reservations (
 id VARCHAR(36) PRIMARY KEY, tenant_id BIGINT NOT NULL REFERENCES tenants(id),
 request_key VARCHAR(120) NOT NULL, capability VARCHAR(20) NOT NULL,
 gift_held BIGINT NOT NULL CHECK (gift_held >= 0), purchased_held BIGINT NOT NULL CHECK (purchased_held >= 0),
 captured BIGINT NOT NULL DEFAULT 0 CHECK (captured >= 0), initial_amount BIGINT NOT NULL CHECK (initial_amount > 0),
 created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
 UNIQUE(tenant_id, request_key)
);
CREATE TABLE billing_ledger (
 id BIGSERIAL PRIMARY KEY, tenant_id BIGINT NOT NULL REFERENCES tenants(id),
 event_key VARCHAR(160) NOT NULL, type VARCHAR(24) NOT NULL, description VARCHAR(240) NOT NULL,
 gift_delta BIGINT NOT NULL, purchased_delta BIGINT NOT NULL, held_delta BIGINT NOT NULL,
 available_after BIGINT NOT NULL CHECK (available_after >= 0), held_after BIGINT NOT NULL CHECK (held_after >= 0),
 order_id VARCHAR(36) REFERENCES billing_orders(id), reservation_id VARCHAR(36) REFERENCES billing_reservations(id),
 created_at TIMESTAMPTZ NOT NULL DEFAULT now(), UNIQUE(tenant_id,event_key)
);
CREATE INDEX idx_billing_orders_history ON billing_orders(tenant_id,created_at DESC);
CREATE INDEX idx_billing_ledger_history ON billing_ledger(tenant_id,id DESC);
CREATE FUNCTION billing_ledger_immutable() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN RAISE EXCEPTION 'billing ledger entries are immutable'; END $$;
CREATE TRIGGER billing_ledger_immutable BEFORE UPDATE OR DELETE ON billing_ledger
 FOR EACH ROW EXECUTE FUNCTION billing_ledger_immutable();
DO $$ DECLARE table_name text; BEGIN
 FOREACH table_name IN ARRAY ARRAY['billing_accounts','billing_orders','billing_reservations','billing_ledger'] LOOP
  EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',table_name);
  EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',table_name);
  EXECUTE format('CREATE POLICY tenant_isolation ON %I USING (tenant_id = NULLIF(current_setting(''app.tenant_id'',true),'''')::bigint) WITH CHECK (tenant_id = NULLIF(current_setting(''app.tenant_id'',true),'''')::bigint)',table_name);
 END LOOP;
END $$;
GRANT SELECT,INSERT,UPDATE ON billing_plans,billing_accounts,billing_orders,billing_reservations TO growth_app;
GRANT SELECT,INSERT ON billing_ledger TO growth_app;
REVOKE UPDATE,DELETE ON billing_ledger FROM growth_app;
GRANT USAGE,SELECT ON SEQUENCE billing_plans_id_seq,billing_ledger_id_seq TO growth_app;
ALTER TABLE image_creations ADD COLUMN billing_reservation_id VARCHAR(36) REFERENCES billing_reservations(id);
ALTER TABLE image_creations ADD COLUMN billing_round INT NOT NULL DEFAULT 0;
ALTER TABLE image_items ADD COLUMN billing_held BOOLEAN NOT NULL DEFAULT false;
ALTER TABLE video_workflows ADD COLUMN billing_reservation_id VARCHAR(36) REFERENCES billing_reservations(id);
ALTER TABLE video_workflows ADD COLUMN billing_quote JSONB;
ALTER TABLE video_workflows ADD COLUMN provider_billing_tokens BIGINT;
ALTER TABLE video_workflows ADD COLUMN billing_captured_credits BIGINT CHECK (billing_captured_credits >= 0);
