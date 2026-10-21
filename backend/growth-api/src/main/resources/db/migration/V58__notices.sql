-- 站内通知：发给商户里的具体账号（目前是管理员）。按租户行级隔离，只能看自己商户的。
-- 同一账号同一事件只记一条：dedupe_key 由发通知的业务给出，任务重试也不会重复。
CREATE TABLE notices (
    id         BIGSERIAL    PRIMARY KEY,
    tenant_id  BIGINT       NOT NULL REFERENCES tenants(id),
    user_id    BIGINT       NOT NULL REFERENCES users(id),
    category   VARCHAR(30)  NOT NULL,
    title      VARCHAR(120) NOT NULL,
    body       VARCHAR(500) NOT NULL,
    link       VARCHAR(300),
    dedupe_key VARCHAR(160) NOT NULL,
    read_at    TIMESTAMPTZ,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, user_id, dedupe_key)
);
CREATE INDEX idx_notices_inbox ON notices (tenant_id, user_id, created_at DESC, id DESC);
CREATE INDEX idx_notices_unread ON notices (tenant_id, user_id) WHERE read_at IS NULL;

ALTER TABLE notices ENABLE ROW LEVEL SECURITY;
ALTER TABLE notices FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON notices
    USING (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::bigint)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::bigint);
GRANT SELECT, INSERT, UPDATE ON notices TO growth_app;
GRANT USAGE, SELECT ON SEQUENCE notices_id_seq TO growth_app;
