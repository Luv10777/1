-- Keep applied billing migrations unchanged. Existing audit actors remain unknown.
ALTER TABLE billing_orders ADD COLUMN created_by BIGINT REFERENCES users(id);
ALTER TABLE billing_orders ADD COLUMN reconciliation_after TIMESTAMPTZ;
ALTER TABLE billing_reservations ADD COLUMN created_by BIGINT REFERENCES users(id);
ALTER TABLE billing_ledger ADD COLUMN actor_user_id BIGINT REFERENCES users(id);
ALTER TABLE billing_accounts ADD COLUMN recovery_after TIMESTAMPTZ;

-- A code identifies a plan family; historical versions may share it.
-- Fail on ambiguous published catalogues so operators can choose the offered version.
CREATE UNIQUE INDEX uq_billing_plans_published_code ON billing_plans(code) WHERE published;
CREATE INDEX idx_billing_reservations_unsettled ON billing_reservations(tenant_id,id)
 WHERE gift_held+purchased_held>0;
