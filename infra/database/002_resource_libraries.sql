-- 阶段三：四大资源库数据表
-- 品牌库、素材库、知识库、作品库

-- 品牌库表
CREATE TABLE brands (
    id BIGSERIAL PRIMARY KEY,
    tenant_id BIGINT NOT NULL REFERENCES tenants(id),
    merchant_id BIGINT NOT NULL REFERENCES merchants(id),
    code VARCHAR(32) UNIQUE NOT NULL,
    name VARCHAR(100) NOT NULL,
    positioning TEXT,
    target_audience TEXT,
    language_style TEXT,
    primary_color VARCHAR(20),
    forbidden_colors TEXT[],
    logo_assets JSONB,
    standard_fonts TEXT[],
    common_cta TEXT[],
    forbidden_expressions TEXT[],
    platform_styles JSONB,
    version INTEGER DEFAULT 1,
    status VARCHAR(20) DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'ARCHIVED', 'DELETED')),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    deleted_at TIMESTAMP
);

CREATE INDEX idx_brands_tenant ON brands(tenant_id);
CREATE INDEX idx_brands_merchant ON brands(merchant_id);
CREATE INDEX idx_brands_status ON brands(status) WHERE deleted_at IS NULL;

-- 素材库表
CREATE TABLE assets (
    id BIGSERIAL PRIMARY KEY,
    tenant_id BIGINT NOT NULL REFERENCES tenants(id),
    merchant_id BIGINT REFERENCES merchants(id),
    store_id BIGINT REFERENCES stores(id),
    code VARCHAR(32) UNIQUE NOT NULL,
    name VARCHAR(200) NOT NULL,
    type VARCHAR(20) NOT NULL CHECK (type IN ('IMAGE', 'VIDEO', 'AUDIO', 'DOCUMENT')),
    category VARCHAR(50),
    tags TEXT[],
    file_url VARCHAR(500) NOT NULL,
    file_size BIGINT,
    mime_type VARCHAR(100),
    width INTEGER,
    height INTEGER,
    duration INTEGER,
    thumbnail_url VARCHAR(500),
    source VARCHAR(50),
    copyright_info TEXT,
    license_file_url VARCHAR(500),
    license_scope TEXT,
    license_valid_from DATE,
    license_valid_until DATE,
    usage_count INTEGER DEFAULT 0,
    status VARCHAR(20) DEFAULT 'AVAILABLE' CHECK (status IN ('AVAILABLE', 'EXPIRED', 'DISABLED', 'DELETED')),
    created_by BIGINT REFERENCES users(id),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    deleted_at TIMESTAMP
);

CREATE INDEX idx_assets_tenant ON assets(tenant_id);
CREATE INDEX idx_assets_merchant ON assets(merchant_id);
CREATE INDEX idx_assets_type ON assets(type);
CREATE INDEX idx_assets_category ON assets(category);
CREATE INDEX idx_assets_tags ON assets USING GIN(tags);
CREATE INDEX idx_assets_status ON assets(status) WHERE deleted_at IS NULL;
CREATE INDEX idx_assets_license_validity ON assets(license_valid_until) WHERE status = 'AVAILABLE';

-- 知识库表
CREATE TABLE knowledge (
    id BIGSERIAL PRIMARY KEY,
    tenant_id BIGINT NOT NULL REFERENCES tenants(id),
    merchant_id BIGINT REFERENCES merchants(id),
    store_id BIGINT REFERENCES stores(id),
    code VARCHAR(32) UNIQUE NOT NULL,
    title VARCHAR(200) NOT NULL,
    type VARCHAR(20) NOT NULL CHECK (type IN ('FILE', 'TEXT', 'URL', 'STRUCTURED')),
    content TEXT,
    file_url VARCHAR(500),
    source_url VARCHAR(500),
    parse_status VARCHAR(20) DEFAULT 'PENDING' CHECK (parse_status IN ('PENDING', 'PARSING', 'PARSED', 'FAILED')),
    ocr_status VARCHAR(20) DEFAULT 'PENDING' CHECK (ocr_status IN ('PENDING', 'PROCESSING', 'COMPLETED', 'FAILED')),
    vector_status VARCHAR(20) DEFAULT 'PENDING' CHECK (vector_status IN ('PENDING', 'PROCESSING', 'COMPLETED', 'FAILED')),
    chunk_count INTEGER DEFAULT 0,
    structured_data JSONB,
    metadata JSONB,
    verified BOOLEAN DEFAULT FALSE,
    verified_by BIGINT REFERENCES users(id),
    verified_at TIMESTAMP,
    status VARCHAR(20) DEFAULT 'DRAFT' CHECK (status IN ('DRAFT', 'PUBLISHED', 'EXPIRED', 'DELETED')),
    created_by BIGINT REFERENCES users(id),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    deleted_at TIMESTAMP
);

