-- V84 · 服务商改成「团队账户 + 成员权限」
--
-- 1. 去掉 BD：BD 账号直接移出服务商，名下商户归整个服务商（不再分到个人）。
-- 2. 每家服务商绑定一个团队账户（team_tenant_id，负责人自己的一方志商户）。平台发的烟火进这个商户的账本，
--    成员都是这个商户的员工，在一方志里创作直接扣它；分给名下商户也从它扣。服务商不再单独记「可分配烟火」，
--    原来的余额在这里一次性转进团队账户。
-- 3. 登录账号分负责人（OWNER，平台开通，全部权限）和成员（MEMBER，负责人或有团队管理权限的成员添加），
--    成员的权限逐项勾选；分配烟火可设每月上限。display_name 是成员在团队里的称呼，第一次进服务商后台时填。
-- 4. 激活码不再限额度：删掉 partner_code_quotas。

-- ---------- 去掉 BD ----------
DELETE FROM partner_members WHERE role = 'BD';
ALTER TABLE partner_tenants DROP COLUMN bd_user_id;
ALTER TABLE partner_members DROP CONSTRAINT partner_members_role_check;
ALTER TABLE partner_members ADD CONSTRAINT partner_members_role_check CHECK (role IN ('OWNER', 'MEMBER'));

-- ---------- 成员权限和称呼 ----------
-- 负责人不看这几列，永远是全部权限。can_distribute、can_finance 和 distribute_monthly_cap 只有负责人能改。
ALTER TABLE partner_members
    ADD COLUMN can_tenants           BOOLEAN     NOT NULL DEFAULT true,
    ADD COLUMN can_codes             BOOLEAN     NOT NULL DEFAULT true,
    ADD COLUMN can_distribute        BOOLEAN     NOT NULL DEFAULT false,
    ADD COLUMN can_finance           BOOLEAN     NOT NULL DEFAULT false,
    ADD COLUMN can_team              BOOLEAN     NOT NULL DEFAULT false,
    ADD COLUMN distribute_monthly_cap BIGINT     CHECK (distribute_monthly_cap IS NULL OR distribute_monthly_cap > 0),
    ADD COLUMN display_name          VARCHAR(20) CHECK (display_name IS NULL OR length(btrim(display_name)) > 0),
    ADD CONSTRAINT partner_members_display_name_unique UNIQUE (partner_id, display_name);

-- ---------- 团队账户 ----------
ALTER TABLE partners ADD COLUMN team_tenant_id BIGINT REFERENCES tenants(id);
UPDATE partners p SET team_tenant_id = (
    SELECT u.tenant_id FROM partner_members m JOIN users u ON u.id = m.user_id
    WHERE m.partner_id = p.id AND m.role = 'OWNER' ORDER BY m.created_at, m.user_id LIMIT 1);

-- 原来的可分配烟火转进团队账户（记成充值烟火），服务商流水记一条转出。账本受 RLS 约束，逐个切到团队账户再写。
DO $$
DECLARE
    p RECORD;
    previous_tenant TEXT := current_setting('app.tenant_id', true);
    after_available BIGINT;
    after_held BIGINT;
BEGIN
    IF EXISTS (SELECT 1 FROM partners WHERE credits_available > 0 AND team_tenant_id IS NULL) THEN
        RAISE EXCEPTION '有服务商还有可分配烟火但没有负责人账号，先在管理后台补上负责人再升级';
    END IF;
    FOR p IN SELECT id, name, credits_available, team_tenant_id FROM partners
             WHERE credits_available > 0 AND team_tenant_id IS NOT NULL ORDER BY id LOOP
        PERFORM set_config('app.tenant_id', p.team_tenant_id::text, true);
        INSERT INTO billing_accounts (tenant_id) VALUES (p.team_tenant_id) ON CONFLICT (tenant_id) DO NOTHING;
        UPDATE billing_accounts SET purchased_available = purchased_available + p.credits_available, updated_at = now()
        WHERE tenant_id = p.team_tenant_id
        RETURNING gift_available + purchased_available, held INTO after_available, after_held;
        INSERT INTO billing_ledger (tenant_id, event_key, type, description, gift_delta, purchased_delta, held_delta,
                                    available_after, held_after)
        VALUES (p.team_tenant_id, 'partner-pool-merge:' || p.id, 'PURCHASE',
                left('服务商「' || p.name || '」可分配烟火转入团队账户', 240), 0, p.credits_available, 0, after_available, after_held);
        INSERT INTO partner_credit_ledger (partner_id, event_key, type, delta, available_after, tenant_id, note, actor_user_id)
        SELECT p.id, 'partner-pool-merge:' || p.id, 'TRANSFER', -p.credits_available, 0, p.team_tenant_id, '转入团队账户',
               (SELECT created_by FROM partners WHERE id = p.id);
    END LOOP;
    PERFORM set_config('app.tenant_id', COALESCE(previous_tenant, ''), true);
END $$;

ALTER TABLE partners DROP COLUMN credits_available;
-- 之后的平台发放直接进团队账户，服务商流水只留作发放记录，不再有「发放后余额」。
ALTER TABLE partner_credit_ledger ALTER COLUMN available_after DROP NOT NULL;

-- ---------- 激活码不限额度 ----------
DROP TABLE partner_code_quotas;
