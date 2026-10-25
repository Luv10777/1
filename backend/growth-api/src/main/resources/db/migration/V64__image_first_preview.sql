-- Browser image load acknowledgements, independent of durable delivery and billing.
ALTER TABLE image_items ADD COLUMN previewed_at TIMESTAMPTZ;
