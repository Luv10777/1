-- V79 · 用户 ID 与服务商
--
-- 1. 每个账号一个用户 ID（uid）：8 位随机数字，注册时由数据库默认值生成，永不重复，展示给用户；服务商按它添加名下商户。
--    users 是系统级表，不受租户 RLS 约束，生成函数能看到全部账号。并发注册撞到同一个号的概率极低，撞到时唯一约束拒绝，重试注册即可。
CREATE FUNCTION next_user_uid() RETURNS BIGINT LANGUAGE plpgsql VOLATILE AS $$
DECLARE candidate BIGINT;
BEGIN
    LOOP
        candidate := 10000000 + floor(random() * 90000000)::BIGINT;
        IF NOT EXISTS (SELECT 1 FROM users WHERE uid = candidate) THEN RETURN candidate; END IF;
    END LOOP;
END $$;

ALTER TABLE users ADD COLUMN uid BIGINT;
-- 已有账号逐行补号：每行一条 UPDATE，后面的行能看到前面已经分到的号。
DO $$ DECLARE r RECORD; BEGIN
    FOR r IN SELECT id FROM users ORDER BY id LOOP
        UPDATE users SET uid = next_user_uid() WHERE id = r.id;
    END LOOP;
END $$;
ALTER TABLE users ALTER COLUMN uid SET DEFAULT next_user_uid();
ALTER TABLE users ALTER COLUMN uid SET NOT NULL;
ALTER TABLE users ADD CONSTRAINT uk_users_uid UNIQUE (uid);
ALTER TABLE users ADD CONSTRAINT ck_users_uid CHECK (uid BETWEEN 10000000 AND 99999999);

-- 2. 服务商：平台线下收款后在后台给服务商发烟火、分激活码额度；服务商在自己的后台（partner.yifangzhi.com）
--    把烟火分给名下商户、在额度内生成激活码。
--
-- 以下几张表和 billing_redeem_codes、billing_plans 一样是平台级数据，不属于任何商户，不开行级隔离；
-- 只有服务层能读写：平台运营名单管全部服务商，服务商账号只能读写自己那一家。分给商户的烟火写进商户自己的账本，账本照旧受 RLS 约束。
CREATE TABLE partners (
    id                BIGSERIAL    PRIMARY KEY,
    name              VARCHAR(80)  NOT NULL,
    contact           VARCHAR(120),
    status            VARCHAR(12)  NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'DISABLED')),
    credits_available BIGINT       NOT NULL DEFAULT 0 CHECK (credits_available >= 0),
    created_by        BIGINT       NOT NULL REFERENCES users(id),
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- 服务商后台的登录账号：就是已有的一方志账号，一个账号最多属于一家服务商。
CREATE TABLE partner_members (
    user_id    BIGINT       PRIMARY KEY REFERENCES users(id),
    partner_id BIGINT       NOT NULL REFERENCES partners(id),
    created_by BIGINT       NOT NULL REFERENCES users(id),
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX idx_partner_members_partner ON partner_members (partner_id);

-- 激活码额度按套餐标识分开设（月卡、季卡、年卡各一个标识）：quota 是平台给的总数，used 是已经生成的个数。
CREATE TABLE partner_code_quotas (
    partner_id BIGINT       NOT NULL REFERENCES partners(id),
    plan_code  VARCHAR(60)  NOT NULL,
    quota      INT          NOT NULL CHECK (quota BETWEEN 0 AND 1000000),
    used       INT          NOT NULL DEFAULT 0 CHECK (used >= 0 AND used <= quota),
    updated_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    PRIMARY KEY (partner_id, plan_code)
);

-- 名下商户：第一次归属为准，之后别家服务商的激活码照样能用但不改归属；只有平台能改。
-- source：UID（服务商按用户 ID 添加）、CODE（兑换了服务商生成的激活码）、PLATFORM（平台指定）。
CREATE TABLE partner_tenants (
    tenant_id  BIGINT       PRIMARY KEY REFERENCES tenants(id),
    partner_id BIGINT       NOT NULL REFERENCES partners(id),
    source     VARCHAR(12)  NOT NULL CHECK (source IN ('UID', 'CODE', 'PLATFORM')),
    bound_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    changed_by BIGINT       REFERENCES users(id)
);
CREATE INDEX idx_partner_tenants_partner ON partner_tenants (partner_id, bound_at DESC);

-- 服务商烟火流水：平台发放（GRANT）为正，分给名下商户（TRANSFER）为负。event_key 去重，重复提交只记一次。
CREATE TABLE partner_credit_ledger (
    id              BIGSERIAL    PRIMARY KEY,
    partner_id      BIGINT       NOT NULL REFERENCES partners(id),
    event_key       VARCHAR(120) NOT NULL UNIQUE,
    type            VARCHAR(12)  NOT NULL CHECK (type IN ('GRANT', 'TRANSFER')),
    delta           BIGINT       NOT NULL CHECK (delta <> 0),
    available_after BIGINT       NOT NULL,
    tenant_id       BIGINT       REFERENCES tenants(id),
    note            VARCHAR(200),
    actor_user_id   BIGINT       NOT NULL REFERENCES users(id),
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CHECK ((type = 'TRANSFER') = (tenant_id IS NOT NULL)),
    CHECK ((type = 'GRANT') = (delta > 0))
);
CREATE INDEX idx_partner_credit_ledger_partner ON partner_credit_ledger (partner_id, id DESC);

-- 服务商生成的激活码记下服务商，兑换后据此把商户归到这家服务商名下。
ALTER TABLE billing_redeem_codes ADD COLUMN partner_id BIGINT REFERENCES partners(id);
ALTER TABLE billing_redeem_codes ADD CONSTRAINT billing_redeem_codes_partner_check CHECK (partner_id IS NULL OR kind = 'MEMBERSHIP');
CREATE INDEX idx_billing_redeem_codes_partner ON billing_redeem_codes (partner_id, created_at DESC) WHERE partner_id IS NOT NULL;

GRANT SELECT, INSERT, UPDATE ON partners, partner_code_quotas, partner_tenants TO growth_app;
GRANT SELECT, INSERT, DELETE ON partner_members TO growth_app;
GRANT SELECT, INSERT ON partner_credit_ledger TO growth_app;
GRANT DELETE ON partner_tenants TO growth_app;
GRANT USAGE, SELECT ON SEQUENCE partners_id_seq, partner_credit_ledger_id_seq TO growth_app;
