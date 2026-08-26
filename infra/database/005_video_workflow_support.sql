-- 梧曜星枢 - AI 视频工作流支撑表
-- 阶段1：企业级基础设施（续）
-- PostgreSQL 14+

-- ============================================================================
-- 质检和审核相关表
-- ============================================================================

-- 质量报告表
CREATE TABLE quality_reports (
    id BIGSERIAL PRIMARY KEY,
    tenant_id BIGINT NOT NULL REFERENCES tenants(id),
    workflow_run_id BIGINT NOT NULL REFERENCES workflow_runs(id),
    entity_type VARCHAR(50) NOT NULL,  -- IMAGE, VIDEO, SCRIPT, FINAL_VIDEO
    entity_id VARCHAR(100) NOT NULL,
    report_type VARCHAR(50) NOT NULL,  -- TECHNICAL, SEMANTIC, FACT_CONSISTENCY
    qa_result VARCHAR(50) NOT NULL,  -- PASS, PASS_WITH_WARNING, RETRY_RECOMMENDED, BLOCKED
    issues JSONB,  -- 问题列表
    blocking_issues JSONB,  -- 阻断级问题
    warnings JSONB,  -- 警告
    quality_score DECIMAL(5, 2),
    checked_by VARCHAR(100),  -- 检查者（系统或人工）
    checked_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_quality_reports_entity ON quality_reports(entity_type, entity_id);
CREATE INDEX idx_quality_reports_run ON quality_reports(workflow_run_id);
CREATE INDEX idx_quality_reports_result ON quality_reports(qa_result);

-- 人工审核记录表
CREATE TABLE review_records (
    id BIGSERIAL PRIMARY KEY,
    tenant_id BIGINT NOT NULL REFERENCES tenants(id),
    workflow_run_id BIGINT NOT NULL REFERENCES workflow_runs(id),
    review_type VARCHAR(50) NOT NULL,  -- CREATIVE, SCRIPT, STORYBOARD, REFERENCE, FINAL
    entity_type VARCHAR(50) NOT NULL,
    entity_id VARCHAR(100) NOT NULL,
    decision VARCHAR(50) NOT NULL,  -- APPROVED, REJECTED, NEEDS_REVISION
    reviewer_id BIGINT NOT NULL REFERENCES users(id),
    review_comment TEXT,
    reviewed_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_review_records_run ON review_records(workflow_run_id);
CREATE INDEX idx_review_records_entity ON review_records(entity_type, entity_id);
CREATE INDEX idx_review_records_reviewer ON review_records(reviewer_id, reviewed_at DESC);

-- ============================================================================
-- 资产管理相关表
-- ============================================================================

-- 资产表（统一管理所有文件）
CREATE TABLE assets (
    id BIGSERIAL PRIMARY KEY,
    tenant_id BIGINT NOT NULL REFERENCES tenants(id),
    asset_type VARCHAR(50) NOT NULL,  -- IMAGE, VIDEO, AUDIO, DOCUMENT
    asset_category VARCHAR(50),  -- REFERENCE, GENERATED, UPLOADED, FINAL
    s3_bucket VARCHAR(100) NOT NULL,
    s3_key VARCHAR(500) NOT NULL,
    file_name VARCHAR(255),
    mime_type VARCHAR(100),
    file_size_bytes BIGINT,
    width INTEGER,  -- 图片/视频宽度
    height INTEGER,  -- 图片/视频高度
    duration_seconds DECIMAL(5, 2),  -- 视频/音频时长
    sha256_hash VARCHAR(64),
    metadata JSONB,  -- 扩展元数据
    uploaded_by BIGINT REFERENCES users(id),
    source VARCHAR(100),  -- FLUAPI, TOAPIS, USER_UPLOAD, FFMPEG
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(s3_bucket, s3_key)
);

CREATE INDEX idx_assets_tenant ON assets(tenant_id);
CREATE INDEX idx_assets_type ON assets(asset_type, asset_category);
CREATE INDEX idx_assets_hash ON assets(sha256_hash);
CREATE INDEX idx_assets_source ON assets(source);

-- 资产授权表（商家授权素材）
CREATE TABLE asset_authorizations (
    id BIGSERIAL PRIMARY KEY,
    tenant_id BIGINT NOT NULL REFERENCES tenants(id),
    merchant_id BIGINT NOT NULL REFERENCES merchants(id),
    asset_id BIGINT NOT NULL REFERENCES assets(id),
    authorization_scope VARCHAR(50) NOT NULL,  -- ALL_VIDEOS, SPECIFIC_PRODUCT, SPECIFIC_CAMPAIGN
    scope_reference VARCHAR(200),  -- 具体产品ID或活动ID
    authorized_by BIGINT REFERENCES users(id),
    authorized_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    expires_at TIMESTAMP,
    status VARCHAR(20) DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'EXPIRED', 'REVOKED')),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_asset_authorizations_merchant ON asset_authorizations(merchant_id, status);
CREATE INDEX idx_asset_authorizations_asset ON asset_authorizations(asset_id);

-- ============================================================================
-- 作品库相关表
-- ============================================================================

-- 作品表（最终成品）
CREATE TABLE work_assets (
    id BIGSERIAL PRIMARY KEY,
    tenant_id BIGINT NOT NULL REFERENCES tenants(id),
    merchant_id BIGINT NOT NULL REFERENCES merchants(id),
    workflow_run_id BIGINT NOT NULL REFERENCES workflow_runs(id),
    work_code VARCHAR(50) UNIQUE NOT NULL,
    work_name VARCHAR(200) NOT NULL,
    work_type VARCHAR(50) DEFAULT 'VIDEO',
    final_asset_id BIGINT NOT NULL REFERENCES assets(id),
    thumbnail_asset_id BIGINT REFERENCES assets(id),
    duration_seconds DECIMAL(5, 2),
    aspect_ratio VARCHAR(20),
    target_platform VARCHAR(50),
    tags JSONB,  -- 标签
    description TEXT,
    is_approved BOOLEAN DEFAULT FALSE,  -- 人工审核通过
    approved_by BIGINT REFERENCES users(id),
    approved_at TIMESTAMP,
    published_at TIMESTAMP,  -- 发布时间（如果已发布）
    view_count INTEGER DEFAULT 0,
    download_count INTEGER DEFAULT 0,
    status VARCHAR(20) DEFAULT 'DRAFT' CHECK (status IN ('DRAFT', 'APPROVED', 'PUBLISHED', 'ARCHIVED')),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_work_assets_tenant ON work_assets(tenant_id);
CREATE INDEX idx_work_assets_merchant ON work_assets(merchant_id);
CREATE INDEX idx_work_assets_run ON work_assets(workflow_run_id);
CREATE INDEX idx_work_assets_status ON work_assets(status);
CREATE INDEX idx_work_assets_approved ON work_assets(is_approved, approved_at DESC);

-- ============================================================================
-- 成本和额度相关表
-- ============================================================================

-- 成本账本表（只追加，不修改）
CREATE TABLE cost_ledger (
    id BIGSERIAL PRIMARY KEY,
    tenant_id BIGINT NOT NULL REFERENCES tenants(id),
    workflow_run_id BIGINT REFERENCES workflow_runs(id),
    activity_id VARCHAR(100),
    provider_job_id VARCHAR(200),
    ledger_type VARCHAR(50) NOT NULL,  -- RESERVATION, CAPTURE, REFUND, ADJUSTMENT
    capability_alias VARCHAR(50),  -- TEXT_PLANNER, IMAGE_PRIMARY, VIDEO_PRIMARY
    provider VARCHAR(50),  -- fluapi, toapis
    provider_model VARCHAR(100),
    input_units INTEGER,  -- 输入单位（tokens, pixels等）
    output_units INTEGER,  -- 输出单位
    image_count INTEGER,
    video_seconds DECIMAL(5, 2),
    currency VARCHAR(10) DEFAULT 'USD',
    estimated_cost DECIMAL(10, 4),
    actual_cost DECIMAL(10, 4),
    billing_status VARCHAR(20) DEFAULT 'PENDING' CHECK (billing_status IN ('PENDING', 'CAPTURED', 'RELEASED', 'FAILED')),
    occurred_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_cost_ledger_tenant ON cost_ledger(tenant_id, occurred_at DESC);
CREATE INDEX idx_cost_ledger_run ON cost_ledger(workflow_run_id);
CREATE INDEX idx_cost_ledger_type ON cost_ledger(ledger_type, billing_status);
CREATE INDEX idx_cost_ledger_provider ON cost_ledger(provider, occurred_at DESC);

-- 额度预占表
CREATE TABLE quota_reservations (
    id BIGSERIAL PRIMARY KEY,
    tenant_id BIGINT NOT NULL REFERENCES tenants(id),
    workflow_run_id BIGINT NOT NULL REFERENCES workflow_runs(id),
    reservation_key VARCHAR(100) UNIQUE NOT NULL,
    reserved_credits DECIMAL(10, 2) NOT NULL,
    captured_credits DECIMAL(10, 2) DEFAULT 0,
    status VARCHAR(20) DEFAULT 'RESERVED' CHECK (status IN ('RESERVED', 'CAPTURED', 'RELEASED', 'EXPIRED')),
    reserved_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    expires_at TIMESTAMP,
    released_at TIMESTAMP
);

CREATE INDEX idx_quota_reservations_tenant ON quota_reservations(tenant_id);
CREATE INDEX idx_quota_reservations_run ON quota_reservations(workflow_run_id);
CREATE INDEX idx_quota_reservations_status ON quota_reservations(status, expires_at);

-- ============================================================================
-- Transactional Outbox / Inbox 相关表
-- ============================================================================

-- Outbox 事件表（数据库事务内写入，异步发布到 RabbitMQ）
CREATE TABLE outbox_events (
    id BIGSERIAL PRIMARY KEY,
    event_id UUID UNIQUE NOT NULL DEFAULT gen_random_uuid(),
    tenant_id BIGINT NOT NULL,
    aggregate_type VARCHAR(100) NOT NULL,  -- WORKFLOW_RUN, SHOT, PROVIDER_JOB
    aggregate_id VARCHAR(100) NOT NULL,
    event_type VARCHAR(100) NOT NULL,  -- STEP_READY, SHOT_GENERATED, JOB_COMPLETED
    payload JSONB NOT NULL,
    routing_key VARCHAR(200) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    published_at TIMESTAMP,
    published_by VARCHAR(100),  -- 发布者实例ID
    retry_count INTEGER NOT NULL DEFAULT 0,
    last_error TEXT,
    version INTEGER NOT NULL DEFAULT 1
);

CREATE INDEX idx_outbox_events_unpublished ON outbox_events(created_at)
    WHERE published_at IS NULL;
CREATE INDEX idx_outbox_events_tenant ON outbox_events(tenant_id);
CREATE INDEX idx_outbox_events_aggregate ON outbox_events(aggregate_type, aggregate_id);

-- Inbox 消息表（消费者幂等去重）
CREATE TABLE inbox_messages (
    id BIGSERIAL PRIMARY KEY,
    message_id UUID UNIQUE NOT NULL,
    consumer_name VARCHAR(100) NOT NULL,
    received_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    processed_at TIMESTAMP,
    processing_error TEXT,
    retry_count INTEGER NOT NULL DEFAULT 0,
    UNIQUE(message_id, consumer_name)
);

CREATE INDEX idx_inbox_messages_unprocessed ON inbox_messages(received_at)
    WHERE processed_at IS NULL;
CREATE INDEX idx_inbox_messages_consumer ON inbox_messages(consumer_name, received_at DESC);

-- ============================================================================
-- 审计日志表
-- ============================================================================

-- 审计日志表（只追加）
CREATE TABLE audit_logs (
    id BIGSERIAL PRIMARY KEY,
    tenant_id BIGINT,
    user_id BIGINT REFERENCES users(id),
    action VARCHAR(100) NOT NULL,  -- LOGIN, CREATE_PROJECT, APPROVE_SCRIPT, DOWNLOAD_VIDEO
    resource_type VARCHAR(50),  -- WORKFLOW_RUN, SHOT, WORK_ASSET
    resource_id VARCHAR(100),
    before_state JSONB,  -- 操作前状态摘要
    after_state JSONB,  -- 操作后状态摘要
    ip_address VARCHAR(45),
    user_agent TEXT,
    trace_id VARCHAR(100),
    occurred_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_audit_logs_tenant ON audit_logs(tenant_id, occurred_at DESC);
CREATE INDEX idx_audit_logs_user ON audit_logs(user_id, occurred_at DESC);
CREATE INDEX idx_audit_logs_resource ON audit_logs(resource_type, resource_id);
CREATE INDEX idx_audit_logs_action ON audit_logs(action, occurred_at DESC);

-- ============================================================================
-- 知识库相关表（支持商家知识注入）
-- ============================================================================

-- 知识文档表
CREATE TABLE knowledge_documents (
    id BIGSERIAL PRIMARY KEY,
    tenant_id BIGINT NOT NULL REFERENCES tenants(id),
    merchant_id BIGINT REFERENCES merchants(id),
    document_type VARCHAR(50) NOT NULL,  -- MENU, PROMOTION, FAQ, POLICY
    title VARCHAR(200) NOT NULL,
    content TEXT,
    source_url VARCHAR(500),
    source_file_asset_id BIGINT REFERENCES assets(id),
    is_verified BOOLEAN DEFAULT FALSE,
    verified_by BIGINT REFERENCES users(id),
    verified_at TIMESTAMP,
    status VARCHAR(20) DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'ARCHIVED', 'DELETED')),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    deleted_at TIMESTAMP
);

CREATE INDEX idx_knowledge_documents_merchant ON knowledge_documents(merchant_id, status);
CREATE INDEX idx_knowledge_documents_type ON knowledge_documents(document_type);

-- 知识块表（向量化）
CREATE TABLE knowledge_chunks (
    id BIGSERIAL PRIMARY KEY,
    document_id BIGINT NOT NULL REFERENCES knowledge_documents(id) ON DELETE CASCADE,
    chunk_index INTEGER NOT NULL,
    chunk_text TEXT NOT NULL,
    chunk_metadata JSONB,
    embedding_vector vector(1536),  -- 需要 pgvector 扩展
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(document_id, chunk_index)
);

CREATE INDEX idx_knowledge_chunks_document ON knowledge_chunks(document_id);

-- pgvector 相似度搜索索引（如果启用 pgvector 扩展）
-- CREATE INDEX idx_knowledge_chunks_embedding ON knowledge_chunks
--     USING ivfflat (embedding_vector vector_cosine_ops)
--     WITH (lists = 100);

-- ============================================================================
-- 视图和辅助对象
-- ============================================================================

-- 工作流运行摘要视图
CREATE OR REPLACE VIEW v_workflow_run_summary AS
SELECT
    wr.id,
    wr.tenant_id,
    wr.project_id,
    wr.run_code,
    wr.state,
    wr.progress,
    vp.name AS project_name,
    m.name AS merchant_name,
    COUNT(DISTINCT ws.id) AS total_steps,
    COUNT(DISTINCT CASE WHEN ws.status = 'SUCCEEDED' THEN ws.id END) AS completed_steps,
    COUNT(DISTINCT CASE WHEN ws.status IN ('FAILED', 'FAILED_FINAL') THEN ws.id END) AS failed_steps,
    wr.estimated_cost_credits,
    wr.actual_cost_credits,
    wr.created_at,
    wr.started_at,
    wr.completed_at
FROM workflow_runs wr
JOIN video_projects vp ON wr.project_id = vp.id
JOIN merchants m ON vp.merchant_id = m.id
LEFT JOIN workflow_steps ws ON wr.id = ws.workflow_run_id
GROUP BY wr.id, vp.name, m.name;

-- 成本统计视图（按租户、按工作流）
CREATE OR REPLACE VIEW v_cost_summary AS
SELECT
    tenant_id,
    workflow_run_id,
    ledger_type,
    provider,
    SUM(estimated_cost) AS total_estimated_cost,
    SUM(actual_cost) AS total_actual_cost,
    COUNT(*) AS transaction_count
FROM cost_ledger
WHERE billing_status IN ('CAPTURED', 'PENDING')
GROUP BY tenant_id, workflow_run_id, ledger_type, provider;

-- ============================================================================
-- 函数和触发器
-- ============================================================================

-- 更新 updated_at 时间戳的函数
CREATE OR REPLACE FUNCTION update_updated_at_column()
RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = CURRENT_TIMESTAMP;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

-- 为所有需要的表添加 updated_at 触发器
CREATE TRIGGER trigger_update_video_projects_updated_at
    BEFORE UPDATE ON video_projects
    FOR EACH ROW
    EXECUTE FUNCTION update_updated_at_column();

CREATE TRIGGER trigger_update_workflow_runs_updated_at
    BEFORE UPDATE ON workflow_runs
    FOR EACH ROW
    EXECUTE FUNCTION update_updated_at_column();

CREATE TRIGGER trigger_update_workflow_steps_updated_at
    BEFORE UPDATE ON workflow_steps
    FOR EACH ROW
    EXECUTE FUNCTION update_updated_at_column();

CREATE TRIGGER trigger_update_shots_updated_at
    BEFORE UPDATE ON shots
    FOR EACH ROW
    EXECUTE FUNCTION update_updated_at_column();

CREATE TRIGGER trigger_update_work_assets_updated_at
    BEFORE UPDATE ON work_assets
    FOR EACH ROW
    EXECUTE FUNCTION update_updated_at_column();

CREATE TRIGGER trigger_update_creative_variants_updated_at
    BEFORE UPDATE ON creative_variants
    FOR EACH ROW
    EXECUTE FUNCTION update_updated_at_column();

CREATE TRIGGER trigger_update_video_scripts_updated_at
    BEFORE UPDATE ON video_scripts
    FOR EACH ROW
    EXECUTE FUNCTION update_updated_at_column();

CREATE TRIGGER trigger_update_storyboards_updated_at
    BEFORE UPDATE ON storyboards
    FOR EACH ROW
    EXECUTE FUNCTION update_updated_at_column();

-- ============================================================================
-- 完成
-- ============================================================================

-- 插入默认工作流定义
INSERT INTO workflow_definitions (workflow_type, workflow_name, description, steps_definition, version, is_active)
VALUES (
    'IDEA2VIDEO',
    'Idea to Video Workflow',
    '从一句话创意生成完整视频的工作流',
    '{
        "steps": [
            {"code": "VALIDATE_INPUT", "name": "校验输入"},
            {"code": "FREEZE_FACTS", "name": "冻结商家事实快照"},
            {"code": "PARSE_INTENT", "name": "解析意图"},
            {"code": "PLAN_CREATIVES", "name": "规划创意"},
            {"code": "WRITE_SCRIPTS", "name": "生成脚本"},
            {"code": "DESIGN_STORYBOARDS", "name": "设计分镜"},
            {"code": "COMPILE_PROMPTS", "name": "编译Prompt"},
            {"code": "GENERATE_REFERENCES", "name": "生成参考图"},
            {"code": "SELECT_REFERENCES", "name": "选择参考图"},
            {"code": "GENERATE_SHOTS", "name": "生成镜头视频"},
            {"code": "CHECK_SHOTS", "name": "质检镜头"},
            {"code": "COMPOSE_VIDEO", "name": "合成视频"},
            {"code": "FINAL_QA", "name": "最终质检"},
            {"code": "HUMAN_REVIEW", "name": "人工审核"},
            {"code": "PUBLISH_TO_LIBRARY", "name": "发布到作品库"}
        ]
    }'::jsonb,
    '1.0.0',
    TRUE
);

COMMENT ON TABLE merchant_facts IS '商家事实表：存储结构化商家信息（地址、价格、套餐等）';
COMMENT ON TABLE merchant_fact_snapshots IS '商家事实快照表：不可变快照，用于工作流执行';
COMMENT ON TABLE video_projects IS '视频项目表：用户创建的视频生成项目';
COMMENT ON TABLE workflow_runs IS '工作流运行表：每次执行的实例';
COMMENT ON TABLE workflow_steps IS '工作流步骤表：每个步骤的状态和进度';
COMMENT ON TABLE provider_jobs IS '供应商任务表：追踪FluAPI/ToAPIs异步任务';
COMMENT ON TABLE outbox_events IS 'Transactional Outbox：确保数据库和消息队列一致性';
COMMENT ON TABLE inbox_messages IS 'Inbox去重表：消费者幂等处理';
COMMENT ON TABLE cost_ledger IS '成本账本表：只追加，记录所有费用';
COMMENT ON TABLE audit_logs IS '审计日志表：记录所有关键操作';
