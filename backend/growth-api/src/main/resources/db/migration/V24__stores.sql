-- V24 · 多门店与门店成员
-- 门店资源属于租户；成员表保存用户对门店的访问范围。

CREATE TABLE stores (
    id              BIGSERIAL    PRIMARY KEY,
    tenant_id       BIGINT       NOT NULL REFERENCES tenants(id),
    name            VARCHAR(120) NOT NULL,
    status          VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE',
    address         VARCHAR(300),
    phone           VARCHAR(40),
    business_hours  VARCHAR(120),
    created_by      BIGINT       NOT NULL REFERENCES users(id),
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    version         BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT uk_stores_tenant_name UNIQUE (tenant_id, name)
);
CREATE INDEX idx_stores_tenant_status ON stores (tenant_id, status, id);

CREATE TABLE store_members (
    id          BIGSERIAL    PRIMARY KEY,
    tenant_id   BIGINT       NOT NULL REFERENCES tenants(id),
    store_id    BIGINT       NOT NULL REFERENCES stores(id),
    user_id     BIGINT       NOT NULL REFERENCES users(id),
    role        VARCHAR(30)  NOT NULL DEFAULT 'STAFF',
    status      VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE',
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uk_store_members_store_user UNIQUE (store_id, user_id)
);
CREATE INDEX idx_store_members_user ON store_members (tenant_id, user_id, status);
CREATE INDEX idx_store_members_store ON store_members (tenant_id, store_id, status);

ALTER TABLE stores ENABLE ROW LEVEL SECURITY;
ALTER TABLE stores FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON stores
    USING (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::bigint)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::bigint);

ALTER TABLE store_members ENABLE ROW LEVEL SECURITY;
ALTER TABLE store_members FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON store_members
    USING (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::bigint)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::bigint);

GRANT SELECT, INSERT, UPDATE, DELETE ON stores, store_members TO growth_app;
GRANT USAGE, SELECT ON SEQUENCE stores_id_seq, store_members_id_seq TO growth_app;
