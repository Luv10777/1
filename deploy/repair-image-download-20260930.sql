-- Repair nine images whose download tasks succeeded before markImagePersisted
-- updated image and creation statuses. Run once with psql -v ON_ERROR_STOP=1.
BEGIN;

DO $$
DECLARE ready_count integer;
BEGIN
  SELECT count(*) INTO ready_count
  FROM image_items i
  WHERE i.id BETWEEN 30 AND 38
    AND i.creation_id IN (15, 16, 18)
    AND i.status = 'GENERATING'
    AND i.output_key IS NOT NULL
    AND i.persisted_at IS NOT NULL
    AND EXISTS (
      SELECT 1 FROM tasks t
      WHERE t.type = 'IMAGE_DOWNLOAD'
        AND t.status = 'SUCCEEDED'
        AND (t.payload->>'itemId')::bigint = i.id
    );
  IF ready_count <> 9 THEN
    RAISE EXCEPTION 'Expected 9 durable, downloaded images; found %', ready_count;
  END IF;
END $$;

UPDATE image_items
SET status = 'SUCCEEDED', error = NULL
WHERE id BETWEEN 30 AND 38 AND creation_id IN (15, 16, 18);

DO $$
BEGIN
  IF EXISTS (
    SELECT 1 FROM image_items
    WHERE creation_id IN (15, 16, 18) AND status <> 'SUCCEEDED'
  ) THEN
    RAISE EXCEPTION 'A repaired creation still has unfinished images';
  END IF;
END $$;

UPDATE image_creations
SET status = 'SUCCEEDED', error = NULL, concurrency_permit_held = false
WHERE id IN (15, 16, 18) AND status = 'GENERATING';

DO $$
BEGIN
  IF (SELECT count(*) FROM image_creations
      WHERE id IN (15, 16, 18) AND status = 'SUCCEEDED'
        AND concurrency_permit_held = false) <> 3 THEN
    RAISE EXCEPTION 'Not all three creations reached the completed state';
  END IF;
END $$;

COMMIT;
