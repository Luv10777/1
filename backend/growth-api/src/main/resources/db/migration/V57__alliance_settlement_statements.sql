-- 联盟券结算账户与月度对账单。设计见 docs/alliance-settlement.md。
-- 都属于供券商户自己的租户：生成、确认、标记打款只动一家商户，可以放在一个事务里。

CREATE TABLE alliance_settlement_accounts (
    id                    BIGSERIAL PRIMARY KEY,
    tenant_id             BIGINT       NOT NULL UNIQUE REFERENCES tenants(id),
    account_type          VARCHAR(20)  NOT NULL CHECK (account_type IN ('CORPORATE', 'INDIVIDUAL_OWNER')),
    account_name          VARCHAR(120) NOT NULL,
    bank_name             VARCHAR(80)  NOT NULL,
    branch_name           VARCHAR(120) NOT NULL,
    -- 银行账号用 AES-256-GCM 加密，附加数据带商户号；列表只显示后 4 位。
    account_number_ciphertext BYTEA    NOT NULL,
    account_number_last4  VARCHAR(4)   NOT NULL,
    finance_contact_name  VARCHAR(40)  NOT NULL,
    finance_contact_phone VARCHAR(20)  NOT NULL,
    status                VARCHAR(20)  NOT NULL DEFAULT 'PENDING_VERIFY'
        CHECK (status IN ('PENDING_VERIFY', 'VERIFIED', 'REJECTED')),
    review_note           VARCHAR(300),
    reviewed_by           BIGINT,
    reviewed_at           TIMESTAMPTZ,
    updated_by            BIGINT       NOT NULL REFERENCES users(id),
    created_at            TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at            TIMESTAMPTZ  NOT NULL DEFAULT now(),
    version               BIGINT       NOT NULL DEFAULT 0
);

CREATE TABLE alliance_statements (
    id                    BIGSERIAL PRIMARY KEY,
    tenant_id             BIGINT       NOT NULL REFERENCES tenants(id),
    period                VARCHAR(7)   NOT NULL CHECK (period ~ '^[0-9]{4}-[0-9]{2}$'),
    item_count            INT          NOT NULL CHECK (item_count > 0),
    face_total_minor      BIGINT       NOT NULL CHECK (face_total_minor >= 0),
    settle_total_minor    BIGINT       NOT NULL CHECK (settle_total_minor >= 0),
    status                VARCHAR(20)  NOT NULL DEFAULT 'PENDING_CONFIRM'
        CHECK (status IN ('PENDING_CONFIRM', 'OBJECTED', 'CONFIRMED', 'PAID', 'VOID')),
    confirm_due_at        TIMESTAMPTZ  NOT NULL,
    confirmed_by          BIGINT,
    confirmed_at          TIMESTAMPTZ,
    auto_confirmed        BOOLEAN      NOT NULL DEFAULT false,
    objection_note        VARCHAR(300),
    objected_at           TIMESTAMPTZ,
    platform_note         VARCHAR(300),
    -- 确认时抄下的收款账户：之后商户改账户，不影响这张对账单打到哪。
    payee_account_type    VARCHAR(20),
    payee_account_name    VARCHAR(120),
    payee_bank_name       VARCHAR(80),
    payee_branch_name     VARCHAR(120),
    payee_account_ciphertext BYTEA,
    payee_account_last4   VARCHAR(4),
    paid_reference        VARCHAR(150),
    paid_date             DATE,
    paid_by               BIGINT,
    paid_at               TIMESTAMPTZ,
    created_at            TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at            TIMESTAMPTZ  NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, id),
    CHECK (status NOT IN ('CONFIRMED', 'PAID') OR payee_account_ciphertext IS NOT NULL)
);
-- 同一商户同一月份只有一张有效对账单；作废的不算。
CREATE UNIQUE INDEX uk_alliance_statements_period ON alliance_statements (tenant_id, period) WHERE status <> 'VOID';
CREATE INDEX idx_alliance_statements_list ON alliance_statements (tenant_id, period DESC, id DESC);
CREATE INDEX idx_alliance_statements_due ON alliance_statements (tenant_id, confirm_due_at) WHERE status = 'PENDING_CONFIRM';

ALTER TABLE alliance_settlements ADD COLUMN statement_id BIGINT;
ALTER TABLE alliance_settlements
    ADD CONSTRAINT fk_alliance_settlements_statement FOREIGN KEY (tenant_id, statement_id) REFERENCES alliance_statements (tenant_id, id);
CREATE INDEX idx_alliance_settlements_statement ON alliance_settlements (tenant_id, statement_id);

DO $$
DECLARE
    table_name text;
BEGIN
    FOREACH table_name IN ARRAY ARRAY['alliance_settlement_accounts', 'alliance_statements']
    LOOP
        EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY', table_name);
        EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY', table_name);
        EXECUTE format(
            'CREATE POLICY tenant_isolation ON %I USING (tenant_id = NULLIF(current_setting(''app.tenant_id'', true), '''')::bigint) WITH CHECK (tenant_id = NULLIF(current_setting(''app.tenant_id'', true), '''')::bigint)',
            table_name);
        EXECUTE format('GRANT SELECT, INSERT, UPDATE ON %I TO growth_app', table_name);
    END LOOP;
END $$;
GRANT USAGE, SELECT ON SEQUENCE alliance_settlement_accounts_id_seq, alliance_statements_id_seq TO growth_app;

-- 平台查看完整收款账号也留审计记录，沿用平台管理的审计表。
ALTER TABLE platform_management_audit DROP CONSTRAINT IF EXISTS platform_management_audit_target_type_check;
ALTER TABLE platform_management_audit ADD CONSTRAINT platform_management_audit_target_type_check
    CHECK (target_type IN ('INFORMATION', 'MERCHANT', 'IDENTITY', 'SETTLEMENT_ACCOUNT'));
