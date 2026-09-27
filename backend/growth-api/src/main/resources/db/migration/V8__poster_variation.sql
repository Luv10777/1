ALTER TABLE image_creations ADD COLUMN variation VARCHAR(16);
ALTER TABLE image_creations ADD COLUMN reference_hash VARCHAR(16);
ALTER TABLE image_items ADD COLUMN image_hash VARCHAR(16);
ALTER TABLE image_items ADD COLUMN similarity_warning BOOLEAN NOT NULL DEFAULT false;
