-- 每次登录（验证码、密码、微信）记一条，用于管理后台「近 7 天登录」和上一个 7 天对比。
-- 登录时还不知道租户，因此和 users、wechat_accounts 一样不启用 RLS；只由登录流程写入，平台运营汇总读取。
CREATE TABLE login_events (
    id         BIGSERIAL   PRIMARY KEY,
    user_id    BIGINT      NOT NULL REFERENCES users(id),
    tenant_id  BIGINT      NOT NULL REFERENCES tenants(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_login_events_created ON login_events (created_at);
GRANT SELECT, INSERT ON login_events TO growth_app;
GRANT USAGE, SELECT ON SEQUENCE login_events_id_seq TO growth_app;
