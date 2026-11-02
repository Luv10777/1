-- V74 · 烟火兑换码：平台运营批量生成，一码一用，商家管理员或店员在「套餐与权益」兑换。
--
-- 兑换码是平台级数据，生成时不属于任何商户，和 billing_plans、referral_rates 一样不分商户、不开行级隔离；
-- 只有服务层的计费运营名单能列出和作废，商户只能按完整码兑换。兑换入账写进兑换商户自己的账本，账本照旧受 RLS 约束。
-- kind：TOPUP 记入充值烟火（账本 PURCHASE），GIFT 记入赠送烟火（账本 GRANT）。兑换不产生订单。
-- amount_minor 是充值码的实收金额（分），兑换后按它给邀请人记充值返利；为 0 不记。赠送码没有实收金额。
CREATE TABLE billing_redeem_codes (
    id                 BIGSERIAL    PRIMARY KEY,
    code               VARCHAR(16)  NOT NULL UNIQUE,
    batch_key          VARCHAR(80)  NOT NULL,
    kind               VARCHAR(12)  NOT NULL CHECK (kind IN ('TOPUP', 'GIFT')),
    credits            BIGINT       NOT NULL CHECK (credits BETWEEN 1 AND 100000),
    amount_minor       BIGINT       NOT NULL DEFAULT 0 CHECK (amount_minor BETWEEN 0 AND 10000000),
    note               VARCHAR(200),
    expires_at         TIMESTAMPTZ,
    created_by         BIGINT       NOT NULL REFERENCES users(id),
    created_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    voided_by          BIGINT       REFERENCES users(id),
    voided_at          TIMESTAMPTZ,
    redeemed_tenant_id BIGINT       REFERENCES tenants(id),
    redeemed_by        BIGINT       REFERENCES users(id),
    redeemed_at        TIMESTAMPTZ,
    CHECK (kind = 'TOPUP' OR amount_minor = 0),
    CHECK (redeemed_at IS NULL OR voided_at IS NULL),
    CHECK ((redeemed_at IS NULL) = (redeemed_tenant_id IS NULL))
);
CREATE INDEX idx_billing_redeem_codes_batch ON billing_redeem_codes (batch_key);
CREATE INDEX idx_billing_redeem_codes_created ON billing_redeem_codes (created_at DESC, id DESC);

GRANT SELECT, INSERT, UPDATE ON billing_redeem_codes TO growth_app;
GRANT USAGE, SELECT ON SEQUENCE billing_redeem_codes_id_seq TO growth_app;
