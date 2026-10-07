-- Create the default quota row together with every tenant. The function is
-- security-definer because tenant_quotas is FORCE RLS and registration runs
-- before a tenant context exists.
CREATE OR REPLACE FUNCTION create_default_tenant_quota()
RETURNS trigger
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
BEGIN
    INSERT INTO tenant_quotas(tenant_id)
    VALUES (NEW.id)
    ON CONFLICT (tenant_id) DO NOTHING;
    RETURN NEW;
END;
$$;

REVOKE ALL ON FUNCTION create_default_tenant_quota() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION create_default_tenant_quota() TO growth_app;

DROP TRIGGER IF EXISTS tenants_create_default_quota ON tenants;
CREATE TRIGGER tenants_create_default_quota
    AFTER INSERT ON tenants
    FOR EACH ROW
    EXECUTE FUNCTION create_default_tenant_quota();

-- Backfill tenants created before this migration.
INSERT INTO tenant_quotas(tenant_id)
SELECT t.id
FROM tenants t
LEFT JOIN tenant_quotas q ON q.tenant_id = t.id
WHERE q.tenant_id IS NULL;
