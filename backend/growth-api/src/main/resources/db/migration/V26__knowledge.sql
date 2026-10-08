-- V26 · 门店知识库（第一阶段：结构化 FAQ）
-- 文档知识集先预留 kind，文档及切片在后续迁移中增加。

CREATE TABLE knowledge_sets (
    id              BIGSERIAL    PRIMARY KEY,
    tenant_id       BIGINT       NOT NULL REFERENCES tenants(id),
    store_id        BIGINT       NOT NULL REFERENCES stores(id),
    name            VARCHAR(120) NOT NULL,
    kind            VARCHAR(20)  NOT NULL DEFAULT 'FAQ',
    status          VARCHAR(20)  NOT NULL DEFAULT 'DRAFT',
    description     VARCHAR(500),
    current_version INT          NOT NULL DEFAULT 0,
    created_by      BIGINT       REFERENCES users(id),
    published_at    TIMESTAMPTZ,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT ck_knowledge_sets_kind CHECK (kind IN ('FAQ', 'DOCUMENT')),
    CONSTRAINT ck_knowledge_sets_status CHECK (status IN ('DRAFT', 'PUBLISHED', 'DISABLED', 'ARCHIVED'))
);
CREATE INDEX idx_knowledge_sets_store_status
    ON knowledge_sets (tenant_id, store_id, status, kind, updated_at DESC);

CREATE TABLE knowledge_entries (
    id                  BIGSERIAL    PRIMARY KEY,
    tenant_id           BIGINT       NOT NULL REFERENCES tenants(id),
    store_id            BIGINT       NOT NULL REFERENCES stores(id),
    knowledge_set_id    BIGINT       NOT NULL REFERENCES knowledge_sets(id) ON DELETE CASCADE,
    product_id          BIGINT       REFERENCES products(id),
    scope               VARCHAR(20)  NOT NULL DEFAULT 'STORE',
    source              VARCHAR(30)  NOT NULL DEFAULT 'MANUAL',
    status              VARCHAR(20)  NOT NULL DEFAULT 'DRAFT',
    question            VARCHAR(500) NOT NULL,
    answer              TEXT         NOT NULL,
    hits                BIGINT       NOT NULL DEFAULT 0,
    manual_corrections  INT          NOT NULL DEFAULT 0,
    version             INT          NOT NULL DEFAULT 1,
    created_by          BIGINT       REFERENCES users(id),
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT ck_knowledge_entries_scope CHECK (scope IN ('STORE', 'PRODUCT')),
    CONSTRAINT ck_knowledge_entries_source CHECK (source IN ('MANUAL', 'LIVE_DRAFT', 'AI_SUGGESTION')),
    CONSTRAINT ck_knowledge_entries_status CHECK (status IN ('DRAFT', 'ACTIVE', 'DISABLED')),
    CONSTRAINT ck_knowledge_entries_product_scope CHECK (
        (scope = 'STORE' AND product_id IS NULL)
        OR (scope = 'PRODUCT' AND product_id IS NOT NULL)
    )
);
CREATE INDEX idx_knowledge_entries_set_status
    ON knowledge_entries (tenant_id, knowledge_set_id, status, updated_at DESC);
CREATE INDEX idx_knowledge_entries_store_scope_product
    ON knowledge_entries (tenant_id, store_id, scope, product_id, status, id);

ALTER TABLE knowledge_sets ENABLE ROW LEVEL SECURITY;
ALTER TABLE knowledge_sets FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON knowledge_sets
    USING (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::bigint)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::bigint);

ALTER TABLE knowledge_entries ENABLE ROW LEVEL SECURITY;
ALTER TABLE knowledge_entries FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON knowledge_entries
    USING (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::bigint)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::bigint);

GRANT SELECT, INSERT, UPDATE, DELETE ON knowledge_sets, knowledge_entries TO growth_app;
GRANT USAGE, SELECT ON SEQUENCE knowledge_sets_id_seq, knowledge_entries_id_seq TO growth_app;
