-- ============================================================
-- V3 · 素材库（示例模块）
--
-- >>> 加新业务表就照抄这个文件的结构 <<<
-- 三件事一件都不能少：
--   1. tenant_id BIGINT NOT NULL
--   2. ENABLE + FORCE ROW LEVEL SECURITY
--   3. CREATE POLICY
-- 少了第 2、3 步，这张表就是裸的——业务代码一旦忘记加租户条件，
-- 就会把别家商家的数据返回给用户。
-- ============================================================

CREATE TABLE assets (
    id            BIGSERIAL    PRIMARY KEY,
    tenant_id     BIGINT       NOT NULL REFERENCES tenants(id),

    name          VARCHAR(200) NOT NULL,
    type          VARCHAR(20)  NOT NULL,          -- IMAGE / VIDEO / AUDIO / DOCUMENT
    status        VARCHAR(20)  NOT NULL DEFAULT 'PENDING', -- PENDING / READY

    storage_key   VARCHAR(500) NOT NULL,
    mime_type     VARCHAR(120),
    size_bytes    BIGINT,
    width         INT,
    height        INT,
    duration_ms   INT,
    sha256        VARCHAR(64),

    source        VARCHAR(40)  NOT NULL DEFAULT 'UPLOAD', -- UPLOAD / GENERATED
    created_by    BIGINT       REFERENCES users(id),
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT uk_assets_key UNIQUE (storage_key)
);
CREATE INDEX idx_assets_tenant ON assets (tenant_id, created_at DESC);

-- ---- 租户隔离（每张业务表都要有这三行）----
ALTER TABLE assets ENABLE ROW LEVEL SECURITY;
ALTER TABLE assets FORCE  ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON assets
    USING      (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::bigint)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::bigint);
