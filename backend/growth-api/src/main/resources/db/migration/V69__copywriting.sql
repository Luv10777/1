-- 文案创作：看图或一句话，给朋友圈、抖音、小红书写 3 条配文。每次生成预占 1 烟火，成功扣除，失败退回；改写不另收费。
CREATE TABLE copy_batches (
    id BIGSERIAL PRIMARY KEY,
    tenant_id BIGINT NOT NULL REFERENCES tenants(id),
    store_id BIGINT NOT NULL REFERENCES stores(id),
    created_by BIGINT REFERENCES users(id),
    request_key VARCHAR(80) NOT NULL,
    platform VARCHAR(20) NOT NULL CHECK (platform IN ('MOMENTS', 'DOUYIN', 'XIAOHONGSHU')),
    style VARCHAR(20) NOT NULL,
    store_level VARCHAR(20) NOT NULL CHECK (store_level IN ('NONE', 'LIGHT', 'VISIT')),
    voice_on BOOLEAN NOT NULL DEFAULT false,
    image_asset_ids JSONB NOT NULL DEFAULT '[]',
    work_item_ids JSONB NOT NULL DEFAULT '[]',
    products JSONB NOT NULL DEFAULT '[]',
    -- 提交时所选商品的资料快照，生成和改写都以它为准，商品后来改价也不影响这批文案的校验。
    product_facts TEXT,
    note TEXT,
    previous_batch_id BIGINT REFERENCES copy_batches(id),
    status VARCHAR(20) NOT NULL DEFAULT 'QUEUED' CHECK (status IN ('QUEUED', 'GENERATING', 'SUCCEEDED', 'FAILED')),
    task_id BIGINT REFERENCES tasks(id),
    billing_reservation_id VARCHAR(36),
    versions JSONB,
    rewrite_count INT NOT NULL DEFAULT 0,
    error_message TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uk_copy_batch_request UNIQUE (tenant_id, request_key)
);
CREATE INDEX idx_copy_batch_store ON copy_batches (tenant_id, store_id, id DESC);

-- 门店志：定稿的那一条，存下发出去时的样子（门店占位已换成真实值）。
CREATE TABLE copy_journal_entries (
    id BIGSERIAL PRIMARY KEY,
    tenant_id BIGINT NOT NULL REFERENCES tenants(id),
    store_id BIGINT NOT NULL REFERENCES stores(id),
    batch_id BIGINT NOT NULL REFERENCES copy_batches(id),
    version_index INT NOT NULL CHECK (version_index BETWEEN 0 AND 2),
    platform VARCHAR(20) NOT NULL,
    title TEXT,
    body TEXT NOT NULL,
    tags JSONB NOT NULL DEFAULT '[]',
    finalized_by BIGINT REFERENCES users(id),
    finalized_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uk_copy_journal_version UNIQUE (batch_id, version_index)
);
CREATE INDEX idx_copy_journal_store ON copy_journal_entries (tenant_id, store_id, id DESC);

-- 学我口吻：每家门店一份样本和模型总结出的口吻档案。
CREATE TABLE copy_voice_profiles (
    id BIGSERIAL PRIMARY KEY,
    tenant_id BIGINT NOT NULL REFERENCES tenants(id),
    store_id BIGINT NOT NULL REFERENCES stores(id),
    samples JSONB NOT NULL DEFAULT '[]',
    profile JSONB,
    status VARCHAR(20) NOT NULL DEFAULT 'EMPTY' CHECK (status IN ('EMPTY', 'LEARNING', 'READY', 'FAILED')),
    revision INT NOT NULL DEFAULT 0,
    task_id BIGINT REFERENCES tasks(id),
    update_day DATE,
    update_count INT NOT NULL DEFAULT 0,
    error_message TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uk_copy_voice_store UNIQUE (tenant_id, store_id)
);

DO $$ DECLARE table_name text; BEGIN
 FOREACH table_name IN ARRAY ARRAY['copy_batches','copy_journal_entries','copy_voice_profiles'] LOOP
  EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',table_name);
  EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',table_name);
  EXECUTE format('CREATE POLICY tenant_isolation ON %I USING (tenant_id = NULLIF(current_setting(''app.tenant_id'',true),'''')::bigint) WITH CHECK (tenant_id = NULLIF(current_setting(''app.tenant_id'',true),'''')::bigint)',table_name);
 END LOOP;
END $$;
GRANT SELECT, INSERT, UPDATE ON copy_batches, copy_journal_entries, copy_voice_profiles TO growth_app;
GRANT USAGE, SELECT ON SEQUENCE copy_batches_id_seq, copy_journal_entries_id_seq, copy_voice_profiles_id_seq TO growth_app;
