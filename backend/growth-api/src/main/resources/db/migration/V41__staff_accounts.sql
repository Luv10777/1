-- ============================================================
-- V41 · 员工子账号、门店范围、声音样本按门店开放
--
-- 角色跟人走：users.role 是商户级的 OWNER / STAFF。
--   OWNER 自动能进本商户的全部门店；STAFF 只能进 store_members 里列出的门店。
-- 声音样本归商户所有，能在哪些门店使用由 voice_sample_stores 决定。
-- ============================================================

-- ---------- 1. 账号角色 ----------
-- 此前产品里没有办法给商户加第二个人，现有账号都是各自商户的注册人或管理员建的全权账号，
-- 所以统一记为 OWNER。之后的店员由老板在员工管理里添加，写入时显式指定 STAFF。
ALTER TABLE users ADD COLUMN role VARCHAR(20) NOT NULL DEFAULT 'OWNER';
ALTER TABLE users ADD CONSTRAINT ck_users_role CHECK (role IN ('OWNER', 'STAFF'));

-- ---------- 2. 门店成员名单只为店员保留 ----------
-- 老板不再需要名单，名单上也不再写角色。逐租户执行，FORCE RLS 对非超级用户的表属主同样生效。
DO $$
DECLARE
    tenant_record RECORD;
    previous_tenant TEXT := current_setting('app.tenant_id', true);
BEGIN
    FOR tenant_record IN SELECT id FROM tenants LOOP
        PERFORM set_config('app.tenant_id', tenant_record.id::text, true);
        DELETE FROM store_members sm USING users u
         WHERE sm.tenant_id = tenant_record.id AND u.id = sm.user_id AND u.role = 'OWNER';
    END LOOP;
    PERFORM set_config('app.tenant_id', COALESCE(previous_tenant, ''), true);
END
$$;
ALTER TABLE store_members DROP COLUMN role;

-- ---------- 3. 声音样本按门店开放 ----------
-- 外键检查不受行级安全约束，用复合外键保证样本只能开放给同一商户的门店。
ALTER TABLE stores ADD CONSTRAINT uk_stores_tenant_id UNIQUE (tenant_id, id);
ALTER TABLE voice_samples ADD CONSTRAINT uk_voice_samples_tenant_id UNIQUE (tenant_id, id);

CREATE TABLE voice_sample_stores (
    id          BIGSERIAL    PRIMARY KEY,
    tenant_id   BIGINT       NOT NULL REFERENCES tenants(id),
    sample_id   BIGINT       NOT NULL,
    store_id    BIGINT       NOT NULL,
    granted_by  BIGINT       REFERENCES users(id),
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uk_voice_sample_stores UNIQUE (sample_id, store_id),
    CONSTRAINT fk_voice_sample_stores_sample FOREIGN KEY (tenant_id, sample_id) REFERENCES voice_samples (tenant_id, id),
    CONSTRAINT fk_voice_sample_stores_store FOREIGN KEY (tenant_id, store_id) REFERENCES stores (tenant_id, id)
);
CREATE INDEX idx_voice_sample_stores_store ON voice_sample_stores (tenant_id, store_id, sample_id);

ALTER TABLE voice_sample_stores ENABLE ROW LEVEL SECURITY;
ALTER TABLE voice_sample_stores FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON voice_sample_stores
    USING (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::bigint)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::bigint);

GRANT SELECT, INSERT, UPDATE, DELETE ON voice_sample_stores TO growth_app;
GRANT USAGE, SELECT ON SEQUENCE voice_sample_stores_id_seq TO growth_app;

-- 现有样本保持原来的可用范围：只开放给上传时所在的门店。
-- voice_samples.store_id 保留为"上传时所在的门店"，不再决定谁能用。
DO $$
DECLARE
    tenant_record RECORD;
    previous_tenant TEXT := current_setting('app.tenant_id', true);
BEGIN
    FOR tenant_record IN SELECT id FROM tenants LOOP
        PERFORM set_config('app.tenant_id', tenant_record.id::text, true);
        INSERT INTO voice_sample_stores (tenant_id, sample_id, store_id, granted_by, created_at)
        SELECT tenant_id, id, store_id, consent_by, created_at
          FROM voice_samples
         WHERE tenant_id = tenant_record.id AND status <> 'DELETED';
    END LOOP;
    PERFORM set_config('app.tenant_id', COALESCE(previous_tenant, ''), true);
END
$$;
