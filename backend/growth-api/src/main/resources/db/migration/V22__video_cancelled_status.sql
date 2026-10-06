-- V22 · Canonical video cancellation spelling; data correction only.
-- Iterate tenants without disabling the existing FORCE RLS policies.
DO $$
DECLARE
    tenant_record RECORD;
    previous_tenant TEXT := current_setting('app.tenant_id', true);
BEGIN
    FOR tenant_record IN SELECT id FROM tenants LOOP
        PERFORM set_config('app.tenant_id', tenant_record.id::text, true);
        UPDATE video_workflows
           SET status = CASE WHEN status = 'CANCELED' THEN 'CANCELLED' ELSE status END,
               stage = CASE WHEN stage = 'CANCELED' THEN 'CANCELLED' ELSE stage END,
               provider_status = CASE WHEN provider_status = 'CANCELED' THEN 'CANCELLED' ELSE provider_status END
         WHERE tenant_id = tenant_record.id
           AND (status = 'CANCELED' OR stage = 'CANCELED' OR provider_status = 'CANCELED');

        UPDATE video_provider_jobs SET status = 'CANCELLED'
         WHERE tenant_id = tenant_record.id AND status = 'CANCELED';
    END LOOP;
    PERFORM set_config('app.tenant_id', COALESCE(previous_tenant, ''), true);
END
$$;
