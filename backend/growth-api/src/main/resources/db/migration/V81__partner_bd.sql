-- V81 · 服务商下面的 BD
--
-- 服务商的登录账号分两种：OWNER（服务商负责人，平台开通的都是）和 BD（负责人在服务商后台按 UID 添加）。
-- BD 只管自己名下的商户：按 UID 添加或兑换了 BD 生成的激活码的商户归到这个 BD；BD 不能分烟火、看不到服务商的烟火流水。
-- 负责人能看全部，也能把商户在 BD 之间调配。BD 生成的激活码按 billing_redeem_codes.created_by 认人，不另加列。
ALTER TABLE partner_members ADD COLUMN role VARCHAR(8) NOT NULL DEFAULT 'OWNER' CHECK (role IN ('OWNER', 'BD'));

-- 商户归在服务商下面的哪个 BD；为空表示由负责人直接管。BD 被移除时由服务层清空。
ALTER TABLE partner_tenants ADD COLUMN bd_user_id BIGINT REFERENCES users(id);
CREATE INDEX idx_partner_tenants_bd ON partner_tenants (partner_id, bd_user_id) WHERE bd_user_id IS NOT NULL;

-- 激活码按生成人筛选（BD 只看自己生成的）。
CREATE INDEX idx_billing_redeem_codes_partner_creator ON billing_redeem_codes (partner_id, created_by, created_at DESC) WHERE partner_id IS NOT NULL;

GRANT UPDATE ON partner_members TO growth_app;
