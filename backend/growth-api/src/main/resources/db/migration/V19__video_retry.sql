-- V19 · Video recovery rounds are distinct from paid submission generations.
-- Existing workflows stay in round/generation 0, retaining their original idempotency keys.
ALTER TABLE video_workflows
    ADD COLUMN retry_round INT NOT NULL DEFAULT 0 CHECK (retry_round >= 0),
    ADD COLUMN submission_generation INT NOT NULL DEFAULT 0 CHECK (submission_generation >= 0),
    ADD COLUMN attempt_started_at TIMESTAMPTZ,
    ADD COLUMN pending_cleanup_keys JSONB NOT NULL DEFAULT '[]'::jsonb
        CHECK (jsonb_typeof(pending_cleanup_keys) = 'array');

COMMENT ON COLUMN video_workflows.retry_round IS
    'Incremented on manual recovery; namespaces every subsequent task and unpublished output object';
COMMENT ON COLUMN video_workflows.submission_generation IS
    'Incremented only after a durable provider job is confirmed FAILED/CANCELED; a new paid submission is possible';
COMMENT ON COLUMN video_workflows.attempt_started_at IS
    'Start of the current manual recovery window; NULL uses the original created_at';
COMMENT ON COLUMN video_workflows.pending_cleanup_keys IS
    'Unpublished output objects whose best-effort deletion failed; retained across retries';

-- No new table or data backfill: the existing ENABLE/FORCE RLS policies remain in force.
