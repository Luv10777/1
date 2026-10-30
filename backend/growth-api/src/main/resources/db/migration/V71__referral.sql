-- V71 · 邀请返利第一期：邀请码、注册归属、充值返利与返利比例。提现在后续版本。
--
-- 邀请人和被邀请人是两个商户，数据按归属分开放，各自行级隔离：
--   referral_codes / referral_invitees / referral_commissions 属于邀请人所在商户；
--   referral_attributions 属于被邀请商户，只记「我的邀请人是谁」。
-- 注册时按邀请码查人要跨商户，用 SECURITY DEFINER 函数只交出用户和商户编号。

CREATE TABLE referral_codes (
    user_id           BIGINT      PRIMARY KEY REFERENCES users(id),
    tenant_id         BIGINT      NOT NULL REFERENCES tenants(id),
    code              VARCHAR(16) NOT NULL UNIQUE,
    agreement_version VARCHAR(20) NOT NULL,
    agreed_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE referral_attributions (
    tenant_id          BIGINT      PRIMARY KEY REFERENCES tenants(id),
    referrer_user_id   BIGINT      NOT NULL REFERENCES users(id),
    referrer_tenant_id BIGINT      NOT NULL REFERENCES tenants(id),
    code               VARCHAR(16) NOT NULL,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE referral_invitees (
    invitee_tenant_id    BIGINT      PRIMARY KEY REFERENCES tenants(id),
    tenant_id            BIGINT      NOT NULL REFERENCES tenants(id),
    referrer_user_id     BIGINT      NOT NULL REFERENCES users(id),
    invitee_phone_masked VARCHAR(20) NOT NULL,
    registered_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_referral_invitees_referrer ON referral_invitees (tenant_id, referrer_user_id, registered_at DESC);

-- 一笔充值只返一次：order_id 唯一。PENDING 在 available_at 之前算冻结中，之后算可提现。
CREATE TABLE referral_commissions (
    id                BIGSERIAL   PRIMARY KEY,
    tenant_id         BIGINT      NOT NULL REFERENCES tenants(id),
    referrer_user_id  BIGINT      NOT NULL REFERENCES users(id),
    invitee_tenant_id BIGINT      NOT NULL REFERENCES tenants(id),
    order_id          VARCHAR(36) NOT NULL UNIQUE,
    paid_minor        BIGINT      NOT NULL CHECK (paid_minor > 0),
    rate_bp           INT         NOT NULL CHECK (rate_bp BETWEEN 0 AND 10000),
    amount_minor      BIGINT      NOT NULL CHECK (amount_minor >= 0),
    status            VARCHAR(16) NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'VOIDED')),
    available_at      TIMESTAMPTZ NOT NULL,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_referral_commissions_referrer ON referral_commissions (tenant_id, referrer_user_id, created_at DESC);

DO $$ DECLARE table_name TEXT; BEGIN
 FOREACH table_name IN ARRAY ARRAY['referral_codes', 'referral_attributions', 'referral_invitees', 'referral_commissions'] LOOP
  EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY', table_name);
  EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY', table_name);
  EXECUTE format('CREATE POLICY tenant_isolation ON %I USING (tenant_id = NULLIF(current_setting(''app.tenant_id'',true),'''')::bigint) WITH CHECK (tenant_id = NULLIF(current_setting(''app.tenant_id'',true),'''')::bigint)', table_name);
 END LOOP;
END $$;

-- 返利比例是平台级配置，和 billing_plans 一样不分商户。只增不改，最新一行生效，历史即调整记录。
CREATE TABLE referral_rates (
    id         BIGSERIAL    PRIMARY KEY,
    rate_bp    INT          NOT NULL CHECK (rate_bp BETWEEN 0 AND 5000),
    note       VARCHAR(200) NOT NULL,
    created_by BIGINT       REFERENCES users(id),
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now()
);
INSERT INTO referral_rates (rate_bp, note) VALUES (1000, '上线默认 10%');

CREATE FUNCTION referral_code_owner(p_code VARCHAR)
RETURNS TABLE (user_id BIGINT, tenant_id BIGINT, active BOOLEAN)
LANGUAGE sql
STABLE
SECURITY DEFINER
SET search_path = public
AS $$
    SELECT c.user_id, c.tenant_id, u.status = 'ACTIVE'
    FROM referral_codes c
    JOIN users u ON u.id = c.user_id
    WHERE c.code = upper(p_code)
$$;
REVOKE ALL ON FUNCTION referral_code_owner(VARCHAR) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION referral_code_owner(VARCHAR) TO growth_app;

GRANT SELECT, INSERT ON referral_codes, referral_attributions, referral_invitees, referral_rates TO growth_app;
GRANT SELECT, INSERT, UPDATE ON referral_commissions TO growth_app;
GRANT USAGE, SELECT ON SEQUENCE referral_commissions_id_seq, referral_rates_id_seq TO growth_app;
