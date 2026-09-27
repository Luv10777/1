-- 租户配额管理表
CREATE TABLE tenant_quotas (
    tenant_id BIGINT PRIMARY KEY REFERENCES tenants(id) ON DELETE CASCADE,
    storage_bytes_used BIGINT NOT NULL DEFAULT 0,
    storage_bytes_limit BIGINT NOT NULL DEFAULT 10737418240, -- 10GB
    image_count INT NOT NULL DEFAULT 0,
    image_count_limit INT NOT NULL DEFAULT 1000,
    concurrent_limit INT NOT NULL DEFAULT 20,
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);

-- 行级安全策略
ALTER TABLE tenant_quotas ENABLE ROW LEVEL SECURITY;
ALTER TABLE tenant_quotas FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_quotas_isolation ON tenant_quotas
    USING (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::BIGINT)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::BIGINT);

-- 索引
CREATE INDEX idx_tenant_quotas_tenant_id ON tenant_quotas(tenant_id);

COMMENT ON TABLE tenant_quotas IS '租户资源配额管理';
COMMENT ON COLUMN tenant_quotas.storage_bytes_used IS '已使用存储空间(字节)';
COMMENT ON COLUMN tenant_quotas.storage_bytes_limit IS '存储空间配额(字节)';
COMMENT ON COLUMN tenant_quotas.image_count IS '已生成图片数量';
COMMENT ON COLUMN tenant_quotas.image_count_limit IS '图片数量配额';
COMMENT ON COLUMN tenant_quotas.concurrent_limit IS '最大并发任务数';
GRANT SELECT, INSERT, UPDATE, DELETE ON tenant_quotas TO growth_app;
