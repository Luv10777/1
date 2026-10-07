-- V20 · Remember publication independently of later workflow status changes.
ALTER TABLE video_workflows ADD COLUMN output_published_at TIMESTAMPTZ;
COMMENT ON COLUMN video_workflows.output_published_at IS
    'Set with successful QA; never cleared by retry/failure/cancel; published video objects must not be deleted';

-- Metadata backfill only. No object-storage deletion occurs in migrations.
-- Iterate tenants so the existing FORCE RLS policy also works for a non-BYPASSRLS owner.
DO $$
DECLARE
    tenant_record RECORD;
    previous_tenant TEXT := current_setting('app.tenant_id', true);
BEGIN
    FOR tenant_record IN SELECT id FROM tenants LOOP
        PERFORM set_config('app.tenant_id', tenant_record.id::text, true);
        UPDATE video_workflows SET output_published_at = updated_at
        WHERE tenant_id = tenant_record.id AND (status = 'SUCCEEDED' OR progress = 100);
    END LOOP;
    PERFORM set_config('app.tenant_id', COALESCE(previous_tenant, ''), true);
END
$$;
