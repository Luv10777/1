-- ============================================================
-- V40 · 品牌库
--
-- 层级：商户（tenant）→ 品牌 → 门店。品牌资料属于品牌，门店通过 brand_id 指明所属品牌。
-- 一个商户至多一个默认品牌：新建门店未指定品牌时归到它名下。
-- 还没有建过品牌的商户，门店的 brand_id 为空，一切照旧。
-- ============================================================

CREATE TABLE brands (
    id                       BIGSERIAL     PRIMARY KEY,
    tenant_id                BIGINT        NOT NULL REFERENCES tenants(id),
    name                     VARCHAR(80)   NOT NULL,
    is_default               BOOLEAN       NOT NULL DEFAULT FALSE,
    status                   VARCHAR(20)   NOT NULL DEFAULT 'ACTIVE',

    industry                 VARCHAR(60),
    slogan                   VARCHAR(120),
    intro                    VARCHAR(1000),
    website                  VARCHAR(300),
    positioning              VARCHAR(500),
    target_audience          VARCHAR(500),
    language_style           VARCHAR(500),

    primary_color            VARCHAR(7),
    vi_guidelines            VARCHAR(2000),
    logo_asset_id            BIGINT        REFERENCES assets(id),
    mini_program_qr_asset_id BIGINT        REFERENCES assets(id),
    wechat_qr_asset_id       BIGINT        REFERENCES assets(id),

    history                  VARCHAR(2000),
    brand_story              VARCHAR(4000),
    core_team                VARCHAR(2000),
    culture                  VARCHAR(2000),

    created_by               BIGINT        REFERENCES users(id),
    created_at               TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at               TIMESTAMPTZ   NOT NULL DEFAULT now(),
    version                  BIGINT        NOT NULL DEFAULT 0,

    CONSTRAINT ck_brands_status CHECK (status IN ('ACTIVE', 'ARCHIVED')),
    CONSTRAINT ck_brands_default_active CHECK (NOT is_default OR status = 'ACTIVE'),
    -- 供 stores 的复合外键引用：门店只能挂到同一商户的品牌上。
    CONSTRAINT uk_brands_tenant_id UNIQUE (tenant_id, id)
);
-- 归档后的品牌名可以再次使用。
CREATE UNIQUE INDEX uk_brands_tenant_name ON brands (tenant_id, name) WHERE status = 'ACTIVE';
CREATE UNIQUE INDEX uk_brands_tenant_default ON brands (tenant_id) WHERE is_default;
CREATE INDEX idx_brands_tenant_status ON brands (tenant_id, status, id);

ALTER TABLE brands ENABLE ROW LEVEL SECURITY;
ALTER TABLE brands FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON brands
    USING (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::bigint)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::bigint);

GRANT SELECT, INSERT, UPDATE, DELETE ON brands TO growth_app;
GRANT USAGE, SELECT ON SEQUENCE brands_id_seq TO growth_app;

-- 外键检查不受行级安全约束，所以用 (tenant_id, brand_id) 复合外键把"同一商户"交给数据库保证。
ALTER TABLE stores ADD COLUMN brand_id BIGINT;
ALTER TABLE stores ADD CONSTRAINT fk_stores_brand
    FOREIGN KEY (tenant_id, brand_id) REFERENCES brands (tenant_id, id);
CREATE INDEX idx_stores_brand ON stores (tenant_id, brand_id) WHERE brand_id IS NOT NULL;
