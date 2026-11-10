-- V78 · 激活码：兑换码新增 MEMBERSHIP 种类。兑换后按生成时选定的套餐开通或顺延会员，不送烟火。
--
-- 激活码和充值码、赠送码共用一张表、一个兑换入口；plan_id 记生成时的套餐版本，套餐之后下架或出新版本，已生成的码照旧按原版本开通。
-- 激活码的 credits 和 amount_minor 都是 0：不记烟火，也不按兑换记邀请返利（线下卖码的实收金额平台不知道）。
ALTER TABLE billing_redeem_codes DROP CONSTRAINT billing_redeem_codes_kind_check;
ALTER TABLE billing_redeem_codes DROP CONSTRAINT billing_redeem_codes_credits_check;
ALTER TABLE billing_redeem_codes ADD COLUMN plan_id BIGINT REFERENCES billing_plans(id);
ALTER TABLE billing_redeem_codes ADD CONSTRAINT billing_redeem_codes_kind_check
    CHECK (kind IN ('TOPUP', 'GIFT', 'MEMBERSHIP'));
ALTER TABLE billing_redeem_codes ADD CONSTRAINT billing_redeem_codes_credits_check
    CHECK (CASE WHEN kind = 'MEMBERSHIP' THEN credits = 0 ELSE credits BETWEEN 1 AND 100000 END);
ALTER TABLE billing_redeem_codes ADD CONSTRAINT billing_redeem_codes_plan_check
    CHECK ((kind = 'MEMBERSHIP') = (plan_id IS NOT NULL));
