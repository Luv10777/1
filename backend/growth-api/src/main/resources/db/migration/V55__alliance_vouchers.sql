-- 联盟券：会员额度、供券单、领取记录、核销尝试、联盟券、结算单。设计见 docs/alliance-vouchers.md。
-- 一张券同时涉及两家商户：供券方 B 的表（offers / claims / attempts / settlements）和领券方 A 的表
-- （quota_grants / vouchers）分别按各自 tenant_id 隔离，两边用 voucher_ref 对应。

-- 套餐里的联盟券额度（分），随订单的套餐快照锁定。
ALTER TABLE billing_plans
    ADD COLUMN alliance_quota_minor BIGINT NOT NULL DEFAULT 0 CHECK (alliance_quota_minor >= 0);

CREATE TABLE alliance_offers (
    id                   BIGSERIAL PRIMARY KEY,
    tenant_id            BIGINT       NOT NULL REFERENCES tenants(id),
    store_id             BIGINT       NOT NULL REFERENCES stores(id),
    product_id           BIGINT       NOT NULL REFERENCES products(id),
    product_name         VARCHAR(200) NOT NULL,
    product_category     VARCHAR(40)  NOT NULL,
    sale_unit            VARCHAR(40)  NOT NULL,
    cover_asset_id       BIGINT,
    store_name           VARCHAR(120) NOT NULL,
    store_address        VARCHAR(300),
    store_phone          VARCHAR(40),
    store_hours          VARCHAR(120),
    supplier_name        VARCHAR(80)  NOT NULL,
    supplier_credit_code VARCHAR(18)  NOT NULL,
    face_value_minor     BIGINT       NOT NULL CHECK (face_value_minor > 0),
    settle_rate_bp       INT          NOT NULL CHECK (settle_rate_bp BETWEEN 1 AND 10000),
    settle_amount_minor  BIGINT       NOT NULL CHECK (settle_amount_minor >= 0),
    total_qty            INT          NOT NULL CHECK (total_qty > 0),
    claimed_qty          INT          NOT NULL DEFAULT 0 CHECK (claimed_qty >= 0 AND claimed_qty <= total_qty),
    status               VARCHAR(20)  NOT NULL DEFAULT 'PENDING_REVIEW'
        CHECK (status IN ('PENDING_REVIEW', 'ACTIVE', 'SOLD_OUT', 'REJECTED', 'WITHDRAWN')),
    review_note          VARCHAR(300),
    reviewed_by          BIGINT,
    reviewed_at          TIMESTAMPTZ,
    created_by           BIGINT       NOT NULL REFERENCES users(id),
    created_at           TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at           TIMESTAMPTZ  NOT NULL DEFAULT now(),
    version              BIGINT       NOT NULL DEFAULT 0,
    UNIQUE (tenant_id, id)
);
CREATE INDEX idx_alliance_offers_tenant ON alliance_offers (tenant_id, created_at DESC);
CREATE INDEX idx_alliance_offers_pool ON alliance_offers (status, updated_at DESC) WHERE status = 'ACTIVE';

CREATE TABLE alliance_offer_claims (
    id                BIGSERIAL PRIMARY KEY,
    tenant_id         BIGINT      NOT NULL REFERENCES tenants(id),
    offer_id          BIGINT      NOT NULL,
    voucher_ref       UUID        NOT NULL UNIQUE,
    holder_tenant_id  BIGINT      NOT NULL REFERENCES tenants(id),
    holder_name       VARCHAR(100) NOT NULL,
    code_hash         VARCHAR(64) NOT NULL,
    expires_at        TIMESTAMPTZ NOT NULL,
    status            VARCHAR(20) NOT NULL DEFAULT 'CLAIMED' CHECK (status IN ('CLAIMED', 'REDEEMED', 'EXPIRED')),
    redeemed_store_id BIGINT,
    redeemed_by       BIGINT,
    redeemed_at       TIMESTAMPTZ,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    FOREIGN KEY (tenant_id, offer_id) REFERENCES alliance_offers (tenant_id, id),
    UNIQUE (tenant_id, code_hash),
    UNIQUE (tenant_id, id)
);
CREATE INDEX idx_alliance_claims_offer ON alliance_offer_claims (tenant_id, offer_id);
CREATE INDEX idx_alliance_claims_due ON alliance_offer_claims (tenant_id, expires_at) WHERE status = 'CLAIMED';

