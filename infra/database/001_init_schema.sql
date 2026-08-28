-- 梧曜星枢数据库初始化脚本
-- PostgreSQL 14+

-- 租户表
CREATE TABLE tenants (
    id BIGSERIAL PRIMARY KEY,
    tenant_code VARCHAR(32) UNIQUE NOT NULL,
    name VARCHAR(100) NOT NULL,
    status VARCHAR(20) DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'SUSPENDED', 'DELETED')),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    deleted_at TIMESTAMP
);

CREATE INDEX idx_tenants_code ON tenants(tenant_code);
CREATE INDEX idx_tenants_status ON tenants(status) WHERE deleted_at IS NULL;

-- 用户表
CREATE TABLE users (
    id BIGSERIAL PRIMARY KEY,
    phone VARCHAR(20) UNIQUE NOT NULL,
    name VARCHAR(50),
    avatar_url VARCHAR(500),
    status VARCHAR(20) DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'DISABLED', 'DELETED')),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    deleted_at TIMESTAMP
);

CREATE INDEX idx_users_phone ON users(phone);
CREATE INDEX idx_users_status ON users(status) WHERE deleted_at IS NULL;

-- 租户成员表
CREATE TABLE tenant_members (
    id BIGSERIAL PRIMARY KEY,
    tenant_id BIGINT NOT NULL REFERENCES tenants(id),
    user_id BIGINT NOT NULL REFERENCES users(id),
    role_id BIGINT,
    status VARCHAR(20) DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'DISABLED')),
    joined_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(tenant_id, user_id)
);

CREATE INDEX idx_tenant_members_tenant ON tenant_members(tenant_id);
CREATE INDEX idx_tenant_members_user ON tenant_members(user_id);

-- 角色表
CREATE TABLE roles (
    id BIGSERIAL PRIMARY KEY,
    tenant_id BIGINT REFERENCES tenants(id),
    code VARCHAR(50) NOT NULL,
    name VARCHAR(50) NOT NULL,
    description TEXT,
    is_system BOOLEAN DEFAULT FALSE,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(tenant_id, code)
);

CREATE INDEX idx_roles_tenant ON roles(tenant_id);

-- 权限表
CREATE TABLE permissions (
    id BIGSERIAL PRIMARY KEY,
    code VARCHAR(100) UNIQUE NOT NULL,
    name VARCHAR(100) NOT NULL,
    resource VARCHAR(50) NOT NULL,
    action VARCHAR(50) NOT NULL,
    description TEXT,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_permissions_resource ON permissions(resource);

-- 角色权限关联表
CREATE TABLE role_permissions (
    id BIGSERIAL PRIMARY KEY,
    role_id BIGINT NOT NULL REFERENCES roles(id),
    permission_id BIGINT NOT NULL REFERENCES permissions(id),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(role_id, permission_id)
);

CREATE INDEX idx_role_permissions_role ON role_permissions(role_id);

-- 商家表
CREATE TABLE merchants (
    id BIGSERIAL PRIMARY KEY,
    tenant_id BIGINT NOT NULL REFERENCES tenants(id),
    code VARCHAR(32) UNIQUE NOT NULL,
    name VARCHAR(100) NOT NULL,
    industry VARCHAR(50),
    logo_url VARCHAR(500),
    contact_name VARCHAR(50),
    contact_phone VARCHAR(20),
    contact_email VARCHAR(100),
    completeness INTEGER DEFAULT 0 CHECK (completeness BETWEEN 0 AND 100),
    status VARCHAR(20) DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'SUSPENDED', 'DELETED')),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    deleted_at TIMESTAMP
);

CREATE INDEX idx_merchants_tenant ON merchants(tenant_id);
CREATE INDEX idx_merchants_code ON merchants(code);
CREATE INDEX idx_merchants_status ON merchants(status) WHERE deleted_at IS NULL;

-- 门店表
CREATE TABLE stores (
    id BIGSERIAL PRIMARY KEY,
    tenant_id BIGINT NOT NULL REFERENCES tenants(id),
    merchant_id BIGINT NOT NULL REFERENCES merchants(id),
    code VARCHAR(32) NOT NULL,
    name VARCHAR(100) NOT NULL,
    address VARCHAR(200),
    city VARCHAR(50),
    province VARCHAR(50),
    latitude DECIMAL(10, 7),
    longitude DECIMAL(10, 7),
    contact_phone VARCHAR(20),
    business_hours JSONB,
    status VARCHAR(20) DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'SUSPENDED', 'DELETED')),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    deleted_at TIMESTAMP,
    UNIQUE(merchant_id, code)
);

