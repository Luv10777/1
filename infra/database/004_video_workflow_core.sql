-- 梧曜星枢 - AI 视频工作流核心表
-- 阶段1：企业级基础设施
-- PostgreSQL 14+

-- ============================================================================
-- 商家事实快照相关表
-- ============================================================================

-- 商家事实表（结构化商家信息）
CREATE TABLE merchant_facts (
    id BIGSERIAL PRIMARY KEY,
    tenant_id BIGINT NOT NULL REFERENCES tenants(id),
    merchant_id BIGINT NOT NULL REFERENCES merchants(id),
    fact_type VARCHAR(50) NOT NULL,  -- ADDRESS, PRICE, PACKAGE, HOURS, FEATURE, CONSTRAINT
    fact_key VARCHAR(100) NOT NULL,
    fact_value JSONB NOT NULL,
    is_critical BOOLEAN DEFAULT FALSE,  -- 关键事实（缺失时阻断）
    source VARCHAR(100),  -- 来源：USER_INPUT, ADMIN_VERIFIED, KNOWLEDGE_BASE
    confirmed_by BIGINT REFERENCES users(id),
    confirmed_at TIMESTAMP,
    expires_at TIMESTAMP,  -- 有效期（价格、套餐等）
    status VARCHAR(20) DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'EXPIRED', 'DELETED')),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    deleted_at TIMESTAMP
);

CREATE INDEX idx_merchant_facts_merchant ON merchant_facts(merchant_id, status);
CREATE INDEX idx_merchant_facts_type ON merchant_facts(fact_type);
CREATE INDEX idx_merchant_facts_critical ON merchant_facts(merchant_id, is_critical) WHERE is_critical = TRUE;

-- 商家事实快照表（不可变快照）
CREATE TABLE merchant_fact_snapshots (
    id BIGSERIAL PRIMARY KEY,
    tenant_id BIGINT NOT NULL REFERENCES tenants(id),
    merchant_id BIGINT NOT NULL REFERENCES merchants(id),
    snapshot_version VARCHAR(50) NOT NULL,  -- 格式：snapshot_yyyymmdd_hhmmss_uuid
    snapshot_hash VARCHAR(64) NOT NULL,  -- SHA-256 哈希，用于去重和校验
    facts_summary JSONB NOT NULL,  -- 快照摘要（用于 Prompt）
    is_complete BOOLEAN DEFAULT FALSE,  -- 所有关键事实是否齐全
    missing_critical_facts JSONB,  -- 缺失的关键事实列表
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(snapshot_hash)
);

CREATE INDEX idx_fact_snapshots_merchant ON merchant_fact_snapshots(merchant_id, created_at DESC);
CREATE INDEX idx_fact_snapshots_version ON merchant_fact_snapshots(snapshot_version);

-- 商家事实快照明细表
CREATE TABLE merchant_fact_snapshot_items (
    id BIGSERIAL PRIMARY KEY,
    snapshot_id BIGINT NOT NULL REFERENCES merchant_fact_snapshots(id) ON DELETE CASCADE,
    fact_id BIGINT NOT NULL REFERENCES merchant_facts(id),
    fact_type VARCHAR(50) NOT NULL,
    fact_key VARCHAR(100) NOT NULL,
    fact_value JSONB NOT NULL,
    is_critical BOOLEAN DEFAULT FALSE,
    source VARCHAR(100),
    confirmed_at TIMESTAMP
);

CREATE INDEX idx_snapshot_items_snapshot ON merchant_fact_snapshot_items(snapshot_id);
CREATE INDEX idx_snapshot_items_fact ON merchant_fact_snapshot_items(fact_id);

-- ============================================================================
-- 视频工作流核心表
-- ============================================================================