CREATE TABLE alliance_redeem_attempts (
    id         BIGSERIAL PRIMARY KEY,
    tenant_id  BIGINT      NOT NULL REFERENCES tenants(id),
    store_id   BIGINT      NOT NULL,
    user_id    BIGINT      NOT NULL,
    succeeded  BOOLEAN     NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_alliance_attempts_user ON alliance_redeem_attempts (tenant_id, user_id, created_at DESC);

CREATE TABLE alliance_quota_grants (
    id               BIGSERIAL PRIMARY KEY,
    tenant_id        BIGINT       NOT NULL REFERENCES tenants(id),
    billing_order_id VARCHAR(36)  NOT NULL UNIQUE,
    plan_code        VARCHAR(60)  NOT NULL,
    plan_name        VARCHAR(100) NOT NULL,
    amount_minor     BIGINT       NOT NULL CHECK (amount_minor >= 0),
    used_minor       BIGINT       NOT NULL DEFAULT 0 CHECK (used_minor >= 0 AND used_minor <= amount_minor),
    valid_from       TIMESTAMPTZ  NOT NULL,
    valid_until      TIMESTAMPTZ  NOT NULL CHECK (valid_until > valid_from),
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX idx_alliance_grants_period ON alliance_quota_grants (tenant_id, valid_from, valid_until);

CREATE TABLE alliance_vouchers (
    voucher_ref        UUID         PRIMARY KEY,
    tenant_id          BIGINT       NOT NULL REFERENCES tenants(id),
    quota_grant_id     BIGINT       NOT NULL REFERENCES alliance_quota_grants(id),
    offer_id           BIGINT       NOT NULL,
    supplier_tenant_id BIGINT       NOT NULL REFERENCES tenants(id),
    store_id           BIGINT       NOT NULL,
    store_name         VARCHAR(120) NOT NULL,
    store_address      VARCHAR(300),
    store_phone        VARCHAR(40),
    store_hours        VARCHAR(120),
    product_name       VARCHAR(200) NOT NULL,
    sale_unit          VARCHAR(40)  NOT NULL,
    cover_asset_id     BIGINT,
    face_value_minor   BIGINT       NOT NULL CHECK (face_value_minor > 0),
    code               VARCHAR(8)   NOT NULL,
    status             VARCHAR(20)  NOT NULL DEFAULT 'CLAIMING'
        CHECK (status IN ('CLAIMING', 'ACTIVE', 'CLAIM_FAILED', 'REDEEMED', 'DISPUTED', 'EXPIRED')),
    claimed_by         BIGINT       NOT NULL REFERENCES users(id),
    claimed_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    expires_at         TIMESTAMPTZ  NOT NULL,
    redeemed_at        TIMESTAMPTZ,
    dispute_reason     VARCHAR(300),
    disputed_at        TIMESTAMPTZ,
    dispute_result     VARCHAR(20)  CHECK (dispute_result IN ('VALID', 'INVALID')),
    dispute_note       VARCHAR(300),
    dispute_resolved_at TIMESTAMPTZ,
    updated_at         TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX idx_alliance_vouchers_list ON alliance_vouchers (tenant_id, claimed_at DESC);
CREATE INDEX idx_alliance_vouchers_grant ON alliance_vouchers (tenant_id, quota_grant_id);

CREATE TABLE alliance_settlements (
    id                  BIGSERIAL PRIMARY KEY,
    tenant_id           BIGINT       NOT NULL REFERENCES tenants(id),
    claim_id            BIGINT       NOT NULL UNIQUE,
    offer_id            BIGINT       NOT NULL,
    voucher_ref         UUID         NOT NULL UNIQUE,
    holder_tenant_id    BIGINT       NOT NULL REFERENCES tenants(id),
    product_name        VARCHAR(200) NOT NULL,
    face_value_minor    BIGINT       NOT NULL CHECK (face_value_minor > 0),
    settle_amount_minor BIGINT       NOT NULL CHECK (settle_amount_minor >= 0),
    status              VARCHAR(20)  NOT NULL DEFAULT 'HOLDING' CHECK (status IN ('HOLDING', 'PAID', 'VOID')),
    payable_after       TIMESTAMPTZ  NOT NULL,
    frozen              BOOLEAN      NOT NULL DEFAULT false,
    paid_reference      VARCHAR(150),
    paid_by             BIGINT,
    paid_at             TIMESTAMPTZ,
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    FOREIGN KEY (tenant_id, claim_id) REFERENCES alliance_offer_claims (tenant_id, id)
);
CREATE INDEX idx_alliance_settlements_list ON alliance_settlements (tenant_id, created_at DESC);

DO $$
DECLARE
    table_name text;
BEGIN
    FOREACH table_name IN ARRAY ARRAY['alliance_offers', 'alliance_offer_claims', 'alliance_redeem_attempts',
            'alliance_quota_grants', 'alliance_vouchers', 'alliance_settlements']
    LOOP
        EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY', table_name);
        EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY', table_name);
        EXECUTE format(
            'CREATE POLICY tenant_isolation ON %I USING (tenant_id = NULLIF(current_setting(''app.tenant_id'', true), '''')::bigint) WITH CHECK (tenant_id = NULLIF(current_setting(''app.tenant_id'', true), '''')::bigint)',
            table_name);
        EXECUTE format('GRANT SELECT, INSERT, UPDATE ON %I TO growth_app', table_name);
    END LOOP;
END $$;

-- 券库：任何商户都能看到已上架且还有剩余的供券单（只读）。领取时的扣减在供券方自己的租户里完成。
CREATE POLICY alliance_offer_pool ON alliance_offers FOR SELECT
    USING (status = 'ACTIVE' AND claimed_qty < total_qty);

GRANT USAGE, SELECT ON SEQUENCE alliance_offers_id_seq, alliance_offer_claims_id_seq, alliance_redeem_attempts_id_seq,
    alliance_quota_grants_id_seq, alliance_settlements_id_seq TO growth_app;