CREATE INDEX idx_stores_tenant ON stores(tenant_id);
CREATE INDEX idx_stores_merchant ON stores(merchant_id);
CREATE INDEX idx_stores_status ON stores(status) WHERE deleted_at IS NULL;

-- 短信验证码记录表
CREATE TABLE sms_verification_records (
    id BIGSERIAL PRIMARY KEY,
    phone VARCHAR(20) NOT NULL,
    code_hash VARCHAR(64) NOT NULL,
    purpose VARCHAR(20) DEFAULT 'LOGIN' CHECK (purpose IN ('LOGIN', 'REGISTER', 'RESET_PASSWORD')),
    ip_address VARCHAR(45),
    device_id VARCHAR(100),
    status VARCHAR(20) DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'VERIFIED', 'EXPIRED', 'FAILED')),
    verified_at TIMESTAMP,
    expires_at TIMESTAMP NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_sms_phone ON sms_verification_records(phone, created_at);
CREATE INDEX idx_sms_status ON sms_verification_records(status, expires_at);

-- Refresh Token表
CREATE TABLE refresh_tokens (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES users(id),
    token_hash VARCHAR(64) UNIQUE NOT NULL,
    device_id VARCHAR(100),
    ip_address VARCHAR(45),
    user_agent TEXT,
    status VARCHAR(20) DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'REVOKED', 'EXPIRED')),
    expires_at TIMESTAMP NOT NULL,
    revoked_at TIMESTAMP,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_refresh_tokens_user ON refresh_tokens(user_id);
CREATE INDEX idx_refresh_tokens_hash ON refresh_tokens(token_hash);
CREATE INDEX idx_refresh_tokens_status ON refresh_tokens(status, expires_at);

-- 注意：audit_logs 表已移至 005_video_workflow_support.sql，避免重复定义

-- 插入系统权限
INSERT INTO permissions (code, name, resource, action, description) VALUES
('merchant.view', '查看商家', 'merchant', 'view', '查看商家列表和详情'),
('merchant.create', '创建商家', 'merchant', 'create', '创建新商家'),
('merchant.edit', '编辑商家', 'merchant', 'edit', '编辑商家信息'),
('merchant.delete', '删除商家', 'merchant', 'delete', '删除商家'),
('merchant.toggle_status', '启停商家', 'merchant', 'toggle_status', '启用或停用商家'),
('store.view', '查看门店', 'store', 'view', '查看门店列表和详情'),
('store.create', '创建门店', 'store', 'create', '创建新门店'),
('store.edit', '编辑门店', 'store', 'edit', '编辑门店信息'),
('store.delete', '删除门店', 'store', 'delete', '删除门店'),
('store.toggle_status', '启停门店', 'store', 'toggle_status', '启用或停用门店');

-- 插入系统角色（超级管理员）
INSERT INTO roles (tenant_id, code, name, description, is_system) VALUES
(NULL, 'SUPER_ADMIN', '超级管理员', '系统超级管理员，拥有所有权限', TRUE),
(NULL, 'TENANT_ADMIN', '租户管理员', '租户管理员，可管理租户内所有资源', TRUE),
(NULL, 'MERCHANT_ADMIN', '商家管理员', '商家管理员，可管理商家和门店', TRUE),
(NULL, 'STORE_STAFF', '门店员工', '门店员工，可查看和使用门店功能', TRUE);

-- 授予系统角色权限（这里只是示例，实际应该根据角色授予相应权限）
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r CROSS JOIN permissions p WHERE r.code = 'SUPER_ADMIN';

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r, permissions p
WHERE r.code = 'MERCHANT_ADMIN' AND p.resource IN ('merchant', 'store');

COMMENT ON TABLE tenants IS '租户表';
COMMENT ON TABLE users IS '用户表';
COMMENT ON TABLE tenant_members IS '租户成员表';
COMMENT ON TABLE roles IS '角色表';
COMMENT ON TABLE permissions IS '权限表';
COMMENT ON TABLE role_permissions IS '角色权限关联表';
COMMENT ON TABLE merchants IS '商家表';
COMMENT ON TABLE stores IS '门店表';
COMMENT ON TABLE sms_verification_records IS '短信验证码记录表';
COMMENT ON TABLE refresh_tokens IS 'Refresh Token表';
COMMENT ON TABLE audit_logs IS '审计日志表';
