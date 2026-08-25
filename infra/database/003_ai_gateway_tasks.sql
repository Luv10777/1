-- 阶段四：AI模型网关与任务中心数据表

-- AI提供商配置表
CREATE TABLE ai_providers (
    id BIGSERIAL PRIMARY KEY,
    code VARCHAR(50) UNIQUE NOT NULL,
    name VARCHAR(100) NOT NULL,
    type VARCHAR(20) NOT NULL CHECK (type IN ('TEXT', 'IMAGE', 'VIDEO', 'AUDIO', 'MULTIMODAL')),
    base_url VARCHAR(500) NOT NULL,
    api_key_encrypted TEXT NOT NULL,
    rate_limit_per_minute INTEGER DEFAULT 60,
    rate_limit_per_day INTEGER DEFAULT 10000,
    timeout_seconds INTEGER DEFAULT 300,
    retry_times INTEGER DEFAULT 3,
    status VARCHAR(20) DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'DISABLED', 'MAINTENANCE')),
    config JSONB,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_providers_type ON ai_providers(type, status);

-- 模型别名表
CREATE TABLE model_aliases (
    id BIGSERIAL PRIMARY KEY,
    alias VARCHAR(50) UNIQUE NOT NULL,
    provider_id BIGINT NOT NULL REFERENCES ai_providers(id),
    provider_model_name VARCHAR(100) NOT NULL,
    type VARCHAR(20) NOT NULL CHECK (type IN ('TEXT', 'IMAGE', 'VIDEO', 'AUDIO')),
    description TEXT,
    cost_per_1k_tokens DECIMAL(10, 6),
    cost_per_image DECIMAL(10, 4),
    cost_per_video_second DECIMAL(10, 4),
    max_tokens INTEGER,
    max_resolution VARCHAR(20),
    max_duration INTEGER,
    features TEXT[],
    priority INTEGER DEFAULT 100,
    status VARCHAR(20) DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'DEPRECATED', 'DISABLED')),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_model_aliases_type ON model_aliases(type, status);
CREATE INDEX idx_model_aliases_alias ON model_aliases(alias);

-- AI任务表
CREATE TABLE ai_tasks (
    id BIGSERIAL PRIMARY KEY,
    tenant_id BIGINT NOT NULL REFERENCES tenants(id),
    code VARCHAR(32) UNIQUE NOT NULL,
    type VARCHAR(20) NOT NULL CHECK (type IN ('TEXT_GENERATION', 'IMAGE_GENERATION', 'VIDEO_GENERATION', 'BATCH_GENERATION')),
    model_alias VARCHAR(50) NOT NULL,
    provider_id BIGINT REFERENCES ai_providers(id),
    provider_task_id VARCHAR(200),
    input_params JSONB NOT NULL,
    estimated_cost DECIMAL(10, 4),
    actual_cost DECIMAL(10, 4),
    estimated_duration INTEGER,
    actual_duration INTEGER,
    status VARCHAR(20) DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'QUEUED', 'PROCESSING', 'COMPLETED', 'FAILED', 'CANCELLED', 'TIMEOUT')),
    progress INTEGER DEFAULT 0 CHECK (progress BETWEEN 0 AND 100),
    result JSONB,
    error_code VARCHAR(50),
    error_message TEXT,
    retry_count INTEGER DEFAULT 0,
    max_retries INTEGER DEFAULT 3,
    webhook_url VARCHAR(500),
    callback_status VARCHAR(20) CHECK (callback_status IN ('PENDING', 'SUCCESS', 'FAILED')),
    callback_attempts INTEGER DEFAULT 0,
    priority INTEGER DEFAULT 50,
    created_by BIGINT REFERENCES users(id),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    started_at TIMESTAMP,
    completed_at TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_tasks_tenant ON ai_tasks(tenant_id, created_at DESC);
CREATE INDEX idx_tasks_status ON ai_tasks(status, priority DESC);
CREATE INDEX idx_tasks_provider ON ai_tasks(provider_id, provider_task_id);
CREATE INDEX idx_tasks_callback ON ai_tasks(callback_status) WHERE callback_status = 'PENDING';

