ALTER TABLE billing_reservations ADD COLUMN payer_tenant_id BIGINT REFERENCES tenants(id);
ALTER TABLE billing_ledger ADD COLUMN generation_capability VARCHAR(20);