-- 视频项目表
CREATE TABLE video_projects (
    id BIGSERIAL PRIMARY KEY,
    tenant_id BIGINT NOT NULL REFERENCES tenants(id),
    merchant_id BIGINT NOT NULL REFERENCES merchants(id),
    project_code VARCHAR(50) UNIQUE NOT NULL,
    name VARCHAR(200) NOT NULL,
    brief TEXT NOT NULL,  -- 一句话需求
    target_platform VARCHAR(50),  -- 抖音、小红书、美团等
    aspect_ratio VARCHAR(20),  -- 9:16, 16:9, 1:1
    target_duration_seconds INTEGER,
    video_count INTEGER DEFAULT 1,
    quality_mode VARCHAR(20) DEFAULT 'STANDARD' CHECK (quality_mode IN ('STRICT', 'STANDARD', 'AUTO_DRAFT')),
    status VARCHAR(30) DEFAULT 'DRAFT',
    created_by BIGINT NOT NULL REFERENCES users(id),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    deleted_at TIMESTAMP
);

CREATE INDEX idx_video_projects_tenant ON video_projects(tenant_id);
CREATE INDEX idx_video_projects_merchant ON video_projects(merchant_id);
CREATE INDEX idx_video_projects_status ON video_projects(status) WHERE deleted_at IS NULL;

