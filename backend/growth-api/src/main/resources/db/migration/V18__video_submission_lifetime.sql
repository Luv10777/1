-- V18 · 视频提交状态与稳定的原始请求字节
ALTER TABLE video_workflows
    ADD COLUMN provider_submit_started_at TIMESTAMPTZ,
    ADD COLUMN provider_submit_body TEXT;

COMMENT ON COLUMN video_workflows.provider_submit_started_at IS
    'NULL means definitely never dispatched; set before HTTP and committed even if the response is lost';
COMMENT ON COLUMN video_workflows.provider_submit_body IS
    'Exact JSON sent to the provider; JSONB cannot preserve byte order for idempotent retries';

-- V17 persisted the candidate before HTTP, without recording whether dispatch occurred.
-- Treat every existing nonempty candidate/job as possibly sent. Never fabricate wire bytes
-- from JSONB: those legacy retries require reconciliation with the provider first.
-- Iterate tenants so FORCE RLS remains active even for a migration owner without BYPASSRLS.
DO $$
DECLARE
    tenant_record RECORD;
    previous_tenant TEXT := current_setting('app.tenant_id', true);
BEGIN
    FOR tenant_record IN SELECT id FROM tenants LOOP
        PERFORM set_config('app.tenant_id', tenant_record.id::text, true);
        UPDATE video_workflows
        SET provider_submit_started_at = updated_at
        WHERE tenant_id = tenant_record.id
          AND ((provider_submit_request IS NOT NULL
                AND provider_submit_request NOT IN ('{}'::jsonb, 'null'::jsonb))
               OR provider_job_id IS NOT NULL);
    END LOOP;
    PERFORM set_config('app.tenant_id', COALESCE(previous_tenant, ''), true);
END
$$;
