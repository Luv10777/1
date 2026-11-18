CREATE TABLE partner_member_numbers (
    partner_id BIGINT NOT NULL REFERENCES partners(id),
    user_id BIGINT NOT NULL REFERENCES users(id),
    member_number BIGINT NOT NULL CHECK (member_number > 0),
    display_name VARCHAR(20),
    PRIMARY KEY (partner_id, user_id),
    UNIQUE (partner_id, member_number)
);
GRANT SELECT, INSERT, UPDATE ON partner_member_numbers TO growth_app;

WITH people AS (
    SELECT m.partner_id, m.user_id, m.display_name, m.created_at, 0 AS priority FROM partner_members m
    UNION ALL
    SELECT d.partner_id, d.actor_user_id, NULL, MIN(d.created_at), 1
    FROM partner_distributions d
    WHERE d.actor_user_id IS NOT NULL AND NOT EXISTS (
        SELECT 1 FROM partner_members m WHERE m.partner_id = d.partner_id AND m.user_id = d.actor_user_id
    )
    GROUP BY d.partner_id, d.actor_user_id
)
INSERT INTO partner_member_numbers (partner_id, user_id, member_number, display_name)
SELECT partner_id, user_id,
       ROW_NUMBER() OVER (PARTITION BY partner_id ORDER BY priority, created_at, user_id), display_name
FROM people;

CREATE FUNCTION remember_partner_member_number() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'INSERT' THEN
        PERFORM 1 FROM partners WHERE id = NEW.partner_id FOR UPDATE;
        INSERT INTO partner_member_numbers (partner_id, user_id, member_number, display_name)
        SELECT NEW.partner_id, NEW.user_id, COALESCE(MAX(member_number), 0) + 1, NEW.display_name
        FROM partner_member_numbers WHERE partner_id = NEW.partner_id
        ON CONFLICT (partner_id, user_id) DO NOTHING;
    ELSE
        UPDATE partner_member_numbers SET display_name = NEW.display_name
        WHERE partner_id = NEW.partner_id AND user_id = NEW.user_id;
    END IF;
    RETURN NEW;
END $$;

CREATE TRIGGER partner_member_number
AFTER INSERT OR UPDATE OF display_name ON partner_members
FOR EACH ROW EXECUTE FUNCTION remember_partner_member_number();

ALTER TABLE billing_reservations ADD COLUMN payer_partner_id BIGINT REFERENCES partners(id);
ALTER TABLE billing_ledger ADD COLUMN payer_partner_id BIGINT REFERENCES partners(id);
CREATE INDEX idx_billing_ledger_team_activity ON billing_ledger(tenant_id, created_at DESC, id DESC)
    WHERE type IN ('CAPTURE', 'TRANSFER');
