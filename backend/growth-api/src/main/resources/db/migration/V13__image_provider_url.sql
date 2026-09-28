-- Keep the provider's short-lived result available while the durable copy is downloaded.
ALTER TABLE image_items
    ADD COLUMN provider_image_url TEXT,
    ADD COLUMN persisted_at TIMESTAMPTZ;

CREATE INDEX idx_image_items_unpersisted
    ON image_items (created_at)
    WHERE provider_image_url IS NOT NULL AND persisted_at IS NULL;

COMMENT ON COLUMN image_items.provider_image_url IS '上游返回的临时图片 URL（通常有效 1 小时）';
COMMENT ON COLUMN image_items.persisted_at IS '图片持久化到对象存储的时间';
