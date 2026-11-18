ALTER TABLE billing_reservations ADD COLUMN payer_tenant_id BIGINT REFERENCES tenants(id);
ALTER TABLE billing_ledger ADD COLUMN generation_capability VARCHAR(20);

-- V84 made the service provider's own workspace its team wallet. Mark older reservations
-- made in that workspace during the team-account period without changing its ledger entries.
DO $$
DECLARE
    team_id BIGINT;
    prior_tenant TEXT := current_setting('app.tenant_id', true);
    team_accounts_since TIMESTAMPTZ;
BEGIN
    SELECT installed_on::timestamptz INTO team_accounts_since
    FROM flyway_schema_history WHERE version = '84' AND success;

    FOR team_id IN SELECT DISTINCT team_tenant_id FROM partners WHERE team_tenant_id IS NOT NULL LOOP
        PERFORM set_config('app.tenant_id', team_id::text, true);
        UPDATE billing_reservations r SET payer_tenant_id = team_id
        WHERE r.tenant_id = team_id AND r.payer_tenant_id IS NULL
          AND EXISTS (
              SELECT 1 FROM partners p
              WHERE p.team_tenant_id = team_id
                AND r.created_at >= GREATEST(p.created_at, team_accounts_since)
                AND (p.status <> 'DELETED' OR r.created_at < p.updated_at)
          );
    END LOOP;
    PERFORM set_config('app.tenant_id', COALESCE(prior_tenant, ''), true);
END $$;

CREATE INDEX idx_billing_ledger_reservation ON billing_ledger(reservation_id, tenant_id)
    WHERE reservation_id IS NOT NULL;

-- The workspace can read the settlement events of its own creative tasks.
-- Writes and unrelated team ledger entries remain scoped to the paying account.
CREATE POLICY creative_workspace_settlements ON billing_ledger FOR SELECT TO growth_app
    USING (generation_capability IS NOT NULL AND EXISTS (
        SELECT 1 FROM billing_reservations r
        WHERE r.id = billing_ledger.reservation_id
          AND r.tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::bigint
          AND r.payer_tenant_id = billing_ledger.tenant_id
    ));
