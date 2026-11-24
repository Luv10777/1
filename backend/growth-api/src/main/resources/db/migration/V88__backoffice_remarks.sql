-- V88 · 后台备注名
--
-- 管理后台给账号加备注名，全体管理员看到同一个；服务商给名下商户加备注名，整个服务商团队共用。
-- 两张表沿用 V79/V83 的平台级数据模型，不属于任何商户，不开行级隔离；只有服务层能读写：
-- 前者由 BackofficeAccess 的 USERS 权限把关，后者先核对商户在该服务商名下且成员有「商户」权限。
-- 服务商备注按 (服务商, 商户) 记：商户改归属后，新服务商看不到上一家写的备注。
CREATE TABLE platform_user_remarks (
    user_id BIGINT PRIMARY KEY REFERENCES users(id),
    remark VARCHAR(30) NOT NULL CHECK (length(remark) > 0),
    updated_by BIGINT NOT NULL REFERENCES users(id),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE TABLE partner_tenant_remarks (
    partner_id BIGINT NOT NULL REFERENCES partners(id),
    tenant_id BIGINT NOT NULL REFERENCES tenants(id),
    remark VARCHAR(30) NOT NULL CHECK (length(remark) > 0),
    updated_by BIGINT NOT NULL REFERENCES users(id),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (partner_id, tenant_id)
);
GRANT SELECT, INSERT, UPDATE, DELETE ON platform_user_remarks, partner_tenant_remarks TO growth_app;
