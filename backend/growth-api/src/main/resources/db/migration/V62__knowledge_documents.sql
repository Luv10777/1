-- V62 · 上传文档，由文本模型整理出待确认的资料
-- 文档和问答集一样挂在品牌或门店上。整理结果是一条条建议：品牌/门店字段的新写法、新的问答；
-- 商家逐条采纳或忽略，采纳时才真正写进品牌、门店或问答集。原文件留在素材库，可以重新整理。

CREATE TABLE knowledge_documents (
    id             BIGSERIAL    PRIMARY KEY,
    tenant_id      BIGINT       NOT NULL REFERENCES tenants(id),
    store_id       BIGINT,
    brand_id       BIGINT,
    asset_id       BIGINT       NOT NULL REFERENCES assets(id),
    name           VARCHAR(200) NOT NULL,
    status         VARCHAR(20)  NOT NULL DEFAULT 'PROCESSING',
    error_message  VARCHAR(300),
    char_count     INT,
    created_by     BIGINT       REFERENCES users(id),
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT fk_knowledge_documents_store FOREIGN KEY (tenant_id, store_id) REFERENCES stores (tenant_id, id),
    CONSTRAINT fk_knowledge_documents_brand FOREIGN KEY (tenant_id, brand_id) REFERENCES brands (tenant_id, id),
    CONSTRAINT ck_knowledge_documents_owner CHECK ((store_id IS NULL) <> (brand_id IS NULL)),
    CONSTRAINT ck_knowledge_documents_status CHECK (status IN ('PROCESSING', 'READY', 'FAILED'))
);
CREATE INDEX idx_knowledge_documents_store ON knowledge_documents (tenant_id, store_id, created_at DESC) WHERE store_id IS NOT NULL;
CREATE INDEX idx_knowledge_documents_brand ON knowledge_documents (tenant_id, brand_id, created_at DESC) WHERE brand_id IS NOT NULL;

-- kind：FIELD 是品牌或门店的某个字段（field 记字段名，value 记建议写法）；QA 是一条问答。
CREATE TABLE knowledge_suggestions (
    id           BIGSERIAL    PRIMARY KEY,
    tenant_id    BIGINT       NOT NULL REFERENCES tenants(id),
    document_id  BIGINT       NOT NULL REFERENCES knowledge_documents(id) ON DELETE CASCADE,
    kind         VARCHAR(10)  NOT NULL,
    field        VARCHAR(40),
    value        TEXT,
    question     VARCHAR(500),
    answer       TEXT,
    status       VARCHAR(20)  NOT NULL DEFAULT 'PENDING',
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT ck_knowledge_suggestions_kind CHECK (
        (kind = 'FIELD' AND field IS NOT NULL AND value IS NOT NULL)
        OR (kind = 'QA' AND question IS NOT NULL AND answer IS NOT NULL)),
    CONSTRAINT ck_knowledge_suggestions_status CHECK (status IN ('PENDING', 'ACCEPTED', 'DISMISSED'))
);
CREATE INDEX idx_knowledge_suggestions_document ON knowledge_suggestions (tenant_id, document_id, id);

ALTER TABLE knowledge_documents ENABLE ROW LEVEL SECURITY;
ALTER TABLE knowledge_documents FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON knowledge_documents
    USING (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::bigint)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::bigint);

ALTER TABLE knowledge_suggestions ENABLE ROW LEVEL SECURITY;
ALTER TABLE knowledge_suggestions FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON knowledge_suggestions
    USING (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::bigint)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::bigint);

GRANT SELECT, INSERT, UPDATE, DELETE ON knowledge_documents, knowledge_suggestions TO growth_app;
GRANT USAGE, SELECT ON SEQUENCE knowledge_documents_id_seq, knowledge_suggestions_id_seq TO growth_app;
