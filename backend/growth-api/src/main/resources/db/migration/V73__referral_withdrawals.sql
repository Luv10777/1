-- V73 · 邀请返利第二期：本人银行卡、提现申请与人工打款、返利作废与扣回。设计见 docs/referral.md。
--
-- 收款银行卡属于用户本人：姓名、身份证号取自已通过的实名，加上卡号和银行预留手机号做四要素核验，一致才保存。
-- 卡号用 AES-256-GCM 加密，只显示后 4 位；预留手机号只用于核验，不保存。
CREATE TABLE bank_cards (
    user_id                BIGINT       PRIMARY KEY REFERENCES users(id),
    tenant_id              BIGINT       NOT NULL REFERENCES tenants(id),
    bank_name              VARCHAR(40)  NOT NULL,
    card_number_ciphertext BYTEA        NOT NULL,
    card_last4             VARCHAR(4)   NOT NULL,
    verified_at            TIMESTAMPTZ  NOT NULL,
    created_at             TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at             TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- 提现单在推广人所在商户。申请时抄下收款卡：之后换卡不影响这张单打到哪。
-- amount = 本次带走的可提现返利 commission - 扣回的已作废返利 clawback；打款时财务填代扣税额，实发 = amount - tax。
CREATE TABLE referral_withdrawals (
    id                     BIGSERIAL    PRIMARY KEY,
    tenant_id              BIGINT       NOT NULL REFERENCES tenants(id),
    user_id                BIGINT       NOT NULL REFERENCES users(id),
    commission_minor       BIGINT       NOT NULL CHECK (commission_minor > 0),
    clawback_minor         BIGINT       NOT NULL DEFAULT 0 CHECK (clawback_minor >= 0),
    amount_minor           BIGINT       NOT NULL CHECK (amount_minor > 0),
    status                 VARCHAR(16)  NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'REJECTED', 'PAID')),
    bank_name              VARCHAR(40)  NOT NULL,
    card_number_ciphertext BYTEA        NOT NULL,
    card_last4             VARCHAR(4)   NOT NULL,
    -- 申请时同意的《推广协议》版本（含提现与扣回规则）。
    agreement_version      VARCHAR(20)  NOT NULL,
    review_note            VARCHAR(200),
    reviewed_by            BIGINT       REFERENCES users(id),
    reviewed_at            TIMESTAMPTZ,
    tax_minor              BIGINT       CHECK (tax_minor >= 0),
    net_minor              BIGINT,
    paid_reference         VARCHAR(100),
    paid_date              DATE,
    created_at             TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CHECK (amount_minor = commission_minor - clawback_minor),
    CHECK (status <> 'PAID' OR (tax_minor IS NOT NULL AND tax_minor <= amount_minor AND net_minor = amount_minor - tax_minor
                                AND paid_reference IS NOT NULL AND paid_date IS NOT NULL))
);
-- 一个人同时只能有一张待审核的提现单。
CREATE UNIQUE INDEX uk_referral_withdrawals_pending ON referral_withdrawals (tenant_id, user_id) WHERE status = 'PENDING';
CREATE INDEX idx_referral_withdrawals_user ON referral_withdrawals (tenant_id, user_id, created_at DESC);

-- withdrawal_id：这笔返利被哪张提现单带走；驳回时清空，打款后记 withdrawn_at。
-- 已打款的返利再作废就成了欠款，由下一张提现单扣回（clawback_withdrawal_id），驳回时清空。
ALTER TABLE referral_commissions
    ADD COLUMN withdrawal_id          BIGINT REFERENCES referral_withdrawals(id),
    ADD COLUMN withdrawn_at           TIMESTAMPTZ,
    ADD COLUMN clawback_withdrawal_id BIGINT REFERENCES referral_withdrawals(id),
    ADD COLUMN void_note              VARCHAR(200),
    ADD COLUMN voided_by              BIGINT REFERENCES users(id),
    ADD COLUMN voided_at              TIMESTAMPTZ,
    ADD CHECK (withdrawn_at IS NULL OR withdrawal_id IS NOT NULL),
    ADD CHECK (clawback_withdrawal_id IS NULL OR (status = 'VOIDED' AND withdrawn_at IS NOT NULL)),
    ADD CHECK (status <> 'VOIDED' OR (void_note IS NOT NULL AND voided_at IS NOT NULL));
CREATE INDEX idx_referral_commissions_withdrawal ON referral_commissions (tenant_id, withdrawal_id);

DO $$ DECLARE table_name TEXT; BEGIN
 FOREACH table_name IN ARRAY ARRAY['bank_cards', 'referral_withdrawals'] LOOP
  EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY', table_name);
  EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY', table_name);
  EXECUTE format('CREATE POLICY tenant_isolation ON %I USING (tenant_id = NULLIF(current_setting(''app.tenant_id'',true),'''')::bigint) WITH CHECK (tenant_id = NULLIF(current_setting(''app.tenant_id'',true),'''')::bigint)', table_name);
  EXECUTE format('GRANT SELECT, INSERT, UPDATE ON %I TO growth_app', table_name);
 END LOOP;
END $$;
GRANT USAGE, SELECT ON SEQUENCE referral_withdrawals_id_seq TO growth_app;

-- 绑卡核验、平台查看完整卡号、审核打款、作废返利都记进平台管理审计表。
ALTER TABLE platform_management_audit DROP CONSTRAINT IF EXISTS platform_management_audit_target_type_check;
ALTER TABLE platform_management_audit ADD CONSTRAINT platform_management_audit_target_type_check
    CHECK (target_type IN ('INFORMATION', 'MERCHANT', 'IDENTITY', 'SETTLEMENT_ACCOUNT', 'USER',
                           'BANK_CARD', 'REFERRAL_WITHDRAWAL', 'REFERRAL_COMMISSION'));
