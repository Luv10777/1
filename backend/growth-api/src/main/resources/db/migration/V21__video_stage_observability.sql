-- V21 · Stage age survives worker restarts and polling updates.
ALTER TABLE video_workflows ADD COLUMN stage_started_at TIMESTAMPTZ NOT NULL DEFAULT now();

-- Historical phase boundaries are unknown. Use the current recovery window conservatively.
UPDATE video_workflows
   SET stage_started_at = COALESCE(attempt_started_at, created_at)
 WHERE status IN ('QUEUED', 'SUBMITTING', 'GENERATING', 'IMPORTING', 'QA');

COMMENT ON COLUMN video_workflows.stage_started_at IS
    'Current stage start; repeated polls/automatic retries do not refresh it; manual recovery starts a new stage';

-- The V15 (status, created_at DESC) index cannot bound the new stage age predicate.
-- Tenant first matches explicit per-tenant observation and FORCE RLS filtering.
CREATE INDEX idx_video_workflow_stuck ON video_workflows (tenant_id, status, stage_started_at)
    WHERE status IN ('QUEUED', 'SUBMITTING', 'GENERATING', 'IMPORTING', 'QA');

-- Existing ENABLE/FORCE RLS and the tenant isolation policy remain in force.
