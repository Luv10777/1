-- ============================================================
-- V1 · 身份与租户（IAM）
--
-- 分层约定（整个项目都遵守，加新表前先读这段）：
--   系统级表 —— 不加 RLS。登录时还不知道租户是谁，加了就登不进来。
--     tenants / users / refresh_tokens / sms_codes / tasks
--   租户级表 —— 必须加 RLS。所有业务表都属于这一类。
--     见 V3__asset.sql 的写法，照抄即可。
-- ============================================================

-- 应用连接用的角色。故意不给 BYPASSRLS，也不是表 owner，
-- 这样即使业务代码忘了加租户条件，数据库也不会返回别家的数据。
-- 口令由 Flyway 占位符注入，取自 DB_PASSWORD 环境变量。
-- 不要把真实口令写进这个文件——它会进 Git。
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'growth_app') THEN
        CREATE ROLE growth_app LOGIN PASSWORD '${app_db_password}';
    ELSE
        ALTER ROLE growth_app WITH PASSWORD '${app_db_password}';
    END IF;
END
$$;

GRANT USAGE ON SCHEMA public TO growth_app;
ALTER DEFAULT PRIVILEGES IN SCHEMA public
    GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO growth_app;
ALTER DEFAULT PRIVILEGES IN SCHEMA public
    GRANT USAGE, SELECT ON SEQUENCES TO growth_app;

-- ---------- 租户 ----------
CREATE TABLE tenants (
    id          BIGSERIAL     PRIMARY KEY,
    name        VARCHAR(120)  NOT NULL,
    status      VARCHAR(20)   NOT NULL DEFAULT 'ACTIVE',
    created_at  TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ   NOT NULL DEFAULT now()
);

-- ---------- 用户 ----------
CREATE TABLE users (
    id          BIGSERIAL     PRIMARY KEY,
    tenant_id   BIGINT        NOT NULL REFERENCES tenants(id),
    phone       VARCHAR(20)   NOT NULL,
    name        VARCHAR(80),
    status      VARCHAR(20)   NOT NULL DEFAULT 'ACTIVE',
    last_login_at TIMESTAMPTZ,
    created_at  TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT uk_users_phone UNIQUE (phone)
);
CREATE INDEX idx_users_tenant ON users(tenant_id);

-- ---------- 刷新令牌 ----------
-- 只存哈希，不存明文。过期时间用 refresh 的 TTL（不是 access 的）。
CREATE TABLE refresh_tokens (
    id          BIGSERIAL     PRIMARY KEY,
    user_id     BIGINT        NOT NULL REFERENCES users(id),
    token_hash  VARCHAR(64)   NOT NULL,
    device_id   VARCHAR(120),
    ip_address  VARCHAR(64),
    user_agent  VARCHAR(400),
    status      VARCHAR(20)   NOT NULL DEFAULT 'ACTIVE',
    expires_at  TIMESTAMPTZ   NOT NULL,
    created_at  TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT uk_refresh_hash UNIQUE (token_hash)
);
CREATE INDEX idx_refresh_user ON refresh_tokens(user_id, status);

-- ---------- 短信验证码 ----------
CREATE TABLE sms_codes (
    id          BIGSERIAL     PRIMARY KEY,
    phone       VARCHAR(20)   NOT NULL,
    code_hash   VARCHAR(64)   NOT NULL,
    purpose     VARCHAR(30)   NOT NULL DEFAULT 'LOGIN',
    ip_address  VARCHAR(64),
    attempts    INT           NOT NULL DEFAULT 0,
    consumed_at TIMESTAMPTZ,
    expires_at  TIMESTAMPTZ   NOT NULL,
    created_at  TIMESTAMPTZ   NOT NULL DEFAULT now()
);
CREATE INDEX idx_sms_phone_time ON sms_codes(phone, created_at DESC);
