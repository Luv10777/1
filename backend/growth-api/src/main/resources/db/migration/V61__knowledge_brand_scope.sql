-- V61 · 问答集可以挂在品牌上
-- 挂在品牌上的问答集由这个品牌下的所有门店共用；挂在门店上的只属于那家店。两者必居其一。
-- 外键检查不受行级安全约束，所以用 (tenant_id, brand_id) 复合外键把"同一商户"交给数据库保证。

ALTER TABLE knowledge_sets ALTER COLUMN store_id DROP NOT NULL;
ALTER TABLE knowledge_sets ADD COLUMN brand_id BIGINT;
ALTER TABLE knowledge_sets ADD CONSTRAINT fk_knowledge_sets_brand
    FOREIGN KEY (tenant_id, brand_id) REFERENCES brands (tenant_id, id);
ALTER TABLE knowledge_sets ADD CONSTRAINT ck_knowledge_sets_owner
    CHECK ((store_id IS NULL) <> (brand_id IS NULL));
CREATE INDEX idx_knowledge_sets_brand_status
    ON knowledge_sets (tenant_id, brand_id, status, kind) WHERE brand_id IS NOT NULL;

ALTER TABLE knowledge_entries ALTER COLUMN store_id DROP NOT NULL;
ALTER TABLE knowledge_entries ADD COLUMN brand_id BIGINT;
ALTER TABLE knowledge_entries ADD CONSTRAINT fk_knowledge_entries_brand
    FOREIGN KEY (tenant_id, brand_id) REFERENCES brands (tenant_id, id);
ALTER TABLE knowledge_entries ADD CONSTRAINT ck_knowledge_entries_owner
    CHECK ((store_id IS NULL) <> (brand_id IS NULL));

-- 从文档整理出来、经商家确认后加入的问答，来源记为 DOCUMENT。
ALTER TABLE knowledge_entries DROP CONSTRAINT ck_knowledge_entries_source;
ALTER TABLE knowledge_entries ADD CONSTRAINT ck_knowledge_entries_source
    CHECK (source IN ('MANUAL', 'LIVE', 'DOCUMENT'));
