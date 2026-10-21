-- 联盟券结算折扣不再全平台统一：平台审核每张供券单时与供券商户约定，审核前为空。
-- 已上架、已领完的供券单必须有约定折扣；待审核、已驳回、未审核就撤回的可以为空。
ALTER TABLE alliance_offers ALTER COLUMN settle_rate_bp DROP NOT NULL;
ALTER TABLE alliance_offers ALTER COLUMN settle_amount_minor DROP NOT NULL;
ALTER TABLE alliance_offers ADD CONSTRAINT ck_alliance_offers_agreed_rate CHECK (
    status IN ('PENDING_REVIEW', 'REJECTED', 'WITHDRAWN')
    OR (settle_rate_bp IS NOT NULL AND settle_amount_minor IS NOT NULL));