-- 工作流定义表（可扩展不同类型的工作流）
CREATE TABLE workflow_definitions (
    id BIGSERIAL PRIMARY KEY,
    workflow_type VARCHAR(50) UNIQUE NOT NULL,  -- IDEA2VIDEO, SCRIPT2VIDEO
    workflow_name VARCHAR(100) NOT NULL,
    description TEXT,
    steps_definition JSONB NOT NULL,  -- 步骤定义（DAG）
    version VARCHAR(20) NOT NULL,
    is_active BOOLEAN DEFAULT TRUE,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_workflow_definitions_type ON workflow_definitions(workflow_type, is_active);

-- 工作流运行表（工作流实例）
CREATE TABLE workflow_runs (
    id BIGSERIAL PRIMARY KEY,
    tenant_id BIGINT NOT NULL REFERENCES tenants(id),
    project_id BIGINT NOT NULL REFERENCES video_projects(id),
    run_code VARCHAR(50) UNIQUE NOT NULL,
    workflow_type VARCHAR(50) NOT NULL,
    merchant_fact_snapshot_id BIGINT REFERENCES merchant_fact_snapshots(id),
    state VARCHAR(50) NOT NULL DEFAULT 'DRAFT',  -- 工作流状态
    progress INTEGER DEFAULT 0 CHECK (progress BETWEEN 0 AND 100),
    estimated_cost_credits DECIMAL(10, 2),
    reserved_credits DECIMAL(10, 2),
    actual_cost_credits DECIMAL(10, 2),
    started_at TIMESTAMP,
    completed_at TIMESTAMP,
    failed_at TIMESTAMP,
    cancelled_at TIMESTAMP,
    error_message TEXT,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_workflow_runs_tenant ON workflow_runs(tenant_id);
CREATE INDEX idx_workflow_runs_project ON workflow_runs(project_id);
CREATE INDEX idx_workflow_runs_state ON workflow_runs(state);
CREATE INDEX idx_workflow_runs_snapshot ON workflow_runs(merchant_fact_snapshot_id);

-- 工作流步骤表
CREATE TABLE workflow_steps (
    id BIGSERIAL PRIMARY KEY,
    tenant_id BIGINT NOT NULL REFERENCES tenants(id),
    workflow_run_id BIGINT NOT NULL REFERENCES workflow_runs(id),
    step_code VARCHAR(100) NOT NULL,  -- VALIDATE_INPUT, FREEZE_FACTS, PLAN_CREATIVES, etc.
    step_name VARCHAR(200),
    entity_type VARCHAR(50),  -- CREATIVE_VARIANT, VIDEO_SCRIPT, STORYBOARD, SHOT, etc.
    entity_id VARCHAR(100),
    status VARCHAR(50) NOT NULL DEFAULT 'PENDING',
    progress INTEGER DEFAULT 0,
    input_ref VARCHAR(500),  -- S3 Key 或 JSON 引用
    output_ref VARCHAR(500),
    input_hash VARCHAR(64),
    current_attempt INTEGER DEFAULT 0,
    max_attempts INTEGER DEFAULT 3,
    next_retry_at TIMESTAMP,
    started_at TIMESTAMP,
    finished_at TIMESTAMP,
    error_category VARCHAR(50),
    error_code VARCHAR(100),
    user_safe_message TEXT,
    version INTEGER DEFAULT 1,  -- 乐观锁
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_workflow_steps_run ON workflow_steps(workflow_run_id);
CREATE INDEX idx_workflow_steps_status ON workflow_steps(status);
CREATE INDEX idx_workflow_steps_entity ON workflow_steps(entity_type, entity_id);
CREATE INDEX idx_workflow_steps_retry ON workflow_steps(next_retry_at) WHERE status IN ('RETRY_SCHEDULED', 'FAILED_RETRYABLE');

-- 工作流步骤尝试表（记录每次执行）
CREATE TABLE workflow_step_attempts (
    id BIGSERIAL PRIMARY KEY,
    step_id BIGINT NOT NULL REFERENCES workflow_steps(id),
    attempt_number INTEGER NOT NULL,
    idempotency_key VARCHAR(100) UNIQUE NOT NULL,
    started_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    finished_at TIMESTAMP,
    status VARCHAR(50) NOT NULL,
    error_message TEXT,
    provider_request_id VARCHAR(200),
    execution_time_ms INTEGER
);

CREATE INDEX idx_step_attempts_step ON workflow_step_attempts(step_id, attempt_number);
CREATE INDEX idx_step_attempts_idem ON workflow_step_attempts(idempotency_key);

-- ============================================================================
-- 创意、脚本、分镜相关表
-- ============================================================================

-- 创意变体表
CREATE TABLE creative_variants (
    id BIGSERIAL PRIMARY KEY,
    tenant_id BIGINT NOT NULL REFERENCES tenants(id),
    workflow_run_id BIGINT NOT NULL REFERENCES workflow_runs(id),
    variant_key VARCHAR(100) NOT NULL,  -- 唯一标识
    variant_index INTEGER NOT NULL,
    creative_angle TEXT NOT NULL,
    user_pain_point TEXT,
    opening_hook TEXT,
    narrative_structure TEXT,
    scene_combination TEXT,
    selling_points_order JSONB,
    call_to_action TEXT,
    differentiation_note TEXT,  -- 与同批其他创意的差异
    similarity_score DECIMAL(5, 4),  -- 与其他创意的相似度
    status VARCHAR(20) DEFAULT 'DRAFT' CHECK (status IN ('DRAFT', 'APPROVED', 'REJECTED')),
    reviewed_by BIGINT REFERENCES users(id),
    reviewed_at TIMESTAMP,
    review_comment TEXT,
    schema_version VARCHAR(20) NOT NULL,
    prompt_version VARCHAR(20) NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(workflow_run_id, variant_key)
);

CREATE INDEX idx_creative_variants_run ON creative_variants(workflow_run_id);
CREATE INDEX idx_creative_variants_status ON creative_variants(status);

-- 视频脚本表
CREATE TABLE video_scripts (
    id BIGSERIAL PRIMARY KEY,
    tenant_id BIGINT NOT NULL REFERENCES tenants(id),
    workflow_run_id BIGINT NOT NULL REFERENCES workflow_runs(id),
    creative_variant_id BIGINT REFERENCES creative_variants(id),
    script_revision INTEGER DEFAULT 1,
    total_duration_seconds DECIMAL(5, 2),
    shot_count INTEGER,
    script_content JSONB NOT NULL,  -- 完整脚本 JSON
    fact_references JSONB,  -- 引用的 fact_id 列表
    status VARCHAR(20) DEFAULT 'DRAFT' CHECK (status IN ('DRAFT', 'APPROVED', 'REJECTED')),
    reviewed_by BIGINT REFERENCES users(id),
    reviewed_at TIMESTAMP,
    review_comment TEXT,
    schema_version VARCHAR(20) NOT NULL,
    prompt_version VARCHAR(20) NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_video_scripts_run ON video_scripts(workflow_run_id);
CREATE INDEX idx_video_scripts_creative ON video_scripts(creative_variant_id);
CREATE INDEX idx_video_scripts_status ON video_scripts(status);

-- 分镜表
CREATE TABLE storyboards (
    id BIGSERIAL PRIMARY KEY,
    tenant_id BIGINT NOT NULL REFERENCES tenants(id),
    workflow_run_id BIGINT NOT NULL REFERENCES workflow_runs(id),
    video_script_id BIGINT NOT NULL REFERENCES video_scripts(id),
    storyboard_revision INTEGER DEFAULT 1,
    shot_count INTEGER NOT NULL,
    total_duration_seconds DECIMAL(5, 2),
    storyboard_content JSONB NOT NULL,
    status VARCHAR(20) DEFAULT 'DRAFT' CHECK (status IN ('DRAFT', 'APPROVED', 'REJECTED')),
    reviewed_by BIGINT REFERENCES users(id),
    reviewed_at TIMESTAMP,
    review_comment TEXT,
    schema_version VARCHAR(20) NOT NULL,
    prompt_version VARCHAR(20) NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_storyboards_run ON storyboards(workflow_run_id);
CREATE INDEX idx_storyboards_script ON storyboards(video_script_id);
CREATE INDEX idx_storyboards_status ON storyboards(status);

-- 镜头表
CREATE TABLE shots (
    id BIGSERIAL PRIMARY KEY,
    tenant_id BIGINT NOT NULL REFERENCES tenants(id),
    workflow_run_id BIGINT NOT NULL REFERENCES workflow_runs(id),
    storyboard_id BIGINT NOT NULL REFERENCES storyboards(id),
    shot_code VARCHAR(50) NOT NULL,  -- 镜头编号
    shot_index INTEGER NOT NULL,
    shot_description JSONB NOT NULL,
    target_duration_seconds DECIMAL(5, 2),
    scene_type VARCHAR(50),
    camera_movement VARCHAR(50),
    continuity_tokens JSONB,  -- 连续性标记（角色、服饰、场景）
    reference_image_asset_id BIGINT,  -- 参考图资产ID（后面会关联 assets 表）
    status VARCHAR(50) DEFAULT 'PENDING',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(storyboard_id, shot_code)
);

CREATE INDEX idx_shots_storyboard ON shots(storyboard_id);
CREATE INDEX idx_shots_run ON shots(workflow_run_id);
CREATE INDEX idx_shots_status ON shots(status);

-- 镜头版本表（支持单镜头重试）
CREATE TABLE shot_revisions (
    id BIGSERIAL PRIMARY KEY,
    shot_id BIGINT NOT NULL REFERENCES shots(id),
    revision_number INTEGER NOT NULL,
    revision_reason VARCHAR(100),  -- INITIAL, QUALITY_FAILED, USER_REQUESTED
    reference_image_asset_id BIGINT,
    video_asset_id BIGINT,
    actual_duration_seconds DECIMAL(5, 2),
    quality_score DECIMAL(5, 2),
    status VARCHAR(50) DEFAULT 'PENDING',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    completed_at TIMESTAMP,
    UNIQUE(shot_id, revision_number)
);

CREATE INDEX idx_shot_revisions_shot ON shot_revisions(shot_id, revision_number DESC);
CREATE INDEX idx_shot_revisions_status ON shot_revisions(status);

-- ============================================================================
-- Prompt 和生成任务相关表
-- ============================================================================

-- Prompt 制品表（记录生成的 Prompt）
CREATE TABLE prompt_artifacts (
    id BIGSERIAL PRIMARY KEY,
    tenant_id BIGINT NOT NULL REFERENCES tenants(id),
    workflow_run_id BIGINT NOT NULL REFERENCES workflow_runs(id),
    artifact_type VARCHAR(50) NOT NULL,  -- IMAGE_PROMPT, VIDEO_PROMPT, TEXT_PROMPT
    entity_type VARCHAR(50),
    entity_id VARCHAR(100),
    prompt_version VARCHAR(20) NOT NULL,
    prompt_template_version VARCHAR(20) NOT NULL,
    compiled_prompt TEXT NOT NULL,
    prompt_spec JSONB,  -- 结构化 Prompt 规范
    model_capability VARCHAR(50),  -- TEXT_PLANNER, IMAGE_PRIMARY, VIDEO_PRIMARY
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_prompt_artifacts_run ON prompt_artifacts(workflow_run_id);
CREATE INDEX idx_prompt_artifacts_entity ON prompt_artifacts(entity_type, entity_id);

-- 生成任务表（幂等键管理）
CREATE TABLE generation_tasks (
    id BIGSERIAL PRIMARY KEY,
    tenant_id BIGINT NOT NULL REFERENCES tenants(id),
    workflow_run_id BIGINT NOT NULL REFERENCES workflow_runs(id),
    step_id BIGINT REFERENCES workflow_steps(id),
    idempotency_key VARCHAR(100) UNIQUE NOT NULL,
    task_type VARCHAR(50) NOT NULL,  -- TEXT, IMAGE, VIDEO
    model_capability VARCHAR(50) NOT NULL,
    input_hash VARCHAR(64) NOT NULL,
    status VARCHAR(50) DEFAULT 'PENDING',
    provider_request_id VARCHAR(200),
    provider_job_id VARCHAR(200),
    result_ref VARCHAR(500),  -- S3 Key 或结果引用
    estimated_cost DECIMAL(10, 4),
    actual_cost DECIMAL(10, 4),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    completed_at TIMESTAMP
);

CREATE INDEX idx_generation_tasks_idem ON generation_tasks(idempotency_key);
CREATE INDEX idx_generation_tasks_run ON generation_tasks(workflow_run_id);
CREATE INDEX idx_generation_tasks_status ON generation_tasks(status);

-- 供应商任务表（Provider Job 追踪）
CREATE TABLE provider_jobs (
    id BIGSERIAL PRIMARY KEY,
    tenant_id BIGINT NOT NULL REFERENCES tenants(id),
    generation_task_id BIGINT NOT NULL REFERENCES generation_tasks(id),
    provider VARCHAR(50) NOT NULL,  -- fluapi, toapis
    provider_job_id VARCHAR(200) NOT NULL,
    job_type VARCHAR(50) NOT NULL,  -- IMAGE, VIDEO
    model_capability VARCHAR(50) NOT NULL,
    provider_model VARCHAR(100),
    status VARCHAR(50) DEFAULT 'SUBMITTED',
    progress INTEGER DEFAULT 0,
    submitted_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    last_checked_at TIMESTAMP,
    completed_at TIMESTAMP,
    failed_at TIMESTAMP,
    error_code VARCHAR(100),
    error_message TEXT,
    result_url TEXT,  -- 供应商临时 URL
    result_asset_id BIGINT,  -- 转存后的资产ID
    actual_duration_seconds DECIMAL(5, 2),  -- 视频实际时长
    actual_width INTEGER,
    actual_height INTEGER,
    file_size_bytes BIGINT,
    usage_data JSONB,  -- 用量数据
    estimated_cost DECIMAL(10, 4),
    actual_cost DECIMAL(10, 4),
    UNIQUE(provider, provider_job_id)
);

CREATE INDEX idx_provider_jobs_task ON provider_jobs(generation_task_id);
CREATE INDEX idx_provider_jobs_provider ON provider_jobs(provider, provider_job_id);
CREATE INDEX idx_provider_jobs_status ON provider_jobs(status);
CREATE INDEX idx_provider_jobs_pending ON provider_jobs(status, last_checked_at)
    WHERE status IN ('SUBMITTED', 'QUEUED', 'PROCESSING');

-- ============================================================================
-- 下一部分继续...
-- ============================================================================
