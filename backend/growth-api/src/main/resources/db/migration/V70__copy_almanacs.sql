-- 文案创作「今日宜发」：每家门店每天一份黄历（宜、忌、时令短句）和 3 个选题。日期事实由程序算，模型只负责解读。
-- 品牌行业当天改了会按新行业另生成一份，所以唯一键带上行业。
CREATE TABLE copy_almanacs (
    id BIGSERIAL PRIMARY KEY,
    tenant_id BIGINT NOT NULL REFERENCES tenants(id),
    store_id BIGINT NOT NULL REFERENCES stores(id),
    day DATE NOT NULL,
    industry VARCHAR(60) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'GENERATING' CHECK (status IN ('GENERATING', 'READY', 'FAILED')),
    facts JSONB NOT NULL,
    good JSONB,
    avoid JSONB,
    quote TEXT,
    topics JSONB,
    journal_hint TEXT,
    task_id BIGINT REFERENCES tasks(id),
    error_message TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uk_copy_almanac_day UNIQUE (tenant_id, store_id, day, industry)
);
ALTER TABLE copy_almanacs ENABLE ROW LEVEL SECURITY;
ALTER TABLE copy_almanacs FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON copy_almanacs
    USING (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::bigint)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::bigint);
GRANT SELECT, INSERT, UPDATE ON copy_almanacs TO growth_app;
GRANT USAGE, SELECT ON SEQUENCE copy_almanacs_id_seq TO growth_app;
