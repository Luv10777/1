-- V50: merchant enrollment, public information, physical goods and orders.

ALTER TABLE products ADD COLUMN online_status VARCHAR(20) NOT NULL DEFAULT 'DRAFT';
ALTER TABLE products ADD COLUMN inventory INTEGER;
ALTER TABLE products ADD CONSTRAINT ck_products_online_status
    CHECK (online_status IN ('DRAFT', 'PUBLISHED'));
ALTER TABLE products ADD CONSTRAINT ck_products_inventory
    CHECK (inventory IS NULL OR inventory >= 0);
CREATE INDEX idx_products_public_storefront
    ON products (store_id, updated_at DESC)
    WHERE status = 'ACTIVE' AND online_status = 'PUBLISHED' AND deleted_at IS NULL;

CREATE TABLE merchant_applications (
    id BIGSERIAL PRIMARY KEY,
    tenant_id BIGINT NOT NULL REFERENCES tenants(id),
    submitted_by BIGINT NOT NULL REFERENCES users(id),
    merchant_name VARCHAR(80) NOT NULL,
    company_name VARCHAR(120) NOT NULL,
    industry VARCHAR(80) NOT NULL,
    region VARCHAR(120) NOT NULL,
    credit_code VARCHAR(18) NOT NULL,
    contact_name VARCHAR(40) NOT NULL,
    contact_phone VARCHAR(20) NOT NULL,
    introduction VARCHAR(1000) NOT NULL,
    status VARCHAR(24) NOT NULL DEFAULT 'PENDING_REVIEW'
        CHECK (status IN ('PENDING_REVIEW', 'APPROVED', 'REJECTED')),
    review_note VARCHAR(500),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_merchant_applications_tenant ON merchant_applications (tenant_id, created_at DESC);

CREATE TABLE information_publications (
    id BIGSERIAL PRIMARY KEY,
    tenant_id BIGINT NOT NULL REFERENCES tenants(id),
    submitted_by BIGINT NOT NULL REFERENCES users(id),
    category VARCHAR(24) NOT NULL CHECK (category IN ('activity', 'cooperation', 'supply', 'notice')),
    title VARCHAR(60) NOT NULL,
    merchant_name VARCHAR(80) NOT NULL,
    region VARCHAR(80) NOT NULL,
    summary VARCHAR(120),
    content VARCHAR(5000) NOT NULL,
    contact_name VARCHAR(40),
    contact_phone VARCHAR(20),
    valid_until DATE,
    status VARCHAR(20) NOT NULL DEFAULT 'PUBLISHED'
        CHECK (status IN ('PUBLISHED', 'OFFLINE')),
    published_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_information_public_feed ON information_publications (published_at DESC, id DESC)
    WHERE status = 'PUBLISHED';

CREATE TABLE merchant_orders (
    id BIGSERIAL PRIMARY KEY,
    tenant_id BIGINT NOT NULL REFERENCES tenants(id),
    store_id BIGINT NOT NULL REFERENCES stores(id),
    order_number VARCHAR(32) NOT NULL UNIQUE,
    lookup_token VARCHAR(36) NOT NULL UNIQUE,
    buyer_name VARCHAR(80) NOT NULL,
    buyer_phone VARCHAR(20) NOT NULL,
    shipping_address VARCHAR(500) NOT NULL,
    shipping_company VARCHAR(60),
    tracking_number VARCHAR(120),
    buyer_note VARCHAR(500),
    total_amount NUMERIC(14,2) NOT NULL CHECK (total_amount >= 0),
    status VARCHAR(24) NOT NULL DEFAULT 'PENDING_CONFIRMATION'
        CHECK (status IN ('PENDING_CONFIRMATION', 'CONFIRMED', 'SHIPPED', 'COMPLETED', 'CANCELLED')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, id)
);
CREATE INDEX idx_merchant_orders_store ON merchant_orders (tenant_id, store_id, created_at DESC);

CREATE TABLE merchant_order_items (
    id BIGSERIAL PRIMARY KEY,
    tenant_id BIGINT NOT NULL REFERENCES tenants(id),
    order_id BIGINT NOT NULL REFERENCES merchant_orders(id) ON DELETE CASCADE,
    product_id BIGINT REFERENCES products(id),
    product_name VARCHAR(200) NOT NULL,
    unit_price NUMERIC(14,2) NOT NULL CHECK (unit_price >= 0),
    quantity INTEGER NOT NULL CHECK (quantity > 0),
    line_total NUMERIC(14,2) NOT NULL CHECK (line_total >= 0)
);
CREATE INDEX idx_merchant_order_items_order ON merchant_order_items (tenant_id, order_id, id);

DO $$
DECLARE table_name TEXT;
BEGIN
    FOREACH table_name IN ARRAY ARRAY['merchant_applications', 'information_publications', 'merchant_orders', 'merchant_order_items'] LOOP
        EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY', table_name);
        EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY', table_name);
        EXECUTE format(
            'CREATE POLICY tenant_isolation ON %I USING (tenant_id = NULLIF(current_setting(''app.tenant_id'', true), '''')::bigint) WITH CHECK (tenant_id = NULLIF(current_setting(''app.tenant_id'', true), '''')::bigint)',
            table_name
        );
        EXECUTE format('GRANT SELECT, INSERT, UPDATE, DELETE ON %I TO growth_app', table_name);
    END LOOP;
END $$;
CREATE POLICY public_order_lookup ON merchant_orders FOR SELECT
    USING (lookup_token = NULLIF(current_setting('app.order_lookup_token', true), ''));
CREATE POLICY public_order_item_lookup ON merchant_order_items FOR SELECT
    USING (EXISTS (
        SELECT 1 FROM merchant_orders o
        WHERE o.id = merchant_order_items.order_id
          AND o.lookup_token = NULLIF(current_setting('app.order_lookup_token', true), '')
    ));
GRANT USAGE, SELECT ON SEQUENCE merchant_applications_id_seq, information_publications_id_seq,
    merchant_orders_id_seq, merchant_order_items_id_seq TO growth_app;

CREATE POLICY public_published_information ON information_publications FOR SELECT
    USING (status = 'PUBLISHED' AND (valid_until IS NULL OR valid_until >= CURRENT_DATE));
CREATE POLICY public_published_products ON products FOR SELECT
    USING (status = 'ACTIVE' AND deleted_at IS NULL AND online_status = 'PUBLISHED');
CREATE POLICY public_product_images ON product_images FOR SELECT
    USING (EXISTS (
        SELECT 1 FROM products p
        WHERE p.id = product_images.product_id AND p.tenant_id = product_images.tenant_id
          AND p.status = 'ACTIVE' AND p.deleted_at IS NULL AND p.online_status = 'PUBLISHED'
    ));
CREATE POLICY public_storefronts ON stores FOR SELECT
    USING (status = 'ACTIVE' AND EXISTS (
        SELECT 1 FROM products p WHERE p.store_id = stores.id AND p.tenant_id = stores.tenant_id
          AND p.status = 'ACTIVE' AND p.deleted_at IS NULL AND p.online_status = 'PUBLISHED'
    ));
CREATE POLICY public_product_images_asset ON assets FOR SELECT
    USING (status = 'READY' AND type = 'IMAGE' AND EXISTS (
        SELECT 1 FROM product_images pi
        JOIN products p ON p.id = pi.product_id AND p.tenant_id = pi.tenant_id
        WHERE pi.asset_id = assets.id AND pi.tenant_id = assets.tenant_id
          AND p.status = 'ACTIVE' AND p.deleted_at IS NULL AND p.online_status = 'PUBLISHED'
    ));