CREATE INDEX idx_knowledge_tenant ON knowledge(tenant_id);
CREATE INDEX idx_knowledge_merchant ON knowledge(merchant_id);
CREATE INDEX idx_knowledge_type ON knowledge(type);
CREATE INDEX idx_knowledge_status ON knowledge(status, verified) WHERE deleted_at IS NULL;
CREATE INDEX idx_knowledge_structured ON knowledge USING GIN(structured_data);

-- 知识切片表（用于向量检索）
CREATE TABLE knowledge_chunks (
    id BIGSERIAL PRIMARY KEY,
    knowledge_id BIGINT NOT NULL REFERENCES knowledge(id) ON DELETE CASCADE,
    chunk_index INTEGER NOT NULL,
    content TEXT NOT NULL,
    embedding vector(1536),
    metadata JSONB,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(knowledge_id, chunk_index)
);

CREATE INDEX idx_chunks_knowledge ON knowledge_chunks(knowledge_id);
CREATE INDEX idx_chunks_embedding ON knowledge_chunks USING ivfflat (embedding vector_cosine_ops);

-- 作品库表
CREATE TABLE works (
    id BIGSERIAL PRIMARY KEY,
    tenant_id BIGINT NOT NULL REFERENCES tenants(id),
    merchant_id BIGINT REFERENCES merchants(id),
    store_id BIGINT REFERENCES stores(id),
    code VARCHAR(32) UNIQUE NOT NULL,
    title VARCHAR(200) NOT NULL,
    type VARCHAR(20) NOT NULL CHECK (type IN ('IMAGE', 'VIDEO', 'TEXT', 'MIXED')),
    version INTEGER DEFAULT 1,
    cover_url VARCHAR(500),
    preview_url VARCHAR(500),
    content_url VARCHAR(500),
    content_text TEXT,
    workflow_id VARCHAR(100),
    workflow_version VARCHAR(50),
    model_alias VARCHAR(50),
    prompt_version VARCHAR(50),
    generation_cost DECIMAL(10, 4),
    generation_duration INTEGER,
    generation_params JSONB,
    qa_result JSONB,
    review_status VARCHAR(20) DEFAULT 'DRAFT' CHECK (review_status IN ('DRAFT', 'PENDING', 'APPROVED', 'REJECTED', 'PUBLISHED')),
    review_notes TEXT,
    reviewed_by BIGINT REFERENCES users(id),
    reviewed_at TIMESTAMP,
    published_platforms TEXT[],
    published_at TIMESTAMP,
    platform_content_ids JSONB,
    created_by BIGINT REFERENCES users(id),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    deleted_at TIMESTAMP
);

CREATE INDEX idx_works_tenant ON works(tenant_id);
CREATE INDEX idx_works_merchant ON works(merchant_id);
CREATE INDEX idx_works_type ON works(type);
CREATE INDEX idx_works_review_status ON works(review_status);
CREATE INDEX idx_works_workflow ON works(workflow_id, workflow_version);
CREATE INDEX idx_works_published_platforms ON works USING GIN(published_platforms);
CREATE INDEX idx_works_created ON works(created_at DESC);

-- 作品审核记录表
CREATE TABLE work_reviews (
    id BIGSERIAL PRIMARY KEY,
    work_id BIGINT NOT NULL REFERENCES works(id) ON DELETE CASCADE,
    version INTEGER NOT NULL,
    reviewer_id BIGINT NOT NULL REFERENCES users(id),
    action VARCHAR(20) NOT NULL CHECK (action IN ('SUBMIT', 'APPROVE', 'REJECT', 'REQUEST_CHANGE')),
    notes TEXT,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_work_reviews_work ON work_reviews(work_id, created_at DESC);

COMMENT ON TABLE brands IS '品牌库表';
COMMENT ON TABLE assets IS '素材库表';
COMMENT ON TABLE knowledge IS '知识库表';
COMMENT ON TABLE knowledge_chunks IS '知识切片表（向量检索）';
COMMENT ON TABLE works IS '作品库表';
COMMENT ON TABLE work_reviews IS '作品审核记录表';
