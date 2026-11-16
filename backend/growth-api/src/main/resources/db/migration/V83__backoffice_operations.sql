-- Platform administrative data, and partner CRM/sales data, follow the V79 platform-table model.
-- Access is enforced by BackofficeAccess and the existing partner OWNER/BD binding checks.
CREATE TABLE platform_roles (
    user_id BIGINT NOT NULL REFERENCES users(id),
    role VARCHAR(16) NOT NULL CHECK (role IN ('ADMIN','REVIEWER','FINANCE','SUPPORT')),
    assigned_by BIGINT NOT NULL REFERENCES users(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, role)
);
CREATE TABLE backoffice_operations (
    id BIGSERIAL PRIMARY KEY,
    actor_user_id BIGINT NOT NULL REFERENCES users(id),
    actor_name VARCHAR(160) NOT NULL,
    partner_id BIGINT REFERENCES partners(id),
    method VARCHAR(8) NOT NULL,
    path VARCHAR(500) NOT NULL,
    action VARCHAR(200) NOT NULL,
    http_status INT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_backoffice_operations_time ON backoffice_operations (created_at DESC, id DESC);
CREATE INDEX idx_backoffice_operations_actor ON backoffice_operations (actor_user_id, id DESC);
CREATE TABLE partner_merchant_profiles (
    partner_id BIGINT NOT NULL REFERENCES partners(id),
    tenant_id BIGINT NOT NULL REFERENCES tenants(id),
    contact_name VARCHAR(80) NOT NULL DEFAULT '',
    contact_phone VARCHAR(40) NOT NULL DEFAULT '',
    industry VARCHAR(80) NOT NULL DEFAULT '',
    region VARCHAR(120) NOT NULL DEFAULT '',
    stage VARCHAR(16) NOT NULL DEFAULT 'ACTIVE' CHECK (stage IN ('ACTIVE','AT_RISK','DORMANT')),
    next_follow_up_at TIMESTAMPTZ,
    note VARCHAR(2000) NOT NULL DEFAULT '',
    version BIGINT NOT NULL DEFAULT 0,
    updated_by BIGINT NOT NULL REFERENCES users(id),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (partner_id, tenant_id)
);
CREATE INDEX idx_partner_profiles_due ON partner_merchant_profiles (partner_id, next_follow_up_at);
CREATE TABLE partner_follow_ups (
    id BIGSERIAL PRIMARY KEY,
    partner_id BIGINT NOT NULL REFERENCES partners(id),
    tenant_id BIGINT NOT NULL REFERENCES tenants(id),
    request_key VARCHAR(80) NOT NULL,
    channel VARCHAR(12) NOT NULL CHECK (channel IN ('PHONE','WECHAT','VISIT','OTHER')),
    content VARCHAR(2000) NOT NULL,
    stage VARCHAR(16) NOT NULL CHECK (stage IN ('ACTIVE','AT_RISK','DORMANT')),
    next_follow_up_at TIMESTAMPTZ,
    actor_user_id BIGINT NOT NULL REFERENCES users(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (partner_id, request_key)
);
CREATE INDEX idx_partner_follow_ups_tenant ON partner_follow_ups (partner_id, tenant_id, id DESC);
CREATE TABLE partner_sales (
    id BIGSERIAL PRIMARY KEY,
    partner_id BIGINT NOT NULL REFERENCES partners(id),
    tenant_id BIGINT NOT NULL REFERENCES tenants(id),
    request_key VARCHAR(80) NOT NULL,
    kind VARCHAR(16) NOT NULL CHECK (kind IN ('MEMBERSHIP','CREDITS','SERVICE')),
    title VARCHAR(120) NOT NULL,
    amount_minor BIGINT NOT NULL CHECK (amount_minor BETWEEN 1 AND 100000000),
    code_id BIGINT UNIQUE REFERENCES billing_redeem_codes(id),
    note VARCHAR(500) NOT NULL DEFAULT '',
    actor_user_id BIGINT NOT NULL REFERENCES users(id),
    void_note VARCHAR(500),
    voided_by BIGINT REFERENCES users(id),
    voided_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (partner_id, request_key)
);
CREATE INDEX idx_partner_sales_list ON partner_sales (partner_id, created_at DESC, id DESC);
CREATE TABLE partner_sale_receipts (
    id BIGSERIAL PRIMARY KEY,
    sale_id BIGINT NOT NULL REFERENCES partner_sales(id),
    request_key VARCHAR(80) NOT NULL,
    amount_minor BIGINT NOT NULL CHECK (amount_minor BETWEEN 1 AND 100000000),
    receipt_reference VARCHAR(150) NOT NULL,
    actor_user_id BIGINT NOT NULL REFERENCES users(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (sale_id, request_key),
    UNIQUE (receipt_reference)
);
GRANT SELECT, INSERT, DELETE ON platform_roles TO growth_app;
GRANT SELECT, INSERT ON backoffice_operations, partner_follow_ups, partner_sale_receipts TO growth_app;
GRANT SELECT, INSERT, UPDATE ON partner_merchant_profiles, partner_sales TO growth_app;
GRANT USAGE, SELECT ON SEQUENCE backoffice_operations_id_seq, partner_follow_ups_id_seq, partner_sales_id_seq, partner_sale_receipts_id_seq TO growth_app;
