-- V80 · 站内购买激活码：商家在「套餐与权益」用支付宝买月卡或年卡激活码，付款成功后生成一个激活码（订单种类 ACTIVATION_CODE）。
--
-- 买到的码和平台、服务商生成的激活码一样一码一用，可以自己兑换，也可以转给别人；order_id 记来自哪笔订单（一笔订单一个码），
-- purchased_tenant_id 记买码的商户，「我购买的激活码」按它列出。billing_orders 的 kind 没有约束，不用改表。
ALTER TABLE billing_redeem_codes ADD COLUMN order_id VARCHAR(36) UNIQUE;
ALTER TABLE billing_redeem_codes ADD COLUMN purchased_tenant_id BIGINT REFERENCES tenants(id);
ALTER TABLE billing_redeem_codes ADD CONSTRAINT billing_redeem_codes_order_check
    CHECK ((order_id IS NULL) = (purchased_tenant_id IS NULL) AND (order_id IS NULL OR (kind = 'MEMBERSHIP' AND partner_id IS NULL)));
CREATE INDEX idx_billing_redeem_codes_purchased ON billing_redeem_codes (purchased_tenant_id, created_at DESC) WHERE purchased_tenant_id IS NOT NULL;
