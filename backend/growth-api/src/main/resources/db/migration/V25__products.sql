-- V25 · 商品库
-- 商品属于租户下的门店；图片只引用素材库中 READY 的 IMAGE 素材。

CREATE TABLE products (
    id                 BIGSERIAL PRIMARY KEY,
    tenant_id          BIGINT       NOT NULL REFERENCES tenants(id),
    store_id           BIGINT       NOT NULL REFERENCES stores(id),
    name               VARCHAR(200) NOT NULL,
    type               VARCHAR(30)  NOT NULL,
    category           VARCHAR(40)  NOT NULL,
    price              NUMERIC(14,2) NOT NULL CHECK (price >= 0),
    sale_unit          VARCHAR(40)  NOT NULL,
    specification      VARCHAR(500),
    promotion_rule     VARCHAR(1000),
    core_selling_points VARCHAR(2000),
    status             VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE',
    deleted_at         TIMESTAMPTZ,
    created_by         BIGINT       REFERENCES users(id),
    created_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    version            BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT ck_products_type CHECK (type IN ('PHYSICAL', 'VOUCHER'))
);
CREATE INDEX idx_products_store_active ON products (tenant_id, store_id, status, updated_at DESC)
    WHERE deleted_at IS NULL;
CREATE INDEX idx_products_store_category ON products (tenant_id, store_id, category)
    WHERE deleted_at IS NULL;

CREATE TABLE product_images (
    id          BIGSERIAL PRIMARY KEY,
    tenant_id   BIGINT      NOT NULL REFERENCES tenants(id),
    product_id  BIGINT      NOT NULL REFERENCES products(id),
    asset_id    BIGINT      NOT NULL REFERENCES assets(id),
    sort_order  INT         NOT NULL DEFAULT 0 CHECK (sort_order >= 0),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uk_product_images_asset UNIQUE (product_id, asset_id)
);
CREATE INDEX idx_product_images_product ON product_images (tenant_id, product_id, sort_order, id);

CREATE TABLE product_faqs (
    id          BIGSERIAL PRIMARY KEY,
    tenant_id   BIGINT       NOT NULL REFERENCES tenants(id),
    product_id  BIGINT       NOT NULL REFERENCES products(id),
    question    VARCHAR(1000) NOT NULL,
    answer      VARCHAR(4000) NOT NULL,
    status      VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE',
    sort_order  INT          NOT NULL DEFAULT 0 CHECK (sort_order >= 0),
    deleted_at  TIMESTAMPTZ,
    created_by  BIGINT       REFERENCES users(id),
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    version     BIGINT       NOT NULL DEFAULT 0
);
CREATE INDEX idx_product_faqs_product_active ON product_faqs (tenant_id, product_id, sort_order, id)
    WHERE deleted_at IS NULL;

ALTER TABLE products ENABLE ROW LEVEL SECURITY;
ALTER TABLE products FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON products
    USING (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::bigint)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::bigint);

ALTER TABLE product_images ENABLE ROW LEVEL SECURITY;
ALTER TABLE product_images FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON product_images
    USING (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::bigint)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::bigint);

ALTER TABLE product_faqs ENABLE ROW LEVEL SECURITY;
ALTER TABLE product_faqs FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON product_faqs
    USING (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::bigint)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::bigint);

GRANT SELECT, INSERT, UPDATE, DELETE ON products, product_images, product_faqs TO growth_app;
GRANT USAGE, SELECT ON SEQUENCE products_id_seq, product_images_id_seq, product_faqs_id_seq TO growth_app;