-- 任务日志表
CREATE TABLE task_logs (
    id BIGSERIAL PRIMARY KEY,
    task_id BIGINT NOT NULL REFERENCES ai_tasks(id) ON DELETE CASCADE,
    level VARCHAR(10) NOT NULL CHECK (level IN ('INFO', 'WARN', 'ERROR')),
    message TEXT NOT NULL,
    details JSONB,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_task_logs_task ON task_logs(task_id, created_at);

-- 租户配额表
CREATE TABLE tenant_quotas (
    id BIGSERIAL PRIMARY KEY,
    tenant_id BIGINT UNIQUE NOT NULL REFERENCES tenants(id),
    total_credits DECIMAL(12, 2) DEFAULT 0,
    used_credits DECIMAL(12, 2) DEFAULT 0,
    reserved_credits DECIMAL(12, 2) DEFAULT 0,
    available_credits DECIMAL(12, 2) GENERATED ALWAYS AS (total_credits - used_credits - reserved_credits) STORED,
    text_quota_per_day INTEGER DEFAULT 10000,
    image_quota_per_day INTEGER DEFAULT 100,
    video_quota_per_day INTEGER DEFAULT 10,
    current_date DATE DEFAULT CURRENT_DATE,
    text_used_today INTEGER DEFAULT 0,
    image_used_today INTEGER DEFAULT 0,
    video_used_today INTEGER DEFAULT 0,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_quotas_tenant ON tenant_quotas(tenant_id);

-- 配额消费记录表
CREATE TABLE quota_transactions (
    id BIGSERIAL PRIMARY KEY,
    tenant_id BIGINT NOT NULL REFERENCES tenants(id),
    task_id BIGINT REFERENCES ai_tasks(id),
    type VARCHAR(20) NOT NULL CHECK (type IN ('CHARGE', 'REFUND', 'RESERVE', 'RELEASE')),
    amount DECIMAL(10, 4) NOT NULL,
    balance_before DECIMAL(12, 2) NOT NULL,
    balance_after DECIMAL(12, 2) NOT NULL,
    description TEXT,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_quota_txn_tenant ON quota_transactions(tenant_id, created_at DESC);
CREATE INDEX idx_quota_txn_task ON quota_transactions(task_id);

-- Provider API调用日志表
CREATE TABLE provider_api_logs (
    id BIGSERIAL PRIMARY KEY,
    task_id BIGINT REFERENCES ai_tasks(id) ON DELETE SET NULL,
    provider_id BIGINT NOT NULL REFERENCES ai_providers(id),
    request_method VARCHAR(10) NOT NULL,
    request_url VARCHAR(500) NOT NULL,
    request_headers JSONB,
    request_body JSONB,
    response_status INTEGER,
    response_body JSONB,
    response_time INTEGER,
    error_message TEXT,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_api_logs_task ON provider_api_logs(task_id);
CREATE INDEX idx_api_logs_provider ON provider_api_logs(provider_id, created_at DESC);

COMMENT ON TABLE ai_providers IS 'AI服务提供商配置表';
COMMENT ON TABLE model_aliases IS '模型别名路由表';
COMMENT ON TABLE ai_tasks IS 'AI任务表';
COMMENT ON TABLE task_logs IS '任务日志表';
COMMENT ON TABLE tenant_quotas IS '租户配额表';
COMMENT ON TABLE quota_transactions IS '配额消费记录表';
COMMENT ON TABLE provider_api_logs IS 'Provider API调用日志表';

-- 插入示例AI提供商
INSERT INTO ai_providers (code, name, type, base_url, api_key_encrypted, config) VALUES
('fluapi', 'FluAPI', 'TEXT', 'https://api.fluapi.example.com', 'encrypted_key_placeholder', '{"version": "v1"}'),
('toapis_image', 'ToAPIs Image', 'IMAGE', 'https://api.toapis.example.com/image', 'encrypted_key_placeholder', '{"version": "v1"}'),
('toapis_video', 'ToAPIs Video', 'VIDEO', 'https://api.toapis.example.com/video', 'encrypted_key_placeholder', '{"version": "v1"}');

-- 插入示例模型别名
INSERT INTO model_aliases (alias, provider_id, provider_model_name, type, description, cost_per_1k_tokens, priority) VALUES
('TEXT_FAST', 1, 'gpt-3.5-turbo', 'TEXT', '快速文本生成，适合简单场景', 0.002, 100),
('TEXT_SMART', 1, 'gpt-4', 'TEXT', '智能文本生成，适合复杂场景', 0.03, 90),
('IMAGE_PRIMARY', 2, 'sd-xl-1.0', 'IMAGE', '主力图片生成模型', 0.05, 100),
('VIDEO_PRIMARY', 3, 'runway-gen2', 'VIDEO', '主力视频生成模型', 0.50, 100);
